package com.example.itinerary.data

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class QuickEntryNaturalTest {
    private val today = LocalDate.of(2026, 9, 27)
    private fun parse(text: String) = QuickEntry.parse(text, today)
    @Test fun relativeDatesCrossMonthAndYearBoundaries() {
        assertEquals(LocalDate.of(2026,9,30), parse("Call plumber in 3 days").date)
        assertEquals("Call plumber", parse("Call plumber in 3 days").title)
        assertEquals(today.plusWeeks(2), parse("Call in 2 weeks").date)
        assertEquals(LocalDate.of(2027,1,2), QuickEntry.parse("Call in 3 days", LocalDate.of(2026,12,30)).date)
        assertEquals(LocalDate.of(2028,2,29), QuickEntry.parse("Call in 1 day", LocalDate.of(2028,2,28)).date)
        assertNotNull(parse("Call in 999999999999999999999999 days").error)
    }
    @Test fun explicitRangesWorkWithDashesAndWordsAndOvernight() {
        for (separator in listOf("-", "–", "—", " to ", " until ")) {
            val result=parse("Meeting Friday 2pm${separator}3:30pm")
            assertNull(result.error);assertEquals("Meeting", result.title)
            assertEquals(LocalTime.of(14,0),result.time);assertEquals(90,result.durationMinutes)
        }
        assertEquals(120,parse("Shift from 23:00 to 01:00").durationMinutes)
        assertEquals(120,parse("Shift 23:00-01:00").durationMinutes)
        assertEquals(90,parse("Meeting 14:00–15:30").durationMinutes)
        assertEquals("Shift",parse("Shift from 23:00 to 01:00").title)
        assertEquals(60,parse("Lunch noon–1pm").durationMinutes)
        assertNull(parse("Meeting 2pm–3pm for an hour").error)
        for (text in listOf("Meeting 2pm–3:30", "Meeting 2pm–3pm for 30 minutes", "Meeting 2pm–2pm", "Meeting 2pm–25:00", "Meeting 2pm–3pm 4pm"))
            assertNotNull(text,parse(text).error)
    }
    @Test fun compoundAndWordDurationsAreWholeMinutes() {
        for (value in listOf("an hour", "a hour", "one hour", "60 minutes")) assertEquals(value,60,parse("Study for $value").durationMinutes)
        for (value in listOf("1h 30m", "1h30m", "1 hour 30 minutes", "1 hour and 30 minutes", "1.5h")) {
            val result=parse("Study for $value")
            assertNull(value,result.error);assertEquals(value,90,result.durationMinutes);assertEquals("Study",result.title)
        }
        assertEquals(30,parse("Study for half an hour").durationMinutes)
        assertNotNull(parse("Study for 1h -30m").error)
        assertNotNull(parse("Study for 24h 1m").error)
    }
    @Test fun bothNamedDateOrdersAndOrdinalsRetainTitle() {
        for (date in listOf("September 30", "Sep 30th", "30th Sep", "September 30, 2026", "30th September 2026")) {
            val result=parse("Dentist on $date")
            assertNull(date,result.error);assertEquals("Dentist",result.title);assertEquals(LocalDate.of(2026,9,30),result.date)
        }
        assertEquals(LocalDate.of(2027,1,2),parse("Call January 2nd").date)
        assertNotNull(parse("Call February 30").error)
    }
    @Test fun locationsDoNotEatScheduleOrQuotedPlaceWords() {
        for (text in listOf("Lunch tomorrow noon at Riverside Cafe", "Lunch at Riverside Cafe tomorrow noon")) {
            val result=parse(text)
            assertNull(result.error);assertEquals("Lunch",result.title);assertEquals("Riverside Cafe",result.location)
            assertEquals(today.plusDays(1),result.date);assertEquals(LocalTime.NOON,result.time)
        }
        val quoted=parse("Lunch at \"Friday at Noon\" tomorrow 12pm")
        assertNull(quoted.error);assertEquals("Friday at Noon",quoted.location);assertEquals("Lunch",quoted.title)
        assertEquals("Friday Cafe",parse("Lunch tomorrow at Friday Cafe").location)
        assertEquals(LocalTime.of(15,0),parse("Lunch at 3pm").time)
        assertEquals("",parse("Lunch at 3pm").location)
    }
    @Test fun quotedTitlesAndOrdinaryNumbersRemainLiteral() {
        val result=parse("\"Pay May tomorrow at noon\" in 3 days")
        assertNull(result.error);assertEquals("Pay May tomorrow at noon",result.title)
        assertNull(result.time);assertEquals("",result.location);assertEquals(today.plusDays(3),result.date)
        assertEquals("Pay May",parse("“Pay May” tomorrow").title)
        assertEquals("Call 12345",parse("Call 12345").title)
        assertEquals("Buy milk for 3 people",parse("Buy milk for 3 people").title)
        assertEquals("Buy 2-3 apples",parse("\"Buy 2-3 apples\"").title)
        assertNotNull(parse("\"Unclosed tomorrow").error)
    }
    @Test fun unclearDatesAndIncompletePhrasesCannotSilentlySave() {
        for (text in listOf("Dentist 03/04", "Dentist 03-04-2027", "Dentist in 3", "Dentist in", "Dentist at",
            "Study for", "Study for 30", "Study for an", "Study for 1 hour and", "Lunch at Cafe in 3", "Lunch at Cafe 03/04", "Call tomorrow 3pm to", "Call tomorrow 3pm–", "Call 15:3"))
            assertNotNull(text,parse(text).error)
        assertNull(parse("\"Call at\" tomorrow").error)
        assertNull(parse("Call 2026-10-01").error)
    }
    @Test fun trailingAmPmCoversIncreasingSameHalfDayRanges() {
        for (separator in listOf("-", "–", "—", " to ", " until ")) {
            for (suffix in listOf("pm", " PM", "am")) {
                val raw="Dentist tomorrow 3${separator}4$suffix"
                val result=parse(raw)
                assertNull(raw,result.error);assertEquals("Dentist",result.title)
                assertEquals(LocalTime.of(if(suffix.trim().equals("pm",true))15 else 3,0),result.time)
                assertEquals(60,result.durationMinutes);assertEquals(today.plusDays(1),result.date)
                val span=result.phrases.single { it.kind==QuickPhraseKind.TIME }
                assertEquals("3${separator}4$suffix",raw.substring(span.start,span.end))
            }
        }
        assertEquals(45,parse("Dentist from 3:15 to 4pm").durationMinutes)
        assertEquals(60,parse("Lunch 12-1pm").durationMinutes)
        assertNull(parse("Dentist 3-4pm for an hour").error)
    }
    @Test fun shorthandRangesKeepAmbiguityAndConflictSafeguards() {
        for (text in listOf("Shift 9-5pm", "Lunch 11-1pm", "Shift 11-1am", "Call 3-3pm", "Call 13-14pm",
            "Call 0-1am", "Call 3:99-4pm", "Call 3pm-4", "Call 3-4pm for 30m", "Call 3-4pm 5pm"))
            assertNotNull(text,parse(text).error)
        val literal=parse("\"Buy 3-4pm labels\" tomorrow")
        assertEquals("Buy 3-4pm labels",literal.title);assertNull(literal.time);assertNull(literal.durationMinutes)
        assertNotNull(parse("Dentist 03/04").error)
    }

}
