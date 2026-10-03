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
    }
}
