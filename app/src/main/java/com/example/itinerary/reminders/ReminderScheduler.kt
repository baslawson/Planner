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
    // D14-1: only "Ring until I stop it" was turned off: a ringing (or starting) alarm goes quiet, the reminder itself stays.
    fun ringOffTask(id: String) {}
    fun ringOffNote(id: String) {}
    fun cancel(reminderId: Long)
    // The time zone task reminders were last set in, so that after a change they keep their clock time (Repository).
    fun reminderZone(): String? = null
    fun setReminderZone(zone: String) {}
    // At most AlarmWindow.LIMIT reminders are armed at once: the ones after [armHorizon] wait (null: all are armed).
    fun armHorizon(): Long? = null
    fun setArmHorizon(horizon: Long?) {}
    fun setArmWindow(triggers: Map<String, Long>, now: Long) { setArmHorizon(AlarmWindow.horizon(triggers.values, now)) }
    fun armedCount(): Int = 0
    fun needsArmRefill(): Boolean = AlarmWindow.needsRefill(armedCount(), armHorizon())
    fun deferredReminders(): Map<String, Long> = emptyMap()
    fun forgetDeferred(reminders: Map<String, Long>) {}
    fun wasDelivered(key: String, trigger: Long): Boolean = false
    // A task reminder or snooze has rung at [trigger] (DeliveredAlarms): it isn't set again for that time.
    fun markDelivered(key: String, trigger: Long) {}
    // After a batch of alarm changes: the snapshot of them for a locked reboot is written (RB-3, LockedAlarm).
    fun saveLockedAlarms(force: Boolean = false) {}
    // T16-1: every alarm has just been set again, so the next snapshot is written from all of them.
    fun lockedAlarmsComplete() {}
}

class ReminderScheduler(private val context: Context) : ReminderAlarms {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)
    val ledger = AlarmLedger(context)
    private val delivered = DeliveredAlarms(context)
    // RB-3: every alarm set here, with what it shows, for a reboot that stays locked (LockedAlarm). Kept beside the ledger.
    private val locked = LockedAlarmMirror(read = { DirectBoot.store(context).read() }, write = { DirectBoot.store(context).write(it) },
        timeFormat = { (context.applicationContext as? com.example.itinerary.ItineraryApp)?.settings?.timeFormat?.value?.name })
    override fun lockedAlarmsComplete() = locked.markComplete(System.currentTimeMillis())
    override fun saveLockedAlarms(force: Boolean) {
        locked.save(System.currentTimeMillis(), force)?.let(::setLockedRefresh)
    }

    // R6-3: a snapshot that left later alarms out is written again before its two weeks run out (BootReceiver), even if
    // no reminder rings and Planner isn't opened by then. Inexact (a day to spare), and cleared when nothing was left out.
    private fun setLockedRefresh(at: Long) {
        val intent = Intent(context, BootReceiver::class.java).setAction(BootReceiver.ACTION_REFRESH_LOCKED)
        if (at == Long.MAX_VALUE) {
            PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
                ?.let { alarmManager.cancel(it); it.cancel() }
            return
        }
        val pending = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        try { alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending) }
        catch (e: Exception) { android.util.Log.w("ReminderScheduler", "Couldn't set the locked-boot refresh", e) }
    }
    private fun armed(alarm: LockedAlarm) {
        ledger.set(alarm.key, alarm.trigger)
        removeDeferred(alarm.key)
        locked.put(alarm)
    }
    private fun disarmed(key: String) { ledger.remove(key); locked.remove(key) }
    override fun markDelivered(key: String, trigger: Long) {
        delivered.record(key, trigger)
        forgetDeferred(mapOf(key to trigger))
    }
    override fun wasDelivered(key: String, trigger: Long): Boolean = delivered.delivered(key, trigger)
    // Backed up with the database on purpose: tasks restored on another phone were set in this zone.
    private val zonePrefs = context.getSharedPreferences("reminder_zone", Context.MODE_PRIVATE)

    override fun reminderZone(): String? = zonePrefs.getString("zone", null)
    override fun setReminderZone(zone: String) { if (reminderZone() != zone) zonePrefs.edit().putString("zone", zone).commit() }

    private val windowPrefs = context.getSharedPreferences("alarm_window", Context.MODE_PRIVATE)
    override fun armHorizon(): Long? = if (windowPrefs.contains("horizon")) windowPrefs.getLong("horizon", 0L) else null
    override fun setArmHorizon(horizon: Long?) {
        windowPrefs.edit().apply {
            if (horizon == null) remove("horizon") else putLong("horizon", horizon)
            remove("at_horizon")
        }.commit()
    }
    override fun deferredReminders(): Map<String, Long> = windowPrefs.all.mapNotNull { (key, value) ->
        if (key.startsWith("deferred:") && value is Long) key.removePrefix("deferred:") to value else null
    }.toMap()

    private fun removeDeferred(key: String) {
        if (windowPrefs.contains("deferred:$key")) windowPrefs.edit().remove("deferred:$key").commit()
    }

    override fun forgetDeferred(reminders: Map<String, Long>) {
        val edit = windowPrefs.edit()
        reminders.forEach { (key, trigger) ->
            if (windowPrefs.getLong("deferred:$key", Long.MIN_VALUE) == trigger) edit.remove("deferred:$key")
        }
        edit.commit()
    }

    private fun defer(key: String, trigger: Long) {
        if (windowPrefs.getLong("deferred:$key", Long.MIN_VALUE) == trigger) return
        windowPrefs.edit().putLong("deferred:$key", trigger).putBoolean("rebalance", true).commit()
    }

    override fun setArmWindow(triggers: Map<String, Long>, now: Long) {
        val candidates = triggers.filter { (key, trigger) -> !wasDelivered(key, trigger) }
        val selection = AlarmWindow.select(candidates, now)
        val previous = deferredReminders()
        val waiting = AlarmWindow.deferred(previous, candidates, selection, now)
        windowPrefs.edit().apply {
            previous.keys.forEach { remove("deferred:$it") }
            waiting.forEach { (key, trigger) -> putLong("deferred:$key", trigger) }
            if (selection.horizon == null) remove("horizon") else putLong("horizon", selection.horizon)
            putStringSet("at_horizon", selection.atHorizon)
            remove("rebalance")
        }.commit()
        // Free excluded future slots before setting selected alarms, including ties whose file order differs.
        ledger.all().filter { (key, trigger) -> trigger > now && !selection.arms(key, trigger) }.keys.forEach { key ->
            disarm(key)
            disarmed(key)
        }
    }

    override fun needsArmRefill(): Boolean = windowPrefs.getBoolean("rebalance", false) ||
        AlarmWindow.needsRefill(armedCount(), armHorizon())

    private fun mayArm(key: String, trigger: Long): Boolean {
        val horizon = armHorizon()
        // Old versions stored just the horizon; the first full reschedule upgrades it to bounded identities.
        val atHorizon = windowPrefs.getStringSet("at_horizon", null)
        val selected = if (atHorizon == null) AlarmWindow.arms(trigger, horizon)
            else AlarmWindow.Selection(horizon, atHorizon).arms(key, trigger)
        if (!selected) { defer(key, trigger); return false }
        // A large import schedules its changed rows before the batch refills the window. Bound that phase too.
        val armed = ledger.all()
        if (key !in armed && armed.size >= AlarmWindow.LIMIT) {
            defer(key, trigger)
            return false
        }
        return true
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
        if (!mayArm(MissedReminders.taskKey(task.id), triggerAt)) {
            taskPending(task.id, PendingIntent.FLAG_NO_CREATE)?.let { alarmManager.cancel(it); it.cancel() }
            disarmed(MissedReminders.taskKey(task.id))
            return
        }
        // Distinct receiver and URI keep task alarms independent of event ids and hash collisions.
        val intent = TaskReminderReceiver.intent(context, task.id).putExtra("trigger", triggerAt).putExtra(EXTRA_RING, task.ringUntilDismissed)
        val updated = PendingIntent.getBroadcast(context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        armed(LockedAlarm.Task(task.id, triggerAt, task.title, task.ringUntilDismissed))
        setAlarm(triggerAt, updated)
    }

    override fun cancelTask(id: String) {
        AlarmService.stopIfRinging(context, "task", id)
        removeDeferred(MissedReminders.taskKey(id))
        taskPending(id, PendingIntent.FLAG_NO_CREATE)?.let { alarmManager.cancel(it); it.cancel() }
        disarmed(MissedReminders.taskKey(id))
        delivered.forget(MissedReminders.taskKey(id))
        androidx.core.app.NotificationManagerCompat.from(context).cancel("task:$id", 0)
    }

    override fun ringOffTask(id: String) = AlarmService.quietIfRinging(context, "task", id)
    override fun ringOffNote(id: String) = AlarmService.quietIfRinging(context, "note", id)

    // A note's reminder, set and cleared as a task's is (a note has no "done": Done clears its reminder).
    override fun scheduleNote(note: com.example.itinerary.data.PlannerNote) {
        val key = MissedReminders.noteKey(note.id)
        val triggerAt = note.reminderAt?.let { note.snoozedUntil ?: it }
        if (triggerAt == null) { cancelNote(note.id); return }
        if (triggerAt <= System.currentTimeMillis()) return
        if (delivered.delivered(key, triggerAt)) return
        if (!mayArm(key, triggerAt)) {
            notePending(note.id, PendingIntent.FLAG_NO_CREATE)?.let { alarmManager.cancel(it); it.cancel() }
            disarmed(key)
            return
        }
        val intent = NoteReminderReceiver.intent(context, note.id).putExtra("trigger", triggerAt).putExtra(EXTRA_RING, note.ringUntilDismissed)
        val updated = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        armed(LockedAlarm.Note(note.id, triggerAt, note.ringUntilDismissed))
        setAlarm(triggerAt, updated)
    }

    override fun cancelNote(id: String) {
        AlarmService.stopIfRinging(context, "note", id)
        removeDeferred(MissedReminders.noteKey(id))
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
        if (!mayArm(MissedReminders.eventKey(reminder.id), triggerAt)) {
            disarmed(MissedReminders.eventKey(reminder.id))
            cancelCode(reminder.id.toInt())
            return
        }

        // Everything the notification shows travels in the intent; each save reschedules with fresh values.
        val shown = LockedAlarm.Event.of(item, reminder, triggerAt)
        val intent = eventIntent(context, shown)
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
        val at = System.currentTimeMillis() + minutes * 60_000L
        // Its own time, so a ringing snooze that Android restarts rings only for what is left of it (AlarmRestart).
        val intent = Intent(context, ReminderReceiver::class.java).putExtras(extras).putExtra(EXTRA_TRIGGER, at)
        val pending = PendingIntent.getBroadcast(
            context,
            snoozeCode(reminderId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        setAlarm(at, pending)
    }

    override fun cancel(reminderId: Long) {
        removeDeferred(MissedReminders.eventKey(reminderId))
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

// [trigger]: the time its alarm was set for (0: not known), handed on to a ringing alarm (AlarmRestart).
fun reminderIntent(context: Context, item: ItineraryItem, reminder: Reminder, trigger: Long = 0L): Intent =
    eventIntent(context, LockedAlarm.Event.of(item, reminder, trigger))

// What an event reminder's notification shows, in its alarm's intent: from the database, or from the locked snapshot.
// R6-1: with its time, which goes on to AlarmService, so a ringing alarm Android restarts rings only for what is left.
internal fun eventIntent(context: Context, e: LockedAlarm.Event): Intent =
    Intent(context, ReminderReceiver::class.java)
        .putExtra(ReminderScheduler.EXTRA_REMINDER_ID, e.reminderId)
        .putExtra(ReminderScheduler.EXTRA_TRIGGER, e.trigger)
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

