package com.example.itinerary.data

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

// Fifth bug hunt of 1 Oct 2026 (E2, E3, E8: regressions from 8d72e5d; probes qa/parser-probe/probe-hunt5.txt and
// probe-hunt5b.txt). When unsure, a rule keeps the old refusal rather than guessing.
class QuickFixesHuntFiveTest {
    private val today = LocalDate.of(2026, 10, 1) // a Thursday
    private fun parse(text: String, dayFirst: Boolean? = true) =
        QuickEntry.parse(text, today, now = LocalDateTime.of(2026, 10, 1, 10, 0), dayFirst = dayFirst, zone = ZoneId.of("Australia/Perth"))
    private fun ok(text: String, dayFirst: Boolean? = true) = parse(text, dayFirst).also { assertTrue("$text: ${it.error}", it.error == null || it.clarificationOnly) }
    private fun refused(text: String) = parse(text).also { assertNotNull(text, it.error); assertFalse(text, it.clarificationOnly) }.error!!
    private fun day(month: Int, day: Int) = LocalDate.of(2026, month, day)
    private fun at(hour: Int, minute: Int = 0) = LocalTime.of(hour, minute)

    // E2: a three-digit number is a range start only when it touches the dash ("630-830pm"); with a space or a range word
    // it is title text, as before 8d72e5d.
    @Test fun numberBeforeASpacedDashStaysInTheTitle() {
        ok("Bus 150 - 3pm").let { assertEquals("Bus 150", it.title); assertEquals(today, it.date); assertEquals(at(15), it.time); assertNull(it.durationMinutes) }
        ok("Flight QF 123 - 9am Friday").let { assertEquals("Flight QF 123", it.title); assertEquals(day(10, 2), it.date); assertEquals(at(9), it.time); assertNull(it.durationMinutes) }
        ok("Room 101 - 2pm").let { assertEquals("Room 101", it.title); assertEquals(at(14), it.time); assertNull(it.durationMinutes) }
        ok("Meet at room 204 till 3pm").let { assertEquals("Meet", it.title); assertEquals(at(15), it.time); assertNull(it.durationMinutes) }
        ok("Room 303 - 4pm Monday").let { assertEquals("Room 303", it.title); assertEquals(day(10, 5), it.date); assertEquals(at(16), it.time); assertNull(it.durationMinutes) }
        // Touching the dash, still a range (D7).
        ok("Dinner 630-830pm").let { assertEquals("Dinner", it.title); assertEquals(at(18, 30), it.time); assertEquals(120, it.durationMinutes) }
        ok("Meeting 930-1030am").let { assertEquals("Meeting", it.title); assertEquals(at(9, 30), it.time); assertEquals(60, it.durationMinutes) }
        ok("Swim 545-645am Thursday").let { assertEquals("Swim", it.title); assertEquals(at(5, 45), it.time); assertEquals(60, it.durationMinutes) }
    }

    // E3: beside a numeric range that could be 24-hour hours, the ordinal isn't dropped: two dates, so it asks.
    @Test fun ordinalBesideAHoursRangeStillAsksForOneDate() {
        assertTrue(refused("Workshop 5th 10-14").startsWith("Use one date"))
        assertTrue(refused("Conference 5th 9-17").startsWith("Use one date"))
        // A numeric date that can't be hours still leaves the ordinal in the title (D2).
        ok("Sam's 21st 17/10 7pm").let { assertEquals("Sam's 21st", it.title); assertEquals(day(10, 17), it.date); assertEquals(at(19), it.time) }
        ok("Sam's 21st 17 Oct 7pm").let { assertEquals("Sam's 21st", it.title); assertEquals(day(10, 17), it.date); assertEquals(at(19), it.time) }
        ok("Sam's 21st tomorrow 7pm").let { assertEquals("Sam's 21st", it.title); assertEquals(day(10, 2), it.date) }
    }

    // E8: "for N" is a party size only for 1–20 (or one–twenty); a larger number may be an unfinished length, so it asks.
    @Test fun onlySmallNumbersAfterForArePartySizes() {
        refused("Study for 45 Monday 4pm")
        refused("Run for 30 tomorrow 6am")
        refused("Party for 21 next Saturday")
        ok("Table for 4 Saturday 7pm").let { assertEquals("Table for 4", it.title); assertEquals(day(10, 3), it.date); assertEquals(at(19), it.time) }
        ok("Table for 1 Friday 12pm").let { assertEquals("Table for 1", it.title); assertEquals(at(12), it.time) }
        ok("Dinner for two Friday 7pm").let { assertEquals("Dinner for two", it.title); assertEquals(day(10, 2), it.date); assertEquals(at(19), it.time) }
        ok("Dinner for twelve Saturday 7pm").let { assertEquals("Dinner for twelve", it.title); assertEquals(day(10, 3), it.date) }
        ok("Party for 20 next Saturday").let { assertEquals("Party for 20", it.title); assertEquals(day(10, 10), it.date) }
        ok("Study for 45 minutes Monday 4pm").let { assertEquals("Study", it.title); assertEquals(45, it.durationMinutes); assertEquals(at(16), it.time) }
    }
}
