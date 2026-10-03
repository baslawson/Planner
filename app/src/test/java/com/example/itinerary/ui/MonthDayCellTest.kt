package com.example.itinerary.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

// AG-3: TalkBack hears a month grid day as its full date, with today and the event dot (selected is a state of its own).
class MonthDayCellTest {
    private val oct5 = LocalDate.of(2026, 10, 5)

    @Test fun aDayReadsAsItsFullDateWithTodayAndEvents() {
        assertEquals("Monday 5 October 2026", dayCellDescription(oct5, isToday = false, hasItems = false, Locale.UK))
        assertEquals("Monday 5 October 2026, today", dayCellDescription(oct5, isToday = true, hasItems = false, Locale.UK))
        assertEquals("Monday 5 October 2026, has events", dayCellDescription(oct5, isToday = false, hasItems = true, Locale.UK))
        assertEquals("Monday 5 October 2026, today, has events", dayCellDescription(oct5, isToday = true, hasItems = true, Locale.UK))
    }
}
