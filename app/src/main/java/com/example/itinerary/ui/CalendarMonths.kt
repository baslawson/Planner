package com.example.itinerary.ui

import java.time.YearMonth
import java.time.temporal.ChronoUnit

// The months the Calendar can show: January 1 to December 9999, one pager page each (about 120,000 pages; the pager is
// lazy, so only the pages on screen are built). A month outside them (an imported event from the year 0 or 10000) is
// shown as the nearest end, so the pager is never asked for a page it doesn't have (E1: 1895 used to crash on start).
internal object CalendarMonths {
    val FIRST: YearMonth = YearMonth.of(1, 1)
    val LAST: YearMonth = YearMonth.of(9999, 12)
    val COUNT: Int = ChronoUnit.MONTHS.between(FIRST, LAST).toInt() + 1

    fun inRange(month: YearMonth): Boolean = month >= FIRST && month <= LAST

    fun clamp(month: YearMonth): YearMonth = if (month < FIRST) FIRST else if (month > LAST) LAST else month

    fun pageOf(month: YearMonth): Int = ChronoUnit.MONTHS.between(FIRST, clamp(month)).toInt()

    fun monthOfPage(page: Int): YearMonth = FIRST.plusMonths(page.coerceIn(0, COUNT - 1).toLong())
}
