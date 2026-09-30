package com.example.itinerary.data

import org.junit.Assert.*
import org.junit.Test
import java.time.*

// Repeat rules from calendar files: where a week starts (WKST) and the Nth-from-last weekday of a month.
class IcsRepeatTest {
    private val nine = LocalTime.of(9, 0)
    private fun dates(rule: String, start: LocalDate, limit: LocalDate = start.plusYears(1)) =
        IcsRepeat.parse(rule, ZoneOffset.UTC, start)!!.dates(start, nine, limit)

    @Test fun weeksOfAnIntervalStartOnWkst() {
        val tuesday = LocalDate.of(2026, 1, 6)
        // RFC 5545's own example: counted from Sunday, the Sunday after the start is in the next (skipped) week.
        assertEquals(listOf(LocalDate.of(2026, 1, 6), LocalDate.of(2026, 1, 18), LocalDate.of(2026, 1, 20), LocalDate.of(2026, 2, 1)),
            dates("FREQ=WEEKLY;INTERVAL=2;COUNT=4;WKST=SU;BYDAY=TU,SU", tuesday))
        // Without WKST (or WKST=MO) weeks start on Monday, so that Sunday is in the start's week.
        val monday = listOf(LocalDate.of(2026, 1, 6), LocalDate.of(2026, 1, 11), LocalDate.of(2026, 1, 20), LocalDate.of(2026, 1, 25))
        assertEquals(monday, dates("FREQ=WEEKLY;INTERVAL=2;COUNT=4;BYDAY=TU,SU", tuesday))
        assertEquals(monday, dates("FREQ=WEEKLY;INTERVAL=2;COUNT=4;WKST=MO;BYDAY=TU,SU", tuesday))
        // Every week, WKST changes nothing.
        assertEquals(dates("FREQ=WEEKLY;COUNT=6;BYDAY=TU,SU", tuesday), dates("FREQ=WEEKLY;COUNT=6;WKST=SU;BYDAY=TU,SU", tuesday))
        assertNull(IcsRepeat.parse("FREQ=WEEKLY;WKST=XX", ZoneOffset.UTC, tuesday))
    }

    @Test fun nthFromLastWeekdayOfTheMonth() {
        // Fridays in Sep 2026: 4 11 18 25; Oct: 2 9 16 23 30; Nov: 6 13 20 27.
        val start = LocalDate.of(2026, 9, 18)
        assertEquals(listOf(start, LocalDate.of(2026, 10, 23), LocalDate.of(2026, 11, 20)), dates("FREQ=MONTHLY;COUNT=3;BYDAY=-2FR", start))
        assertEquals(listOf(LocalDate.of(2026, 9, 25), LocalDate.of(2026, 10, 30), LocalDate.of(2026, 11, 27)),
            dates("FREQ=MONTHLY;COUNT=3;BYDAY=-1FR", LocalDate.of(2026, 9, 25)))
        // A fifth-from-last Friday only in months with five.
        assertEquals(listOf(LocalDate.of(2026, 10, 2), LocalDate.of(2027, 1, 1)),
            dates("FREQ=MONTHLY;COUNT=2;BYDAY=-5FR", LocalDate.of(2026, 10, 2)))
        // In a yearly rule: the second-to-last Sunday of March.
        assertEquals(listOf(LocalDate.of(2026, 3, 22), LocalDate.of(2027, 3, 21)),
            dates("FREQ=YEARLY;COUNT=2;BYMONTH=3;BYDAY=-2SU", LocalDate.of(2026, 3, 22)))
        // Ordinals a month can't have are refused, not guessed.
        listOf("FREQ=MONTHLY;BYDAY=-6FR", "FREQ=MONTHLY;BYDAY=6FR", "FREQ=MONTHLY;BYDAY=0FR").forEach {
            assertNull(it, IcsRepeat.parse(it, ZoneOffset.UTC, start))
        }
    }
}
