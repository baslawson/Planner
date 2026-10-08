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
                // R6-3: the snapshot is old enough by now (D6-4's half a day), so every alarm is set again and it is written.
                if (intent.action == ACTION_REFRESH_LOCKED) { DirectBoot.afterRing(app); return@launch }
                // Only a reboot leaves due alarms unseen for long: a time change fires past ones late, an update takes seconds.
                if (intent.action == Intent.ACTION_BOOT_COMPLETED) showMissedReminders(context, afterBoot = true)
                // Hunt 24 D5: a time or time zone change gives this receiver about 10 s, and a restore or sync may hold the
                // repository for longer: past a few seconds the reschedule is handed to WorkManager, which isn't timed.
                if (!app.repository.tryRescheduleAllReminders(LOCK_WAIT_MS)) RescheduleRemindersWorker.enqueue(context)
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
 */
class RescheduleRemindersWorker(context: Context, params: androidx.work.WorkerParameters) : androidx.work.CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        return try {
            (applicationContext.applicationContext as ItineraryApp).repository.rescheduleAllReminders()
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("RescheduleReminders", "Couldn't set the reminders again", e)
            Result.retry()
        }
    }

    companion object {
        private const val WORK = "reschedule-reminders"
        fun enqueue(context: Context) {
            // Logged, not thrown (WorkManager may not be ready, ItineraryApp): opening Planner sets them again too (RescheduleOnOpen).
            runCatching {
                androidx.work.WorkManager.getInstance(context).enqueueUniqueWork(WORK, androidx.work.ExistingWorkPolicy.REPLACE,
                    androidx.work.OneTimeWorkRequestBuilder<RescheduleRemindersWorker>().build())
            }.onFailure { android.util.Log.w("RescheduleReminders", "Couldn't hand the reschedule on", it) }
        }
    }
}
