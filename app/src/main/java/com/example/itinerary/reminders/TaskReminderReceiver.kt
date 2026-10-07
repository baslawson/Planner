package com.example.itinerary.reminders

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.itinerary.ItineraryApp
import com.example.itinerary.R
import kotlinx.coroutines.*

class TaskReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.data?.lastPathSegment ?: return
        val trigger = intent.getLongExtra("trigger", 0L)
        // RB-3: before the first unlock after a reboot the database can't be read. This alarm was set from the locked
        // snapshot (BootReceiver), with the task's title; once unlocked it's noted as rung.
        if (!DirectBoot.isUnlocked(context)) {
            DirectBoot.fired(context, MissedReminders.taskKey(id), trigger)
            val title = intent.getStringExtra(EXTRA_LOCKED_TITLE) ?: "Task reminder"
            val ringFor = ringSecondsNow(context, intent.getBooleanExtra(ReminderScheduler.EXTRA_RING, false),
                intent.getIntExtra(ReminderScheduler.EXTRA_RING_SECONDS, 0))
            if (!startOwnedAlarm(context, "task", id, title, trigger, ringFor))
                postTaskReminder(context, id, title, trigger, couldNotRing = ringFor != null)
            return
        }
        (context.applicationContext as ItineraryApp).reminderScheduler.ledger.fired(MissedReminders.taskKey(id), System.currentTimeMillis())
        val pending = goAsync()
        CoroutineScope(Dispatchers.Main).launch {
            try {
                (context.applicationContext as ItineraryApp).repository.deliverTaskReminder(id, trigger) { task ->
                    val ringFor = ringSecondsNow(context, task.ringUntilDismissed, task.ringSeconds)
                    if (!startOwnedAlarm(context, "task", id, task.title, trigger, ringFor, task.notes))
                        postTaskReminder(context, id, task.title, trigger, couldNotRing = ringFor != null, notes = task.notes)
                }
            } catch (e: Exception) {
                android.util.Log.w("TaskReminderReceiver", "Couldn't deliver task reminder", e)
            } finally {
                pending.finish()
                // One alarm fewer: a reminder waiting for one gets it (AlarmWindow); and the locked-reboot snapshot is kept
                // fresh. H17-R1: after finish(), so the next alarm's receiver isn't held up by it.
                DirectBoot.afterRingLater(context.applicationContext as ItineraryApp)
            }
        }
    }

    companion object {
        // The task's title, in an alarm set before the first unlock (LockedAlarmManager): the database can't say it then.
        const val EXTRA_LOCKED_TITLE = "locked_title"

        fun intent(context: Context, id: String): Intent = Intent(context, TaskReminderReceiver::class.java)
            .setData(Uri.Builder().scheme("planner").authority("task-reminder").appendPath(id).build())
    }
}

/** A task reminder's notification. [quiet]: shown again (with its Done, after the unlock), without sounding again. */
// [notes]: the task's notes, what it is for: their first line instead of "Task reminder", all of them when opened.
// R6 (hunt 21): one that couldn't ring keeps its notes too, with the reason under them. R2: on the lock screen only the
// title and time (publicReminder), not the notes.
internal fun postTaskReminder(context: Context, id: String, title: String, trigger: Long, quiet: Boolean = false, couldNotRing: Boolean = false, silent: Boolean = false, notes: String = "") {
    if (!notificationsEnabled(context)) return
    val open = PendingIntent.getActivity(context, 0,
        openPlannerIntent(context),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    val firstLine = notes.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
    val why = if (couldNotRing) CouldNotRing.now(context) else null // H17-R2
    val notification = NotificationCompat.Builder(context, REMINDER_CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(title)
        .setContentText(firstLine ?: why?.short ?: "Task reminder")
        .apply {
            val big = listOfNotNull(notes.trim().takeIf { it.isNotEmpty() }, why?.full).joinToString("\n")
            if (big.isNotEmpty()) setStyle(NotificationCompat.BigTextStyle().bigText(big))
            if (why != null && firstLine != null) setSubText(why.brief)
        }
        .setCategory(NotificationCompat.CATEGORY_REMINDER)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setContentIntent(open).setAutoCancel(true)
        .setOnlyAlertOnce(quiet).setSilent(silent)
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        .setPublicVersion(publicReminder(context, REMINDER_CHANNEL_ID, title, reminderMoment(context, trigger)))
        .addDataAction(context, "Done", TaskActionReceiver.done(context, id, trigger))
        .addAction(0, "Snooze", SnoozeActivity.taskAction(context, id, trigger)).build()
    try {
        NotificationManagerCompat.from(context).notify("task:$id", 0, notification)
    } catch (_: SecurityException) {
        // Permission can be revoked after notificationsEnabled was checked.
    }
}
