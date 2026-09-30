package com.example.itinerary.data

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

// Bug hunt of 30 Sep 2026: quick-entry findings in the round 7 and 8 additions (probe qa/parser-probe/probe-bugfix.txt).
class QuickFixesSep30Test {
    private val today = LocalDate.of(2026, 9, 30) // a Wednesday
    private fun parse(text: String) = QuickEntry.parse(text, today, now = LocalDateTime.of(2026, 9, 30, 10, 0), dayFirst = true, zone = ZoneId.of("Australia/Perth"))
    private fun ok(text: String) = parse(text).also { assertTrue("$text: ${it.error}", it.error == null || it.clarificationOnly) }
    private fun day(month: Int, day: Int) = LocalDate.of(2026, month, day)
    private fun at(hour: Int) = LocalTime.of(hour, 0)

    @Test fun partOfTheDaySettlesAWrappingRange() {
        ok("Party tonight 10-2").let { assertEquals("Party", it.title); assertEquals(at(22), it.time); assertEquals(240, it.durationMinutes) }
        ok("Drinks Friday evening 8-1").let { assertEquals(day(10, 2), it.date); assertEquals(at(20), it.time); assertEquals(300, it.durationMinutes) }
    }

    @Test fun severalFourDigitTimes() {
        ok("Meds 0800 and 2000 daily").let { assertEquals("Meds", it.title); assertEquals(at(8), it.time); assertEquals(listOf(at(20)), it.extraTimes) }
        ok("Meds 0800, 1400 and 2000 daily").let { assertEquals(listOf(at(14), at(20)), it.extraTimes) }
        ok("Stretch 0700 and 1900 weekdays").let { assertEquals("Stretch", it.title); assertEquals(listOf(at(19)), it.extraTimes) }
        // Numbers that aren't times stay in the title.
        assertEquals("Buy 1500 and 2000 screws", ok("Buy 1500 and 2000 screws").title)
    }

    @Test fun hyphenDatesBesideADayStayDates() {
        ok("Gym every Monday 12-10 6pm").let { assertEquals(day(10, 12), it.date); assertEquals(at(18), it.time) }
        ok("Dentist Fri 2-10 3pm").let { assertEquals(day(10, 2), it.date); assertEquals(at(15), it.time) }
        ok("Dentist Fri 2-10").let { assertEquals(day(10, 2), it.date); assertNull(it.time); assertNull(it.error) }
        // Still hours where no date soon fits.
        ok("Work Monday 9-5").let { assertEquals(day(10, 5), it.date); assertEquals(at(9), it.time) }
        ok("Class 2-4 Thursday").let { assertEquals(day(10, 1), it.date); assertEquals(listOf(at(2), at(14)), it.timeChoices) }
    }

    @Test fun pastChoiceAndHolidayYear() {
        ok("Dentist yesterday or today").let { assertTrue(it.pastDate); assertEquals(listOf(day(9, 29), day(9, 30)), it.dateChoices) }
        assertTrue(parse("Gym yesterday or today every day").error!!.startsWith("A repeat can't start in the past"))
        assertEquals("That date isn't valid.", parse("Christmas Day 0000").error)
        assertEquals(LocalDate.of(2027, 12, 26), ok("Boxing Day 2027").date)
    }
}
