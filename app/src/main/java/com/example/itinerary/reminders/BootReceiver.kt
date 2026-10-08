package com.example.itinerary.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.itinerary.ItineraryApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

// Alarms are cleared by a reboot and by app updates, so put every future reminder back, and after a reboot show the missed ones.
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_LOCKED_BOOT_COMPLETED, Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
                Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_TIME_CHANGED, ACTION_EXACT_ALARMS_ALLOWED, ACTION_REFRESH_LOCKED)) return
        // RB-3: directBootAware, so this also runs before the first unlock, when the database can't be read. Then the
        // nearest alarms are set from the locked snapshot; BOOT_COMPLETED, which Android sends once the phone is
        // unlocked, sets them all from the database (the same request codes: each replaces its snapshot one). A time
        // change while locked leaves them: they are set for fixed instants, and the unlock puts the times right.
        if (!DirectBoot.isUnlocked(context)) {
            if (intent.action == Intent.ACTION_LOCKED_BOOT_COMPLETED) DirectBoot.armFromSnapshot(context)
            return
        }
        // Unlocked already (no screen lock, say): BOOT_COMPLETED follows and does it all.
        if (intent.action == Intent.ACTION_LOCKED_BOOT_COMPLETED) return
        val app = context.applicationContext as ItineraryApp
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Hunt 25 D3: the whole of it within the seconds Android gives (about 10 s): each part waits for a change in
                // progress (a restore, a sync) only until [LOCK_WAIT_MS] from here, and what can't start by then is handed
                // to WorkManager, which isn't timed (RescheduleRemindersWorker, with what it was to do).
                val deadline = System.currentTimeMillis() + LOCK_WAIT_MS
                fun left() = (deadline - System.currentTimeMillis()).coerceAtLeast(0L)
                // R6-3: the snapshot is old enough by now (D6-4's half a day), so every alarm is set again and it is written.
                if (intent.action == ACTION_REFRESH_LOCKED) {
                    if (app.repository.freeWithin(left())) DirectBoot.afterRing(app) else RescheduleRemindersWorker.enqueue(context, RescheduleRemindersWorker.Job.AFTER_RING)
                    return@launch
                }
                // Only a reboot leaves due alarms unseen for long: a time change fires past ones late, an update takes seconds.
                if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
                    if (!app.repository.freeWithin(left())) { RescheduleRemindersWorker.enqueue(context, RescheduleRemindersWorker.Job.AFTER_BOOT); return@launch }
                    showMissedReminders(context, afterBoot = true)
                }
                // Hunt 24 D5: a time or time zone change gives this receiver about 10 s, and a restore or sync may hold the
                // repository for longer: past a few seconds the reschedule is handed to WorkManager, which isn't timed.
                if (!app.repository.tryRescheduleAllReminders(left())) RescheduleRemindersWorker.enqueue(context)
            } catch (e: Exception) {
                // Hunt 23: a failure here (storage full, say) must not crash Planner in the background; the next boot,
                // update or ring tries again.
                android.util.Log.w("BootReceiver", "Couldn't set the reminders again (${intent.action})", e)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        // Planner's own: the locked-boot snapshot is due to be written again (ReminderScheduler).
        const val ACTION_REFRESH_LOCKED = "com.example.itinerary.REFRESH_LOCKED_ALARMS"
        // H17-R3: "Alarms & reminders" turned back on (Android 12/12L; from 13 USE_EXACT_ALARM keeps it on). The alarms set
        // inexact meanwhile are set again exact, as after a time change. AlarmManager's constant, which needs API 31.
        const val ACTION_EXACT_ALARMS_ALLOWED = "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"
        // Hunt 24 D5: well inside the ~10 s, leaving time for the reschedule itself.
        internal const val LOCK_WAIT_MS = 4_000L
    }
}

/**
 * Hunt 24 D5: every reminder's alarm set again (Repository.rescheduleAllReminders), for a BootReceiver that couldn't wait for
 * a change in progress. WorkManager runs it soon, waits as long as it takes, and keeps it across a restart of the process.
 * Hunt 25 D3: with what else the receiver didn't get to ([Job]): after a boot, the missed reminders first; after a ring,
 * DirectBoot.afterRing (which sets them again itself when due).
 */
class RescheduleRemindersWorker(context: Context, params: androidx.work.WorkerParameters) : androidx.work.CoroutineWorker(context, params) {
    enum class Job { RESCHEDULE, AFTER_BOOT, AFTER_RING }

    override suspend fun doWork(): Result {
        val app = applicationContext.applicationContext as ItineraryApp
        return try {
            when (jobOf(inputData.getString(KEY_JOB))) {
                Job.AFTER_RING -> DirectBoot.afterRing(app)
                // Its own catches: a failure there still lets the alarms be set again.
                Job.AFTER_BOOT -> { showMissedReminders(app, afterBoot = true); app.repository.rescheduleAllReminders() }
                Job.RESCHEDULE -> app.repository.rescheduleAllReminders()
            }
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("RescheduleReminders", "Couldn't set the reminders again", e)
            Result.retry()
        }
    }

    /**
     * Hunt 25 D5: only ever asked for on Android 11 and lower, where expedited work runs as a foreground service. [enqueue]
     * doesn't expedite there, so this is a safety net: WorkManager throws without it (CoroutineWorker's own).
     */
    override suspend fun getForegroundInfo(): androidx.work.ForegroundInfo = androidx.work.ForegroundInfo(FOREGROUND_ID,
        androidx.core.app.NotificationCompat.Builder(applicationContext, REMINDER_CHANNEL_ID).setSmallIcon(com.example.itinerary.R.drawable.ic_notification)
            .setContentTitle("Setting reminders").setSilent(true).setPriority(androidx.core.app.NotificationCompat.PRIORITY_MIN).build())

    companion object {
        private const val WORK = "reschedule-reminders"
        private const val KEY_JOB = "job"
        // Not a reminder's (ids start at 1 and stay far below this) nor AlarmService's.
        private const val FOREGROUND_ID = 0x5245_5343
        internal fun jobOf(raw: String?): Job = Job.entries.firstOrNull { it.name == raw } ?: Job.RESCHEDULE
        // Each job its own unique work, so a later plain reschedule (REPLACE) doesn't drop a boot's missed reminders.
        internal fun workName(job: Job): String = if (job == Job.RESCHEDULE) WORK else "$WORK-${job.name.lowercase()}"

        fun enqueue(context: Context, job: Job = Job.RESCHEDULE) {
            // Hunt 25 D5: and opening Planner sets them all again (RescheduleOnOpen), even with the process still running, so
            // a reschedule WorkManager hasn't got to yet doesn't wait for it.
            com.example.itinerary.data.RescheduleOnOpen.again()
            // Logged, not thrown (WorkManager may not be ready, ItineraryApp): opening Planner sets them again too (RescheduleOnOpen).
            runCatching {
                val request = androidx.work.OneTimeWorkRequestBuilder<RescheduleRemindersWorker>()
                    .setInputData(androidx.work.workDataOf(KEY_JOB to job.name))
                    // Hunt 25 D5: soon, not whenever the phone's idle (alarms are wrong until it runs). Android 12 and later
                    // only, where it needs no foreground service; out of quota, it runs as ordinary work.
                    .apply { if (android.os.Build.VERSION.SDK_INT >= 31) setExpedited(androidx.work.OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST) }
                    .build()
                androidx.work.WorkManager.getInstance(context).enqueueUniqueWork(workName(job), androidx.work.ExistingWorkPolicy.REPLACE, request)
            }.onFailure { android.util.Log.w("RescheduleReminders", "Couldn't hand the reschedule on", it) }
        }
    }
}
