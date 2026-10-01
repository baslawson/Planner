package com.example.itinerary.reminders

import com.example.itinerary.data.ItineraryItem
import com.example.itinerary.data.Reminder
import com.example.itinerary.data.reminderTrigger

/**
 * Android 12 and later lets an app hold at most 500 alarms at once and refuses any more. A long series has an alarm for
 * every occurrence, so Planner keeps only the [LIMIT] nearest reminders armed (events, tasks and snoozes together). The
 * later ones wait and are armed as the window moves on: whenever one of them rings, when the app opens, after a reboot or
 * time change, and after a save that leaves too many or too few armed. A waiting reminder has no alarm and is not in the
 * AlarmLedger, so it is never shown as "missed".
 */
object AlarmWindow {
    const val LIMIT = 400

    /**
     * The time of the [limit]th nearest of [triggers] still ahead of [now]: reminders after it wait. Null when all fit.
     * Reminders at that very time are all armed, so a tie can go a little over [limit] (Android's 500 leaves room).
     */
    fun horizon(triggers: Collection<Long>, now: Long, limit: Int = LIMIT): Long? {
        val ahead = triggers.filter { it > now }
        return if (ahead.size <= limit) null else ahead.sorted()[limit - 1]
    }

    /** Whether a reminder at [trigger] gets its alarm now, with the ones after [horizon] waiting. */
    fun arms(trigger: Long, horizon: Long?): Boolean = horizon == null || trigger <= horizon

    /** After a save: whether to choose the nearest again, because [armed] is over the limit or, while some wait, well under it. */
    fun needsRefill(armed: Int, horizon: Long?, limit: Int = LIMIT): Boolean = armed > limit || horizon != null && armed < limit / 2
}

/** When an event reminder's alarm goes off: its snooze, or its offset before the event. */
fun eventReminderAt(item: ItineraryItem, reminder: Reminder): Long =
    reminder.snoozedUntil ?: reminderTrigger(item.date, item.startTime, reminder.offsetMinutes).toInstant().toEpochMilli()
