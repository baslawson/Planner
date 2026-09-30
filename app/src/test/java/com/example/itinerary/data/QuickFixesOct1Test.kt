package com.example.itinerary.data

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

// 1 Oct 2026: the quick-entry cases left open after the fifth bug hunt (phone numbers, numbered buses and rooms, "for 15").
class QuickFixesOct1Test {
    private val today = LocalDate.of(2026, 10, 1) // a Thursday
    private fun parse(text: String) = QuickEntry.parse(text, today, now = LocalDateTime.of(2026, 10, 1, 10, 0), dayFirst = true, zone = ZoneId.of("Australia/Perth"))
    private fun ok(text: String) = parse(text).also { assertTrue("$text: ${it.error}", it.error == null || it.clarificationOnly) }
    private fun day(month: Int, day: Int) = LocalDate.of(2026, month, day)
    private fun at(hour: Int, minute: Int = 0) = LocalTime.of(hour, minute)

    @Test fun phoneNumbersStayInTheTitle() {
        ok("Call 0491 570 156 tomorrow").let { assertEquals("Call 0491 570 156", it.title); assertEquals(day(10, 2), it.date); assertNull(it.time) }
        ok("Call mum 0491 570 156 at 3pm").let { assertEquals("Call mum 0491 570 156", it.title); assertEquals(at(15), it.time) }
        ok("Call 0491-570-156 tomorrow 2pm").let { assertEquals("Call 0491-570-156", it.title); assertEquals(day(10, 2), it.date); assertEquals(at(14), it.time) }
        ok("Call +61 491 570 156 Friday 10am").let { assertEquals("Call +61 491 570 156", it.title); assertEquals(at(10), it.time) }
        ok("Ring (08) 5550 1234 Monday 3pm").let { assertEquals("Ring (08) 5550 1234", it.title); assertEquals(day(10, 5), it.date) }
    }

    @Test fun lookalikesOfPhoneNumbersKeepTheirTimesAndDates() {
        // A lone four-digit time with a leading zero is still a time.
        ok("Flight 0412 tomorrow").let { assertEquals("Flight", it.title); assertEquals(at(4, 12), it.time) }
        ok("Shift 0900-1700 Monday").let { assertEquals(at(9), it.time); assertEquals(480, it.durationMinutes) }
        ok("Shift 0900 - 1700 Monday").let { assertEquals(at(9), it.time); assertEquals(480, it.durationMinutes) }
        ok("Meds 0800, 1400 and 2000 daily").let { assertEquals(at(8), it.time); assertEquals(listOf(at(14), at(20)), it.extraTimes) }
        ok("Trip 2026-10-05").let { assertEquals("Trip", it.title); assertEquals(day(10, 5), it.date) }
        ok("Dentist 03-10-2026 2pm").let { assertEquals(day(10, 3), it.date); assertEquals(at(14), it.time) }
    }

    @Test fun aNumberedBusOrRoomIsNotARangeStart() {
        ok("Bus 2 - 3pm").let { assertEquals("Bus 2", it.title); assertEquals(at(15), it.time); assertNull(it.durationMinutes) }
        ok("Bus #2 - 3pm").let { assertEquals("Bus #2", it.title); assertEquals(at(15), it.time) }
        ok("Ferry 12 - 10am Sunday").let { assertEquals("Ferry 12", it.title); assertEquals(day(10, 4), it.date); assertEquals(at(10), it.time); assertNull(it.durationMinutes) }
        ok("Platform 9 - 10am Friday").let { assertEquals("Platform 9", it.title); assertEquals(at(10), it.time) }
        ok("Train no. 7 - 8am Monday").let { assertEquals("Train no. 7", it.title); assertEquals(at(8), it.time) }
        ok("Tram 96 till 5pm").let { assertEquals(at(17), it.time); assertNull(it.durationMinutes) }
    }

    @Test fun rangesWithoutANumberedThingStayRanges() {
        ok("Meeting 2 - 3pm Friday").let { assertEquals("Meeting", it.title); assertEquals(at(14), it.time); assertEquals(60, it.durationMinutes) }
        ok("Dinner 7 - 9pm Friday").let { assertEquals(at(19), it.time); assertEquals(120, it.durationMinutes) }
        // Tight against the dash, even after a label word.
        ok("Tennis court 1-2pm").let { assertEquals("Tennis court", it.title); assertEquals(at(13), it.time); assertEquals(60, it.durationMinutes) }
    }

    @Test fun tenToTwentyAfterForIsPeopleOnlyWithABookingWord() {
        for (text in listOf("Practice for 15 tomorrow", "Read for 20 tomorrow", "Walk for 10 tomorrow 7am", "Study for 12 Monday"))
            assertEquals(text, "Finish the date, time or duration, or put literal title text in quotes.", parse(text).error)
        ok("Party for 20 next Saturday").let { assertEquals("Party for 20", it.title) }
        ok("Booking for 12 Saturday 7pm").let { assertEquals("Booking for 12", it.title); assertEquals(at(19), it.time) }
        ok("BBQ for 12 on Sunday at 1pm").let { assertEquals("BBQ for 12", it.title) }
        ok("Dinner for 15 Friday").let { assertEquals("Dinner for 15", it.title) }
        // Small numbers and numbers in words are unchanged.
        ok("Table for 4 Saturday 7pm").let { assertEquals("Table for 4", it.title) }
        ok("Dinner for fifteen Friday 7pm").let { assertEquals("Dinner for fifteen", it.title) }
        ok("Practice for 15 minutes tomorrow").let { assertEquals("Practice", it.title); assertEquals(15, it.durationMinutes) }
    }
}
