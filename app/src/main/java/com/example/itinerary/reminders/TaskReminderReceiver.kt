package com.example.itinerary.reminders

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.itinerary.ItineraryApp
import com.example.itinerary.MainActivity
import com.example.itinerary.R
import kotlinx.coroutines.*

class TaskReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.data?.lastPathSegment ?: return
        val trigger = intent.getLongExtra("trigger", 0L)
        (context.applicationContext as ItineraryApp).reminderScheduler.ledger.fired(MissedReminders.taskKey(id), System.currentTimeMillis())
        val pending = goAsync()
        CoroutineScope(Dispatchers.Main).launch {
            try {
                (context.applicationContext as ItineraryApp).repository.deliverTaskReminder(id, trigger) { task ->
                    if (notificationsEnabled(context)) {
                        val open = PendingIntent.getActivity(context, 0,
                            Intent(context, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                        val notification = NotificationCompat.Builder(context, REMINDER_CHANNEL_ID)
                            .setSmallIcon(R.drawable.ic_notification)
                            .setContentTitle(task.title)
                            .setContentText("Task reminder")
                            .setCategory(NotificationCompat.CATEGORY_REMINDER)
                            .setPriority(NotificationCompat.PRIORITY_HIGH)
                            .setContentIntent(open).setAutoCancel(true)
                            .addAction(0, "Done", TaskActionReceiver.done(context, id, trigger))
                            .addAction(0, "Snooze", SnoozeActivity.taskAction(context, id, trigger)).build()
                        try {
                            NotificationManagerCompat.from(context).notify("task:$id", 0, notification)
                        } catch (_: SecurityException) {
                            // Permission can be revoked after notificationsEnabled was checked.
                        }
                    }
                }
                // One alarm fewer: a reminder waiting for one gets it (AlarmWindow).
                withContext(Dispatchers.IO) { (context.applicationContext as ItineraryApp).repository.refillReminders() }
            } catch (e: Exception) {
                android.util.Log.w("TaskReminderReceiver", "Couldn't deliver task reminder", e)
            } finally { pending.finish() }
        }
    }

    companion object {
        fun intent(context: Context, id: String): Intent = Intent(context, TaskReminderReceiver::class.java)
            .setData(Uri.Builder().scheme("planner").authority("task-reminder").appendPath(id).build())
    }
}
