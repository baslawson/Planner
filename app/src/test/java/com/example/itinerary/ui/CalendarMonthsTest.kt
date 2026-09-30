package com.example.itinerary.ui

import org.junit.Assert.*
import org.junit.Test
import java.time.YearMonth

// E1: every month the Calendar is asked to show maps to a page the pager has.
class CalendarMonthsTest {
    @Test fun coversYearsOneTo9999() {
        assertEquals(9999 * 12, CalendarMonths.COUNT)
        assertEquals(0, CalendarMonths.pageOf(YearMonth.of(1, 1)))
        assertEquals(CalendarMonths.COUNT - 1, CalendarMonths.pageOf(YearMonth.of(9999, 12)))
    }

    @Test fun oldAndFarFutureMonthsHaveTheirOwnPage() {
        for (month in listOf(YearMonth.of(1895, 6), YearMonth.of(1899, 12), YearMonth.of(2300, 1), YearMonth.of(2400, 1), YearMonth.of(1, 2))) {
            assertTrue(CalendarMonths.inRange(month))
            assertEquals(month, CalendarMonths.monthOfPage(CalendarMonths.pageOf(month)))
        }
    }

    @Test fun monthsOutsideTheRangeShowTheNearestEnd() {
        assertEquals(YearMonth.of(1, 1), CalendarMonths.clamp(YearMonth.of(0, 12)))
        assertEquals(YearMonth.of(1, 1), CalendarMonths.clamp(YearMonth.of(-500, 3)))
        assertEquals(YearMonth.of(9999, 12), CalendarMonths.clamp(YearMonth.of(10000, 1)))
        assertEquals(0, CalendarMonths.pageOf(YearMonth.of(-500, 3)))
        assertEquals(CalendarMonths.COUNT - 1, CalendarMonths.pageOf(YearMonth.of(123456, 7)))
        assertFalse(CalendarMonths.inRange(YearMonth.of(0, 12)))
        assertFalse(CalendarMonths.inRange(YearMonth.of(10000, 1)))
        assertEquals(YearMonth.of(2026, 10), CalendarMonths.clamp(YearMonth.of(2026, 10)))
    }

    @Test fun pagesOutsideThePagerGiveTheNearestEnd() {
        assertEquals(YearMonth.of(1, 1), CalendarMonths.monthOfPage(-55))
        assertEquals(YearMonth.of(9999, 12), CalendarMonths.monthOfPage(CalendarMonths.COUNT))
    }
}
