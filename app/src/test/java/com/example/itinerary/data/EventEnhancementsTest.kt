package com.example.itinerary.data

import org.junit.Assert.*
import org.junit.Test
import java.time.*

class EventEnhancementsTest {
    @Test fun monthlyRepeatsKeepAnchorAcrossShortMonthsAndLeapYears() {
        assertEquals(listOf("2027-01-31", "2027-02-28", "2027-03-31", "2027-04-30"),
            RepeatRule.MONTHLY.dates(LocalDate.of(2027, 1, 31), 4).map { it.toString() })
        assertEquals(LocalDate.of(2028, 2, 29), RepeatRule.MONTHLY.dates(LocalDate.of(2028, 1, 31), 2).last())
    }
    @Test fun yearlyRepeatsKeepDateAndRecoverLeapDay() {
        assertEquals(listOf("2026-09-25", "2027-09-25", "2028-09-25"),
            RepeatRule.YEARLY.dates(LocalDate.of(2026, 9, 25), 3).map { it.toString() })
        assertEquals(listOf("2028-02-29", "2029-02-28", "2030-02-28", "2031-02-28", "2032-02-29"),
            RepeatRule.YEARLY.dates(LocalDate.of(2028, 2, 29), 5).map { it.toString() })
        assertEquals(LocalDate.of(2100, 2, 28),
            RepeatRule.YEARLY.dates(LocalDate.of(2096, 2, 29), 5).last())
    }
    @Test fun repeatBoundariesAndIntervals() {
        val date = LocalDate.of(2026, 12, 31)
        assertEquals(listOf(date, LocalDate.of(2027, 1, 1)), RepeatRule.DAILY.dates(date, 2))
        assertEquals(LocalDate.of(2027, 1, 14), RepeatRule.WEEKLY.dates(date, 3).last())
        assertEquals(listOf("2026-12-31", "2027-01-14", "2027-01-28"),
            RepeatRule.FORTNIGHTLY.dates(date, 3).map { it.toString() })
        assertEquals(listOf("2028-02-22", "2028-03-07", "2028-03-21"),
            RepeatRule.FORTNIGHTLY.dates(LocalDate.of(2028, 2, 22), 3).map { it.toString() })
        assertEquals(listOf(date), RepeatRule.NONE.dates(date, 12))
        assertThrows(IllegalArgumentException::class.java) { RepeatRule.DAILY.dates(date, 366) }
        assertThrows(IllegalArgumentException::class.java) { RepeatRule.DAILY.dates(date, 0) }
    }
    @Test fun midnightDelayUsesLocalDayAndDaylightSaving() {
        val zone = ZoneId.of("Australia/Sydney")
        assertEquals(23 * 60 * 60 * 1000L, millisUntilNextDay(LocalDate.of(2026, 10, 4).atStartOfDay(zone)))
        assertEquals(25 * 60 * 60 * 1000L, millisUntilNextDay(LocalDate.of(2026, 4, 5).atStartOfDay(zone)))
        assertEquals(500L, millisUntilNextDay(ZonedDateTime.of(2026, 9, 25, 23, 59, 59, 500_000_000, ZoneId.of("Australia/Perth"))))
    }
}
