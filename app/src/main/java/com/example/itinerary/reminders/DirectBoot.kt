package com.example.itinerary.reminders

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.UserManager
import android.util.AtomicFile
import androidx.core.app.NotificationCompat
import com.example.itinerary.data.Repository
import java.io.File

/**
 * RB-3, see LockedAlarm. Before the first unlock after a reboot only device-protected storage can be read; these are the
 * pieces that work then. The two lambdas are seams for tests (they are set back afterwards).
 */
object DirectBoot {
    /** Whether the phone has been unlocked since it started, so the database and the app's settings can be read. */
    @Volatile internal var unlocked: (Context) -> Boolean = { it.getSystemService(UserManager::class.java)?.isUserUnlocked != false }
    private var shared: LockedAlarmStore? = null
    // One per process, so its writes don't overlap.
    @Volatile internal var store: (Context) -> LockedAlarmStore = { context ->
        synchronized(this) { shared ?: LockedAlarmStore(context.applicationContext ?: context).also { shared = it } }
    }
    @Volatile internal var alarms: (Context) -> LockedAlarmSetter = { LockedAlarmManager(it) }

    fun isUnlocked(context: Context): Boolean = unlocked(context)

    /**
     * D6-4: after a reminder rang, unlocked. One alarm fewer, so one waiting for a free alarm gets it (AlarmWindow); and
     * once the snapshot is [LockedAlarmSelection.REFRESH_MS] old every alarm is set again (as opening Planner does) and
     * the snapshot written from them, so it keeps the next two weeks however long Planner goes unopened.
     */
    suspend fun afterRing(app: com.example.itinerary.ItineraryApp, now: Long = System.currentTimeMillis()) {
        fun stale(at: Long) = runCatching { LockedAlarmSelection.stale(store(app).writtenAt(), at) }.getOrDefault(false)
        if (!stale(now)) {
            app.repository.refillReminders()
            deliverDeferredReminders(app, now)
            return
        }
        app.repository.rescheduleAllReminders()
        // Nothing had changed, so that wrote nothing: written now, so the next ring doesn't do it all again.
        if (stale(System.currentTimeMillis())) app.reminderScheduler.saveLockedAlarms(force = true)
        deliverDeferredReminders(app, now)
    }

    /** At LOCKED_BOOT_COMPLETED: the snapshot's alarms still ahead are set. The number set. */
    fun armFromSnapshot(context: Context, now: Long = System.currentTimeMillis()): Int {
        val snapshot = store(context).read() ?: return 0
        val setter = alarms(context)
        val due = LockedAlarmSelection.select(snapshot.alarms, now)
        due.forEach { alarm -> runCatching { setter.set(alarm) }.onFailure { android.util.Log.w("DirectBoot", "Couldn't set ${alarm.key}", it) } }
        return due.size
    }

    /** A receiver ran an alarm while locked: noted, for [replayFired] once the phone is unlocked. */
    fun fired(context: Context, key: String, trigger: Long, now: Long = System.currentTimeMillis()) {
        runCatching { store(context).recordFired(LockedFired(key, trigger, now)) }
            .onFailure { android.util.Log.w("DirectBoot", "Couldn't note $key", it) }
    }

    /**
     * Once unlocked, before missed reminders are looked for: the alarms that rang while locked are taken off the ledger
     * (they aren't missed) and recorded as delivered, as ringing unlocked would have done. A note reminder shown then
     * said only "Note reminder"; if it is still there it is shown again with the note's words ([showNote]). D6-10: on
     * Android 11 and lower a task's Done and a bill's Mark paid were left out while locked (addDataAction), so with
     * [restoreActions] a task or event notification still there is shown again with its buttons ([showTask], [showEvent]).
     */
    internal suspend fun replayFired(context: Context, repository: Repository, ledger: AlarmLedger,
                                     restoreActions: Boolean = Build.VERSION.SDK_INT < 31,
                                     showTask: (com.example.itinerary.data.PlannerTask, Long) -> Unit = { _, _ -> },
                                     showEvent: (com.example.itinerary.data.ItineraryItem, com.example.itinerary.data.Reminder) -> Unit = { _, _ -> },
                                     showNote: (com.example.itinerary.data.PlannerNote, Long) -> Unit) {
        val store = store(context)
        val fired = store.fired()
        if (fired.isEmpty()) return
        fired.forEach { f ->
            try {
                ledger.fired(f.key, f.at)
                MissedReminders.eventId(f.key)?.let { id -> repository.deliverReminder(id, f.trigger) { item, reminder ->
                    if (restoreActions && notificationShown(context, null, id.toInt())) showEvent(item, reminder)
                } }
                MissedReminders.taskId(f.key)?.let { id -> repository.deliverTaskReminder(id, f.trigger) { task ->
                    if (restoreActions && notificationShown(context, "task:$id")) showTask(task, f.trigger)
                } }
                MissedReminders.noteId(f.key)?.let { id ->
                    repository.deliverNoteReminder(id, f.trigger) { note -> if (notificationShown(context, "note:$id")) showNote(note, f.trigger) }
                }
            } catch (e: Exception) { android.util.Log.w("DirectBoot", "Couldn't record ${f.key}", e) }
        }
        store.clearFired()
    }

}

/**
 * The snapshot and the alarms rung while locked, in device-protected storage (out of backups: they belong to this phone,
 * as the AlarmLedger does). Each is written whole through an AtomicFile, so a crash leaves the old or the new one.
 */
class LockedAlarmStore(dir: File) {
    constructor(context: Context) : this(File(context.createDeviceProtectedStorageContext().noBackupFilesDir, "locked-alarms"))

    private val snapshotPath = File(dir, "snapshot")
    private val snapshotFile = AtomicFile(snapshotPath)
    private val firedFile = AtomicFile(File(dir, "fired"))
    private val folder = dir

    @Synchronized fun read(): LockedSnapshot? = text(snapshotFile)?.let(LockedAlarmCodec::decode)
    @Synchronized fun write(snapshot: LockedSnapshot) = put(snapshotFile, LockedAlarmCodec.encode(snapshot))
    /** When the snapshot was last written; null when there is none. */
    @Synchronized fun writtenAt(): Long? = snapshotPath.lastModified().takeIf { it > 0L }

    @Synchronized fun fired(): List<LockedFired> = text(firedFile)?.let(LockedAlarmCodec::decodeFired).orEmpty()
    @Synchronized fun recordFired(fired: LockedFired) = put(firedFile, LockedAlarmCodec.encodeFired(fired() + fired))
    @Synchronized fun clearFired() = firedFile.delete()

    // None yet (or unreadable): null.
    private fun text(file: AtomicFile): String? = runCatching { file.readFully().toString(Charsets.UTF_8) }.getOrNull()

    private fun put(file: AtomicFile, text: String) {
        folder.mkdirs()
        val out = file.startWrite()
        try { out.write(text.toByteArray(Charsets.UTF_8)); file.finishWrite(out) }
        catch (e: Exception) { file.failWrite(out); throw e }
    }
}

/** Whether Planner's notification [tag]/[id] is still shown. */
internal fun notificationShown(context: Context, tag: String?, id: Int = 0): Boolean = runCatching {
    context.getSystemService(NotificationManager::class.java).activeNotifications.any { it.tag == tag && it.id == id }
}.getOrDefault(false)

/** Sets one alarm from the snapshot. Faked in tests. */
interface LockedAlarmSetter {
    fun set(alarm: LockedAlarm)
}

/**
 * The same PendingIntents ReminderScheduler makes (receiver, request code, data), so its full reschedule after the unlock
 * replaces these instead of ringing twice. The extras carry what the locked receivers show.
 */
class LockedAlarmManager(private val context: Context) : LockedAlarmSetter {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    override fun set(alarm: LockedAlarm) {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pending = when (alarm) {
            is LockedAlarm.Event -> PendingIntent.getBroadcast(context, alarm.reminderId.toInt(),
                eventIntent(context, alarm), flags)
            is LockedAlarm.Task -> PendingIntent.getBroadcast(context, 0, TaskReminderReceiver.intent(context, alarm.id)
                .putExtra("trigger", alarm.trigger).putExtra(TaskReminderReceiver.EXTRA_LOCKED_TITLE, alarm.title).putExtra(ReminderScheduler.EXTRA_RING, alarm.ring), flags)
            is LockedAlarm.Note -> PendingIntent.getBroadcast(context, 0, NoteReminderReceiver.intent(context, alarm.id)
                .putExtra("trigger", alarm.trigger).putExtra(ReminderScheduler.EXTRA_RING, alarm.ring), flags)
        }
        setReminderAlarm(alarmManager, alarm.trigger, pending)
    }
}

/**
 * A notification button whose receiver reads the database (Done, Mark paid). Before the first unlock that receiver can't
 * run (it isn't directBootAware, having nothing to work with): Android 12 and later asks for the unlock first, then sends
 * it; older Android would drop the tap, so there the button is left out. Unlocked, a plain button as always.
 */
internal fun NotificationCompat.Builder.addDataAction(context: Context, title: String, intent: PendingIntent): NotificationCompat.Builder = when {
    DirectBoot.isUnlocked(context) -> addAction(0, title, intent)
    Build.VERSION.SDK_INT >= 31 -> addAction(NotificationCompat.Action.Builder(0, title, intent).setAuthenticationRequired(true).build())
    else -> this
}

/** The time format for a reminder's text: the setting, or before the first unlock the one the snapshot was written with. */
internal fun reminderTimeFormat(context: Context): com.example.itinerary.data.TimeFormat =
    if (DirectBoot.isUnlocked(context)) (context.applicationContext as com.example.itinerary.ItineraryApp).settings.timeFormat.value
    else runCatching { DirectBoot.store(context).read()?.timeFormat?.let { com.example.itinerary.data.TimeFormat.valueOf(it) } }.getOrNull()
        ?: com.example.itinerary.data.TimeFormat.SYSTEM
