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
import com.example.itinerary.data.PlannerNote
import kotlinx.coroutines.*

/** A note's reminder rings: its name and first lines, with Done (clears the reminder) and Snooze — as a task's does. */
class NoteReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.data?.lastPathSegment ?: return
        val trigger = intent.getLongExtra("trigger", 0L)
        // RB-3: before the first unlock after a reboot the database can't be read. This alarm was set from the locked
        // snapshot (BootReceiver), which keeps no word of the note: it says "Note reminder" until the phone is unlocked,
        // and is then shown again with the note's words (DirectBoot.replayFired).
        if (!DirectBoot.isUnlocked(context)) {
            DirectBoot.fired(context, MissedReminders.noteKey(id), trigger)
            val ring = intent.getBooleanExtra(ReminderScheduler.EXTRA_RING, false)
            if (!startOwnedAlarm(context, "note", id, "Note reminder", trigger, ring))
                postNoteReminder(context, id, trigger, null, couldNotRing = ring)
            return
        }
        val app = context.applicationContext as ItineraryApp
        app.reminderScheduler.ledger.fired(MissedReminders.noteKey(id), System.currentTimeMillis())
        val pending = goAsync()
        CoroutineScope(Dispatchers.Main).launch {
            try {
                app.repository.deliverNoteReminder(id, trigger) { note ->
                    if (!startOwnedAlarm(context, "note", id, "Note reminder", trigger, note.ringUntilDismissed))
                        postNoteReminder(context, id, trigger, note, couldNotRing = note.ringUntilDismissed)
                }
                // One alarm fewer: a reminder waiting for one gets it (AlarmWindow); and the locked-reboot snapshot is kept fresh.
                withContext(Dispatchers.IO) { DirectBoot.afterRing(app) }
            } catch (e: Exception) {
                android.util.Log.w("NoteReminderReceiver", "Couldn't deliver note reminder", e)
            } finally { pending.finish() }
        }
    }

    companion object {
        // U-13: tapping the notification opens this note's editor (MainActivity, AppNav, then the Notes page).
        const val OPEN_NOTE = "com.example.itinerary.OPEN_NOTE"
        const val EXTRA_NOTE_ID = "note_id"

        fun intent(context: Context, id: String): Intent = Intent(context, NoteReminderReceiver::class.java)
            .setData(Uri.Builder().scheme("planner").authority("note-reminder").appendPath(id).build())

        /** Planner opened on this note. Each note has its own data, so each notification keeps its own note. */
        fun openIntent(context: Context, id: String): Intent = Intent(context, MainActivity::class.java).setAction(OPEN_NOTE)
            .setData(Uri.Builder().scheme("planner").authority("note").appendPath(id).build())
            .putExtra(EXTRA_NOTE_ID, id).addFlags(OPEN_PLANNER_FLAGS)
    }
}

/**
 * A note reminder's notification: [note]'s name and first lines, or with no note (before the first unlock, see
 * NoteReminderReceiver) just "Note reminder". On the lock screen it is "Note reminder" either way (R-2): posted while the phone
 * is locked it leaves the words out of the notification itself too, and is shown again with them after the unlock
 * (NoteWords). [quiet]: shown again with the note's words, without sounding a second time.
 */
internal fun postNoteReminder(context: Context, id: String, trigger: Long, note: PlannerNote?, quiet: Boolean = false, couldNotRing: Boolean = false, missed: Boolean = false) {
    if (!notificationsEnabled(context)) return
    val open = PendingIntent.getActivity(context, 0, NoteReminderReceiver.openIntent(context, id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    // D6-3: posted while locked (or with the screen off), the note's words wait for the unlock (NoteWords).
    val tag = "note:$id"
    val shown = note?.takeIf { !NoteWords.hide(context) }
    if (note != null && shown == null) NoteWords.later(context, tag) { postNoteReminder(context, id, trigger, note, quiet = true, couldNotRing = couldNotRing, missed = missed) }
    else NoteWords.shown(tag)
    val label = shown?.let(Notes::label) ?: "Note reminder"
    // The text after the name, as the note's card shows it.
    val lines = shown?.let { n -> Markdown.plain(n.content).lines().filter { it.isNotBlank() } }.orEmpty()
    val body = (if (shown == null || shown.title.isBlank()) lines.drop(1) else lines).take(6).joinToString("\n")
    val notification = NotificationCompat.Builder(context, REMINDER_CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(label)
        .setContentText(body.lineSequence().firstOrNull()?.takeIf { it.isNotBlank() } ?: "Note reminder")
        .apply { if (body.isNotBlank()) setStyle(NotificationCompat.BigTextStyle().bigText(body)) }
        .setSubText(when { couldNotRing -> "Couldn’t ring. Check alarm and notification permissions."; missed -> "Missed alarm"; else -> "Note reminder" })
        .setCategory(NotificationCompat.CATEGORY_REMINDER)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setContentIntent(open).setAutoCancel(true)
        // On the lock screen just "Note reminder": a note's text can be private (task reminders show
        // only their title).
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        .setPublicVersion(NotificationCompat.Builder(context, REMINDER_CHANNEL_ID).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Note reminder").setCategory(NotificationCompat.CATEGORY_REMINDER).build())
        .setOnlyAlertOnce(quiet)
        .addDataAction(context, "Done", NoteActionReceiver.done(context, id, trigger))
        .addAction(0, "Snooze", SnoozeActivity.noteAction(context, id, trigger)).build()
    try { NotificationManagerCompat.from(context).notify(tag, 0, notification) }
    catch (_: SecurityException) { /* Permission can be revoked after notificationsEnabled was checked. */ }
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
