package com.example.itinerary.data

import org.junit.Assert.*
import org.junit.Test
import java.time.*

/** Second round of everyday wording that used to be saved wrongly or rejected. */
class QuickWordingMoreTest {
    private val today = LocalDate.of(2026, 9, 28) // a Monday
    private val now = LocalDateTime.of(2026, 9, 28, 10, 7)
    private fun parse(text: String) = QuickEntry.parse(text, today, now = now, dayFirst = true)
    private fun ok(text: String) = parse(text).also { assertNull(text, it.error) }
    private val tomorrow = today.plusDays(1)
    private val friday = LocalDate.of(2026, 10, 2)

    @Test fun dottedTimesBesideADateOrAfterAt() {
        ok("Meeting 15.30 tomorrow").let { assertEquals("Meeting", it.title); assertEquals(LocalTime.of(15, 30), it.time) }
        ok("Meeting tomorrow at 15.30").let { assertEquals(LocalTime.of(15, 30), it.time) }
        parse("Meeting 10.30 tomorrow").let { assertEquals("Meeting", it.title); assertEquals(listOf(LocalTime.of(10, 30), LocalTime.of(22, 30)), it.timeChoices) }
        ok("Leave at 7.45am tomorrow").let { assertEquals(LocalTime.of(7, 45), it.time) }
        // Elsewhere a dotted number is title text.
        ok("Release version 2.10").let { assertEquals("Release version 2.10", it.title); assertNull(it.time) }
        ok("Pay $12.50 tomorrow").let { assertEquals("Pay $12.50", it.title); assertNull(it.time) }
        ok("Pay €12.50 tomorrow").let { assertEquals("Pay €12.50", it.title) }
    }

    @Test fun shortAmPm() {
        ok("Call 3p tomorrow").let { assertEquals("Call", it.title); assertEquals(LocalTime.of(15, 0), it.time); assertEquals(tomorrow, it.date) }
        ok("Call 3:30p Friday").let { assertEquals(LocalTime.of(15, 30), it.time); assertEquals(friday, it.date) }
        ok("Gym at 6a").let { assertEquals("Gym", it.title); assertEquals(LocalTime.of(6, 0), it.time) }
        ok("Meeting room 6a").let { assertEquals("Meeting room 6a", it.title); assertNull(it.time) }
        ok("Take 3 a day").let { assertEquals("Take 3 a day", it.title) }
    }

    @Test fun midday() {
        ok("Lunch midday tomorrow").let { assertEquals("Lunch", it.title); assertEquals(LocalTime.NOON, it.time) }
        ok("Lunch at mid-day").let { assertEquals(LocalTime.NOON, it.time) }
        ok("Midday Mass").let { assertEquals("Midday Mass", it.title); assertNull(it.time) }
    }

    @Test fun dottedAndSlashedDates() {
        ok("Leave 3.10.2026").let { assertEquals("Leave", it.title); assertEquals(LocalDate.of(2026, 10, 3), it.date); assertNull(it.time) }
        ok("Flight 2026/10/03 9am").let { assertEquals("Flight", it.title); assertEquals(LocalDate.of(2026, 10, 3), it.date); assertEquals(LocalTime.of(9, 0), it.time) }
        ok("Flight 2026.10.03").let { assertEquals(LocalDate.of(2026, 10, 3), it.date) }
        ok("Leave 3/10/26 9am").let { assertEquals(LocalDate.of(2026, 10, 3), it.date) }
        ok("Leave 3.10.26").let { assertEquals(LocalDate.of(2026, 10, 3), it.date) }
        assertNotNull(parse("Leave 3/10/026").error)
    }

    @Test fun weekAfterNextAndFridayWeek() {
        val later = LocalDate.of(2026, 10, 9)
        for (text in listOf("Meeting Friday week", "Meeting a week on Friday", "Meeting a week from Friday")) {
            ok(text).let { assertEquals(text, "Meeting", it.title); assertEquals(text, later, it.date) }
        }
        for (text in listOf("Dentist week after next Tuesday", "Dentist Tuesday week after next", "Dentist the week after next on Tuesday")) {
            ok(text).let { assertEquals(text, "Dentist", it.title); assertEquals(text, LocalDate.of(2026, 10, 13), it.date) }
        }
        // On a Friday, Friday week is a week today.
        assertEquals(friday.plusWeeks(1), QuickEntry.parse("Meeting Friday week", friday).date)
    }

    @Test fun strayPunctuationAndLeadWordsLeaveTheTitle() {
        ok("Dentist 3 October, 2pm").let { assertEquals("Dentist", it.title); assertEquals(LocalTime.of(14, 0), it.time) }
        ok("Dentist, tomorrow, 2pm").let { assertEquals("Dentist", it.title) }
        ok("Submit report by Friday").let { assertEquals("Submit report", it.title); assertEquals(friday, it.date) }
        ok("Finish essay by 5pm tomorrow").let { assertEquals("Finish essay", it.title); assertEquals(LocalTime.of(17, 0), it.time) }
        for (text in listOf("Meeting around 3pm Friday", "Meeting about 3pm Friday", "Meeting approx 3pm Friday", "Meeting at around 3pm Friday",
            "Meeting 3pm-ish Friday", "Meeting 3pmish Friday", "Meeting ~3pm Friday")) {
            ok(text).let { assertEquals(text, "Meeting", it.title); assertEquals(text, LocalTime.of(15, 0), it.time) }
        }
        parse("Dinner 7ish Friday").let { assertEquals("Dinner", it.title); assertEquals(listOf(LocalTime.of(7, 0), LocalTime.of(19, 0)), it.timeChoices) }
        ok("Talk about the budget").let { assertEquals("Talk about the budget", it.title) }
    }

    @Test fun allDay() {
        for (text in listOf("Conference all day Friday", "Conference all-day Friday", "Conference Friday all day")) {
            ok(text).let { assertEquals(text, "Conference", it.title); assertEquals(friday, it.date); assertNull(it.time) }
        }
        assertNotNull(parse("Conference all day Friday 3pm").error)
    }

    @Test fun pluralWeekdaysRepeatWeekly() {
        ok("Yoga Tuesdays 6pm").let {
            assertEquals("Yoga", it.title); assertEquals(RepeatRule.WEEKLY, it.repeat)
            assertEquals(LocalDate.of(2026, 9, 29), it.date); assertEquals(LocalTime.of(18, 0), it.time)
        }
        ok("Call mum on Sundays").let { assertEquals("Call mum", it.title); assertEquals(DayOfWeek.SUNDAY, it.date.dayOfWeek); assertEquals(RepeatRule.WEEKLY, it.repeat) }
    }

    @Test fun everyOtherWeekdayIsFortnightly() {
        ok("Team lunch every other Friday").let {
            assertEquals("Team lunch", it.title); assertEquals(RepeatRule.FORTNIGHTLY, it.repeat); assertEquals(friday, it.date)
        }
    }

    @Test fun lastDayOfTheMonthAndNextMonth() {
        ok("Pay rent last day of the month").let { assertEquals("Pay rent", it.title); assertEquals(LocalDate.of(2026, 9, 30), it.date) }
        ok("Pay rent on the last day of the month").let { assertEquals("Pay rent", it.title) }
        ok("Dentist 3rd of next month").let { assertEquals("Dentist", it.title); assertEquals(LocalDate.of(2026, 10, 3), it.date) }
        ok("Dentist the 31st of next month").let { assertEquals(LocalDate.of(2026, 10, 31), it.date) }
        assertNotNull(QuickEntry.parse("Dentist 31st of next month", LocalDate.of(2026, 10, 5)).error) // November has 30 days
        assertNotNull(parse("Dentist next month").error)
    }

    @Test fun now() {
        ok("Meeting now").let { assertEquals("Meeting", it.title); assertEquals(today, it.date); assertEquals(LocalTime.of(10, 10), it.time) }
        ok("Call right now").let { assertEquals("Call", it.title) }
        ok("Now TV subscription").let { assertEquals("Now TV subscription", it.title); assertNull(it.time) }
        ok("Walk the dog now and then").let { assertEquals("Walk the dog now and then", it.title); assertNull(it.time) }
        assertNotNull(QuickEntry.parse("Meeting now", today).error)
    }

    @Test fun namedHolidays() {
        ok("Christmas Day lunch").let { assertEquals("Christmas Day lunch", it.title); assertEquals(LocalDate.of(2026, 12, 25), it.date) }
        ok("Christmas lunch").let { assertEquals(LocalDate.of(2026, 12, 25), it.date) }
        ok("Christmas Eve drinks 6pm").let { assertEquals(LocalDate.of(2026, 12, 24), it.date); assertEquals(LocalTime.of(18, 0), it.time) }
        ok("Boxing Day sales").let { assertEquals(LocalDate.of(2026, 12, 26), it.date) }
        ok("New Year's Eve party").let { assertEquals(LocalDate.of(2026, 12, 31), it.date); assertEquals("New Year's Eve party", it.title) }
        ok("New Year’s Day swim").let { assertEquals(LocalDate.of(2027, 1, 1), it.date) }
        ok("Mum birthday on Valentine's Day").let { assertEquals(LocalDate.of(2027, 2, 14), it.date) }
        ok("Halloween party").let { assertEquals(LocalDate.of(2026, 10, 31), it.date) }
        ok("Call on Christmas").let { assertEquals(LocalDate.of(2026, 12, 25), it.date) }
        // Another date wins; plain Christmas is not dated.
        ok("Xmas shopping Dec 20").let { assertEquals(LocalDate.of(2026, 12, 20), it.date); assertEquals("Xmas shopping", it.title) }
        ok("Christmas shopping").let { assertFalse(it.dateSpecified) }
        ok("Christmas party").let { assertFalse(it.dateSpecified) }
        ok("Halloween costume shopping").let { assertFalse(it.dateSpecified) }
        ok("Christmas Eve prep").let { assertFalse(it.dateSpecified) }
        // Kept literally, a holiday is only a word.
        val raw = "Halloween party"
        val p = parse(raw).phrases.single { it.kind == QuickPhraseKind.DATE }
        assertFalse(QuickEntry.parse(raw, today, listOf(p.start until p.end)).dateSpecified)
        // Every year from the next one.
        ok("Christmas Day every year").let { assertEquals(RepeatRule.YEARLY, it.repeat); assertEquals(LocalDate.of(2026, 12, 25), it.date) }
    }

    @Test fun firstWeekdayOfTheMonthIsARepeat() {
        assertEquals(RepeatRule.monthlyOn(1, DayOfWeek.MONDAY), ok("Board meeting first Monday of every month").repeat)
    }

    @Test fun numberBeforeAPartOfTheDay() {
        ok("Call 3 in the afternoon").let { assertEquals("Call", it.title); assertEquals(LocalTime.of(15, 0), it.time) }
        ok("Dinner 7 in the evening Friday").let { assertEquals("Dinner", it.title); assertEquals(LocalTime.of(19, 0), it.time); assertEquals(friday, it.date) }
        ok("Run 6 in the morning").let { assertEquals(LocalTime.of(6, 0), it.time) }
        ok("Dinner at 7 tonight").let { assertEquals("Dinner", it.title); assertEquals(LocalTime.of(19, 0), it.time) }
        ok("Movie 8 tonight").let { assertEquals("Movie", it.title); assertEquals(LocalTime.of(20, 0), it.time); assertEquals(today, it.date) }
        assertNotNull(parse("Run 9 in the evening 7pm").error)
    }

    @Test fun tilAsARangeWord() {
        ok("Party Sat 8pm til midnight").let { assertEquals("Party", it.title); assertEquals(LocalTime.of(20, 0), it.time); assertEquals(240, it.durationMinutes) }
        ok("Party Sat 8pm till 11pm").let { assertEquals(180, it.durationMinutes) }
    }

    @Test fun remindMeWithAWhenBeforeTo() {
        ok("Remind me tomorrow to call Bob").let { assertEquals("Call Bob", it.title); assertEquals(tomorrow, it.date); assertTrue(it.taskHint) }
        ok("Remind me in 2 hours to check the oven").let { assertEquals("Check the oven", it.title); assertEquals(LocalTime.of(12, 10), it.time) }
        ok("Remind me at 5pm to call Bob").let { assertEquals("Call Bob", it.title); assertEquals(LocalTime.of(17, 0), it.time) }
        ok("Remind me on Friday to pay rent").let { assertEquals("Pay rent", it.title); assertEquals(friday, it.date) }
    }

    @Test fun compoundAndClockReminders() {
        assertEquals(90, ok("Dentist tomorrow 3pm remind me 1 hour and 30 minutes before").reminderMinutes)
        assertEquals(90, ok("Dentist tomorrow 3pm remind me an hour and a half before").reminderMinutes)
        assertEquals(30, ok("Dentist tomorrow 3pm remind me half an hour before").reminderMinutes)
        ok("Dentist tomorrow 3pm remind me at 9am").let { assertEquals("Dentist", it.title); assertEquals(360, it.reminderMinutes); assertEquals(LocalTime.of(15, 0), it.time) }
        assertEquals(1440 + 360, ok("Dentist tomorrow 3pm remind me at 9am the day before").reminderMinutes)
        // A reminder clock time after the event means the day before.
        assertEquals(15 * 60, ok("Flight tomorrow 6am remind me at 3pm").reminderMinutes)
        assertNotNull(parse("Dentist tomorrow remind me at 9am").error)
        assertNotNull(parse("Dentist tomorrow 3pm remind me at 9").error)
        assertNotNull(parse("Dentist notify me half a minute before").error)
    }
}
