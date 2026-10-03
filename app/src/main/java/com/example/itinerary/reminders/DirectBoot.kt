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
     * said only "Note reminder"; if it is still there it is shown again with the note's words ([showNote]).
     */
    internal suspend fun replayFired(context: Context, repository: Repository, ledger: AlarmLedger,
                                     showNote: (com.example.itinerary.data.PlannerNote, Long) -> Unit) {
        val store = store(context)
        val fired = store.fired()
        if (fired.isEmpty()) return
        fired.forEach { f ->
            try {
                ledger.fired(f.key, f.at)
                MissedReminders.eventId(f.key)?.let { repository.deliverReminder(it, f.trigger) { _, _ -> } }
                MissedReminders.taskId(f.key)?.let { repository.deliverTaskReminder(it, f.trigger) {} }
                MissedReminders.noteId(f.key)?.let { id ->
                    repository.deliverNoteReminder(id, f.trigger) { note -> if (stillShown(context, "note:$id")) showNote(note, f.trigger) }
                }
            } catch (e: Exception) { android.util.Log.w("DirectBoot", "Couldn't record ${f.key}", e) }
        }
        store.clearFired()
    }

    private fun stillShown(context: Context, tag: String): Boolean = runCatching {
        context.getSystemService(NotificationManager::class.java).activeNotifications.any { it.tag == tag }
    }.getOrDefault(false)
}

/**
 * The snapshot and the alarms rung while locked, in device-protected storage (out of backups: they belong to this phone,
 * as the AlarmLedger does). Each is written whole through an AtomicFile, so a crash leaves the old or the new one.
 */
class LockedAlarmStore(dir: File) {
    constructor(context: Context) : this(File(context.createDeviceProtectedStorageContext().noBackupFilesDir, "locked-alarms"))

    private val snapshotFile = AtomicFile(File(dir, "snapshot"))
    private val firedFile = AtomicFile(File(dir, "fired"))
    private val folder = dir

    @Synchronized fun read(): LockedSnapshot? = text(snapshotFile)?.let(LockedAlarmCodec::decode)
    @Synchronized fun write(snapshot: LockedSnapshot) = put(snapshotFile, LockedAlarmCodec.encode(snapshot))

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
                eventIntent(context, alarm).putExtra(ReminderScheduler.EXTRA_TRIGGER, alarm.trigger), flags)
            is LockedAlarm.Task -> PendingIntent.getBroadcast(context, 0, TaskReminderReceiver.intent(context, alarm.id)
                .putExtra("trigger", alarm.trigger).putExtra(TaskReminderReceiver.EXTRA_LOCKED_TITLE, alarm.title), flags)
            is LockedAlarm.Note -> PendingIntent.getBroadcast(context, 0, NoteReminderReceiver.intent(context, alarm.id)
                .putExtra("trigger", alarm.trigger), flags)
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
