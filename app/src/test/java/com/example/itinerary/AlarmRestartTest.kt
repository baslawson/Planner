package com.example.itinerary

import com.example.itinerary.reminders.AlarmRestart
import com.example.itinerary.reminders.AlarmService
import org.junit.Assert.*
import org.junit.Test

class AlarmRestartTest {
    private val full = AlarmService.MAX_RING_MINUTES * 60_000L

    @Test fun aRestartedAlarmRingsOnForWhatIsLeft() {
        assertEquals(full, AlarmRestart.ringFor(redelivered = false, trigger = 1_000, now = 999_999_999))
        assertEquals(full, AlarmRestart.ringFor(redelivered = true, trigger = 0, now = 5_000))
        assertEquals(full - 120_000, AlarmRestart.ringFor(redelivered = true, trigger = 0L + 10_000, now = 130_000))
        assertEquals(60_000L, AlarmRestart.ringFor(redelivered = true, trigger = 0L + 10_000, now = 10_000 + full + 60_000)) // at least a minute
        assertNull(AlarmRestart.ringFor(redelivered = true, trigger = 0L + 10_000, now = 10_000 + full + 6 * 60_000)) // long gone: missed
        // 0 seconds is "until I stop it", as before.
        assertEquals(full, AlarmRestart.ringFor(redelivered = false, trigger = 1_000, now = 2_000, seconds = 0))
    }

    // Reminder sound: a timed ring rings its own length, however late it starts; after a restart only what is left of it
    // from the reminder's time, and nothing (null: its normal notification, not missed) once that has passed.
    @Test fun aTimedRingRingsItsLengthAndAfterARestartOnlyWhatIsLeft() {
        assertEquals(10_000L, AlarmRestart.ringFor(redelivered = false, trigger = 1_000, now = 999_999_999, seconds = 10))
        assertEquals(30_000L, AlarmRestart.ringFor(redelivered = true, trigger = 0, now = 5_000, seconds = 30))
        assertEquals(60_000L, AlarmRestart.ringFor(redelivered = false, trigger = 1_000, now = 2_000, seconds = 60))
        assertEquals(20_000L, AlarmRestart.ringFor(redelivered = true, trigger = 100_000, now = 110_000, seconds = 30))
        assertEquals(30_000L, AlarmRestart.ringFor(redelivered = true, trigger = 100_000, now = 50_000, seconds = 30)) // clock back
        assertNull(AlarmRestart.ringFor(redelivered = true, trigger = 100_000, now = 130_000, seconds = 30))
        assertNull(AlarmRestart.ringFor(redelivered = true, trigger = 100_000, now = 100_000 + full, seconds = 10))
    }
}
