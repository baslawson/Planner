package com.example.itinerary.ui

import com.example.itinerary.data.DayTimeline
import com.example.itinerary.data.ItineraryItem
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

// Bug hunt 37: the hour grid's column under a touch (C2) and the hour it opens at (C3).
class BugHunt37CalendarTest {
    private val monday = LocalDate.of(2026, 10, 12)
    private val week = (0L until 7L).map(monday::plusDays)
    private val today = LocalDate.of(2026, 1, 1)

    private fun event(id: Long, date: LocalDate, at: LocalTime, minutes: Int) =
        ItineraryItem(id = id, tripId = 1, date = date, startTime = at, durationMinutes = minutes, title = "E$id")

    private fun blocks(days: List<LocalDate>, events: List<ItineraryItem>) = days.map { DayTimeline.blocks(events, it) }

    // A grid 56 + 7 × 100 px wide, the labels 56 px.
    private fun column(x: Float, rtl: Boolean = false, fallback: Int = 3) = gridColumnAt(x, 756, 56f, 100f, 7, rtl, fallback)

    @Test fun touchOnTheHourLabelsIsOnTheChosenDay() {
        assertEquals(3, column(10f))
        assertEquals(3, column(746f, rtl = true))
        // Not shown (-1): the first day, as before.
        assertEquals(0, column(10f, fallback = -1))
    }

    @Test fun touchOnTheGridIsOnTheDayUnderIt() {
        assertEquals(0, column(60f))
        assertEquals(1, column(160f))
        assertEquals(6, column(755f))
        assertEquals(0, column(690f, rtl = true))
        assertEquals(6, column(1f, rtl = true))
    }

    @Test fun opensAtTheFirstEventThatStartsThatDay() {
        val day = listOf(monday)
        assertEquals(9, gridStartHour(day, today, 12, blocks(day, listOf(event(1, monday, LocalTime.of(9, 30), 60)))))
    }

    @Test fun theMorningEndOfLastNightsEventIsNotTheFirstEvent() {
        val day = listOf(monday)
        val overnight = event(1, monday.minusDays(1), LocalTime.of(22, 0), 240)
        assertEquals(7, gridStartHour(day, today, 12, blocks(day, listOf(overnight))))
        assertEquals(10, gridStartHour(day, today, 12, blocks(day, listOf(overnight, event(2, monday, LocalTime.of(10, 0), 30)))))
        // In the week, the evening it starts on counts.
        assertEquals(22, gridStartHour(week, today, 12, blocks(week, listOf(overnight.copy(date = monday, startTime = LocalTime.of(22, 0))))))
    }

    @Test fun todayOpensAnHourBeforeNow() {
        val day = listOf(monday)
        assertEquals(13, gridStartHour(day, monday, 14, blocks(day, listOf(event(1, monday, LocalTime.of(9, 0), 60)))))
        assertEquals(0, gridStartHour(day, monday, 0, emptyList()))
        assertEquals(7, gridStartHour(day, today, 0, blocks(day, emptyList())))
    }
}
