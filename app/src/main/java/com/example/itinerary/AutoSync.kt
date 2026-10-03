package com.example.itinerary

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.SystemClock
import com.example.itinerary.data.CalendarSync
import com.example.itinerary.data.NoteSync
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull

// "Sync changes automatically" (Settings → Calendars, on by default). While Planner is on screen, Nextcloud is checked
// for changes (events, tasks, notes) at once, then every minute and whenever the connection comes back; Planner's own
// changes are sent a few seconds after they're made (ItineraryApp). Off: only Sync now and the background schedule.
// A check that finds nothing costs one small request per kind (calendars' ctag, the notes list's ETag).
class AutoSync(private val calendars: CalendarSync, private val notes: NoteSync, private val scope: CoroutineScope) {
    private val checking = Mutex()

    // One check, in [scope], so leaving the screen never cuts a write to Nextcloud off half way. One at a time.
    suspend fun check() {
        scope.launch {
            if (!checking.tryLock()) return@launch
            try {
                try { calendars.check() } catch (e: CancellationException) { throw e } catch (_: Exception) {}
                try { notes.check() } catch (e: CancellationException) { throw e } catch (_: Exception) {}
            } finally { checking.unlock() }
        }.join()
    }

    // Checks until cancelled (Planner leaves the screen, or the switch goes off).
    suspend fun watch(context: Context, intervalMs: Long = INTERVAL_MS) {
        val wake = Channel<Unit>(Channel.CONFLATED)
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { wake.trySend(Unit) }
        }
        // Without it (no permission, say) the minute's check still runs.
        val listening = runCatching { connectivity?.registerDefaultNetworkCallback(callback) != null }.getOrDefault(false)
        try {
            var last = 0L
            while (true) {
                // The callback also fires on registering, with a connection already there: not a second check straight away.
                if (SystemClock.elapsedRealtime() - last >= MIN_GAP_MS) { check(); last = SystemClock.elapsedRealtime() }
                withTimeoutOrNull(intervalMs) { wake.receive() }
            }
        } finally {
            if (listening) runCatching { connectivity?.unregisterNetworkCallback(callback) }
        }
    }

    companion object {
        const val INTERVAL_MS = 60_000L
        const val MIN_GAP_MS = 5_000L
    }
}
