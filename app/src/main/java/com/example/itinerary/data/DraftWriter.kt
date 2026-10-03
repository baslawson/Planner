package com.example.itinerary.data

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * B1: an editor's draft kept on disk without writing (and fsyncing) it on the main thread at every keystroke.
 * [schedule] keeps only the newest draft per key and writes it on one background thread once typing has paused for
 * [delayMs]; [flush] writes whatever is waiting at once, on the caller's thread (an editor leaving, the app going to the
 * background, so Android closing Planner later loses nothing). [now] runs a write or a delete in order with them and
 * drops what was waiting for its key first, so a Discard is never undone by an older draft written after it.
 * [pending] is what a reader should see before it looks at the disk.
 * E5-4: a write that fails stays waiting (readers still see it) and is tried once more by itself after [retryMs]; after
 * that it waits for the next flush (a newer draft's, the app going to the background), and each flush tries it once.
 */
class DraftWriter(
    private val delayMs: Long = 300,
    private val retryMs: Long = 3000,
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "draft-writer").apply { isDaemon = true }
    },
) {
    private class Entry(val value: Any, val onFailure: (Exception) -> Unit, val perform: () -> Unit) {
        var failures = 0 // guarded by the writer's io
    }

    private val pending = LinkedHashMap<String, Entry>() // guarded by this
    private var timer: ScheduledFuture<*>? = null         // guarded by this
    private var retry: ScheduledFuture<*>? = null         // guarded by this
    // Held while the disk is written, so writes, deletes and flushes happen one at a time and in order.
    private val io = Any()

    /** Write [value] (by [perform]) for [key] after the pause, unless something newer for [key] comes first. A failed
     *  write calls [onFailure], on the writer's thread (or the flushing caller's). */
    fun schedule(key: String, value: Any, onFailure: (Exception) -> Unit = {}, perform: () -> Unit) {
        synchronized(this) {
            pending[key] = Entry(value, onFailure, perform)
            timer?.cancel(false)
            timer = executor.schedule({ flush() }, delayMs, TimeUnit.MILLISECONDS)
        }
    }

    /** The newest value waiting to be written for [key], or null. */
    fun pending(key: String): Any? = synchronized(this) { pending[key]?.value }
    fun pendingValues(): List<Any> = synchronized(this) { pending.values.map { it.value } }

    /** Writes everything waiting, and returns once it is on disk (or has failed): each waiting draft is tried once. */
    fun flush() {
        synchronized(io) {
            val tried = HashSet<Entry>()
            var again = false
            while (true) {
                val (key, entry) = synchronized(this) {
                    pending.entries.firstOrNull { it.value !in tried }?.let { it.key to it.value }
                } ?: break
                tried += entry
                val written = try { entry.perform(); true }
                catch (e: Exception) { entry.onFailure(e); false }
                // Not a disk error: dropped, so it can't stop every later flush at the same place.
                catch (t: Throwable) { synchronized(this) { if (pending[key] === entry) pending.remove(key) }; throw t }
                // Still shown as waiting until written, so a reader never finds neither it nor the file; a newer one
                // that arrived meanwhile stays and is written next. A failed one stays too.
                synchronized(this) {
                    if (written) { if (pending[key] === entry) pending.remove(key) }
                    else if (++entry.failures == 1 && pending[key] === entry) again = true
                }
            }
            if (again) synchronized(this) {
                if (retry == null) retry = executor.schedule({ synchronized(this) { retry = null }; flush() }, retryMs, TimeUnit.MILLISECONDS)
            }
        }
    }

    /** Drops what is waiting for [key] and runs [block] (a direct write or a delete) after any write under way. */
    fun <T> now(key: String, block: () -> T): T = synchronized(io) {
        synchronized(this) { pending.remove(key) }
        block()
    }
}
