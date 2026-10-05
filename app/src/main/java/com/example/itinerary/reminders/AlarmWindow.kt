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
     * A time-only boundary for older ReminderAlarms implementations. The scheduler uses [select] to bound ties by key.
     */
    fun horizon(triggers: Collection<Long>, now: Long, limit: Int = LIMIT): Long? {
        val ahead = triggers.filter { it > now }
        return if (ahead.size <= limit) null else ahead.sorted()[limit - 1]
    }

    data class Selection(val horizon: Long?, val atHorizon: Set<String>) {
        fun arms(key: String, trigger: Long): Boolean = horizon == null || trigger < horizon ||
            trigger == horizon && key in atHorizon
    }

    /** A bounded window across event, task and note keys, with deterministic selection of simultaneous alarms. */
    fun select(triggers: Map<String, Long>, now: Long, limit: Int = LIMIT): Selection {
        require(limit > 0)
        val ahead = triggers.entries.filter { it.value > now }.sortedWith(compareBy({ it.value }, { it.key }))
        if (ahead.size <= limit) return Selection(null, emptySet())
        val chosen = ahead.take(limit)
        val horizon = chosen.last().value
        return Selection(horizon, chosen.filter { it.value == horizon }.mapTo(hashSetOf()) { it.key })
    }

    /** Newly waiting future alarms, plus already-waiting alarms now due that still match the stored reminder. */
    fun deferred(previous: Map<String, Long>, triggers: Map<String, Long>, selection: Selection, now: Long): Map<String, Long> =
        triggers.filter { (key, trigger) ->
            trigger > now && !selection.arms(key, trigger) || trigger <= now && previous[key] == trigger
        }

    /** The legacy time-only admission check; production uses [Selection.arms] so ties cannot exceed the limit. */
    fun arms(trigger: Long, horizon: Long?): Boolean = horizon == null || trigger <= horizon

    /** After a save: whether to choose the nearest again, because [armed] is over the limit or, while some wait, well under it. */
    fun needsRefill(armed: Int, horizon: Long?, limit: Int = LIMIT): Boolean = armed > limit || horizon != null && armed < limit / 2
}

/** When an event reminder's alarm goes off: its snooze, or its offset before the event. */
fun eventReminderAt(item: ItineraryItem, reminder: Reminder): Long =
    reminder.snoozedUntil ?: reminderTrigger(item.date, item.startTime, reminder).toInstant().toEpochMilli()
