package com.example.itinerary.data

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

// Fourth bug hunt of 30 Sep 2026 (D2–D4, D6, D7; probes qa/parser-probe/probe-hunt4.txt and probe-hunt4b.txt).
class QuickFixesHuntFourTest {
    private val today = LocalDate.of(2026, 9, 30) // a Wednesday
    private fun parse(text: String, dayFirst: Boolean? = true) =
        QuickEntry.parse(text, today, now = LocalDateTime.of(2026, 9, 30, 10, 0), dayFirst = dayFirst, zone = ZoneId.of("Australia/Perth"))
    private fun ok(text: String, dayFirst: Boolean? = true) = parse(text, dayFirst).also { assertTrue("$text: ${it.error}", it.error == null || it.clarificationOnly) }
    private fun refused(text: String) = parse(text).also { assertNotNull(text, it.error); assertFalse(text, it.clarificationOnly) }.error!!
    private fun day(month: Int, day: Int) = LocalDate.of(2026, month, day)
    private fun at(hour: Int, minute: Int = 0) = LocalTime.of(hour, minute)

    // D2: an ordinal without "the" is a date only when nothing else in the entry is one, it is 1–31, and no date follows it.
    @Test fun birthdayOrdinalsBesideADateStayInTheTitle() {
        ok("Sam's 21st 17 Oct 7pm").let { assertEquals("Sam's 21st", it.title); assertEquals(day(10, 17), it.date); assertEquals(at(19), it.time) }
        ok("Mum's 60th, 12 Oct").let { assertEquals("Mum's 60th", it.title); assertEquals(day(10, 12), it.date); assertNull(it.time) }
        ok("Sam's 21st 7pm Saturday").let { assertEquals("Sam's 21st", it.title); assertEquals(day(10, 3), it.date); assertEquals(at(19), it.time) }
        ok("Grandma's 90th at 2pm Sunday").let { assertEquals("Grandma's 90th", it.title); assertEquals(day(10, 4), it.date); assertEquals(at(14), it.time) }
        ok("Dad's 50th 7pm").let { assertEquals("Dad's 50th", it.title); assertEquals(today, it.date); assertEquals(at(19), it.time) }
        ok("Sam's 21st 17th October").let { assertEquals("Sam's 21st", it.title); assertEquals(day(10, 17), it.date) }
        ok("Aunt Mary's 80th 25 October 1pm").let { assertEquals("Aunt Mary's 80th", it.title); assertEquals(day(10, 25), it.date); assertEquals(at(13), it.time) }
        ok("Sam's 21st tomorrow 7pm").let { assertEquals("Sam's 21st", it.title); assertEquals(day(10, 1), it.date); assertEquals(at(19), it.time) }
        ok("Sam's 21st 17/10 7pm").let { assertEquals("Sam's 21st", it.title); assertEquals(day(10, 17), it.date); assertEquals(at(19), it.time) }
        // Still dates (C12).
        ok("Dentist on 3rd at 2pm").let { assertEquals("Dentist", it.title); assertEquals(day(10, 3), it.date); assertEquals(at(14), it.time) }
        ok("Dentist 3rd 2pm").let { assertEquals("Dentist", it.title); assertEquals(day(10, 3), it.date); assertEquals(at(14), it.time) }
        ok("Dentist 3rd at 2pm").let { assertEquals("Dentist", it.title); assertEquals(day(10, 3), it.date) }
    }

    // D3: a time after the month ("8.30am", "10:30") is not the day of a month-first date.
    @Test fun timeAfterADateRangeIsNotADay() {
        ok("Conference 12-14 Nov 8.30am").let {
            assertEquals("Conference", it.title); assertEquals(day(11, 12), it.date); assertEquals(day(11, 14), it.endDate); assertEquals(at(8, 30), it.time)
        }
        ok("Trip 3-7 Oct 10:30").let {
            assertEquals("Trip", it.title); assertEquals(day(10, 3), it.date); assertEquals(day(10, 7), it.endDate)
            assertTrue(it.clarificationOnly); assertEquals(listOf(at(10, 30), at(22, 30)), it.timeChoices)
        }
        // Hours before a month-first date still (C5).
        ok("Work 9-5 Oct 3rd").let { assertEquals("Work", it.title); assertEquals(day(10, 3), it.date); assertEquals(at(9), it.time); assertEquals(480, it.durationMinutes); assertNull(it.endDate) }
        ok("Lunch 12-2 Oct 5").let { assertEquals("Lunch", it.title); assertEquals(day(10, 5), it.date); assertEquals(120, it.durationMinutes); assertNull(it.endDate) }
    }

    // D4: "for" with a number is a party size in the title, not an unfinished length.
    @Test fun partySizeAfterForStaysInTheTitle() {
        ok("Table for 4 Saturday 7pm").let { assertEquals("Table for 4", it.title); assertEquals(day(10, 3), it.date); assertEquals(at(19), it.time) }
        ok("Dinner for 2 Friday").let { assertEquals("Dinner for 2", it.title); assertEquals(day(10, 2), it.date) }
        ok("Booking for 6 tomorrow at 7pm").let { assertEquals("Booking for 6", it.title); assertEquals(day(10, 1), it.date); assertEquals(at(19), it.time) }
        ok("Dinner for two Friday 7pm").let { assertEquals("Dinner for two", it.title); assertEquals(day(10, 2), it.date); assertEquals(at(19), it.time) }
        ok("Book table for 6 for Saturday").let { assertEquals("Book table for 6", it.title); assertEquals(day(10, 3), it.date) }
        // Lengths and counts still.
        ok("Hotel for 3 nights from Friday").let { assertEquals("Hotel", it.title); assertEquals(day(10, 2), it.date); assertEquals(day(10, 5), it.endDate) }
        ok("Meeting for 2 hours tomorrow 10am").let { assertEquals("Meeting", it.title); assertEquals(120, it.durationMinutes) }
        ok("Holiday for 3 weeks from 1 Nov").let { assertEquals("Holiday", it.title); assertEquals(day(11, 1), it.date); assertEquals(day(11, 21), it.endDate) }
        ok("Book table for Saturday").let { assertEquals("Book table", it.title); assertEquals(day(10, 3), it.date) }
        // At the very end the length may still be being typed.
        refused("Study for 30")
        refused("Meeting for 1.5")
        refused("Meeting for half")
    }

    // D6: a number right after a currency sign is an amount, never a clock time.
    @Test fun moneyIsNotATime() {
        ok("Rent $450pm due Friday").let { assertEquals("Rent \$450pm", it.title); assertEquals(day(10, 2), it.date); assertNull(it.time) }
        ok("Gym $120 pm Monday").let { assertEquals("Gym \$120 pm", it.title); assertEquals(day(10, 5), it.date); assertNull(it.time) }
        ok("Phone bill £45pm due Friday").let { assertEquals("Phone bill £45pm", it.title); assertEquals(day(10, 2), it.date); assertNull(it.time) }
        ok("Insurance €99 am Friday").let { assertEquals("Insurance €99 am", it.title); assertEquals(day(10, 2), it.date); assertNull(it.time) }
        ok("Groceries $200 Saturday 10am").let { assertEquals("Groceries \$200", it.title); assertEquals(at(10), it.time) }
        ok("Dinner 730pm tomorrow").let { assertEquals(at(19, 30), it.time) }
    }

    // D7: a three-digit start takes am/pm from the end, as "7-830pm" does.
    @Test fun threeDigitStartOfARange() {
        ok("Dinner 630-830pm").let { assertEquals("Dinner", it.title); assertEquals(today, it.date); assertEquals(at(18, 30), it.time); assertEquals(120, it.durationMinutes) }
        ok("Meeting 930-1030am").let { assertEquals("Meeting", it.title); assertEquals(at(9, 30), it.time); assertEquals(60, it.durationMinutes) }
        ok("Swim 545-645am Thursday").let { assertEquals("Swim", it.title); assertEquals(day(10, 1), it.date); assertEquals(at(5, 45), it.time); assertEquals(60, it.durationMinutes) }
        ok("Class 7-830pm Tuesday").let { assertEquals(at(19), it.time); assertEquals(90, it.durationMinutes) }
    }
}
