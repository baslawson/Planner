package com.example.itinerary.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

// Bug hunt 26: an entire-series edit's dates (seriesEditDates).
class BugHunt26Test {
    private fun d(m: Int, day: Int, y: Int = 2026) = LocalDate.of(y, m, day)
    private val monthly = RepeatRule.parse("MONTHLY")

    // D1: a monthly series on the 30th moved to the 31st keeps one occurrence a month (Feb's stays the 28th).
    @Test fun monthlySeriesMovedWithinTheMonthStaysInEachMonth() {
        val members = listOf(d(1, 30), d(2, 28), d(3, 30), d(4, 30), d(5, 30))
        assertEquals(listOf(d(1, 31), d(2, 28), d(3, 31), d(4, 30), d(5, 31)), seriesEditDates(members, 1, monthly, false, RepeatRule.NONE))
        // Back a day from the 31st: Feb 28 and Apr 30 (not Feb 27, Apr 29).
        val thirtyFirst = listOf(d(1, 31), d(2, 28), d(3, 31), d(4, 30))
        assertEquals(listOf(d(1, 30), d(2, 28), d(3, 30), d(4, 30)), seriesEditDates(thirtyFirst, -1, monthly, false, RepeatRule.NONE))
        // Every 3 months the same way; a gap left by a deleted occurrence stays a gap.
        assertEquals(listOf(d(1, 31), d(7, 31)), seriesEditDates(listOf(d(1, 30), d(7, 30)), 1, RepeatRule.parse("EVERY_N_MONTHS:3"), false, RepeatRule.NONE))
    }

    // Moves out of the month, other repeats and no move at all are plain day shifts, as before.
    @Test fun otherMovesShiftEachDate() {
        val members = listOf(d(1, 30), d(2, 28), d(3, 30))
        assertEquals(members.map { it.plusDays(5) }, seriesEditDates(members, 5, monthly, false, RepeatRule.NONE))
        assertEquals(members, seriesEditDates(members, 0, monthly, false, RepeatRule.NONE))
        val weekly = listOf(d(1, 29), d(2, 5), d(2, 12))
        assertEquals(weekly.map { it.plusDays(1) }, seriesEditDates(weekly, 1, RepeatRule.parse("WEEKLY"), false, RepeatRule.NONE))
    }

    // D2: a new repeat for a month-end series keeps its day; a weekly series turned monthly takes its first date's day.
    @Test fun changedRepeatKeepsAMonthlySeriesDay() {
        val rent = listOf(d(11, 30, 2025), d(12, 31, 2025), d(1, 31))
        assertEquals(listOf(d(11, 30, 2025), d(1, 31), d(3, 31)), seriesEditDates(rent, 0, monthly, true, RepeatRule.parse("EVERY_N_MONTHS:2")!!))
        val weekly = listOf(d(1, 3), d(1, 10), d(1, 31))
        assertEquals(listOf(d(1, 3), d(2, 3), d(3, 3)), seriesEditDates(weekly, 0, RepeatRule.parse("WEEKLY"), true, monthly!!))
    }
}
