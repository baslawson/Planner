package com.example.itinerary.reminders

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.itinerary.ItineraryApp
import kotlinx.coroutines.*
import java.time.ZonedDateTime

fun snoozeTime(tomorrow: Boolean, now: ZonedDateTime = ZonedDateTime.now()): Long =
    (if (tomorrow) now.toLocalDate().plusDays(1).atTime(9, 0).atZone(now.zone) else now.plusHours(1)).toInstant().toEpochMilli()

// Hunt 23: nothing posts this any more (snoozing goes through SnoozeActivity's chooser). Kept for notifications an earlier
// version posted, whose Snooze buttons still come here, and for DraftRecoveryUiTest. Not exported: only Planner can send it.
class SnoozeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val token = intent.getStringExtra(ReminderScheduler.EXTRA_SNOOZE_TOKEN) ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val id = intent.getLongExtra(ReminderScheduler.EXTRA_REMINDER_ID, 0)
                val tomorrow = intent.action == TOMORROW
                val saved = (context.applicationContext as ItineraryApp).repository.snoozeReminder(id, snoozeTime(tomorrow), token)
                if (saved) android.widget.Toast.makeText(context, if (tomorrow) "Snoozed until tomorrow at 9 am" else "Snoozed for 1 hour", android.widget.Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                android.util.Log.w("SnoozeReceiver", "Couldn't snooze", e)
                android.widget.Toast.makeText(context, "Couldn't snooze. Please try again.", android.widget.Toast.LENGTH_LONG).show()
            } finally { pending.finish() }
        }
    }
    companion object {
        private const val TOMORROW = "com.example.itinerary.SNOOZE_TOMORROW"
        fun action(context: Context, id: Long, tomorrow: Boolean, token: String): PendingIntent = PendingIntent.getBroadcast(
            context, id.toInt(), Intent(context, SnoozeReceiver::class.java)
                .setAction(if (tomorrow) TOMORROW else "com.example.itinerary.SNOOZE_HOUR")
                .setData(android.net.Uri.parse("planner://event-snooze/$id/$token"))
                .putExtra(ReminderScheduler.EXTRA_SNOOZE_TOKEN, token)
                .putExtra(ReminderScheduler.EXTRA_REMINDER_ID, id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}
