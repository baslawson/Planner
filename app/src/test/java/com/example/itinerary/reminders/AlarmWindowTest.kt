package com.example.itinerary.reminders

import org.junit.Assert.*
import org.junit.Test

// Android 12+ refuses an app's 501st alarm: only the nearest AlarmWindow.LIMIT reminders are armed at once.
class AlarmWindowTest {
    private val now = 1_000_000L

    @Test fun allArmedWhileTheyFit() {
        val triggers = (1..AlarmWindow.LIMIT).map { now + it * 60_000L }
        assertNull(AlarmWindow.horizon(triggers, now))
        assertTrue(triggers.all { AlarmWindow.arms(it, null) })
    }

    @Test fun onlyTheNearestAreArmedNearestFirst() {
        // A daily series of a year, a task and a snooze, in no particular order.
        val series = (365 downTo 1).map { now + it * 86_400_000L }
        val triggers = series + (now + 30_000L) + (now + 90 * 86_400_000L + 1) + (now + 2 * 86_400_000L + 5)
        val horizon = AlarmWindow.horizon(triggers, now, limit = 100)!!
        val armed = triggers.filter { AlarmWindow.arms(it, horizon) }
        assertEquals(100, armed.size)
        assertEquals(triggers.sorted().take(100).toSet(), armed.toSet())
        assertTrue(AlarmWindow.arms(now + 30_000L, horizon))
        assertFalse(AlarmWindow.arms(now + 365 * 86_400_000L, horizon))
    }

    @Test fun pastTriggersDontTakeASlot() {
        val past = (1..50).map { now - it * 1_000L }
        val ahead = (1..10).map { now + it * 1_000L }
        assertNull(AlarmWindow.horizon(past + ahead, now, limit = 10))
        assertEquals(now + 9_000L, AlarmWindow.horizon(past + ahead + (now + 99_000L), now, limit = 9))
    }

    @Test fun aTieAtTheHorizonIsArmedTogether() {
        val triggers = listOf(now + 1, now + 2, now + 2, now + 3)
        val horizon = AlarmWindow.horizon(triggers, now, limit = 2)
        assertEquals(now + 2, horizon)
        assertEquals(3, triggers.count { AlarmWindow.arms(it, horizon) })
    }

    @Test fun refillWhenOverTheLimitOrWellUnderWhileSomeWait() {
        assertTrue(AlarmWindow.needsRefill(AlarmWindow.LIMIT + 1, null))
        assertFalse(AlarmWindow.needsRefill(AlarmWindow.LIMIT, null))
        assertFalse(AlarmWindow.needsRefill(3, null))
        assertTrue(AlarmWindow.needsRefill(AlarmWindow.LIMIT / 2 - 1, now))
        assertFalse(AlarmWindow.needsRefill(AlarmWindow.LIMIT / 2, now))
    }
}
