package com.example.itinerary.data

import org.junit.Assert.*
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

// Quick entry items from bug hunt 17 (H17-Q1 … H17-Q3), as approved.
class QuickHuntSeventeenTest {
    private val zone = ZoneId.of("Europe/London")
    private val today = LocalDate.of(2026, 10, 6) // a Tuesday
    private val now = LocalDateTime.of(2026, 10, 6, 5, 0)
    private fun parse(text: String) = QuickEntry.parse(text, today, now = now, dayFirst = true)
    private fun day(year: Int, month: Int, day: Int) = LocalDate.of(year, month, day)

    /** "Move to next occurrence", as the Quick entry dialog does it. */
    private fun moved(text: String, at: ZonedDateTime, task: Boolean = false): QuickEntrySuggestion {
        val input = QuickInput(text = text, task = task, baseDate = at.toLocalDate())
        val next = input.suggestion(at).nextRepeatDate(task, at)
        return input.copy(dateOverride = next.toString()).suggestion(at)
    }

    // H17-Q1: a monthly repeat moved onto a shorter month's end keeps coming back to its own day.
    @Test fun monthlyMovedToNextOccurrenceKeepsItsDay() {
        val oct31 = ZonedDateTime.of(2026, 10, 31, 10, 0, 0, 0, zone)
        moved("Rent monthly 9am", oct31).let {
            assertEquals(day(2026, 11, 30), it.date)
            assertEquals(31, it.repeatAnchorDay)
            assertEquals(listOf(day(2026, 11, 30), day(2026, 12, 31), day(2027, 1, 31), day(2027, 2, 28), day(2027, 3, 31)),
                it.repeat.dates(it.date, 5, it.repeatAnchorDay))
        }
        val jan30 = ZonedDateTime.of(2027, 1, 30, 10, 0, 0, 0, zone)
        moved("Rent monthly 9am", jan30).let {
            assertEquals(day(2027, 2, 28), it.date)
            assertEquals(listOf(day(2027, 2, 28), day(2027, 3, 30), day(2027, 4, 30)), it.repeat.dates(it.date, 3, it.repeatAnchorDay))
        }
        // A task carries the day too.
        moved("Rent monthly", oct31, task = true).quickTask().let {
            assertEquals(day(2026, 11, 30), it.dueDate)
            assertEquals(day(2026, 12, 31), it.nextOccurrence(day(2026, 11, 30))!!.dueDate)
        }
        // Every few months as well; a day picked by hand that is no month end is still its own.
        val every2 = QuickInput(text = "Rent every 2 months 9am", baseDate = oct31.toLocalDate()).let { it.copy(dateOverride = "2026-11-30").suggestion(oct31) }
        assertEquals(listOf(day(2026, 11, 30), day(2027, 1, 31)), every2.repeat.dates(every2.date, 2, every2.repeatAnchorDay))
        assertEquals(0, QuickInput(text = "Rent monthly 9am", baseDate = oct31.toLocalDate(), dateOverride = "2026-11-15").suggestion(oct31).repeatAnchorDay)
    }

    // H17-Q2: days left out of a repeat make it a repeat on the others, never its start date.
    @Test fun exceptDaysNarrowTheRepeat() {
        val workdays = (1..5).map { DayOfWeek.of(it) }.toSet()
        parse("Gym every day except Sunday 6am").let {
            assertNull(it.error); assertEquals("Gym", it.title); assertEquals(today, it.date); assertEquals(LocalTime.of(6, 0), it.time)
            assertEquals(RepeatRule.onDays(DayOfWeek.entries.toSet() - DayOfWeek.SUNDAY), it.repeat)
        }
        parse("Standup weekdays except Friday 9am").let {
            assertNull(it.error); assertEquals("Standup", it.title); assertEquals(today, it.date); assertEquals(LocalTime.of(9, 0), it.time)
            assertEquals(RepeatRule.onDays(workdays - DayOfWeek.FRIDAY), it.repeat)
        }
        assertEquals(RepeatRule.WEEKDAYS, parse("Yoga every day excluding Saturday and Sunday 7am").repeat)
        assertEquals(RepeatRule.onDays(setOf(DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY)),
            parse("Standup weekdays but not Mon or Fri 9am").repeat)
        parse("Walk every day apart from Sun 7am").let { assertEquals("Walk", it.title); assertFalse(DayOfWeek.SUNDAY in it.repeat.days) }
        // Without a repeat on several days it is refused, not read as a date.
        for (text in listOf("Gym except Sunday 6am", "Rent monthly except Monday", "Gym weekdays other than Mon, Tue, Wed, Thu and Fri 6am")) {
            parse(text).let { assertNotNull(text, it.error); assertTrue(text, it.phrases.any { p -> p.kind == QuickPhraseKind.UNSUPPORTED }) }
        }
    }

    // H17-Q3: a spoken time still asks morning or afternoon on a phone set to Arabic.
    @Test fun spokenTimeAsksInAnArabicLocale() {
        val before = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-EG"))
            parse("Meeting at half past 3 tomorrow").let {
                assertTrue(it.ambiguousTime)
                assertEquals(listOf(LocalTime.of(3, 30), LocalTime.of(15, 30)), it.timeChoices)
            }
            assertEquals(LocalTime.of(16, 45), parse("Call at quarter to 5pm tomorrow").time)
        } finally {
            Locale.setDefault(before)
        }
    }

    // Bug hunt 18, batch 2 (R18-Q1 … R18-Q7): Quick entry, as approved. Today is Wednesday 7 October 2026.
    private val today18 = LocalDate.of(2026, 10, 7)
    private fun parse18(text: String, dayFirst: Boolean? = true) = QuickEntry.parse(text, today18,
        now = LocalDateTime.of(2026, 10, 7, 10, 0), dayFirst = dayFirst, zone = ZoneId.of("Australia/Sydney"))
    private val skipOneDate = "Planner can't skip one date. Add the repeat, then delete that occurrence."

    // R18-Q1: one date left out of a repeat is refused plainly; it never becomes the start.
    @Test fun aDateLeftOutOfARepeatIsRefused() {
        for (text in listOf("Gym daily except 25 Dec", "Class every Tuesday except 10 Nov", "Class Tuesdays until 1 Dec except 10 Nov",
            "Gym daily but not 10 Nov", "Gym daily apart from 10 Nov", "Gym daily skip 10 Nov", "Gym daily except tomorrow",
            "Gym daily except the 10th", "Gym every day excluding Christmas", "Gym daily except 10/11")) {
            parse18(text).let {
                assertEquals(text, skipOneDate, it.error)
                assertTrue(text, it.phrases.any { p -> p.kind == QuickPhraseKind.UNSUPPORTED })
            }
        }
        // Weekdays left out still narrow the repeat; without a repeat the words are left alone.
        parse18("Gym every day except Fridays 6am").let {
            assertNull(it.error); assertEquals("Gym", it.title)
            assertEquals(RepeatRule.onDays(DayOfWeek.entries.toSet() - DayOfWeek.FRIDAY), it.repeat)
        }
        assertNotEquals(skipOneDate, parse18("Skip 10 Nov").error)
    }

    // R18-Q2 is in SharedMessageTest. R18-Q3: a range from noon ends in the afternoon.
    @Test fun aRangeFromNoonEndsInTheAfternoon() {
        for ((text, minutes) in listOf("Lunch noon-1:30" to 90, "Lunch noon to 2:30" to 150, "Lunch midday-1:15" to 75, "Lunch noon-1" to 60,
            "Lunch 12 noon - 12:30" to 30)) {
            parse18(text).let {
                assertNull(text, it.error); assertEquals(text, "Lunch", it.title)
                assertEquals(text, LocalTime.NOON, it.time); assertEquals(text, minutes, it.durationMinutes)
            }
        }
    }

    // R18-Q4: a repeat until a month runs to that month's last day, next year's once it has passed this year.
    @Test fun aRepeatUntilAMonth() {
        parse18("Yoga every Sat until December").let {
            assertNull(it.error); assertEquals("Yoga", it.title); assertTrue(it.repeatCountSpecified)
            assertEquals(day(2026, 12, 26), it.repeat.dates(it.date, it.repeatCount).last())
        }
        parse18("Pay rent every month until March").let {
            assertNull(it.error); assertEquals("Pay rent", it.title); assertEquals(6, it.repeatCount)
            assertEquals(day(2027, 3, 7), it.repeat.dates(it.date, it.repeatCount).last())
        }
        parse18("Yoga every Sat until end of December").let {
            assertNull(it.error); assertEquals("Yoga", it.title); assertEquals(12, it.repeatCount); assertTrue(it.repeatCountSpecified)
        }
        parse18("Gym daily until October").let { assertNull(it.error); assertEquals("Gym", it.title); assertEquals(25, it.repeatCount) }
        // Without a repeat, "until March" is not a stay of five months.
        assertNull(parse18("Away until March").endDate)
    }

    // R18-Q6: a day and month with a dot after each is a date, in the order the setting gives.
    @Test fun aDottedDayAndMonth() {
        parse18("Meeting 12.10. 3pm").let {
            assertNull(it.error); assertEquals("Meeting", it.title); assertEquals(day(2026, 10, 12), it.date); assertEquals(LocalTime.of(15, 0), it.time)
        }
        assertEquals(day(2026, 12, 10), parse18("Meeting 12.10. 3pm", dayFirst = false).date)
        // A time at a sentence end stays a time: 3.30 is no day and month.
        parse18("Meeting at 3.30.").let { assertEquals(today18, it.date); assertFalse(it.dateSpecified) }
        parse18("Meeting 3.30. tomorrow").let { assertEquals(today18.plusDays(1), it.date) }
        parse18("Call at 10.10.").let { assertEquals(today18, it.date); assertFalse(it.dateSpecified) }
        parse18("Study for 1.5.").let { assertFalse(it.dateSpecified) }
        assertEquals(day(2027, 10, 1), parse18("Pay rent by 1.10.").date)
    }

    // R18-Q7: digits of other scripts are read as digits; invisible format characters leave the title.
    @Test fun otherDigitsAndInvisibleCharacters() {
        assertEquals(LocalTime.of(15, 0), parse18("Meet at ٣pm").time)
        assertEquals(LocalTime.of(15, 0), parse18("Meet ３pm").time)
        parse18("Lunch １２:３０").let { assertEquals("Lunch", it.title); assertEquals(listOf(LocalTime.of(0, 30), LocalTime.of(12, 30)), it.timeChoices) }
        assertEquals("Call", parse18("Call ‏").title)
        assertEquals("Call Sam", parse18("Call​ Sam tomorrow").title)
        // Emoji joined by ZWJ keep it.
        assertEquals("Family 👨‍👩", parse18("Family 👨‍👩 tomorrow").title)
    }
}
