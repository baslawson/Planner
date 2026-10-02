package com.example.itinerary

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.itinerary.data.BackgroundSync
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

// Calendar sync in the background, at the interval chosen in Settings → Calendars (Off by default). Android's
// WorkManager picks the exact moment (later when the battery is low or the phone is idle), needs no permission and
// keeps the schedule across restarts. Each run is the same sync as opening the app.
object CalendarBackground {
    const val WORK = "calendar-sync"

    fun schedule(context: Context, hours: Int) {
        val work = WorkManager.getInstance(context)
        if (hours == BackgroundSync.OFF) { work.cancelUniqueWork(WORK); return }
        work.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<CalendarSyncWorker>(hours.toLong(), TimeUnit.HOURS)
                .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED)).build())
    }
}

class CalendarSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        return try {
            // The worker may be given the app or just a context (test tools do); ask it for the app either way.
            (applicationContext.applicationContext as ItineraryApp).calendarSync.backgroundSync()
            // Notes too, when their sync is on (it does nothing otherwise); its problems show on the Notes page.
            (applicationContext.applicationContext as ItineraryApp).noteSync.sync()
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Sync failures are shown in Settings → Calendars and the next run tries again; anything else is logged so it
            // can't go unnoticed.
            android.util.Log.w("CalendarSyncWorker", "Background calendar sync failed", e)
            Result.success()
        }
    }
}
