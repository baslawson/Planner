package com.example.itinerary.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// SY-2: the minute check backs off from repeating full work that keeps leaving the same thing out of step.
class SyncBackoffTest {
    private val minute = 60_000L

    // The minute check at each whole minute; which of them may do the full work while [left] stays the same.
    private fun fullChecks(backoff: SyncBackoff, minutes: Int, left: Any? = "refused note"): List<Int> = (0 until minutes).filter { m ->
        val now = m * minute
        backoff.due(now).also { if (it) backoff.after(left, now) }
    }

    @Test fun theSameLeftoverWaitsLongerEachTimeUpToFifteenMinutes() {
        // Gaps: 1, 1, 2, 4, 8, then 15 minutes.
        assertEquals(listOf(0, 1, 2, 4, 8, 16, 31, 46, 61), fullChecks(SyncBackoff(), 70))
    }

    @Test fun settledNeverWaits() {
        assertEquals((0 until 10).toList(), fullChecks(SyncBackoff(), 10, left = null))
    }

    @Test fun aDifferentResultOrAResetStartsAgain() {
        val backoff = SyncBackoff()
        backoff.after("A", 0); backoff.after("A", minute); backoff.after("A", 2 * minute)
        assertFalse(backoff.due(3 * minute))
        backoff.after("B", 3 * minute)
        assertTrue(backoff.due(3 * minute))
        backoff.after("B", 4 * minute)
        assertFalse(backoff.due(4 * minute + 30_000))
        backoff.reset()
        assertTrue(backoff.due(4 * minute + 30_000))
        // After a reset the same leftover counts as new: no wait yet.
        backoff.after("B", 5 * minute)
        assertTrue(backoff.due(5 * minute))
    }

    @Test fun aLightCheckThatFindsSomethingElseEndsTheWait() {
        val backoff = SyncBackoff()
        backoff.after("A", 0); backoff.after("A", minute)
        backoff.light("A")
        assertFalse(backoff.due(minute + 30_000))
        backoff.light(null)
        assertTrue(backoff.due(minute + 30_000))
        backoff.after("A", 2 * minute); backoff.after("A", 3 * minute)
        backoff.light("B")
        assertTrue(backoff.due(3 * minute))
    }

    @Test fun aCheckALittleEarlyStillCounts() {
        val backoff = SyncBackoff()
        backoff.after("A", 0); backoff.after("A", 0)
        assertTrue(backoff.due(minute - 1_000))
        assertFalse(backoff.due(minute - 10_000))
    }
}
