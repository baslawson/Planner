package com.example.itinerary.data

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

// 1 Oct 2026 review: time ranges without am/pm, backwards hour and day ranges, lengths such as "1 hour 30".
class QuickRangesLengthsTest {
    private val today = LocalDate.of(2026, 10, 1) // a Thursday
    private fun parse(text: String) = QuickEntry.parse(text, today, now = LocalDateTime.of(2026, 10, 1, 10, 7), dayFirst = true, zone = ZoneId.of("Australia/Sydney"))
    private fun ok(text: String) = parse(text).also { assertTrue("$text: ${it.error}", it.error == null || it.clarificationOnly) }
    private fun day(month: Int, day: Int, year: Int = 2026) = LocalDate.of(year, month, day)
    private fun at(hour: Int, minute: Int = 0) = LocalTime.of(hour, minute)
    private val morningOrAfternoon = "Morning or afternoon? Choose a time below, or type am or pm."
    private fun asks(text: String, first: LocalTime, minutes: Int?) = parse(text).let {
        assertEquals(text, morningOrAfternoon, it.error); assertTrue(text, it.clarificationOnly)
        assertNull(text, it.time); assertEquals(text, listOf(first, first.plusHours(12)), it.timeChoices); assertEquals(text, minutes, it.durationMinutes)
    }

    @Test fun aColonOrDotRangeWithoutAmPmAsks() {
        asks("Meeting 1:00-2:00 tomorrow", at(1), 60)
        asks("Dinner 7:30-9:30 Friday", at(7, 30), 120)
        asks("Dinner 7.30-9.30 Friday", at(7, 30), 120)
        asks("Meeting 9:00-10:30 tomorrow", at(9), 90)
        asks("Lunch 12:30-1:30 tomorrow", at(0, 30), 60)
        // Only one reading: an hour of 0 or above 12, or a leading zero, as for a single time.
        ok("Meeting 13:00-14:00 tomorrow").let { assertEquals(at(13), it.time); assertEquals(60, it.durationMinutes); assertNull(it.error) }
        ok("Shift 9:00-17:30 Monday").let { assertEquals(at(9), it.time); assertEquals(510, it.durationMinutes); assertNull(it.error) }
        ok("Shift 08:00-09:30 Monday").let { assertEquals(at(8), it.time); assertEquals(90, it.durationMinutes); assertNull(it.error) }
        ok("Call 0:30-1:30 tomorrow").let { assertEquals(at(0, 30), it.time); assertNull(it.error) }
        ok("Shift 1000-1200 Monday").let { assertEquals(at(10), it.time); assertEquals(120, it.durationMinutes); assertNull(it.error) }
        ok("Dinner 7:30-9:30pm Friday").let { assertEquals(at(19, 30), it.time); assertNull(it.error) }
        // The working day still needs no question.
        ok("Work 9:00-5:00 Monday").let { assertEquals(at(9), it.time); assertEquals(480, it.durationMinutes); assertNull(it.error) }
        ok("Work 8:30-5 Monday").let { assertEquals(at(8, 30), it.time); assertEquals(510, it.durationMinutes); assertNull(it.error) }
    }

    @Test fun aBackwardsHourRangeIsTheWorkingDayOnlyFromMorningToAfternoon() {
        asks("Party Saturday 8-1", at(8), 300)
        asks("Match 2-1 Saturday", at(2), 660)
        asks("Score 3-1 Saturday", at(3), 600)
        ok("Work 9-5 Monday").let { assertEquals(at(9), it.time); assertEquals(480, it.durationMinutes); assertNull(it.error) }
        ok("Shift Saturday 10-2").let { assertEquals(at(10), it.time); assertEquals(240, it.durationMinutes); assertNull(it.error) }
        ok("Shift Saturday 6-2").let { assertEquals(at(6), it.time); assertEquals(480, it.durationMinutes); assertNull(it.error) }
        ok("Work 9-5 weekdays").let { assertEquals(at(9), it.time); assertNull(it.error) }
        ok("Brunch 11-1 Sunday").let { assertEquals(at(11), it.time); assertEquals(120, it.durationMinutes); assertNull(it.error) }
    }
}
