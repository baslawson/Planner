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
import com.example.itinerary.data.Markdown
import com.example.itinerary.data.Notes
import kotlinx.coroutines.*

/** A note's reminder rings: its name and first lines, with Done (clears the reminder) and Snooze — as a task's does. */
class NoteReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.data?.lastPathSegment ?: return
        val trigger = intent.getLongExtra("trigger", 0L)
        val app = context.applicationContext as ItineraryApp
        app.reminderScheduler.ledger.fired(MissedReminders.noteKey(id), System.currentTimeMillis())
        val pending = goAsync()
        CoroutineScope(Dispatchers.Main).launch {
            try {
                app.repository.deliverNoteReminder(id, trigger) { note ->
                    if (notificationsEnabled(context)) {
                        val open = PendingIntent.getActivity(context, 0,
                            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                        val label = Notes.label(note)
                        // The text after the name, as the note's card shows it.
                        val lines = Markdown.plain(note.content).lines().filter { it.isNotBlank() }
                        val body = (if (note.title.isBlank()) lines.drop(1) else lines).take(6).joinToString("\n")
                        val notification = NotificationCompat.Builder(context, REMINDER_CHANNEL_ID)
                            .setSmallIcon(R.drawable.ic_notification)
                            .setContentTitle(label)
                            .setContentText(body.lineSequence().firstOrNull()?.takeIf { it.isNotBlank() } ?: "Note reminder")
                            .apply { if (body.isNotBlank()) setStyle(NotificationCompat.BigTextStyle().bigText(body)) }
                            .setSubText("Note reminder")
                            .setCategory(NotificationCompat.CATEGORY_REMINDER)
                            .setPriority(NotificationCompat.PRIORITY_HIGH)
                            .setContentIntent(open).setAutoCancel(true)
                            // On the lock screen just "Note reminder": a note's text can be private (task reminders show
                            // only their title).
                            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                            .setPublicVersion(NotificationCompat.Builder(context, REMINDER_CHANNEL_ID).setSmallIcon(R.drawable.ic_notification)
                                .setContentTitle("Note reminder").setCategory(NotificationCompat.CATEGORY_REMINDER).build())
                            .addAction(0, "Done", NoteActionReceiver.done(context, id, trigger))
                            .addAction(0, "Snooze", SnoozeActivity.noteAction(context, id, trigger)).build()
                        try { NotificationManagerCompat.from(context).notify("note:$id", 0, notification) }
                        catch (_: SecurityException) { /* Permission can be revoked after notificationsEnabled was checked. */ }
                    }
                }
                // One alarm fewer: a reminder waiting for one gets it (AlarmWindow).
                withContext(Dispatchers.IO) { app.repository.refillReminders() }
            } catch (e: Exception) {
                android.util.Log.w("NoteReminderReceiver", "Couldn't deliver note reminder", e)
            } finally { pending.finish() }
        }
    }

    companion object {
        fun intent(context: Context, id: String): Intent = Intent(context, NoteReminderReceiver::class.java)
            .setData(Uri.Builder().scheme("planner").authority("note-reminder").appendPath(id).build())
    }
}

/** Done on a note reminder: the reminder is cleared (the note itself stays as it is). */
class NoteActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.data?.lastPathSegment ?: return
        val trigger = intent.getLongExtra("trigger", 0)
        val pending = goAsync()
        CoroutineScope(Dispatchers.Main).launch {
            try {
                (context.applicationContext as ItineraryApp).repository.actOnNoteReminder(id, trigger)
                NotificationManagerCompat.from(context).cancel("note:$id", 0)
            } catch (e: Exception) {
                android.util.Log.w("NoteActionReceiver", "Couldn't clear note reminder", e)
                android.widget.Toast.makeText(context, "Couldn't clear this reminder. Open Planner to review it.", android.widget.Toast.LENGTH_LONG).show()
            } finally { pending.finish() }
        }
    }

    companion object {
        fun done(context: Context, id: String, trigger: Long): PendingIntent = PendingIntent.getBroadcast(context, 0,
            Intent(context, NoteActionReceiver::class.java)
                .setData(Uri.Builder().scheme("planner").authority("note-done").appendPath(id).appendQueryParameter("trigger", trigger.toString()).build())
                .putExtra("trigger", trigger), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}
