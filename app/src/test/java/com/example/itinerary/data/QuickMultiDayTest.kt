package com.example.itinerary.data

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

// Quick entry wording for all-day entries over several days.
class QuickMultiDayTest {
    private val today = LocalDate.of(2026, 9, 28) // a Monday
    private fun parse(text: String) = QuickEntry.parse(text, today)
    private fun span(text: String) = parse(text).let { assertNull(text, it.error); Triple(it.title, it.date, it.endDate) }
    private val oct3 = LocalDate.of(2026, 10, 3)
    private val oct7 = LocalDate.of(2026, 10, 7)

    @Test fun dateRangesInEverydayForms() {
        for (text in listOf("Trip 3 Oct – 7 Oct", "Trip 3 Oct - 7 Oct", "Trip 3–7 Oct", "Trip 3-7 October", "Trip from 3 to 7 Oct",
            "Trip 3rd to 7th October", "Trip Oct 3–7", "Trip October 3 - 7", "Trip from 3 Oct until 7 Oct", "Trip 3 October 2026 to 7 October 2026"))
            assertEquals(text, Triple("Trip", oct3, oct7), span(text))
        assertEquals(Triple("Trip", LocalDate.of(2026, 10, 30), LocalDate.of(2026, 11, 2)), span("Trip 30 Oct – 2 Nov"))
        assertEquals(Triple("Trip", LocalDate.of(2026, 10, 30), LocalDate.of(2026, 11, 2)), span("Trip Oct 30 – Nov 2"))
        // Across the year end without years: it ends in the next year.
        assertEquals(Triple("Holiday", LocalDate.of(2026, 12, 28), LocalDate.of(2027, 1, 3)), span("Holiday 28 Dec – 3 Jan"))
    }

    @Test fun weekdayRangesNeedAWordAndDashesStayRepeats() {
        assertEquals(Triple("Camping", LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 4)), span("Camping from Friday to Sunday"))
        assertEquals(Triple("Camping", LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 4)), span("Camping Fri to Sun"))
        assertNull(parse("Gym every Mon-Fri 6am").endDate)
    }

    @Test fun forNDaysIsALengthOnlyWithoutARepeat() {
        assertEquals(Triple("Conference", oct3, LocalDate.of(2026, 10, 5)), span("Conference 3 Oct for 3 days"))
        assertEquals(Triple("Holiday", oct3, LocalDate.of(2026, 10, 16)), span("Holiday 3 Oct for 2 weeks"))
        assertNull(parse("Visit 3 Oct for 1 day").endDate)
        // With a repeat it stays the occurrence count, as before.
        parse("Yoga every day for 5 days").let { assertNull(it.error); assertNull(it.endDate); assertEquals(5, it.repeatCount) }
    }

    @Test fun timesAndClocksAreNotReadAsDateRanges() {
        parse("Meeting 3-4pm").let { assertNull(it.error); assertNull(it.endDate); assertEquals(LocalTime.of(15, 0), it.time) }
        parse("Dentist 3 Oct 3pm").let { assertNull(it.endDate) }
    }

    @Test fun problemsAreExplained() {
        assertNotNull(parse("Trip 7 Oct – 3 Oct 2026").error)          // ends before it starts
        assertNotNull(parse("Trip 3–7 Oct on Friday").error)            // two dates
        assertNotNull(parse("Trip 3–7 Oct for 3 days").error)           // range and length
        val now = java.time.ZonedDateTime.of(today.atTime(9, 0), ZoneId.of("UTC"))
        assertNotNull(parse("Trip 3–7 Oct at 9am").quickProblem(false, now)) // a span is all day
        assertNotNull(parse("Trip 3–7 Oct").quickProblem(true, now))         // tasks have one due date
        assertNull(parse("Trip 3–7 Oct").quickProblem(false, now))
    }

    @Test fun changingTheStartDateKeepsTheLength() {
        val moved = parse("Trip 3–7 Oct").corrected("2026-11-10", null)
        assertEquals(LocalDate.of(2026, 11, 10), moved.date); assertEquals(LocalDate.of(2026, 11, 14), moved.endDate)
    }
}
