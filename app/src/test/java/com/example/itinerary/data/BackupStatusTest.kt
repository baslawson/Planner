package com.example.itinerary.data

import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

// AS-2: a backup stopped with Cancel isn't recorded as a failed one; a real failure or an ended app still is.
class BackupStatusTest {
    private val before = BackupStatus("2026-10-01T08:00:00Z", "Nextcloud", "SUCCESS", "Nextcloud")
    private val running = before.copy(outcome = "RUNNING", attemptDestination = "Nextcloud")

    @Test fun cancelLeavesTheStatusAsItWas() {
        val after = running.afterFailure(before, TransferCancelledException())
        assertEquals(before, after)
        assertFalse(after.failed)
        // A cancel after an earlier failure keeps that one showing.
        val failedBefore = before.copy(outcome = "FAILED")
        assertTrue(running.afterFailure(failedBefore, TransferCancelledException()).failed)
    }

    @Test fun aFailureOrAnEndedAppStillShows() {
        assertEquals("FAILED", running.afterFailure(before, BackupException("Nextcloud timed out.")).outcome)
        assertEquals("INTERRUPTED", running.afterFailure(before, CancellationException("ended")).outcome)
        assertTrue(running.afterFailure(before, BackupException("x")).failed)
        assertEquals("2026-10-01T08:00:00Z", running.afterFailure(before, BackupException("x")).lastSuccess)
    }
}
