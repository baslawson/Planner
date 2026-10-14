package com.example.itinerary.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

// The day view with hours: where timed events sit and how overlapping ones share the width.
class DayTimelineTest {
    private val day = LocalDate.of(2026, 10, 15)
    private var next = 1L
    private fun event(title: String, hour: Int, minute: Int = 0, minutes: Int? = 60, date: LocalDate = day) =
        ItineraryItem(id = next++, tripId = 0, date = date, startTime = LocalTime.of(hour, minute), durationMinutes = minutes, title = title)
    private fun layout(vararg events: ItineraryItem) =
        DayTimeline.blocks(events.toList(), day).map { "${it.item.title} ${it.startMinute}-${it.endMinute} ${it.column}/${it.columns}" }

    @Test fun eventsApartTakeTheWholeWidthAndOverlappingOnesShareIt() {
        assertEquals(listOf("Gym 420-480 0/1", "Work 540-1020 0/2", "Call 600-630 1/2", "Lunch 720-780 1/2", "Dinner 1140-1230 0/1"), layout(
            event("Gym", 7), event("Work", 9, minutes = 480), event("Call", 10, minutes = 30), event("Lunch", 12), event("Dinner", 19, minutes = 90)))
        // Three at once: three columns; the next free column is reused.
        assertEquals(listOf("A 540-660 0/3", "B 570-630 1/3", "C 600-690 2/3", "D 630-660 1/3"),
            layout(event("A", 9, minutes = 120), event("B", 9, 30), event("C", 10, minutes = 90), event("D", 10, 30, minutes = 30)))
    }

    @Test fun shortAndOvernightEventsAndAllDayOnes() {
        // No length, or a few minutes: still half an hour tall. Back to back isn't overlapping.
        assertEquals(listOf("Pills 480-510 0/1", "Bus 510-540 0/1"), layout(event("Pills", 8, minutes = null), event("Bus", 8, 30, minutes = 5)))
        // From last night until 2 am, and from 11 pm into tomorrow: cut at midnight.
        assertEquals(listOf("Flight 0-120 0/1", "Party 1380-1440 0/1"),
            layout(event("Flight", 22, minutes = 240, date = day.minusDays(1)), event("Party", 23, minutes = 180)))
        // Late at night with no length: still half an hour tall, ending at midnight (bug hunt 28 P6).
        assertEquals(listOf("Late 1410-1440 0/1"), layout(event("Late", 23, 50, minutes = null)))
        val holiday = ItineraryItem(id = 99, tripId = 0, date = day.minusDays(1), endDate = day.plusDays(1), startTime = null, title = "Holiday")
        assertEquals(listOf(holiday), DayTimeline.allDay(listOf(holiday, event("Gym", 7))))
    }
}
