package com.example.itinerary.data

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

// Bug hunt 33 (probe of 263 sentences on Thu 15 Oct 2026, Brisbane): the quick entry parser.
class QuickHuntThirtyThreeTest {
    private val today = LocalDate.of(2026, 10, 15) // a Thursday
    private val now = LocalDateTime.of(2026, 10, 15, 14, 0)
    private val brisbane = ZoneId.of("Australia/Brisbane")
    private fun parse(text: String) = QuickEntry.parse(text, today, now = now, dayFirst = true, zone = brisbane)
    private fun ok(text: String) = parse(text).also { assertTrue("$text: ${it.error}", it.error == null || it.clarificationOnly) }
    private fun refused(text: String) = parse(text).also { assertNotNull(text, it.error); assertFalse(text, it.clarificationOnly) }.error!!
    private fun day(month: Int, day: Int) = LocalDate.of(2026, month, day)
    private fun at(hour: Int, minute: Int = 0) = LocalTime.of(hour, minute)

    // Q1: counted from the day named, not that day itself with "a week" left in the title.
    @Test fun countedFromADay() {
        ok("Call Bob a week from tomorrow").let { assertEquals("Call Bob", it.title); assertEquals(day(10, 23), it.date) }
        assertEquals(day(10, 30), ok("Call Bob two weeks from tomorrow").date)
        assertEquals(day(10, 19), ok("Call Bob 3 days from tomorrow").date)
        assertEquals(day(11, 16), ok("Call Bob a month from tomorrow").date)
        ok("Dentist 2 weeks from Monday").let { assertEquals("Dentist", it.title); assertEquals(day(11, 2), it.date) }
        // Unchanged: from today, a week on Friday, a stay from Monday.
        assertEquals(day(10, 22), ok("Call Bob a week from today").date)
        assertEquals(day(10, 23), ok("Call Bob a week from Friday").date)
        ok("Trip for 5 days from Monday").let { assertEquals(day(10, 19), it.date); assertEquals(day(10, 23), it.endDate) }
    }

    // Q2: "last day of every month" without "the".
    @Test fun lastDayOfEveryMonth() {
        ok("Rent last day of every month").let {
            assertEquals("Rent", it.title); assertEquals(day(10, 31), it.date); assertEquals(RepeatRule.Kind.MONTHLY, it.repeat.kind); assertEquals(31, it.repeatAnchorDay)
        }
        assertEquals(day(10, 31), ok("Rent monthly on last day").date)
        assertEquals(day(10, 31), ok("Rent the last day of every month").date)
    }

    // Q3, Q8: an hour in words.
    @Test fun hoursInWords() {
        ok("Tea at four tomorrow").let { assertEquals("Tea", it.title); assertEquals("", it.location); assertEquals(listOf(at(4), at(16)), it.timeChoices) }
        ok("Tea tomorrow at seven pm").let { assertEquals("", it.location); assertEquals(at(19), it.time) }
        ok("Tea tomorrow at seven thirty").let { assertEquals(listOf(at(7, 30), at(19, 30)), it.timeChoices) }
        ok("Dinner tonight at eight").let { assertEquals(day(10, 15), it.date); assertEquals(at(20), it.time) }
        ok("Tea four thirty pm tomorrow").let { assertEquals("Tea", it.title); assertEquals(at(16, 30), it.time) }
        ok("Movie half past twelve tomorrow").let { assertEquals("Movie", it.title); assertEquals(listOf(at(0, 30), at(12, 30)), it.timeChoices) }
        // Names stay places: capitalised, or followed by more of the name.
        ok("Meet at Four Seasons tomorrow 3pm").let { assertEquals("Four Seasons", it.location); assertEquals(at(15), it.time) }
        ok("Party at Seven Tuesday").let { assertEquals("Seven", it.location); assertNull(it.time) }
        assertEquals("one point", ok("Meet at one point tomorrow").location)
    }

    // Q4: an end before the start in the same half of the day.
    @Test fun endBeforeStart() {
        listOf("Study tomorrow 3pm to 2pm", "Study tomorrow 9am to 8am", "Study tomorrow 10am-9am", "Study tomorrow 15:00-14:00",
            "Party noon to 11am Saturday").forEach {
            assertEquals(it, "The end time is before the start time. Correct one of them.", refused(it))
        }
        // Overnight still works.
        assertEquals(480, ok("Shift Friday 10pm-6am").durationMinutes)
        assertEquals(780, ok("Study tomorrow 2pm to 3am").durationMinutes)
        assertEquals(180, ok("Party Friday 9pm to midnight").durationMinutes)
        assertEquals(480, ok("Shift Friday 22:00-06:00").durationMinutes)
        assertEquals(960, ok("Shift Friday 1pm to 5am").durationMinutes)
    }

    // Q5: overseas abbreviations, with daylight saving.
    @Test fun moreTimeZones() {
        ok("Meeting 3pm PST tomorrow").let { assertEquals("Meeting", it.title); assertEquals(day(10, 17), it.date); assertEquals(at(8), it.time) }
        // Bug hunt 34: EST and EDT stay in the title; in Australia they usually mean Sydney time.
        ok("Webinar 7pm EST Thursday").let { assertEquals("Webinar EST", it.title); assertEquals(day(10, 15), it.date); assertEquals(at(19), it.time) }
        assertEquals(at(23), ok("Call 3pm CET tomorrow").time)
        ok("Call 3pm BST tomorrow").let { assertEquals(day(10, 17), it.date); assertEquals(at(0), it.time) }
        assertEquals(at(12), ok("Call 3pm NZT tomorrow").time)
        assertEquals(at(16), ok("Call 3pm JST tomorrow").time)
        // Ambiguous or ordinary words stay in the title.
        assertEquals("Gym PT", ok("Gym 6am PT tomorrow").title)
    }

    // Q6: a second time without am/pm is asked about, not dropped into the title.
    @Test fun secondTimeWithoutAmPm() {
        assertEquals("Add am or pm to each time, for example 8am and 8pm.", refused("Pills 8am and 8 daily"))
        ok("Pills at 8am and 20 daily").let { assertEquals(at(8), it.time); assertEquals(listOf(at(20)), it.extraTimes) }
        ok("Meeting 3pm and 4 people tomorrow").let { assertEquals(at(15), it.time); assertEquals("Meeting and 4 people", it.title) }
    }

    // Q7: "Remind me tomorrow morning to …".
    @Test fun remindMeWithAPartOfTheDay() {
        ok("Remind me tomorrow morning to buy milk").let { assertEquals("Buy milk", it.title); assertTrue(it.taskHint); assertEquals(day(10, 16), it.date) }
    }
}
