package com.example.itinerary.reminders

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.content.ContextCompat
import com.example.itinerary.data.PlannerNote
import com.example.itinerary.data.PlannerTask
import com.example.itinerary.data.activeReminderAt
import kotlinx.coroutines.launch

internal const val EXTRA_OWNER_KIND = "alarm_owner_kind"
internal const val EXTRA_OWNER_ID = "alarm_owner_id"

internal fun ownedAlarmExtras(kind: String, id: String, title: String, trigger: Long): Bundle = Bundle().apply {
    putString(EXTRA_OWNER_KIND, kind)
    putString(EXTRA_OWNER_ID, id)
    // Notes deliberately carry no words into an intent or direct-boot storage.
    putString(ReminderScheduler.EXTRA_TITLE, if (kind == "note") "Note reminder" else title)
    putString(ReminderScheduler.EXTRA_LOCATION, "")
    val local = java.time.Instant.ofEpochMilli(trigger).atZone(java.time.ZoneId.systemDefault())
    putString(ReminderScheduler.EXTRA_DATE, local.toLocalDate().toString())
    putString(ReminderScheduler.EXTRA_TIME, local.toLocalTime().toString())
    putString(ReminderScheduler.EXTRA_OFFSET_LABEL, if (kind == "note") "Note reminder" else "Task reminder")
    putLong(ReminderScheduler.EXTRA_TRIGGER, trigger)
}

/** False means the receiver must still post its notification, including a truthful fallback message. */
internal fun startOwnedAlarm(context: Context, kind: String, id: String, title: String, trigger: Long, ring: Boolean): Boolean {
    if (!ring || !ringingAlarmsEnabled(context)) return false
    var token: String? = null
    return try {
        token = OwnedAlarmStarts.reserve(context, kind, id)
        ContextCompat.startForegroundService(context, Intent(context, AlarmService::class.java)
            .putExtras(ownedAlarmExtras(kind, id, title, trigger)).putExtra(EXTRA_OWNER_START, token))
        true
    } catch (e: Exception) {
        token?.let { runCatching { OwnedAlarmStarts.cancel(context, kind, id, it) } }
        android.util.Log.w("OwnedAlarm", "Couldn't start ringing reminder", e)
        false
    }
}

/**
 * An alarm displaced by another, or timed out, keeps its own normal notification and actions. [silent]: ringing was just
 * turned off, so it doesn't sound again (A15-3).
 */
internal fun postOwnedAlarm(context: Context, extras: Bundle, missed: Boolean = false, silent: Boolean = false, allowChime: Boolean = true): Boolean {
    val id = extras.getString(EXTRA_OWNER_ID) ?: return false
    val trigger = extras.getLong(ReminderScheduler.EXTRA_TRIGGER)
    val kind = extras.getString(EXTRA_OWNER_KIND)
    when (kind) {
        "task" -> postTaskReminder(context, id, (if (missed) "Missed alarm: " else "") +
            extras.getString(ReminderScheduler.EXTRA_TITLE).orEmpty(), trigger, silent = silent, allowChime = allowChime)
        // The alarm carries no word of the note: "Note reminder" at once, its words once read (below).
        "note" -> postNoteReminder(context, id, trigger, null, missed = missed, silent = silent, allowChime = allowChime)
        else -> return false
    }
    refreshOwnedAlarm(context, kind, id, trigger, missed, silent)
    return true
}

/**
 * A14-3/A15-3: shown again, quietly, from the reminder as it now is (a note's words, a task's current title). A15-2: one
 * dealt with meanwhile (Done, Snooze, changed, deleted) is taken away instead, and it is checked again after posting, so a
 * Done landing in between can't bring it back. Before the first unlock nothing can be read: it waits, as any note (DirectBoot).
 */
private fun refreshOwnedAlarm(context: Context, kind: String, id: String, trigger: Long, missed: Boolean, silent: Boolean) {
    if (!DirectBoot.isUnlocked(context)) return
    val app = context.applicationContext as com.example.itinerary.ItineraryApp
    val tag = "$kind:$id"
    app.appScope.launch(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            val manager = androidx.core.app.NotificationManagerCompat.from(context)
            suspend fun current(): Any? = if (kind == "task") app.repository.task(id)?.takeIf { !it.done && it.activeReminderAt == trigger }
                else app.repository.note(id)?.takeIf { it.activeReminderAt == trigger }
            val item = current() ?: run { manager.cancel(tag, 0); return@runCatching }
            val shown = context.getSystemService(android.app.NotificationManager::class.java).activeNotifications.any { it.tag == tag }
            if (!shown) return@runCatching
            when (item) {
                is PlannerTask -> postTaskReminder(context, id, (if (missed) "Missed alarm: " else "") + item.title, trigger, quiet = true, silent = silent)
                is PlannerNote -> postNoteReminder(context, id, trigger, item, quiet = true, missed = missed, silent = silent)
            }
            if (current() == null) manager.cancel(tag, 0)
        }.onFailure { android.util.Log.w("OwnedAlarm", "Couldn't show the reminder as it now is", it) }
    }
}
