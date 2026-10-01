package com.example.itinerary.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// App lock: when Planner must be unlocked again on coming back into view.
class AppLockRuleTest {
    private val minute = 60_000L
    private fun must(enabled: Boolean = true, unlocked: Boolean = true, leftAt: Long? = 0, ownTrip: Boolean = false,
                     now: Long, lockAfter: LockAfter = LockAfter.ONE_MINUTE) =
        AppLockRule.mustUnlock(enabled, unlocked, leftAt, ownTrip, now, lockAfter.millis)

    @Test fun offNeverLocks() {
        assertFalse(must(enabled = false, unlocked = false, leftAt = null, now = 0))
        assertFalse(must(enabled = false, now = 99 * minute))
    }

    @Test fun freshStartAlwaysLocks() {
        assertTrue(must(unlocked = false, leftAt = null, now = 0))
    }

    @Test fun unlockedAndNeverLeftStaysOpen() {
        assertFalse(must(leftAt = null, now = 99 * minute, lockAfter = LockAfter.IMMEDIATELY))
    }

    @Test fun locksOnceTheChosenTimeHasPassed() {
        assertFalse(must(now = minute - 1))
        assertTrue(must(now = minute))
        assertFalse(must(now = 5 * minute - 1, lockAfter = LockAfter.FIVE_MINUTES))
        assertTrue(must(now = 15 * minute, lockAfter = LockAfter.FIFTEEN_MINUTES))
    }

    @Test fun immediatelyLocksOnAnyReturn() {
        assertTrue(must(leftAt = 1000, now = 1000, lockAfter = LockAfter.IMMEDIATELY))
    }

    @Test fun ownTripGetsAtLeastFiveMinutes() {
        // A file picker opened from Planner: "Immediately" and "1 minute" wait five minutes, longer choices keep theirs.
        assertFalse(must(ownTrip = true, now = 5 * minute - 1, lockAfter = LockAfter.IMMEDIATELY))
        assertTrue(must(ownTrip = true, now = 5 * minute, lockAfter = LockAfter.IMMEDIATELY))
        assertFalse(must(ownTrip = true, now = 3 * minute, lockAfter = LockAfter.ONE_MINUTE))
        assertFalse(must(ownTrip = true, now = 15 * minute - 1, lockAfter = LockAfter.FIFTEEN_MINUTES))
        assertTrue(must(ownTrip = true, now = 15 * minute, lockAfter = LockAfter.FIFTEEN_MINUTES))
    }

    @Test fun unknownSavedChoiceFallsBackToOneMinute() {
        assertEquals(LockAfter.ONE_MINUTE, LockAfter.fromName(null))
        assertEquals(LockAfter.ONE_MINUTE, LockAfter.fromName("SOMETIMES"))
        assertEquals(LockAfter.IMMEDIATELY, LockAfter.fromName("IMMEDIATELY"))
    }
}
