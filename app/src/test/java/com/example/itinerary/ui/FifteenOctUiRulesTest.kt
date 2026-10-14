package com.example.itinerary.ui

import com.example.itinerary.data.EventTimes
import com.example.itinerary.data.ItineraryItem
import com.example.itinerary.data.PendingMove
import com.example.itinerary.data.PlannerTask
import com.example.itinerary.data.Reminder
import com.example.itinerary.data.ReminderUnit
import com.example.itinerary.reminders.LockedAlarm
import com.example.itinerary.widget.WeekWidget
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

// The 15 Oct features' wording: the Undo bar after a drag, "When to leave", the week widget's lines.
class FifteenOctUiRulesTest {
    // Bug hunt 29 S10: weekday and month names and am/pm in English, whatever the computer's language.
    private val locale = java.util.Locale.getDefault()
    @org.junit.Before fun english() = java.util.Locale.setDefault(java.util.Locale.UK)
    @org.junit.After fun back() = java.util.Locale.setDefault(locale)

    private val today = LocalDate.of(2026, 10, 15)
    private fun move(from: LocalDate, to: LocalDate, times: Pair<EventTimes, EventTimes>? = null) =
        PendingMove(itemId = 1, title = "Gym", fromDate = from, toDate = to, times = times)

    @Test fun theUndoBarSaysWhereTheEventWent() {
        assertEquals("Moved Gym to tomorrow", moveMessage(move(today, today.plusDays(1)), today, true))
        assertEquals("Moved Gym to Saturday 17 October", moveMessage(move(today, today.plusDays(2)), today, true))
        val nine = EventTimes(LocalTime.of(9, 0), 60)
        assertEquals("Moved Gym to 10:30", moveMessage(move(today, today, nine to EventTimes(LocalTime.of(10, 30), 60)), today, true))
        assertEquals("moved gym to 10:30 am", moveMessage(move(today, today, nine to EventTimes(LocalTime.of(10, 30), 60)), today, false).lowercase())
        assertEquals("Moved Gym to Saturday 17 October, 08:00", moveMessage(move(today, today.plusDays(2), nine to EventTimes(LocalTime.of(8, 0), 60)), today, true))
        assertEquals("Gym now ends at 10:45", moveMessage(move(today, today, nine to EventTimes(LocalTime.of(9, 0), 105)), today, true))
    }

    @Test fun aReminderAtTheEndOfTheTravelBufferIsWhenToLeave() {
        assertEquals("Time to leave (20 min before)", leaveLabel(20))
        assertEquals("Time to leave (1 h before)", leaveLabel(60))
        val event = ItineraryItem(id = 1, tripId = 0, date = today, startTime = LocalTime.of(9, 0), title = "Dentist", bufferBeforeMinutes = 20)
        val leave = Reminder(itemId = 1, amount = 20, unit = ReminderUnit.MINUTES)
        assertEquals("Time to leave · ${leave.label}", LockedAlarm.Event.offsetLabel(event, leave))
        val other = Reminder(itemId = 1, amount = 1, unit = ReminderUnit.HOURS)
        assertEquals(other.label, LockedAlarm.Event.offsetLabel(event, other))
        assertEquals(leave.label, LockedAlarm.Event.offsetLabel(event.copy(bufferBeforeMinutes = 0), leave))
    }

    @Test fun theWeekWidgetsDayLine() {
        val time: (LocalTime) -> String = { "%d:%02d".format(it.hour, it.minute) }
        val events = listOf(
            ItineraryItem(tripId = 0, date = today, startTime = LocalTime.of(9, 0), title = "Dentist"),
            ItineraryItem(tripId = 0, date = today.minusDays(1), endDate = today.plusDays(1), startTime = null, title = "Holiday"),
            ItineraryItem(tripId = 0, date = today, startTime = null, title = "Rent", category = "Bills"),
        )
        val tasks = listOf(PlannerTask(title = "Tax", dueDate = today.minusDays(2)), PlannerTask(title = "Plants", dueDate = today.plusDays(1)),
            PlannerTask(title = "Done one", dueDate = today, done = true))
        assertEquals("9:00 Dentist · Holiday · Bill: Rent · ☐ Tax", WeekWidget.dayText(events, tasks, today, today, 6, time))
        assertEquals("9:00 Dentist · Holiday · +2 more", WeekWidget.dayText(events, tasks, today, today, 2, time))
        // Overdue tasks only on today's line.
        assertEquals("☐ Plants", WeekWidget.dayText(emptyList(), tasks, today.plusDays(1), today, 6, time))
        assertEquals("", WeekWidget.dayText(emptyList(), tasks, today.plusDays(2), today, 6, time))
    }
}
