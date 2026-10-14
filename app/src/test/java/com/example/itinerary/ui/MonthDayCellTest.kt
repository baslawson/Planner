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
        assertEquals("Monday 5 October 2026, has entries", dayCellDescription(oct5, isToday = false, hasItems = true, Locale.UK))
        assertEquals("Monday 5 October 2026, today, has entries", dayCellDescription(oct5, isToday = true, hasItems = true, Locale.UK))
    }

    // Maximised, the day names what it holds.
    @Test fun aMaximisedDayNamesItsEntries() {
        assertEquals("Monday 5 October 2026, today, 2 entries: Dentist, Pay rent",
            dayCellDescription(oct5, isToday = true, hasItems = true, Locale.UK, listOf("Dentist", "Pay rent")))
        assertEquals("Monday 5 October 2026, 1 entry: Dentist",
            dayCellDescription(oct5, isToday = false, hasItems = true, Locale.UK, listOf("Dentist")))
        assertEquals("Monday 5 October 2026", dayCellDescription(oct5, isToday = false, hasItems = false, Locale.UK, emptyList()))
    }
}

// The bar under the calendar: a swipe down is one size bigger, up one size smaller, stopping at the ends.
class CalendarSizeTest {
    @Test fun sizesStepOneAtATimeAndStopAtTheEnds() {
        assertEquals(CalendarSize.MONTH, CalendarSize.WEEK.bigger)
        assertEquals(CalendarSize.FULL, CalendarSize.MONTH.bigger)
        assertEquals(CalendarSize.FULL, CalendarSize.FULL.bigger)
        assertEquals(CalendarSize.MONTH, CalendarSize.FULL.smaller)
        assertEquals(CalendarSize.WEEK, CalendarSize.WEEK.smaller)
    }
}
