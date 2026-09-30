package com.example.itinerary.data

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

// Second bug hunt of 30 Sep 2026 (B2, B6, B7; probe qa/parser-probe/probe-hunt2.txt).
class QuickFixesHuntTwoTest {
    private val today = LocalDate.of(2026, 9, 30) // a Wednesday
    private fun parse(text: String, dayFirst: Boolean? = true) =
        QuickEntry.parse(text, today, now = LocalDateTime.of(2026, 9, 30, 10, 0), dayFirst = dayFirst, zone = ZoneId.of("Australia/Perth"))
    private fun ok(text: String, dayFirst: Boolean? = true) = parse(text, dayFirst).also { assertTrue("$text: ${it.error}", it.error == null || it.clarificationOnly) }
    private fun refused(text: String) = parse(text).also { assertNotNull(text, it.error); assertFalse(text, it.clarificationOnly) }.error!!
    private fun day(month: Int, day: Int) = LocalDate.of(2026, month, day)
    private fun at(hour: Int) = LocalTime.of(hour, 0)

    // B2: an hour range beside a day or repeat is hours again.
    @Test fun hourRangesBesideADayAreHours() {
        ok("Dinner tonight 7-10").let { assertEquals("Dinner", it.title); assertEquals(today, it.date); assertEquals(at(19), it.time); assertEquals(180, it.durationMinutes) }
        ok("Drinks tonight 8-11").let { assertEquals(at(20), it.time); assertEquals(180, it.durationMinutes) }
        ok("Gym tomorrow 9-10").let { assertEquals("Gym", it.title); assertEquals(day(10, 1), it.date); assertTrue(it.clarificationOnly); assertEquals(60, it.durationMinutes) }
        ok("Meeting next Friday 9-10").let { assertEquals(day(10, 9), it.date); assertTrue(it.clarificationOnly); assertEquals(60, it.durationMinutes) }
        ok("Meeting Friday 9-10").let { assertEquals("Meeting", it.title); assertEquals(day(10, 2), it.date); assertTrue(it.clarificationOnly); assertEquals(60, it.durationMinutes) }
        ok("Class Tuesday 10-11").let { assertEquals(day(10, 6), it.date); assertTrue(it.clarificationOnly) }
        ok("Choir every Thursday 8-10").let {
            assertEquals("Choir", it.title); assertEquals(day(10, 1), it.date); assertEquals(RepeatRule.WEEKLY, it.repeat)
            assertTrue(it.clarificationOnly); assertEquals(120, it.durationMinutes)
        }
        ok("Standup weekdays 9-10").let { assertEquals(today, it.date); assertEquals(RepeatRule.WEEKDAYS, it.repeat); assertTrue(it.clarificationOnly) }
        ok("Study daily 7-10").let { assertEquals(today, it.date); assertEquals(RepeatRule.DAILY, it.repeat); assertEquals(180, it.durationMinutes) }
        for (dayFirst in listOf(true, false, null)) ok("Shift tomorrow 10-2", dayFirst).let {
            assertEquals("Shift", it.title); assertEquals(day(10, 1), it.date); assertEquals(at(10), it.time); assertEquals(240, it.durationMinutes); assertNull(it.error)
        }
    }

    // B2: still a date where the weekday cross-checks it, or beside another clock time.
    @Test fun hyphenDatesThatStayDates() {
        ok("Dentist Fri 2-10").let { assertEquals("Dentist", it.title); assertEquals(day(10, 2), it.date); assertNull(it.time); assertNull(it.error) }
        ok("Gym every Monday 12-10 6pm").let { assertEquals(day(10, 12), it.date); assertEquals(at(18), it.time) }
        ok("Dentist Fri 2-10 3pm").let { assertEquals(day(10, 2), it.date); assertEquals(at(15), it.time) }
        ok("Work Monday 9-5").let { assertEquals(day(10, 5), it.date); assertEquals(at(9), it.time); assertEquals(480, it.durationMinutes) }
    }

    // B6: a number in the title is not another clock time.
    @Test fun numbersInTheTitleDoNotCancelHours() {
        ok("Budget 2026 review Monday 9-5").let { assertEquals("Budget 2026 review", it.title); assertEquals(day(10, 5), it.date); assertEquals(at(9), it.time); assertEquals(480, it.durationMinutes) }
        ok("Room 1204 Monday 9-5").let { assertEquals("Room 1204", it.title); assertEquals(day(10, 5), it.date); assertEquals(at(9), it.time); assertEquals(480, it.durationMinutes) }
    }

    // B7: "this Monday" on a Wednesday is this week's, already past.
    @Test fun thisWeekdayBeforeTodayIsPast() {
        ok("Dentist this Monday").let { assertEquals(day(9, 28), it.date); assertTrue(it.pastDate) }
        ok("Dentist Monday this week").let { assertEquals(day(9, 28), it.date); assertTrue(it.pastDate) }
        assertTrue(refused("Gym this Monday every week").startsWith("A repeat can't start in the past"))
        assertTrue(refused("Gym yesterday every day").startsWith("A repeat can't start in the past"))
        ok("Dentist this Friday").let { assertEquals(day(10, 2), it.date); assertFalse(it.pastDate) }
        ok("Dentist this Wednesday").let { assertEquals(today, it.date); assertFalse(it.pastDate) }
        ok("Gym this Friday every week").let { assertEquals(day(10, 2), it.date); assertFalse(it.pastDate); assertEquals(RepeatRule.WEEKLY, it.repeat) }
    }
}
