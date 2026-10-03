package com.example.itinerary.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import com.example.itinerary.data.ItineraryItem
import com.example.itinerary.data.Reminder
import com.example.itinerary.data.activeReminderAt

interface ReminderAlarms {
    fun schedule(item: ItineraryItem, reminder: Reminder)
    fun reconcile(item: ItineraryItem, reminder: Reminder) = schedule(item, reminder)
    fun scheduleTask(task: com.example.itinerary.data.PlannerTask) {}
    fun cancelTask(id: String) {}
    fun scheduleNote(note: com.example.itinerary.data.PlannerNote) {}
    fun cancelNote(id: String) {}
    fun cancel(reminderId: Long)
    // The time zone task reminders were last set in, so that after a change they keep their clock time (Repository).
    fun reminderZone(): String? = null
    fun setReminderZone(zone: String) {}
    // At most AlarmWindow.LIMIT reminders are armed at once: the ones after [armHorizon] wait (null: all are armed).
    fun armHorizon(): Long? = null
    fun setArmHorizon(horizon: Long?) {}
    fun armedCount(): Int = 0
    // A task reminder or snooze has rung at [trigger] (DeliveredAlarms): it isn't set again for that time.
    fun markDelivered(key: String, trigger: Long) {}
    // After a batch of alarm changes: the snapshot of them for a locked reboot is written (RB-3, LockedAlarm).
    fun saveLockedAlarms() {}
}

class ReminderScheduler(private val context: Context) : ReminderAlarms {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)
    val ledger = AlarmLedger(context)
    private val delivered = DeliveredAlarms(context)
    // RB-3: every alarm set here, with what it shows, for a reboot that stays locked (LockedAlarm). Kept beside the ledger.
    private val locked = LockedAlarmMirror(read = { DirectBoot.store(context).read() }, write = { DirectBoot.store(context).write(it) },
        timeFormat = { (context.applicationContext as? com.example.itinerary.ItineraryApp)?.settings?.timeFormat?.value?.name })
    override fun saveLockedAlarms() = locked.save(System.currentTimeMillis())
    private fun armed(alarm: LockedAlarm) { ledger.set(alarm.key, alarm.trigger); locked.put(alarm) }
    private fun disarmed(key: String) { ledger.remove(key); locked.remove(key) }
    override fun markDelivered(key: String, trigger: Long) = delivered.record(key, trigger)
    // Backed up with the database on purpose: tasks restored on another phone were set in this zone.
    private val zonePrefs = context.getSharedPreferences("reminder_zone", Context.MODE_PRIVATE)

    override fun reminderZone(): String? = zonePrefs.getString("zone", null)
    override fun setReminderZone(zone: String) { if (reminderZone() != zone) zonePrefs.edit().putString("zone", zone).commit() }

    private val windowPrefs = context.getSharedPreferences("alarm_window", Context.MODE_PRIVATE)
    override fun armHorizon(): Long? = if (windowPrefs.contains("horizon")) windowPrefs.getLong("horizon", 0L) else null
    override fun setArmHorizon(horizon: Long?) {
        if (horizon != armHorizon()) windowPrefs.edit().apply { if (horizon == null) remove("horizon") else putLong("horizon", horizon) }.commit()
    }
    // Every alarm set and not yet seen go off is in the ledger.
    override fun armedCount(): Int = ledger.all().size

    override fun scheduleTask(task: com.example.itinerary.data.PlannerTask) {
        val triggerAt = task.activeReminderAt
        if (task.done || triggerAt == null) { cancelTask(task.id); return }
        // Android may still have an inexact/idle-delayed alarm queued after its requested time.
        // Opening the app must leave that alarm and any delivered notification alone. Edits that
        // replace/remove its time, completion and deletion explicitly cancel the old reminder.
        if (triggerAt <= System.currentTimeMillis()) return
        // Rang already, and the clock was set back since.
        if (delivered.delivered(MissedReminders.taskKey(task.id), triggerAt)) return
        // Waits until it is among the nearest (AlarmWindow); the notification of an earlier time stays.
        if (!AlarmWindow.arms(triggerAt, armHorizon())) {
            taskPending(task.id, PendingIntent.FLAG_NO_CREATE)?.let { alarmManager.cancel(it); it.cancel() }
            disarmed(MissedReminders.taskKey(task.id))
            return
        }
        // Distinct receiver and URI keep task alarms independent of event ids and hash collisions.
        val intent = TaskReminderReceiver.intent(context, task.id).putExtra("trigger", triggerAt)
        val updated = PendingIntent.getBroadcast(context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        armed(LockedAlarm.Task(task.id, triggerAt, task.title))
        setAlarm(triggerAt, updated)
    }

    override fun cancelTask(id: String) {
        taskPending(id, PendingIntent.FLAG_NO_CREATE)?.let { alarmManager.cancel(it); it.cancel() }
        disarmed(MissedReminders.taskKey(id))
        delivered.forget(MissedReminders.taskKey(id))
        androidx.core.app.NotificationManagerCompat.from(context).cancel("task:$id", 0)
    }

    // A note's reminder, set and cleared as a task's is (a note has no "done": Done clears its reminder).
    override fun scheduleNote(note: com.example.itinerary.data.PlannerNote) {
        val key = MissedReminders.noteKey(note.id)
        val triggerAt = note.reminderAt?.let { note.snoozedUntil ?: it }
        if (triggerAt == null) { cancelNote(note.id); return }
        if (triggerAt <= System.currentTimeMillis()) return
        if (delivered.delivered(key, triggerAt)) return
        if (!AlarmWindow.arms(triggerAt, armHorizon())) {
            notePending(note.id, PendingIntent.FLAG_NO_CREATE)?.let { alarmManager.cancel(it); it.cancel() }
            disarmed(key)
            return
        }
        val intent = NoteReminderReceiver.intent(context, note.id).putExtra("trigger", triggerAt)
        val updated = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        armed(LockedAlarm.Note(note.id, triggerAt))
        setAlarm(triggerAt, updated)
    }

    override fun cancelNote(id: String) {
        notePending(id, PendingIntent.FLAG_NO_CREATE)?.let { alarmManager.cancel(it); it.cancel() }
        disarmed(MissedReminders.noteKey(id))
        delivered.forget(MissedReminders.noteKey(id))
        androidx.core.app.NotificationManagerCompat.from(context).cancel("note:$id", 0)
    }

    private fun notePending(id: String, flags: Int): PendingIntent? = PendingIntent.getBroadcast(
        context, 0, NoteReminderReceiver.intent(context, id), flags or PendingIntent.FLAG_IMMUTABLE)

    // A late alarm already shown as missed (MissedReminders); its notification stays.
    fun disarm(key: String) {
        MissedReminders.eventId(key)?.let { cancelCode(it.toInt()) }
        MissedReminders.taskId(key)?.let { id -> taskPending(id, PendingIntent.FLAG_NO_CREATE)?.let { alarmManager.cancel(it); it.cancel() } }
        MissedReminders.noteId(key)?.let { id -> notePending(id, PendingIntent.FLAG_NO_CREATE)?.let { alarmManager.cancel(it); it.cancel() } }
    }

    private fun taskPending(id: String, flags: Int): PendingIntent? = PendingIntent.getBroadcast(
        context, 0, TaskReminderReceiver.intent(context, id), flags or PendingIntent.FLAG_IMMUTABLE)

    // Exact alarms can be switched off by the user on Android 12+; reminders then arrive a little late.
    fun canScheduleExact(): Boolean = Build.VERSION.SDK_INT < 31 || alarmManager.canScheduleExactAlarms()

    override fun schedule(item: ItineraryItem, reminder: Reminder) = scheduleEvent(item, reminder, preservePending = false)

    override fun reconcile(item: ItineraryItem, reminder: Reminder) = scheduleEvent(item, reminder, preservePending = true)

    private fun scheduleEvent(item: ItineraryItem, reminder: Reminder, preservePending: Boolean) {
        val triggerAt = eventReminderAt(item, reminder)
        if (item.paid || item.skipped) { cancel(reminder.id); return }
        if (triggerAt <= System.currentTimeMillis()) {
            // Reloading the app must not dismiss a delivered reminder or an active ringing alarm.
            // Reloading must also preserve an alarm Android has not delivered yet, including one set before an
            // eastward time-zone change moved this time into the past (Repository.deliverReminder accepts it).
            if (!preservePending) {
                disarmed(MissedReminders.eventKey(reminder.id))
                cancelCode(reminder.id.toInt())
                cancelCode(snoozeCode(reminder.id))
            }
            return
        }
        // A snooze that rang already, and the clock was set back since.
        if (delivered.delivered(MissedReminders.eventKey(reminder.id), triggerAt)) return
        cancelCode(snoozeCode(reminder.id))
        if (!AlarmWindow.arms(triggerAt, armHorizon())) {
            disarmed(MissedReminders.eventKey(reminder.id))
            cancelCode(reminder.id.toInt())
            return
        }

        // Everything the notification shows travels in the intent; each save reschedules with fresh values.
        val shown = LockedAlarm.Event.of(item, reminder, triggerAt)
        val intent = eventIntent(context, shown).putExtra(EXTRA_TRIGGER, triggerAt)
        val pending = PendingIntent.getBroadcast(
            context,
            reminder.id.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        armed(shown)
        setAlarm(triggerAt, pending)
    }

    // Fires the same alarm again after [minutes]. It has its own request code, so editing or
    // removing the reminder in the meantime doesn't tangle with it.
    fun snooze(extras: Bundle, minutes: Long) {
        val reminderId = extras.getLong(EXTRA_REMINDER_ID)
        val intent = Intent(context, ReminderReceiver::class.java).putExtras(extras)
        val pending = PendingIntent.getBroadcast(
            context,
            snoozeCode(reminderId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        setAlarm(System.currentTimeMillis() + minutes * 60_000L, pending)
    }

    override fun cancel(reminderId: Long) {
        disarmed(MissedReminders.eventKey(reminderId))
        delivered.forget(MissedReminders.eventKey(reminderId))
        cancelCode(reminderId.toInt())
        cancelCode(snoozeCode(reminderId))
        androidx.core.app.NotificationManagerCompat.from(context).cancel(reminderId.toInt())
        AlarmService.stopIfRinging(context, reminderId)
    }

    private fun setAlarm(triggerAt: Long, pending: PendingIntent) = setReminderAlarm(alarmManager, triggerAt, pending)

    private fun cancelCode(requestCode: Int) {
        // Extras don't take part in matching, so a bare intent finds the alarm we scheduled.
        val pending = PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, ReminderReceiver::class.java),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        ) ?: return
        alarmManager.cancel(pending)
        pending.cancel()
    }

    // Reminder ids start at 1, so their negatives never collide with a reminder's own request code.
    private fun snoozeCode(reminderId: Long): Int = -reminderId.toInt()

    companion object {
        const val EXTRA_BILL = "bill_task"
        const val EXTRA_BILL_TOKEN = "bill_token"
        const val EXTRA_TRIGGER = "trigger_at"
        const val EXTRA_SNOOZE_TOKEN = "snooze_token"
        const val EXTRA_REMINDER_ID = "reminder_id"
        const val EXTRA_TITLE = "title"
        const val EXTRA_LOCATION = "location"
        const val EXTRA_DATE = "date"
        const val EXTRA_TIME = "time"
        const val EXTRA_OFFSET_LABEL = "offset_label"
        const val EXTRA_RING = "ring"
    }
}

fun reminderIntent(context: Context, item: ItineraryItem, reminder: Reminder): Intent =
    eventIntent(context, LockedAlarm.Event.of(item, reminder, 0L))

// What an event reminder's notification shows, in its alarm's intent: from the database, or from the locked snapshot.
internal fun eventIntent(context: Context, e: LockedAlarm.Event): Intent =
    Intent(context, ReminderReceiver::class.java)
        .putExtra(ReminderScheduler.EXTRA_REMINDER_ID, e.reminderId)
        .putExtra(ReminderScheduler.EXTRA_SNOOZE_TOKEN, e.snoozeToken)
        .putExtra(ReminderScheduler.EXTRA_TITLE, e.title)
        .putExtra(ReminderScheduler.EXTRA_BILL, e.bill)
        .putExtra(ReminderScheduler.EXTRA_LOCATION, e.location)
        .putExtra(ReminderScheduler.EXTRA_DATE, e.date)
        .putExtra(ReminderScheduler.EXTRA_TIME, e.time)
        .putExtra(ReminderScheduler.EXTRA_OFFSET_LABEL, e.offsetLabel)
        .putExtra(ReminderScheduler.EXTRA_RING, e.ring)
        .putExtra(ReminderScheduler.EXTRA_BILL_TOKEN, e.billToken)

// Exact when Android allows it; otherwise a little late (see ReminderScheduler.canScheduleExact).
internal fun setReminderAlarm(alarmManager: AlarmManager, triggerAt: Long, pending: PendingIntent) {
    try {
        if (Build.VERSION.SDK_INT < 31 || alarmManager.canScheduleExactAlarms()) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        }
    } catch (e: SecurityException) {
        // Permission was revoked between the check and the call.
        try { alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending) }
        catch (e: IllegalStateException) { tooManyAlarms(e) }
    } catch (e: IllegalStateException) { tooManyAlarms(e) }
}

// Over Android's 500 alarms (AlarmWindow keeps Planner well under it). It stays in the ledger, so the next
// rescheduling sets it, or the app shows it as missed if that comes too late.
private fun tooManyAlarms(e: IllegalStateException) {
    android.util.Log.w("ReminderScheduler", "Android refused another alarm", e)
}

