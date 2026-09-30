package com.example.itinerary.data

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

// Parser round 8 (probe list qa/parser-probe/probe-round8.txt): dates before today, for logging what happened.
class QuickRound8Test {
    private val today = LocalDate.of(2026, 9, 30) // a Wednesday
    private val now = LocalDateTime.of(2026, 9, 30, 10, 0)
    private val perth = ZoneId.of("Australia/Perth")
    private fun parse(text: String) = QuickEntry.parse(text, today, now = now, dayFirst = true, zone = perth)
    private fun ok(text: String) = parse(text).also { assertTrue("$text: ${it.error}", it.error == null || it.clarificationOnly) }
    private fun refused(text: String) = parse(text).also { assertNotNull(text, it.error); assertFalse(text, it.clarificationOnly) }.error!!
    private fun day(month: Int, day: Int) = LocalDate.of(2026, month, day)
    private fun at(hour: Int, minute: Int = 0) = LocalTime.of(hour, minute)

    @Test fun yesterday() {
        ok("service td929 yesterday from 1700 to 0600").let {
            assertEquals("service td929", it.title); assertEquals(day(9, 29), it.date); assertEquals(at(17), it.time)
            assertEquals(780, it.durationMinutes); assertTrue(it.pastDate); assertNull(it.error)
        }
        ok("Dentist yesterday 3pm").let { assertEquals("Dentist", it.title); assertEquals(day(9, 29), it.date); assertEquals(at(15), it.time) }
        ok("Yesterday gym 6am").let { assertEquals("Gym", it.title); assertEquals(day(9, 29), it.date) }
        ok("Car service yesterday").let { assertEquals(day(9, 29), it.date); assertNull(it.time); assertTrue(it.dateSpecified) }
        ok("Oil change the day before yesterday").let { assertEquals("Oil change", it.title); assertEquals(day(9, 28), it.date) }
        ok("Pay rent by yesterday").let { assertEquals("Pay rent", it.title); assertEquals(day(9, 29), it.date) }
        ok("Pills yesterday 8am and 8pm").let { assertEquals(day(9, 29), it.date); assertEquals(listOf(at(20)), it.extraTimes) }
        ok("Swim yesterday all morning").let { assertEquals(day(9, 29), it.date); assertEquals(at(9), it.time) }
    }

    @Test fun partsOfYesterday() {
        ok("Gym yesterday morning").let { assertEquals("Gym", it.title); assertEquals(day(9, 29), it.date); assertTrue(it.ambiguousTime) }
        ok("Gym yesterday morning 6").let { assertEquals(day(9, 29), it.date); assertEquals(at(6), it.time) }
        ok("Dinner with Sam yesterday evening at 7").let { assertEquals(at(19), it.time); assertEquals(day(9, 29), it.date) }
        ok("Party last night").let { assertEquals("Party", it.title); assertEquals(day(9, 29), it.date); assertTrue(it.ambiguousTime); assertTrue(it.pastDate) }
        ok("Party last night 9pm").let { assertEquals(day(9, 29), it.date); assertEquals(at(21), it.time) }
        ok("Movie last night 11pm-1am").let { assertEquals(at(23), it.time); assertEquals(120, it.durationMinutes) }
        // The early hours after last night are this morning.
        ok("Call with Jo last night at 1am").let { assertEquals(today, it.date); assertEquals(at(1), it.time) }
        ok("Last night of the Proms Saturday").let { assertEquals("Last night of the Proms", it.title); assertEquals(day(10, 3), it.date); assertFalse(it.pastDate) }
    }

    @Test fun agoAndLast() {
        assertEquals(day(9, 28), ok("Plumber 2 days ago").date)
        ok("Plumber two days ago 2pm").let { assertEquals("Plumber", it.title); assertEquals(day(9, 28), it.date); assertEquals(at(14), it.time) }
        assertEquals(day(9, 23), ok("Haircut a week ago").date)
        assertEquals(day(9, 9), ok("Tyres 3 weeks ago").date)
        assertEquals(day(9, 16), ok("Vet a fortnight ago").date)
        ok("Rego renewed a month ago").let { assertEquals("Rego renewed", it.title); assertEquals(day(8, 30), it.date) }
        assertEquals(day(3, 30), ok("Service 6 months ago").date)
        // The most recent one before today, even on that weekday.
        ok("Meeting last Friday 2pm").let { assertEquals(day(9, 25), it.date); assertEquals(at(14), it.time) }
        assertEquals(day(9, 23), ok("Meeting last Wednesday").date)
        assertEquals(day(9, 23), ok("Meeting last Wed").date)
        ok("Last Monday team lunch 12pm").let { assertEquals("Team lunch", it.title); assertEquals(day(9, 28), it.date) }
        // Unchanged: the last weekday of a month.
        ok("Book club last Friday of the month").let { assertEquals(RepeatRule.monthlyOn(RepeatRule.LAST, java.time.DayOfWeek.FRIDAY), it.repeat) }
        ok("Dinner last Friday of October").let { assertEquals(day(10, 30), it.date); assertFalse(it.pastDate) }
    }

    @Test fun titlesAndLimits() {
        ok("Review yesterday's notes tomorrow").let { assertEquals("Review yesterday's notes", it.title); assertEquals(day(10, 1), it.date) }
        ok("Read yesterday's paper").let { assertEquals("Read yesterday's paper", it.title); assertFalse(it.dateSpecified) }
        assertTrue(refused("Gym yesterday every Monday").startsWith("A repeat can't start in the past"))
        assertTrue(refused("Gym every Monday starting yesterday").startsWith("A repeat can't start in the past"))
        assertTrue(refused("Call mum yesterday and today").startsWith("Use one date"))
        // Future dates are not marked as past.
        assertFalse(ok("Dentist tomorrow 3pm").pastDate)
        assertFalse(ok("Dentist this Monday").pastDate)
    }

    @Test fun pastDateIsReadAgainstTheDraftsDay() {
        val input = QuickInput("Service yesterday 5pm", baseDate = LocalDate.of(2026, 9, 28))
        assertEquals(LocalDate.of(2026, 9, 27), input.parse().date)
    }
}
