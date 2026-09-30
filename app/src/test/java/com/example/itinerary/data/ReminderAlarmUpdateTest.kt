package com.example.itinerary.data

import com.example.itinerary.reminders.ReminderAlarms
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

// After a commit (B8): an ordinary save reconciles, so an alarm that is due but not yet delivered is kept.
class ReminderAlarmUpdateTest {
    private class Recorder : ReminderAlarms {
        val calls = mutableListOf<String>()
        override fun schedule(item: ItineraryItem, reminder: Reminder) { calls += "schedule" }
        override fun reconcile(item: ItineraryItem, reminder: Reminder) { calls += "reconcile" }
        override fun cancel(reminderId: Long) { calls += "cancel" }
    }
    private val event = ItineraryItem(id = 1, tripId = 1, date = LocalDate.of(2026, 10, 1), startTime = LocalTime.of(9, 0), title = "Dentist")
    private val reminder = Reminder(id = 5, itemId = 1, amount = 1, unit = ReminderUnit.HOURS)
    private fun run(event: ItineraryItem?, reset: Boolean = false, delivered: Boolean = false) = Recorder().also { r ->
        runBlocking { updateReminderAlarm(r, 5, event, reminder.takeIf { event != null }, reset) { _, _ -> delivered } } }.calls

    @Test fun ordinarySaveKeepsAPendingAlarm() = assertEquals(listOf("reconcile"), run(event))
    @Test fun changedScheduleCancelsFirst() = assertEquals(listOf("cancel", "reconcile"), run(event, reset = true))
    @Test fun paidSkippedOrDeletedEventCancels() {
        assertEquals(listOf("cancel"), run(event.copy(paid = true, category = "Bills", billAmountMinor = 100)))
        assertEquals(listOf("cancel"), run(event.copy(skipped = true)))
        assertEquals(listOf("cancel"), run(null))
    }
    @Test fun deliveredReminderIsNotScheduledAgain() = assertEquals(emptyList<String>(), run(event, delivered = true))
}
