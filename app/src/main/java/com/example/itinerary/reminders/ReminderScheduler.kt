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
    fun cancel(reminderId: Long)
    // The time zone task reminders were last set in, so that after a change they keep their clock time (Repository).
    fun reminderZone(): String? = null
    fun setReminderZone(zone: String) {}
    // At most AlarmWindow.LIMIT reminders are armed at once: the ones after [armHorizon] wait (null: all are armed).
    fun armHorizon(): Long? = null
    fun setArmHorizon(horizon: Long?) {}
    fun armedCount(): Int = 0
}

class ReminderScheduler(private val context: Context) : ReminderAlarms {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)
    val ledger = AlarmLedger(context)
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
        // Waits until it is among the nearest (AlarmWindow); the notification of an earlier time stays.
        if (!AlarmWindow.arms(triggerAt, armHorizon())) {
            taskPending(task.id, PendingIntent.FLAG_NO_CREATE)?.let { alarmManager.cancel(it); it.cancel() }
            ledger.remove(MissedReminders.taskKey(task.id))
            return
        }
        // Distinct receiver and URI keep task alarms independent of event ids and hash collisions.
        val intent = TaskReminderReceiver.intent(context, task.id).putExtra("trigger", triggerAt)
        val updated = PendingIntent.getBroadcast(context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        ledger.set(MissedReminders.taskKey(task.id), triggerAt)
        setAlarm(triggerAt, updated)
    }

    override fun cancelTask(id: String) {
        taskPending(id, PendingIntent.FLAG_NO_CREATE)?.let { alarmManager.cancel(it); it.cancel() }
        ledger.remove(MissedReminders.taskKey(id))
        androidx.core.app.NotificationManagerCompat.from(context).cancel("task:$id", 0)
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
                ledger.remove(MissedReminders.eventKey(reminder.id))
                cancelCode(reminder.id.toInt())
                cancelCode(snoozeCode(reminder.id))
            }
            return
        }
        cancelCode(snoozeCode(reminder.id))
        if (!AlarmWindow.arms(triggerAt, armHorizon())) {
            ledger.remove(MissedReminders.eventKey(reminder.id))
            cancelCode(reminder.id.toInt())
            return
        }

        // Everything the notification shows travels in the intent; each save reschedules with fresh values.
        val intent = reminderIntent(context, item, reminder).putExtra(EXTRA_TRIGGER, triggerAt)
        val pending = PendingIntent.getBroadcast(
            context,
            reminder.id.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        ledger.set(MissedReminders.eventKey(reminder.id), triggerAt)
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
        ledger.remove(MissedReminders.eventKey(reminderId))
        cancelCode(reminderId.toInt())
        cancelCode(snoozeCode(reminderId))
        androidx.core.app.NotificationManagerCompat.from(context).cancel(reminderId.toInt())
        AlarmService.stopIfRinging(context, reminderId)
    }

    private fun setAlarm(triggerAt: Long, pending: PendingIntent) {
        try {
            if (canScheduleExact()) {
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
    Intent(context, ReminderReceiver::class.java)
        .putExtra(ReminderScheduler.EXTRA_REMINDER_ID, reminder.id)
        .putExtra(ReminderScheduler.EXTRA_SNOOZE_TOKEN, com.example.itinerary.data.eventReminderToken(item, reminder))
        .putExtra(ReminderScheduler.EXTRA_TITLE, item.title)
        .putExtra(ReminderScheduler.EXTRA_BILL, item.category == "Bills")
        .putExtra(ReminderScheduler.EXTRA_LOCATION, item.location)
        .putExtra(ReminderScheduler.EXTRA_DATE, item.date.toString())
        .putExtra(ReminderScheduler.EXTRA_TIME, item.startTime?.toString() ?: "")
        .putExtra(ReminderScheduler.EXTRA_OFFSET_LABEL, reminder.label)
        .putExtra(ReminderScheduler.EXTRA_RING, reminder.ringUntilDismissed)
        .putExtra(ReminderScheduler.EXTRA_BILL_TOKEN, if (item.category == "Bills" && !item.paid && !item.skipped)
            com.example.itinerary.data.billReminderToken(item, reminder) else null)

