package com.example.itinerary.data

import org.junit.Assert.*
import org.junit.Test
import java.time.*

class QuickEntryReviewTest {
    private val today = LocalDate.of(2026, 9, 27)
    @Test fun commonFortnightlyPhrasesShareTheExistingRepeatRule() {
        for (phrase in listOf("every two week", "every two weeks", "every 2 week", "every 2 weeks", "every other week", "every other weeks", "fortnightly", "every fortnight", "EVERY   TWO   WEEKS")) {
            val text = "Green Bin $phrase for 3 occurrences"
            val parsed = QuickEntry.parse(text, today)
            assertNull(phrase, parsed.error)
            assertEquals("Green Bin", parsed.title)
            assertEquals(RepeatRule.FORTNIGHTLY, parsed.repeat)
            assertEquals(listOf(today, today.plusDays(14), today.plusDays(28)), parsed.repeat.dates(parsed.date, parsed.repeatCount))
            val span = parsed.phrases.first { it.kind == QuickPhraseKind.REPEAT }
            assertEquals(phrase, text.substring(span.start, span.end))
        }
    }
    @Test fun fortnightlyLiteralAndUnsupportedIntervalsStayDistinct() {
        assertEquals(RepeatRule.NONE, QuickEntry.parse("Green Bin \"every two weeks\"", today).repeat)
        val text = "Green Bin every other week"
        val phrase = QuickEntry.parse(text, today).phrases.single()
        val literal = QuickEntry.parse(text, today, listOf(phrase.start until phrase.end))
        assertEquals(text, literal.title);assertEquals(RepeatRule.NONE, literal.repeat)
        assertEquals(RepeatRule.everyWeeks(3), QuickEntry.parse("Green Bin every 3 weeks", today).repeat)
        for (unsupported in listOf("every other month", "every other weekend", "every two months")) {
            assertNotNull(unsupported, QuickEntry.parse("Green Bin $unsupported", today).error)
        }
    }
    @Test fun phraseOffsetsMatchOriginalWhitespaceAndLiteralEditsArePreserved() {
        val text="  Meet   Friday\n3pm  at Cafe"
        val result=QuickEntry.parse(text,today)
        assertNull(result.error)
        assertEquals(listOf("Friday", "3pm", "at Cafe"),result.phrases.map { text.substring(it.start,it.end).trim() })
        assertEquals(listOf(QuickPhraseKind.DATE, QuickPhraseKind.TIME, QuickPhraseKind.LOCATION),result.phrases.map { it.kind })
        val phrase=result.phrases.first()
        val literal=listOf(phrase.start until phrase.end)
        val kept=QuickEntry.parse(text,today,literal)
        assertEquals("Meet Friday",kept.title);assertFalse(kept.dateSpecified);assertEquals(LocalTime.of(15,0),kept.time)
        val moved=moveQuickEntryLiterals(text,"New $text",literal)
        assertEquals(listOf((phrase.start+4) until (phrase.end+4)),moved)
        assertEquals("New Meet Friday",QuickEntry.parse("New $text",today,moved).title)
        assertEquals(literal,moveQuickEntryLiterals(text,text+" tomorrow",literal))
        assertTrue(moveQuickEntryLiterals(text,text.replace("Friday","Monday"),literal).isEmpty())
    }
    @Test fun simultaneousDateAndTimeChoicesMustBothBeResolved() {
        val parsed=QuickEntry.parse("Call 03/04 at 3",today)
        assertEquals(listOf(LocalDate.of(2027,4,3), LocalDate.of(2027,3,4)),parsed.dateChoices)
        assertEquals(listOf(LocalTime.of(3,0),LocalTime.of(15,0)),parsed.timeChoices)
        assertNotNull(parsed.error)
        assertNotNull(parsed.corrected("2027-04-03",null).error)
        val fixed=parsed.corrected("2027-04-03","15:00")
        assertNull(fixed.error);assertEquals("Call",fixed.title);assertEquals(LocalTime.of(15,0),fixed.time)
        assertFalse(fixed.ambiguousTime);assertTrue(fixed.dateChoices.isEmpty())
        assertNull(QuickEntry.parse("Call 30/09",today).error)
        assertNotNull(QuickEntry.parse("Call 03/04/27",today).error)
        assertNotNull(QuickEntry.parse("Call 2026-02-30",today).corrected("2026-03-01","15:00").error)
    }
    @Test fun remindersAndRepeatsUseExistingModelsAndSchedule() {
        val text="Gym every Monday 6pm, remind me 30 minutes before for 4 occurrences"
        val parsed=QuickEntry.parse(text,today)
        assertNull(parsed.error);assertEquals("Gym",parsed.title)
        assertEquals(RepeatRule.WEEKLY,parsed.repeat);assertEquals(4,parsed.repeatCount)
        assertEquals(LocalDate.of(2026,9,28),parsed.date);assertEquals(LocalTime.of(18,0),parsed.time)
        assertEquals(30,parsed.reminderMinutes);assertEquals(30L,parsed.quickReminders().single().offsetMinutes)
        val dates=parsed.repeat.dates(parsed.date,parsed.repeatCount)
        assertEquals(LocalDate.of(2026,10,19),dates.last())
        assertEquals(LocalTime.of(17,30),reminderTrigger(parsed.date,parsed.time,30,ZoneId.of("Australia/Perth")).toLocalTime())
        val task=QuickEntry.parse("Pay rent every month remind me 1 day before",today).quickTask()
        assertEquals("Pay rent",task.title);assertEquals("MONTHLY",task.repeat);assertEquals(today,task.dueDate)
        assertEquals(9,Instant.ofEpochMilli(task.reminderAt!!).atZone(ZoneId.systemDefault()).hour)
        assertEquals(today.minusDays(1),Instant.ofEpochMilli(task.reminderAt).atZone(ZoneId.systemDefault()).toLocalDate())
        val next=task.nextOccurrence(today)!!
        assertEquals(today.plusMonths(1),next.dueDate)
        assertEquals(next.dueDate!!.minusDays(1),Instant.ofEpochMilli(next.reminderAt!!).atZone(ZoneId.systemDefault()).toLocalDate())
    }
    @Test fun quotedAndKeptReminderPhrasesNeverScheduleAnything() {
        val text="Call tomorrow remind me 30 minutes before"
        val parsed=QuickEntry.parse(text,today)
        val token=parsed.phrases.single { it.kind==QuickPhraseKind.REMINDER }
        val kept=QuickEntry.parse(text,today,listOf(token.start until token.end))
        assertNull(kept.error);assertNull(kept.reminderMinutes);assertEquals("Call remind me 30 minutes before",kept.title)
        val quoted=QuickEntry.parse("\"Gym every Monday remind me 30 minutes before\" tomorrow",today)
        assertEquals(RepeatRule.NONE,quoted.repeat);assertNull(quoted.reminderMinutes)
    }
    @Test fun invalidReminderAndRepeatPhrasesAreNotSilentlySaved() {
        for(text in listOf("Gym every", "Gym every 400 days", "Gym remind me", "Gym remind me -2 minutes before",
            "Gym remind me 0.5 minutes before", "Gym every Monday for 1 occurrence", "Gym every Monday for 366 times",
            "Gym every Monday every Tuesday", "Gym every Monday tomorrow 2026-10-01",
            "Gym remind me 5 minutes before remind me 1 hour before")) assertNotNull(text,QuickEntry.parse(text,today).error)
        assertNull(QuickEntry.parse("Gym tomorrow remind me 0 minutes before",today).error)
        assertNull(QuickEntry.parse("Gym tomorrow remind me 1.5 hours before",today).error)
        assertNotNull(QuickEntry.parse("Gym every Monday 2026-09-29",today).error)
    }
    @Test fun remindersAfterLocationsAndIsoDatesDoNotCollide() {
        val parsed=QuickEntry.parse("Lunch 2026-10-01 noon at Cafe, remind me 1 hour before",today)
        assertNull(parsed.error);assertEquals("Lunch",parsed.title);assertEquals("Cafe",parsed.location)
        assertEquals(60,parsed.reminderMinutes);assertEquals(LocalDate.of(2026,10,1),parsed.date)
        assertEquals(120,QuickEntry.parse("Shift 23:00-01:00 remind me 1 hour before",today).durationMinutes)
    }
    @Test fun pastWarningsDistinguishDatesAndTodaysTimesFromUndatedTasks() {
        val now=today.atTime(14,0).atZone(ZoneId.of("Australia/Perth"))
        assertTrue(QuickEntry.parse("Call 2026-09-26",today).isPast(now,true))
        assertTrue(QuickEntry.parse("Call today 1pm",today).isPast(now,false))
        assertFalse(QuickEntry.parse("Call today",today).isPast(now,true))
        assertFalse(QuickEntry.parse("Call",today).isPast(now,true))
        assertFalse(QuickEntry.parse("Call today 3pm",today).isPast(now,false))
    }
}
