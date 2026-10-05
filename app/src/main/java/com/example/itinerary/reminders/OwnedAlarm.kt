package com.example.itinerary.reminders

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.content.ContextCompat

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

/** An alarm displaced by another, or timed out, keeps its own normal notification and actions. */
internal fun postOwnedAlarm(context: Context, extras: Bundle, missed: Boolean = false): Boolean {
    val id = extras.getString(EXTRA_OWNER_ID) ?: return false
    val trigger = extras.getLong(ReminderScheduler.EXTRA_TRIGGER)
    when (extras.getString(EXTRA_OWNER_KIND)) {
        "task" -> postTaskReminder(context, id, (if (missed) "Missed alarm: " else "") +
            extras.getString(ReminderScheduler.EXTRA_TITLE).orEmpty(), trigger)
        "note" -> postNoteReminder(context, id, trigger, null, missed = missed)
        else -> return false
    }
    return true
}
