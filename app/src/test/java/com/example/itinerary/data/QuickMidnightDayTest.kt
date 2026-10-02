package com.example.itinerary.data

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

// Bug hunt 2 Oct 2026 (qa/bughunt-20261002, Q-3): "midnight" with a day is the end of that day, 00:00 of the next one,
// not the night before it.
class QuickMidnightDayTest {
    private val today = LocalDate.of(2026, 10, 1) // a Thursday
    private fun parse(text: String) = QuickEntry.parse(text, today, now = LocalDateTime.of(2026, 10, 1, 10, 0), dayFirst = true, zone = ZoneId.of("Australia/Perth"))
    private fun ok(text: String) = parse(text).also { assertTrue("$text: ${it.error}", it.error == null || it.clarificationOnly) }
    private fun day(month: Int, day: Int) = LocalDate.of(2026, month, day)

    @Test fun midnightWithADayIsTheEndOfThatDay() {
        for ((text, title) in listOf(
            "Submit report by midnight Friday" to "Submit report",
            "Assignment due Friday midnight" to "Assignment",
            "Submit at midnight on Friday" to "Submit",
            "Launch Friday at 12 midnight" to "Launch",
        )) ok(text).let {
            assertEquals(text, title, it.title); assertEquals(text, day(10, 3), it.date); assertEquals(text, LocalTime.MIDNIGHT, it.time)
        }
        ok("Deadline midnight 5/10").let { assertEquals(day(10, 6), it.date); assertEquals(LocalTime.MIDNIGHT, it.time) }
        ok("Deadline midnight tomorrow").let { assertEquals(day(10, 3), it.date); assertEquals(LocalTime.MIDNIGHT, it.time) }
        // A reminder before it counts back from the new start: 9pm Friday is 3 hours before.
        ok("Submit report midnight Friday remind me at 9pm").let { assertEquals(day(10, 3), it.date); assertEquals(180, it.reminderMinutes) }
    }

    @Test fun otherMidnightsAreUnchanged() {
        // No date: tonight's 00:00 as before (the past-time warning offers tomorrow).
        ok("Flight at midnight").let { assertEquals(today, it.date); assertEquals(LocalTime.MIDNIGHT, it.time) }
        // "midnight tonight" keeps its 23:59 on the day.
        ok("Deadline midnight tonight").let { assertEquals(today, it.date); assertEquals(LocalTime.of(23, 59), it.time) }
        // 12am and 00:00 with a day are a clock time, read as written.
        ok("Flight Friday 12am").let { assertEquals(day(10, 2), it.date); assertEquals(LocalTime.MIDNIGHT, it.time) }
        ok("Flight Friday 00:00").let { assertEquals(day(10, 2), it.date); assertEquals(LocalTime.MIDNIGHT, it.time) }
        // A range ending at midnight is unchanged.
        ok("Party Sat 8pm til midnight").let { assertEquals(day(10, 3), it.date); assertEquals(LocalTime.of(20, 0), it.time); assertEquals(240, it.durationMinutes) }
        // A name, not a time.
        ok("Watch Midnight in Paris Friday").let { assertEquals(day(10, 2), it.date); assertNull(it.time) }
    }
}
