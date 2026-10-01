package com.example.itinerary.reminders

import com.example.itinerary.data.ItineraryItem
import com.example.itinerary.data.PlannerTask
import com.example.itinerary.data.Reminder
import com.example.itinerary.data.ReminderDeliveries
import com.example.itinerary.data.ReminderUnit
import com.example.itinerary.data.reminderTrigger
import com.example.itinerary.reminders.MissedReminders.eventKey
import com.example.itinerary.reminders.MissedReminders.taskKey
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class MissedRemindersTest {
    private val hour = 3_600_000L
    private val item = ItineraryItem(id = 1, tripId = 1, date = LocalDate.of(2026, 10, 5), startTime = LocalTime.of(10, 0), title = "Dentist")
    private val reminder = Reminder(id = 7, itemId = 1, amount = 1, unit = ReminderUnit.HOURS)
    private val trigger = reminderTrigger(item.date, item.startTime, 60).toInstant().toEpochMilli()
    private val now = trigger + 2 * hour
    private val task = PlannerTask(id = "t1", title = "Call mechanic", reminderAt = trigger - hour)

    private fun select(pending: Map<String, Long>, item: ItineraryItem = this.item, reminder: Reminder = this.reminder,
                       task: PlannerTask = this.task, delivered: Map<Long, String> = emptyMap(), now: Long = this.now) =
        MissedReminders.select(MissedReminders.due(pending, now), mapOf(reminder.id to (item to reminder)), delivered, mapOf(task.id to task))

    @Test fun keysRoundTrip() {
        assertEquals(7L, MissedReminders.eventId(eventKey(7)))
        assertEquals("a:b", MissedReminders.taskId(taskKey("a:b")))
        assertNull(MissedReminders.eventId(taskKey("7"))); assertNull(MissedReminders.taskId(eventKey(7)))
    }

    @Test fun windowIsTheLast24HoursUpToNow() {
        val pending = mapOf("a" to now, "b" to now + 1, "c" to now - MissedReminders.WINDOW_MS, "d" to now - MissedReminders.WINDOW_MS + 1)
        assertEquals(setOf("a", "d"), MissedReminders.due(pending, now).keys)
    }

    @Test fun eventAndTaskDueWhileOffAreShownNewestFirst() {
        val missed = select(mapOf(eventKey(7) to trigger, taskKey("t1") to trigger - hour))
        assertEquals(listOf(MissedReminders.Event(item, reminder, trigger), MissedReminders.Task(task, trigger - hour)), missed)
    }

    @Test fun olderThan24HoursIsNotShown() {
        assertTrue(select(mapOf(eventKey(7) to trigger), now = trigger + MissedReminders.WINDOW_MS).isEmpty())
        assertEquals(1, select(mapOf(eventKey(7) to trigger), now = trigger + MissedReminders.WINDOW_MS - 1).size)
    }

    @Test fun alreadyDeliveredEventIsNotShown() {
        val key = ReminderDeliveries.key(item, reminder)!!
        assertTrue(select(mapOf(eventKey(7) to trigger), delivered = mapOf(7L to key)).isEmpty())
        // A delivery of an earlier date's occurrence doesn't count.
        assertEquals(1, select(mapOf(eventKey(7) to trigger), delivered = mapOf(7L to ReminderDeliveries.key(item.date.minusDays(1), item.startTime, 60))).size)
    }

    @Test fun snoozedReminderUsesItsSnoozeTime() {
        val snoozed = reminder.copy(snoozedUntil = trigger + hour)
        assertEquals(listOf(MissedReminders.Event(item, snoozed, trigger + hour)), select(mapOf(eventKey(7) to trigger + hour), reminder = snoozed))
        // The alarm for the original time was replaced by the snooze.
        assertTrue(select(mapOf(eventKey(7) to trigger), reminder = snoozed).isEmpty())
        val snoozedTask = task.copy(snoozedUntil = trigger)
        assertEquals(listOf(MissedReminders.Task(snoozedTask, trigger)), select(mapOf(taskKey("t1") to trigger), task = snoozedTask))
        assertTrue(select(mapOf(taskKey("t1") to trigger - hour), task = snoozedTask).isEmpty())
    }

    @Test fun doneTaskAndPaidOrSkippedEventAreNotShown() {
        assertTrue(select(mapOf(taskKey("t1") to trigger - hour), task = task.copy(done = true)).isEmpty())
        assertTrue(select(mapOf(eventKey(7) to trigger), item = item.copy(paid = true)).isEmpty())
        assertTrue(select(mapOf(eventKey(7) to trigger), item = item.copy(skipped = true)).isEmpty())
    }

    @Test fun changedOrDeletedReminderIsNotShown() {
        assertTrue(select(mapOf(eventKey(7) to trigger), reminder = reminder.copy(amount = 2)).isEmpty())
        assertTrue(select(mapOf(eventKey(7) to trigger), item = item.copy(startTime = LocalTime.of(11, 0))).isEmpty())
        assertTrue(select(mapOf(taskKey("t1") to trigger - hour), task = task.copy(reminderAt = trigger)).isEmpty())
        assertTrue(select(mapOf(taskKey("t1") to trigger - hour), task = task.copy(reminderAt = null)).isEmpty())
        assertTrue(select(mapOf(eventKey(8) to trigger, taskKey("gone") to trigger)).isEmpty())
    }

    @Test fun repeatedBootDoesNotShowAgain() {
        val pending = mapOf(eventKey(7) to trigger, taskKey("t1") to trigger - hour, taskKey("later") to now + hour, "old" to now - 2 * MissedReminders.WINDOW_MS)
        assertEquals(2, select(pending).size)
        val left = MissedReminders.remaining(pending, now)
        assertEquals(mapOf(taskKey("later") to now + hour), left)
        assertTrue(select(left, now = now + 60_000).isEmpty())
    }

    // R-M1: a force stop clears alarms without a reboot. When the app opens, an alarm still in the ledger well after its
    // time never rang; one only a little late may still be on its way.
    @Test fun appOpenShowsOnlyAlarmsWellOverdue() {
        val grace = MissedReminders.GRACE_MS
        val pending = mapOf("lost" to now - grace, "late" to now - grace + 1, "ahead" to now + hour, "old" to now - MissedReminders.WINDOW_MS)
        assertEquals(setOf("lost"), MissedReminders.due(pending, now, grace).keys)
        assertEquals(setOf("late", "ahead"), MissedReminders.remaining(pending, now, grace).keys)
        // The one shown is forgotten, the late one is shown at a later opening if it still hasn't rung.
        val left = MissedReminders.remaining(pending, now, grace)
        assertEquals(setOf("late"), MissedReminders.due(left, now + 60_000, grace).keys)
    }
}
