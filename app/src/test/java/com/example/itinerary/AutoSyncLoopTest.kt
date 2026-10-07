package com.example.itinerary

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

// Bug hunt 18: AutoSync's watch loop, on a made-up clock.
class AutoSyncLoopTest {
    private class Stop : CancellationException()

    // Runs the loop with [script] answering each wait (given the wait asked for and the clock; returns the time it took
    // and whether a wake came), until the script runs out. Gives the waits asked for and the checks made (fresh or not).
    private fun run(script: List<(Long) -> Pair<Long, Boolean>>): Pair<List<Long>, List<Boolean>> {
        var now = 100_000L
        val waits = mutableListOf<Long>()
        val checks = mutableListOf<Boolean>()
        try {
            runBlocking {
                AutoSync.loop(60_000, { now }, { ms ->
                    if (waits.size == script.size) throw Stop()
                    val (took, woke) = script[waits.size](ms)
                    waits += ms; now += took; woke
                }) { checks += it; now += 200 }
            }
        } catch (_: Stop) {}
        return waits to checks
    }

    private fun timeout(): (Long) -> Pair<Long, Boolean> = { ms -> ms to false }
    private fun wakeAfter(t: Long): (Long) -> Pair<Long, Boolean> = { _ -> t to true }

    // R18-S6: a connection back within the gap after a check is remembered: the next wait is only the rest of the gap,
    // and the check that follows is a fresh one.
    @Test fun wakeTooSoonIsRememberedAndCheckedAfterTheGap() {
        val (waits, checks) = run(listOf(
            wakeAfter(100), // the callback firing on registering, just after the first check: not a check of its own
            timeout(), // the minute's check
            wakeAfter(1_000), // the connection back 1 s after that check
            timeout(),
        ))
        assertEquals(listOf(60_000L, 60_000L, 60_000L, 4_000L), waits)
        assertEquals(listOf(false, false, true), checks)
    }

    // A wake after the gap is a fresh check at once; the registering one never causes a check of its own.
    @Test fun wakeAfterTheGapChecksAtOnce() {
        val (waits, checks) = run(listOf(wakeAfter(100), wakeAfter(10_000), timeout()))
        assertEquals(listOf(60_000L, 60_000L, 60_000L), waits)
        assertEquals(listOf(false, true, false), checks)
    }
}
