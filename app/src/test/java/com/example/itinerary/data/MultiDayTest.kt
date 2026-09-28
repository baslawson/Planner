package com.example.itinerary.data

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class MultiDayTest {
    private val start = LocalDate.of(2026, 10, 3)
    private fun trip(end: LocalDate?) = ItineraryItem(tripId = 0, date = start, startTime = null, title = "Trip", endDate = end)

    @Test fun validSpansAreAllDayNonBillAndEndAfterTheStart() {
        assertTrue(MultiDay.valid(start, null, LocalTime.NOON, "Bills")) // single-day: anything goes
        assertTrue(MultiDay.valid(start, start.plusDays(4), null, "Other"))
        assertFalse(MultiDay.valid(start, start, null, "Other")) // must end after it starts
        assertFalse(MultiDay.valid(start, start.minusDays(1), null, "Other"))
        assertFalse(MultiDay.valid(start, start.plusDays(1), LocalTime.NOON, "Other")) // timed events keep durations
        assertFalse(MultiDay.valid(start, start.plusDays(1), null, "Bills"))
        assertTrue(MultiDay.valid(start, start.plusDays(365), null, "Other")) // 366 days
        assertFalse(MultiDay.valid(start, start.plusDays(366), null, "Other"))
    }

    @Test fun dayCountCoversAndDayNumberCountBothEnds() {
        val t = trip(LocalDate.of(2026, 10, 7))
        assertEquals(5, t.dayCount)
        assertEquals(LocalDate.of(2026, 10, 7), t.lastDay)
        assertFalse(t.covers(start.minusDays(1))); assertTrue(t.covers(start)); assertTrue(t.covers(LocalDate.of(2026, 10, 7)))
        assertFalse(t.covers(LocalDate.of(2026, 10, 8)))
        assertEquals(3, t.dayNumber(LocalDate.of(2026, 10, 5)))
        val single = trip(null)
        assertEquals(1, single.dayCount); assertEquals(start, single.lastDay); assertTrue(single.covers(start))
    }

    @Test fun startingOnKeepsTheLength() {
        val moved = trip(LocalDate.of(2026, 10, 7)).startingOn(LocalDate.of(2026, 12, 30))
        assertEquals(LocalDate.of(2026, 12, 30), moved.date)
        assertEquals(LocalDate.of(2027, 1, 3), moved.endDate) // across the year end
        assertNull(trip(null).startingOn(LocalDate.of(2026, 12, 30)).endDate)
    }
}
