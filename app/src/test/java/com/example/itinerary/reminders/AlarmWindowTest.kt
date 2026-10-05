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
    @Test fun simultaneousEventsTasksAndNotesShareOneBoundedWindow() {
        val triggers = (1..601).associate { i ->
            (when (i % 3) { 0 -> "e:$i"; 1 -> "t:$i"; else -> "n:$i" }) to now + 1
        }
        val selection = AlarmWindow.select(triggers, now)
        assertEquals(AlarmWindow.LIMIT, triggers.count { (key, trigger) -> selection.arms(key, trigger) })
        val selected = triggers.filter { (key, trigger) -> selection.arms(key, trigger) }.keys
        assertEquals(triggers.keys.sorted().take(AlarmWindow.LIMIT).toSet(), selected)
        assertEquals(selection, AlarmWindow.select(triggers.entries.reversed().associate { it.toPair() }, now))
        // Once one selected alarm rings, the first waiting identity obtains the free slot.
        val next = AlarmWindow.select(triggers - selected.first(), now)
        assertEquals(AlarmWindow.LIMIT, (triggers - selected.first()).count { (key, trigger) -> next.arms(key, trigger) })
        assertEquals(1, next.atHorizon.count { it !in selected })
    }

    @Test fun identityWindowKeepsEarlierAlarmsAndIgnoresPastSlots() {
        val triggers = (1..10).associate { "past:$it" to now - it } +
            (1..3).associate { "near:$it" to now + it } + (1..20).associate { "tie:$it" to now + 20 }
        val selection = AlarmWindow.select(triggers, now, limit = 5)
        assertTrue((1..3).all { selection.arms("near:$it", now + it) })
        assertEquals(5, triggers.count { (key, trigger) -> trigger > now && selection.arms(key, trigger) })
        assertNull(AlarmWindow.select(triggers.filterValues { it <= now + 3 }, now, limit = 5).horizon)
    }

    @Test fun overdueDeferredTiesRemainAccountedForUntilDelivery() {
        val at = now + 100
        val triggers = (1..601).associate { "t:$it" to at }
        val selected = AlarmWindow.select(triggers, now)
        val waiting = AlarmWindow.deferred(emptyMap(), triggers, selected, now)
        assertEquals(201, waiting.size)
        val afterDue = AlarmWindow.select(triggers, at)
        assertNull(afterDue.horizon)
        assertEquals(waiting, AlarmWindow.deferred(waiting, triggers, afterDue, at))
        // Newly saved past reminders were never waiting and must not be delivered as overflow.
        assertTrue(AlarmWindow.deferred(emptyMap(), triggers, afterDue, at).isEmpty())
        val changed = waiting.mapValues { it.value - 1 }
        assertTrue(AlarmWindow.deferred(waiting, changed, afterDue, at).isEmpty())
    }

}
