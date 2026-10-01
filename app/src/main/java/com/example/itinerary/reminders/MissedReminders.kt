package com.example.itinerary.reminders

import com.example.itinerary.data.ItineraryItem
import com.example.itinerary.data.PlannerTask
import com.example.itinerary.data.Reminder
import com.example.itinerary.data.ReminderDeliveries
import com.example.itinerary.data.activeReminderAt
import com.example.itinerary.data.reminderTrigger

/**
 * A reboot clears Android's alarms, and so does a force stop (some phones do one when an app is swiped away). Planner keeps its own list of the alarms it set (key → trigger, see AlarmLedger); after a
 * boot, the ones that fell due while the phone was off are shown once as "missed"; when the app opens, the ones over
 * [GRACE_MS] overdue (an alarm that still exists has gone off by then: it is taken off the list as it rings). Only alarms that were really set count, so
 * a reminder saved or synced with a time already past is never "missed", and the list is emptied of everything due, so a
 * second reboot shows nothing again.
 */
object MissedReminders {
    const val WINDOW_MS = 24 * 3_600_000L
    const val MAX_SHOWN = 10
    // When the app opens, a reminder this late has lost its alarm. Android lets a late one through at the latest when
    // the phone wakes up or the app opens, so a shorter wait would show it as missed just before it rings.
    const val GRACE_MS = 10 * 60_000L

    fun eventKey(reminderId: Long) = "e:$reminderId"
    fun taskKey(taskId: String) = "t:$taskId"
    fun eventId(key: String): Long? = key.removePrefix("e:").takeIf { key.startsWith("e:") }?.toLongOrNull()
    fun taskId(key: String): String? = key.removePrefix("t:").takeIf { key.startsWith("t:") && it.isNotEmpty() }

    sealed interface Missed { val due: Long }
    data class Event(val item: ItineraryItem, val reminder: Reminder, override val due: Long) : Missed
    data class Task(val task: PlannerTask, override val due: Long) : Missed

    /** Alarms that fell due at most 24 hours ago, and at least [graceMs] ago. */
    fun due(pending: Map<String, Long>, now: Long, graceMs: Long = 0L): Map<String, Long> =
        pending.filterValues { it > now - WINDOW_MS && it <= now - graceMs }

    /** What is left once they were handled: only alarms still ahead (they are set again anyway) or less than [graceMs] late. */
    fun remaining(pending: Map<String, Long>, now: Long, graceMs: Long = 0L): Map<String, Long> = pending.filterValues { it > now - graceMs }

    /**
     * The [due] alarms still worth showing, newest first: the event or task still has that reminder at that time, it is not
     * done, paid or skipped, and an event reminder was not already delivered.
     */
    fun select(due: Map<String, Long>, events: Map<Long, Pair<ItineraryItem, Reminder>>, delivered: Map<Long, String>,
               tasks: Map<String, PlannerTask>): List<Missed> = due.mapNotNull { (key, trigger) ->
        eventId(key)?.let { id ->
            val (item, reminder) = events[id] ?: return@mapNotNull null
            val expected = reminder.snoozedUntil ?: reminderTrigger(item.date, item.startTime, reminder.offsetMinutes).toInstant().toEpochMilli()
            // An exact match only: a reminder changed since (or moved by a time-zone change while off) isn't the one that was missed.
            if (item.paid || item.skipped || trigger != expected || ReminderDeliveries.delivered(delivered[id], item, reminder)) null
            else Event(item, reminder, trigger)
        } ?: taskId(key)?.let { id -> tasks[id]?.takeIf { !it.done && it.activeReminderAt == trigger }?.let { Task(it, trigger) } }
    }.sortedByDescending { it.due }
}
