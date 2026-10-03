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
// A check that finds nothing costs one small request per kind (calendars' ctag, the notes list's ETag). SY-2: work that
// keeps leaving the same thing out of step is repeated less and less often (CalendarSync.check, NoteSync.check).
class AutoSync(private val calendars: CalendarSync, private val notes: NoteSync, private val scope: CoroutineScope) {
    private val checking = Mutex()

    // One check, in [scope], so leaving the screen never cuts a write to Nextcloud off half way. One at a time. [fresh]:
    // the connection came back, so nothing that backed off waits any longer.
    suspend fun check(fresh: Boolean = false) {
        scope.launch {
            if (!checking.tryLock()) return@launch
            try {
                try { calendars.check(fresh) } catch (e: CancellationException) { throw e } catch (_: Exception) {}
                try { notes.check(fresh) } catch (e: CancellationException) { throw e } catch (_: Exception) {}
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
            var fresh = false
            while (true) {
                // The callback also fires on registering, with a connection already there: not a second check straight away.
                if (SystemClock.elapsedRealtime() - last >= MIN_GAP_MS) { check(fresh); fresh = false; last = SystemClock.elapsedRealtime() }
                // A connection back (not the one there on registering, which comes straight after the first check).
                if (withTimeoutOrNull(intervalMs) { wake.receive() } != null && SystemClock.elapsedRealtime() - last >= MIN_GAP_MS) fresh = true
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
