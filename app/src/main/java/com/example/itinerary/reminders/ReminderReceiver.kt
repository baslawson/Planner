package com.example.itinerary.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.example.itinerary.ItineraryApp
import kotlinx.coroutines.*

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(ReminderScheduler.EXTRA_REMINDER_ID, 0L)
        // Sample alarms use id zero and have no database record.
        if (id == 0L) { show(context, intent); return }
        val pending = goAsync()
        CoroutineScope(Dispatchers.Main).launch {
            try {
                (context.applicationContext as ItineraryApp).repository.deliverReminder(
                    id, intent.getLongExtra(ReminderScheduler.EXTRA_TRIGGER, 0L)) { item, reminder ->
                    show(context, reminderIntent(context, item, reminder))
                }
            } catch (e: Exception) { android.util.Log.w("ReminderReceiver", "Couldn't deliver reminder", e) }
            finally { pending.finish() }
        }
    }

    private fun show(context: Context, intent: Intent) {
        val id = intent.getLongExtra(ReminderScheduler.EXTRA_REMINDER_ID, 0L)
        val content = reminderContent(context, intent.extras) ?: return
        if (intent.getBooleanExtra(ReminderScheduler.EXTRA_RING, false) && ringingAlarmsEnabled(context)) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, AlarmService::class.java).putExtras(intent))
                return
            } catch (_: Exception) { /* Fall back to a normal notification. */ }
        }
        postReminderNotification(context, id.toInt(), content.title, content.text, content.subText, id, intent.getStringExtra(ReminderScheduler.EXTRA_BILL_TOKEN), intent.getStringExtra(ReminderScheduler.EXTRA_SNOOZE_TOKEN))
    }
}
