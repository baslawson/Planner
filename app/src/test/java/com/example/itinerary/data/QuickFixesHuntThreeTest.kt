package com.example.itinerary.data

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

// Third bug hunt of 30 Sep 2026 (C3–C7, C11–C14; probe qa/parser-probe/probe-hunt3.txt).
class QuickFixesHuntThreeTest {
    private val today = LocalDate.of(2026, 9, 30) // a Wednesday
    private fun parse(text: String, dayFirst: Boolean? = true) =
        QuickEntry.parse(text, today, now = LocalDateTime.of(2026, 9, 30, 10, 0), dayFirst = dayFirst, zone = ZoneId.of("Australia/Perth"))
    private fun ok(text: String, dayFirst: Boolean? = true) = parse(text, dayFirst).also { assertTrue("$text: ${it.error}", it.error == null || it.clarificationOnly) }
    private fun refused(text: String) = parse(text).also { assertNotNull(text, it.error); assertFalse(text, it.clarificationOnly) }.error!!
    private fun day(month: Int, day: Int) = LocalDate.of(2026, month, day)
    private fun at(hour: Int, minute: Int = 0) = LocalTime.of(hour, minute)

    // C3: after until/till, 5.30 is a time, so the words make an hour range, not the repeat's end date.
    @Test fun untilWithADotTimeIsAnHourRange() {
        ok("Work every weekday 9 until 5.30").let {
            assertEquals("Work", it.title); assertEquals(RepeatRule.WEEKDAYS, it.repeat); assertEquals(today, it.date)
            assertEquals(at(9), it.time); assertEquals(510, it.durationMinutes); assertNull(it.error); assertFalse(it.repeatCountSpecified)
        }
        // As "9-10.30": morning or evening is asked, the length is known.
        ok("Swim every Saturday 9 till 10.30").let {
            assertEquals("Swim", it.title); assertEquals(RepeatRule.WEEKLY, it.repeat); assertEquals(day(10, 3), it.date)
            assertTrue(it.clarificationOnly); assertEquals(listOf(at(9), at(21)), it.timeChoices); assertEquals(90, it.durationMinutes)
        }
        ok("Gym every Monday 6.30 until 7.30").let {
            // 1 Oct review: without am/pm, 6.30 could be morning or evening, as for 6.30 alone.
            assertEquals("Gym", it.title); assertEquals(day(10, 5), it.date); assertEquals(listOf(at(6, 30), at(18, 30)), it.timeChoices); assertEquals(60, it.durationMinutes)
        }
        ok("Work weekdays 0900 until 17.30").let {
            assertEquals("Work", it.title); assertEquals(RepeatRule.WEEKDAYS, it.repeat); assertEquals(at(9), it.time); assertEquals(510, it.durationMinutes)
        }
        // Still end dates.
        ok("Gym every Monday until 26/10").let { assertEquals(4, it.repeatCount); assertTrue(it.repeatCountSpecified) }
        ok("Gym every Monday until 26.10.26").let { assertEquals(4, it.repeatCount); assertTrue(it.repeatCountSpecified) }
    }

    // C4: a four-digit number after a month-name date is a year only when it could be one.
    @Test fun implausibleYearsAreTwentyFourHourTimes() {
        ok("Dentist 3 Oct 1500").let { assertEquals("Dentist", it.title); assertEquals(day(10, 3), it.date); assertEquals(at(15), it.time) }
        ok("Meeting 5 October 0930").let { assertEquals(day(10, 5), it.date); assertEquals(at(9, 30), it.time) }
        ok("Dinner Oct 3 1930").let { assertEquals("Dinner", it.title); assertEquals(day(10, 3), it.date); assertEquals(at(19, 30), it.time) }
        ok("Dinner Oct 3, 1930").let { assertEquals("Dinner", it.title); assertEquals(day(10, 3), it.date); assertEquals(at(19, 30), it.time) }
        ok("Christmas Day 1100").let { assertEquals("Christmas Day", it.title); assertEquals(day(12, 25), it.date); assertEquals(at(11), it.time) }
        ok("Trip 3-7 Oct 0800").let { assertEquals(day(10, 3), it.date); assertEquals(day(10, 7), it.endDate); assertEquals(at(8), it.time) }
        ok("Review last day of October 1700").let { assertEquals(day(10, 31), it.date); assertEquals(at(17), it.time) }
        // Years still.
        ok("Dentist 3 Oct 2027").let { assertEquals(LocalDate.of(2027, 10, 3), it.date); assertNull(it.time) }
        ok("Dinner Oct 3, 2027").let { assertEquals(LocalDate.of(2027, 10, 3), it.date); assertNull(it.time) }
        ok("Boxing Day 2027").let { assertEquals(LocalDate.of(2027, 12, 26), it.date); assertNull(it.time) }
        ok("Trip 3-7 Oct 2027").let { assertEquals(LocalDate.of(2027, 10, 3), it.date); assertEquals(LocalDate.of(2027, 10, 7), it.endDate) }
    }

    // C5: an hour range before a month-first date is hours on that date.
    @Test fun hourRangeBeforeMonthFirstDate() {
        ok("Work 9-5 Oct 3rd").let { assertEquals("Work", it.title); assertEquals(day(10, 3), it.date); assertEquals(at(9), it.time); assertEquals(480, it.durationMinutes); assertNull(it.endDate) }
        ok("Meeting 10-11 Nov 3rd").let { assertEquals("Meeting", it.title); assertEquals(day(11, 3), it.date); assertTrue(it.clarificationOnly); assertEquals(60, it.durationMinutes) }
        ok("Lunch 12-2 Oct 5").let { assertEquals("Lunch", it.title); assertEquals(day(10, 5), it.date); assertEquals(120, it.durationMinutes); assertNull(it.endDate) }
        // Date ranges still.
        ok("Trip 3-7 Oct").let { assertEquals(day(10, 3), it.date); assertEquals(day(10, 7), it.endDate) }
        ok("Trip 3-7 Oct 2026").let { assertEquals(day(10, 3), it.date); assertEquals(day(10, 7), it.endDate) }
        ok("Trip 3-7 Oct 5pm").let { assertEquals(day(10, 3), it.date); assertEquals(day(10, 7), it.endDate); assertEquals(at(17), it.time) }
    }

    // C6: for/to/until/from before a date (for/from also before a time) go with it, and leave the title.
    @Test fun wordBeforeADateLeavesTheTitle() {
        ok("Book table for Saturday").let { assertEquals("Book table", it.title); assertEquals(day(10, 3), it.date); assertNull(it.error) }
        ok("Pick up cake for tomorrow").let { assertEquals("Pick up cake", it.title); assertEquals(day(10, 1), it.date) }
        ok("Dinner reservation for Saturday 7pm").let { assertEquals("Dinner reservation", it.title); assertEquals(day(10, 3), it.date); assertEquals(at(19), it.time) }
        ok("Tickets for tomorrow night").let {
            val plain = ok("Tickets tomorrow night")
            assertEquals("Tickets", it.title); assertEquals(plain.date, it.date); assertEquals(plain.error, it.error); assertEquals(plain.timePrompt, it.timePrompt)
        }
        ok("Move dentist to Friday").let { assertEquals("Move dentist", it.title); assertEquals(day(10, 2), it.date) }
        // Since 4 Oct (Q-N9) "until" a date is one entry from today to that day; see QuickFixesOct4Test.
        ok("Away until Friday").let { assertEquals("Away", it.title); assertEquals(today, it.date); assertEquals(day(10, 2), it.endDate) }
        ok("Party from 7pm").let { assertEquals("Party", it.title); assertEquals(at(19), it.time) }
        // Unchanged.
        ok("Trip to Paris Friday").let { assertEquals("Trip to Paris", it.title); assertEquals(day(10, 2), it.date) }
        ok("Away from Friday to Sunday").let { assertEquals("Away", it.title); assertEquals(day(10, 2), it.date); assertEquals(day(10, 4), it.endDate) }
        ok("Gym every Monday until 26 October").let { assertEquals("Gym", it.title); assertEquals(4, it.repeatCount) }
        // "until 5pm" is an end, not a start: still asks to be finished.
        refused("Work until 5pm")
    }

    // C7: "a night" or "one night" is a stay only with "for".
    @Test fun aNightIsAStayOnlyWithFor() {
        ok("Book a night out Friday").let { assertEquals("Book a night out", it.title); assertEquals(day(10, 2), it.date); assertNull(it.endDate) }
        ok("One night in Bangkok Friday 8pm").let { assertEquals("One night in Bangkok", it.title); assertEquals(day(10, 2), it.date); assertEquals(at(20), it.time) }
        ok("Hotel for a night Friday").let { assertEquals("Hotel", it.title); assertEquals(day(10, 3), it.endDate) }
        ok("Hotel for one night Friday").let { assertEquals("Hotel", it.title); assertEquals(day(10, 3), it.endDate) }
        ok("Hotel 2 nights Friday").let { assertEquals("Hotel", it.title); assertEquals(day(10, 4), it.endDate) }
        ok("Hotel for 3 nights Friday").let { assertEquals("Hotel", it.title); assertEquals(day(10, 5), it.endDate) }
    }

    // C11: 730pm, 1030am, 1215pm.
    @Test fun clockTimesWithoutAColon() {
        ok("Dinner 730pm tomorrow").let { assertEquals("Dinner", it.title); assertEquals(day(10, 1), it.date); assertEquals(at(19, 30), it.time) }
        ok("Call 1030am Friday").let { assertEquals("Call", it.title); assertEquals(day(10, 2), it.date); assertEquals(at(10, 30), it.time) }
        ok("Lunch 1215pm").let { assertEquals("Lunch", it.title); assertEquals(at(12, 15), it.time) }
        ok("Lunch 1215 pm").let { assertEquals("Lunch", it.title); assertEquals(at(12, 15), it.time) }
        ok("Dinner 730pm-930pm").let { assertEquals(at(19, 30), it.time); assertEquals(120, it.durationMinutes) }
        // Not a time: minutes over 59, hour over 12.
        ok("Budget 2026pm").let { assertNull(it.time) }
        ok("Code 1275pm").let { assertNull(it.time) }
    }

    // C12: a day number with st/nd/rd/th and no "the", after "on" or before a time.
    @Test fun ordinalWithoutThe() {
        ok("Dentist on 3rd at 2pm").let { assertEquals("Dentist", it.title); assertEquals(day(10, 3), it.date); assertEquals(at(14), it.time) }
        ok("Dentist 3rd 2pm").let { assertEquals("Dentist", it.title); assertEquals(day(10, 3), it.date); assertEquals(at(14), it.time) }
        ok("Dentist 3rd at 2pm").let { assertEquals("Dentist", it.title); assertEquals(day(10, 3), it.date) }
        ok("Pay rent on 1st").let { assertEquals("Pay rent", it.title); assertEquals(day(10, 1), it.date) }
        ok("Dentist the 3rd 2pm").let { assertEquals("Dentist", it.title); assertEquals(day(10, 3), it.date); assertEquals(at(14), it.time) }
        // Title words.
        ok("Meeting 3rd floor Friday").let { assertEquals("Meeting 3rd floor", it.title); assertEquals(day(10, 2), it.date) }
        ok("Meeting on 3rd floor Friday 2pm").let { assertEquals("Meeting on 3rd floor", it.title); assertEquals(day(10, 2), it.date) }
        ok("Sam's 21st Saturday").let { assertEquals("Sam's 21st", it.title); assertEquals(day(10, 3), it.date) }
    }

    // C13: "12 noon", "12 midnight".
    @Test fun twelveNoonAndMidnight() {
        ok("Lunch 12 noon tomorrow").let { assertEquals("Lunch", it.title); assertEquals(day(10, 1), it.date); assertEquals(LocalTime.NOON, it.time) }
        ok("Lunch at 12 noon").let { assertEquals("Lunch", it.title); assertEquals(today, it.date); assertEquals(LocalTime.NOON, it.time) }
        ok("Lunch 12noon Friday").let { assertEquals("Lunch", it.title); assertEquals(LocalTime.NOON, it.time) }
        ok("Launch at 12 midnight").let { assertEquals("Launch", it.title); assertEquals(LocalTime.MIDNIGHT, it.time) }
    }

    // C14: "Jan 3 pm" is 3pm with Jan in the title; "Jan 3 at 3pm" is still 3 January.
    @Test fun monthDayBeforeAmPm() {
        ok("Call Jan 3 pm").let { assertEquals("Call Jan", it.title); assertEquals(today, it.date); assertEquals(at(15), it.time) }
        ok("Call Jan 3 at 3pm").let { assertEquals("Call", it.title); assertEquals(LocalDate.of(2027, 1, 3), it.date); assertEquals(at(15), it.time) }
        ok("Call Jan 3 3pm").let { assertEquals("Call", it.title); assertEquals(LocalDate.of(2027, 1, 3), it.date); assertEquals(at(15), it.time) }
    }
}
