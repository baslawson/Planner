package com.example.itinerary.data

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant

/** Device-local history: restoring a backup must not rewrite the truth about the last export. */
data class BackupStatus(val lastSuccess: String? = null, val destination: String? = null,
    val outcome: String = "NONE", val attemptDestination: String? = null) {
    val failed get() = outcome == "FAILED" || outcome == "INTERRUPTED"
}
class BackupStatusStore(context: Context) {
    private val prefs = context.getSharedPreferences("backup_status", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(BackupStatus(prefs.getString("success", null), prefs.getString("destination", null),
        prefs.getString("outcome", "NONE").let { if (it == "RUNNING") "INTERRUPTED" else it ?: "NONE" }, prefs.getString("attemptDestination", null)))
    val state = mutable.asStateFlow()
    private val reminderMutable = MutableStateFlow(BackupReminder(
        prefs.getBoolean("reminders_enabled", true),
        prefs.getString("reminder_deferred", null)?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }))
    val reminder = reminderMutable.asStateFlow()
    fun setRemindersEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("reminders_enabled", enabled).apply()
        reminderMutable.value = reminderMutable.value.copy(enabled = enabled)
    }
    fun remindNextWeek(today: java.time.LocalDate) {
        val until = today.plusDays(7)
        prefs.edit().putString("reminder_deferred", until.toString()).apply()
        reminderMutable.value = reminderMutable.value.copy(deferredUntil = until)
    }
    private fun save(value: BackupStatus) {
        mutable.value = value
        prefs.edit().putString("success", value.lastSuccess).putString("destination", value.destination)
            .putString("outcome", value.outcome).putString("attemptDestination", value.attemptDestination).apply()
    }
    fun seedLegacy(time: String) {
        if (mutable.value.lastSuccess == null && runCatching { Instant.parse(time) }.isSuccess)
            save(mutable.value.copy(lastSuccess = time, destination = "Nextcloud"))
    }
    suspend fun <T> track(destination: String, action: suspend () -> T): T {
        val before = mutable.value
        save(before.copy(outcome = "RUNNING", attemptDestination = destination))
        return try {
            action().also { save(BackupStatus(Instant.now().toString(), destination, "SUCCESS", destination)) }
        } catch (e: Exception) {
            save(mutable.value.afterFailure(before, e)); throw e
        }
    }
}

/**
 * The status after a backup that didn't finish, from [before] it started. Stopped with Cancel (S6-1): nothing went
 * wrong, so it is as before, not shown as a failed backup (AS-2). Ended with the app (a coroutine cancelled):
 * interrupted. Anything else: failed.
 */
internal fun BackupStatus.afterFailure(before: BackupStatus, error: Exception): BackupStatus = when (error) {
    is TransferCancelledException -> before
    is CancellationException -> copy(outcome = "INTERRUPTED")
    else -> copy(outcome = "FAILED")
}
