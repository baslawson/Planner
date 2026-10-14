package com.example.itinerary.reminders

import com.example.itinerary.data.ItineraryItem
import com.example.itinerary.data.PlannerTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

// The morning summary: what it says, and when the next one is due.
class MorningSummaryTest {
    private val today = LocalDate.of(2026, 10, 15)
    private fun event(title: String, time: LocalTime? = null, date: LocalDate = today, category: String = "Other", paid: Boolean = false) =
        ItineraryItem(tripId = 0, date = date, startTime = time, title = title, category = category, paid = paid)
    private fun content(events: List<ItineraryItem>, tasks: List<PlannerTask> = emptyList()) =
        MorningSummary.content(events, tasks, today) { "%d:%02d".format(it.hour, it.minute) }

    @Test fun listsTheDaysEventsTasksAndUnpaidBills() {
        val summary = content(listOf(
            event("Holiday", date = today.minusDays(2)),
            event("Lunch with Sam", LocalTime.of(12, 30)),
            event("Market"),
            event("Rent", category = "Bills"),
            event("Power", category = "Bills", paid = true),
        ), listOf(PlannerTask(title = "Tax return", dueDate = today.minusDays(1)), PlannerTask(title = "Water plants", dueDate = today)))!!
        assertEquals("Today: 3 events, 2 tasks, 1 bill due", summary.title)
        assertEquals(listOf("Continues: Holiday", "12:30  Lunch with Sam", "All day: Market", "☐ Tax return (overdue)",
            "☐ Water plants", "Bill due: Rent"), summary.lines)
    }

    @Test fun nothingPlannedShowsNothingAndALongDayIsCounted() {
        assertNull(content(listOf(event("Power", category = "Bills", paid = true))))
        val busy = content((8..17).map { event("Meeting $it", LocalTime.of(it, 0)) })!!
        assertEquals("Today: 10 events", busy.title)
        assertEquals(MorningSummary.MAX_LINES, busy.lines.size)
        assertEquals("and 5 more", busy.lines.last())
    }

    @Test fun theEveningSummaryIsTomorrowsAndTheButtonsKnowWhatIsListed() {
        val tomorrow = today.plusDays(1)
        val tax = PlannerTask(title = "Tax return", dueDate = today.minusDays(1))
        val plants = PlannerTask(title = "Water plants", dueDate = tomorrow)
        val rent = event("Rent", date = tomorrow, category = "Bills").copy(id = 9)
        val summary = MorningSummary.content(listOf(event("Dentist", LocalTime.of(9, 0), date = tomorrow), rent), listOf(tax, plants), tomorrow, tomorrow = true,
        ) { "%d:%02d".format(it.hour, it.minute) }!!
        // Only the tasks due that day: today's overdue ones were the morning's.
        assertEquals("Tomorrow: 1 event, 1 task, 1 bill due", summary.title)
        assertEquals(listOf("9:00  Dentist", "☐ Water plants", "Bill due: Rent"), summary.lines)
        assertEquals(listOf(plants.id), summary.taskIds)
        assertEquals(listOf(9L), summary.billIds)
    }

    @Test fun theNextSummaryIsTodayWhileItsTimeIsAheadElseTomorrow() {
        val at = LocalTime.of(7, 30)
        assertEquals(today.atTime(at), MorningSummary.nextAt(today.atTime(6, 0), at, shownToday = false))
        assertEquals(today.plusDays(1).atTime(at), MorningSummary.nextAt(today.atTime(8, 0), at, shownToday = false))
        // Already shown today (an alarm a little early, say): tomorrow.
        assertEquals(today.plusDays(1).atTime(at), MorningSummary.nextAt(today.atTime(7, 25), at, shownToday = true))
    }
}
