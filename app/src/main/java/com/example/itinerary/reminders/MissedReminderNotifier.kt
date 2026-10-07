package com.example.itinerary.reminders

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.itinerary.ItineraryApp
import com.example.itinerary.R
import com.example.itinerary.data.Repository
import com.example.itinerary.data.billReminderToken
import com.example.itinerary.ui.label
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The alarms Planner has set and not yet seen go off (see MissedReminders). Kept outside Android, which forgets them at a reboot. */
class AlarmLedger(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE))

    companion object {
        // Left out of Android's backup and device transfer (res/xml/backup_rules.xml, data_extraction_rules.xml): these
        // alarms were set on this phone only.
        const val PREFS = "pending_alarms"
    }

    fun set(key: String, trigger: Long) { if (prefs.getLong(key, 0L) != trigger) prefs.edit().putLong(key, trigger).apply() }
    fun remove(key: String) { if (prefs.contains(key)) prefs.edit().remove(key).apply() }
    // A snooze's own alarm firing leaves a later, still pending alarm of the same reminder in place.
    fun fired(key: String, now: Long) { if (prefs.getLong(key, Long.MAX_VALUE) <= now) prefs.edit().remove(key).commit() }
    fun all(): Map<String, Long> = prefs.all.mapNotNull { (k, v) -> (v as? Long)?.let { k to it } }.toMap()
    fun keepOnly(remaining: Map<String, Long>) {
        val edit = prefs.edit()
        all().keys.filter { it !in remaining }.forEach(edit::remove)
        edit.commit()
    }
}

/**
 * The task reminders and snoozes that have rung here (key as in MissedReminders → time). Those are fixed times, so after
 * the clock is set back one is ahead again, and rescheduling would ring it twice; an on-time event reminder has its own
 * record (ReminderDeliveries).
 */
class DeliveredAlarms(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences("delivered_alarms", Context.MODE_PRIVATE))

    fun record(key: String, trigger: Long) { if (prefs.getLong(key, 0L) != trigger) prefs.edit().putLong(key, trigger).apply() }
    fun delivered(key: String, trigger: Long): Boolean = prefs.contains(key) && prefs.getLong(key, 0L) == trigger
    fun forget(key: String) { if (prefs.contains(key)) prefs.edit().remove(key).apply() }
}

/**
 * Shows the reminders whose alarms went without ringing, then forgets those alarms: at boot every one no longer ahead,
 * when the app opens every one at least [graceMs] late. A late alarm that is still set is [disarm]ed, so it can't show again.
 */
internal suspend fun handleMissedReminders(repository: Repository, ledger: AlarmLedger, now: Long, graceMs: Long = 0L,
                                           disarm: (String) -> Unit = {}, post: (List<MissedReminders.Missed>) -> Unit) {
    repository.deliverMissedReminders(ledger.all(), now, graceMs) { missed ->
        // Forget them before showing, so a crash can lose a missed note but never repeat it at the next boot.
        val remaining = MissedReminders.remaining(ledger.all(), now, graceMs)
        ledger.all().keys.filter { it !in remaining }.forEach(disarm)
        ledger.keepOnly(remaining)
        post(missed)
    }
}

/** Drain the separately tracked waiting alarms outside Repository's scheduling mutex. */
internal suspend fun drainDeferredReminders(repository: Repository, scheduler: ReminderAlarms, now: Long,
                                             post: (List<MissedReminders.Missed>) -> Unit) {
    val pending = scheduler.deferredReminders()
    if (pending.values.none { it <= now }) return
    repository.deliverMissedReminders(pending, now) { due ->
        // Remove only the captured due version: a later edit to the same reminder must stay waiting.
        scheduler.forgetDeferred(pending.filterValues { it <= now })
        post(due)
    }
}

internal suspend fun deliverDeferredReminders(app: ItineraryApp, now: Long = System.currentTimeMillis()) {
    drainDeferredReminders(app.repository, app.reminderScheduler, now) { due ->
        postMissedReminders(app, due, now, afterBoot = false, waiting = true)
    }
}

/**
 * After a reboot ([afterBoot]), the reminders due while the phone was off. When the app opens, the ones whose alarm
 * Android dropped without ringing: a force stop (some phones do one when Planner is swiped away) or the exact-alarm
 * permission being turned off clears an app's alarms.
 */
suspend fun showMissedReminders(context: Context, afterBoot: Boolean) {
    val app = context.applicationContext as ItineraryApp
    if (!afterBoot) kotlinx.coroutines.delay(MissedReminders.SETTLE_MS)
    // RB-3: the alarms that rang before the first unlock aren't missed.
    try {
        DirectBoot.replayFired(app, app.repository, app.reminderScheduler.ledger,
            showTask = { task, trigger -> postTaskReminder(app, task.id, task.title, trigger, quiet = true, notes = task.notes) },
            showEvent = { item, reminder -> showEventAgain(app, item, reminder) }) { note, trigger -> postNoteReminder(app, note.id, trigger, note, quiet = true) }
    }
    catch (e: Exception) { android.util.Log.w("MissedReminders", "Couldn't record the reminders rung while locked", e) }
    val now = System.currentTimeMillis()
    try {
        deliverDeferredReminders(app, now)
        handleMissedReminders(app.repository, app.reminderScheduler.ledger, now,
            if (afterBoot) 0L else MissedReminders.openGraceMs(app.reminderScheduler.canScheduleExact()),
            app.reminderScheduler::disarm) { postMissedReminders(app, it, now, afterBoot) }
    } catch (e: Exception) { android.util.Log.w("MissedReminders", "Couldn't show missed reminders", e) }
    finally {
        // Draining dropped alarms frees slots even when the same-process full-reschedule gate is closed.
        // Finish that repair if the screen closes after the ledger was drained, and leave a full window alone.
        if (app.reminderScheduler.needsArmRefill()) {
            try { kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { app.repository.refillReminders() } }
            catch (e: Exception) { android.util.Log.w("MissedReminders", "Couldn't refill waiting reminders", e) }
        }
    }
}

// D6-10: an event or bill reminder shown before the first unlock, shown again with its buttons (Mark paid), quietly.
// Its title is kept ("Missed alarm: …" after a ringing one gave up), and the "couldn't ring" note if it had one.
internal fun showEventAgain(context: Context, item: com.example.itinerary.data.ItineraryItem, reminder: com.example.itinerary.data.Reminder) {
    val intent = reminderIntent(context, item, reminder)
    val content = reminderContent(context, intent.extras) ?: return
    val id = reminder.id.toInt()
    val shown = runCatching {
        context.getSystemService(android.app.NotificationManager::class.java).activeNotifications.firstOrNull { it.tag == null && it.id == id }?.notification
    }.getOrNull()
    val title = shown?.extras?.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString() ?: content.title
    val couldNotRing = shown != null && shown.flags and android.app.Notification.FLAG_INSISTENT != 0
    postReminderNotification(context, id, title, content.text, content.subText, reminder.id,
        intent.getStringExtra(ReminderScheduler.EXTRA_BILL_TOKEN), intent.getStringExtra(ReminderScheduler.EXTRA_SNOOZE_TOKEN),
        couldNotRing = couldNotRing, quiet = true, details = content.details)
}

private const val MISSED_GROUP = "planner.missed"

/**
 * Normal (never ringing) notifications, in the reminder's own slot so a later real one replaces it. Several go in one group
 * whose summary alone alerts, and at most [MissedReminders.MAX_SHOWN] are shown; the summary counts the rest.
 */
fun postMissedReminders(context: Context, missed: List<MissedReminders.Missed>, now: Long, afterBoot: Boolean = true,
                        waiting: Boolean = false) {
    if (missed.isEmpty() || !notificationsEnabled(context)) return
    val grouped = missed.size > 1
    val shown = missed.take(MissedReminders.MAX_SHOWN)
    val manager = NotificationManagerCompat.from(context)
    val timeFormat = (context.applicationContext as ItineraryApp).settings.timeFormat.value
    fun dueText(due: Long): String {
        val at = Instant.ofEpochMilli(due).atZone(ZoneId.systemDefault())
        val time = at.toLocalTime().label(timeFormat, context)
        val today = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
        val day = if (at.toLocalDate() == today) "" else at.toLocalDate().format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())) + ", "
        return (if (waiting) "Due " else "Missed · due ") + "$day$time"
    }
    fun open(code: Int) = PendingIntent.getActivity(context, code,
        openPlannerIntent(context),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun builder(title: String, text: String, code: Int) = NotificationCompat.Builder(context, REMINDER_CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notification).setContentTitle(title).setContentText(text)
        .setCategory(NotificationCompat.CATEGORY_REMINDER).setPriority(NotificationCompat.PRIORITY_HIGH)
        .setContentIntent(open(code)).setAutoCancel(true)
        .apply { if (grouped) setGroup(MISSED_GROUP).setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY) }
    // On the lock screen no note words, as the reminder itself (R-2). Posted while locked, not in the notification
    // itself either: it is shown again with them, quietly, after the unlock (D6-3, NoteWords).
    fun missedNote(m: MissedReminders.Note, quiet: Boolean) {
        val tag = "note:${m.note.id}"
        val words = !NoteWords.hide(context)
        if (words) NoteWords.shown(tag) else NoteWords.later(context, tag) { missedNote(m, quiet = true) }
        // R18-A4: also posted again after the unlock (NoteWords.later), outside the try below: checked and caught here too.
        if (!notificationsEnabled(context)) return
        try { manager.notify(tag, 0, builder(if (words) com.example.itinerary.data.Notes.label(m.note) else if (waiting) "Note reminder" else "Missed note reminder", dueText(m.due), 0)
            .setSubText("Note reminder").setWhen(m.due).setShowWhen(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(NotificationCompat.Builder(context, REMINDER_CHANNEL_ID).setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(if (waiting) "Note reminder" else "Missed note reminder").setCategory(NotificationCompat.CATEGORY_REMINDER).build())
            // U-13: opens the note, as the reminder itself does.
            .setContentIntent(PendingIntent.getActivity(context, 0, NoteReminderReceiver.openIntent(context, m.note.id),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .setOnlyAlertOnce(quiet)
            .addAction(0, "Done", NoteActionReceiver.done(context, m.note.id, m.due)).build()) }
        catch (_: SecurityException) { /* Permission can be revoked after notificationsEnabled was checked. */ }
    }
    try {
        shown.forEach { m ->
            when (m) {
                is MissedReminders.Event -> {
                    val id = m.reminder.id.toInt()
                    val details = reminderContent(context, reminderIntent(context, m.item, m.reminder).extras)?.text
                    val bill = m.item.category == "Bills" && !m.item.paid && !m.item.skipped
                    manager.notify(id, builder(m.item.title, dueText(m.due), id).setSubText(m.reminder.label).setWhen(m.due).setShowWhen(true)
                        .apply { if (details != null) setStyle(NotificationCompat.BigTextStyle().bigText("${dueText(m.due)}\n$details")) }
                        .apply { if (bill) addAction(0, "Mark paid", BillPaymentReceiver.action(context, m.reminder.id, billReminderToken(m.item, m.reminder))) }
                        .build())
                }
                is MissedReminders.Task -> manager.notify("task:${m.task.id}", 0, builder(m.task.title, dueText(m.due), 0)
                    .setSubText("Task reminder").setWhen(m.due).setShowWhen(true)
                    .addAction(0, "Done", TaskActionReceiver.done(context, m.task.id, m.due)).build())
                is MissedReminders.Note -> missedNote(m, quiet = false)
            }
        }
        if (grouped) {
            val more = missed.size - shown.size
            val style = NotificationCompat.InboxStyle()
            shown.forEach { style.addLine(it.title()) }
            if (more > 0) style.setSummaryText("+$more more in Planner")
            manager.notify("missed-reminders", 0, NotificationCompat.Builder(context, REMINDER_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification).setContentTitle("${missed.size} ${if (waiting) "reminders due" else "missed reminders"}")
                .setContentText((if (waiting) "Open Planner to see them" else if (afterBoot) "While your phone was off" else "Android stopped their alarms") + if (more > 0) " · $more more in Planner" else "")
                .setStyle(style).setCategory(NotificationCompat.CATEGORY_REMINDER).setPriority(NotificationCompat.PRIORITY_HIGH)
                .setGroup(MISSED_GROUP).setGroupSummary(true).setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
                .setContentIntent(open(0)).setAutoCancel(true).build())
        }
    } catch (_: SecurityException) {
        // Permission can be revoked after notificationsEnabled was checked.
    }
}

private fun MissedReminders.Missed.title() = when (this) {
    // The summary is shown on the lock screen: a note is named only by what it is (its own notification has its words).
    is MissedReminders.Event -> item.title; is MissedReminders.Task -> task.title; is MissedReminders.Note -> "Note reminder"
}
