package com.example.itinerary.data

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class EventTimingTest {
    private val day = LocalDate.of(2026, 12, 31)
    private fun at(hour: Int, minute: Int = 0) = day.atTime(hour, minute)
    @Test fun overlapBoundariesContainmentAndUnknownDurations() {
        assertTrue(overlaps(at(9), 60, at(9, 30), 60))
        assertTrue(overlaps(at(9), 120, at(9, 30), 15))
        assertFalse(overlaps(at(9), 60, at(10), 60))
        assertTrue(overlaps(at(9, 30), null, at(9), 60))
        assertFalse(overlaps(at(10), null, at(9), 60))
        assertTrue(overlaps(at(9), null, at(9), null))
        assertFalse(overlaps(at(9), null, at(10), null))
    }
    @Test fun minuteGridProvidesIndependentOracleAndSymmetry() {
        for (a in 0..40 step 5) for (b in 0..40 step 5) for (da in listOf(5, 10, 30)) for (db in listOf(5, 15, 25)) {
            val oracle = (a until a + da).any { it in b until b + db }
            assertEquals(oracle, overlaps(at(0).plusMinutes(a.toLong()), da, at(0).plusMinutes(b.toLong()), db))
            assertEquals(oracle, overlaps(at(0).plusMinutes(b.toLong()), db, at(0).plusMinutes(a.toLong()), da))
        }
    }
    @Test fun overnightConflictsAndRecurrenceExcludeSelfAndAllDay() {
        val overnight = ItineraryItem(id = 1, tripId = 1, date = day, startTime = LocalTime.of(23, 30), title = "Overnight", durationMinutes = 120)
        val allDay = overnight.copy(id = 2, date = day.plusDays(1), startTime = null, durationMinutes = null)
        val events = listOf(overnight, allDay)
        assertEquals(listOf(overnight), overlappingEvents(events, listOf(day.plusDays(1)), LocalTime.of(0, 30), 60, emptySet()))
        assertTrue(overlappingEvents(events, listOf(day.plusDays(1)), LocalTime.of(1, 30), 60, emptySet()).isEmpty())
        assertTrue(overlappingEvents(events, listOf(day.plusDays(1)), LocalTime.of(0, 30), 60, setOf(1)).isEmpty())
        assertTrue(overlappingEvents(events, listOf(day.plusDays(1)), null, null, emptySet()).isEmpty())
        assertEquals(listOf(overnight), overlappingEvents(events, listOf(day.minusWeeks(1), day), LocalTime.of(23, 45), null, emptySet()))
    }
    @Test fun todayIncludesOvernightButNotEventsEndingAtMidnight() {
        val event = ItineraryItem(id = 1, tripId = 1, date = day, startTime = LocalTime.of(23, 0), title = "Late", durationMinutes = 120)
        val midnight = event.copy(id = 2, durationMinutes = 60)
        val allDay = event.copy(id = 3, date = day.plusDays(1), startTime = null, durationMinutes = null)
        assertEquals(listOf(allDay, event), eventsOnDay(listOf(midnight, event, allDay), day.plusDays(1)))
        assertTrue(eventsOnDay(listOf(event), day.plusDays(2)).isEmpty())
    }

    // U-N6: the widget hid skipped dates but listed a paid bill as "Bill due" and counted it; the Agenda hides it.
    @Test fun widgetLeavesOutPaidAndSkippedBillsLikeTheAgenda() {
        val due = ItineraryItem(id = 1, tripId = 0, date = day, startTime = null, title = "Due", category = "Bills")
        val paid = due.copy(id = 2, title = "Paid", paid = true)
        val skipped = due.copy(id = 3, title = "Skipped", skipped = true)
        val event = ItineraryItem(id = 4, tripId = 0, date = day, startTime = LocalTime.of(9, 0), title = "Event")
        val skippedEvent = event.copy(id = 5, skipped = true)
        assertEquals(listOf(due, event), widgetEventsOnDay(listOf(paid, skipped, event, due, skippedEvent), day))
        assertEquals(BillTasks.visible(listOf(due, paid, skipped).map { it.billTaskSummary() }, AgendaRange.TODAY, "", day, false).map { it.id },
            widgetEventsOnDay(listOf(due, paid, skipped), day).map { it.id })
    }
}
