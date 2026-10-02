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
        (context.applicationContext as ItineraryApp).reminderScheduler.ledger.fired(MissedReminders.eventKey(id), System.currentTimeMillis())
        val pending = goAsync()
        CoroutineScope(Dispatchers.Main).launch {
            try {
                (context.applicationContext as ItineraryApp).repository.deliverReminder(
                    id, intent.getLongExtra(ReminderScheduler.EXTRA_TRIGGER, 0L)) { item, reminder ->
                    show(context, reminderIntent(context, item, reminder))
                }
                // One alarm fewer: a reminder waiting for one gets it (AlarmWindow).
                withContext(Dispatchers.IO) { (context.applicationContext as ItineraryApp).repository.refillReminders() }
            } catch (e: Exception) { android.util.Log.w("ReminderReceiver", "Couldn't deliver reminder", e) }
            finally { pending.finish() }
        }
    }

    private fun show(context: Context, intent: Intent) {
        val id = intent.getLongExtra(ReminderScheduler.EXTRA_REMINDER_ID, 0L)
        val content = reminderContent(context, intent.extras) ?: return
        var couldNotRing = false
        if (intent.getBooleanExtra(ReminderScheduler.EXTRA_RING, false) && ringingAlarmsEnabled(context)) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, AlarmService::class.java).putExtras(intent))
                return
            } catch (e: Exception) {
                // Android 12+ lets a background app start the ringing service from an exact alarm only. Without "Alarms &
                // reminders" (off by default from Android 14) this alarm came inexact, and nothing else allowed then can
                // ring: setAlarmClock needs the same permission, and full-screen intents are for calling and clock apps.
                // So the notification itself keeps sounding until it is seen, and says why it didn't ring.
                android.util.Log.w("ReminderReceiver", "Couldn't start the ringing alarm", e)
                couldNotRing = true
            }
        }
        postReminderNotification(context, id.toInt(), content.title, content.text, content.subText, id, intent.getStringExtra(ReminderScheduler.EXTRA_BILL_TOKEN), intent.getStringExtra(ReminderScheduler.EXTRA_SNOOZE_TOKEN), couldNotRing)
    }
}
