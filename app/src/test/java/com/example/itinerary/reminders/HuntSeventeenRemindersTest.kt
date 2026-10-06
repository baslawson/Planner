package com.example.itinerary.reminders

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HuntSeventeenRemindersTest {
    // H17-R2: "Alarms & reminders" is named only when exact alarms are what's refused.
    @Test fun couldNotRingNamesAlarmsOnlyWhenTheyAreRefused() {
        assertEquals(CouldNotRing.ALLOW_ALARMS, CouldNotRing.of(exactAllowed = false))
        assertEquals(CouldNotRing.CHECK_SETTINGS, CouldNotRing.of(exactAllowed = true))
        CouldNotRing.CHECK_SETTINGS.let { assertTrue(listOf(it.full, it.short, it.brief).none { s -> "Alarms & reminders" in s }) }
        CouldNotRing.ALLOW_ALARMS.let { assertTrue(listOf(it.full, it.short, it.brief).all { s -> "Alarms & reminders" in s }) }
    }

    // H17-R1: the full rewrite after a ring waits while another armed alarm is about to ring (or just was due).
    @Test fun fullRewriteWaitsForANearbyAlarm() {
        val now = 1_000_000_000L
        val clear = LockedAlarmSelection.RING_CLEAR_MS
        assertFalse(LockedAlarmSelection.ringSoon(emptyList(), now))
        assertTrue(LockedAlarmSelection.ringSoon(listOf(now + 30_000L), now))
        assertTrue(LockedAlarmSelection.ringSoon(listOf(now + clear), now))
        assertTrue("due a moment ago, not rung yet", LockedAlarmSelection.ringSoon(listOf(now - 10_000L), now))
        assertFalse(LockedAlarmSelection.ringSoon(listOf(now + clear + 1, now + 3_600_000L), now))
        assertFalse("long past (dropped)", LockedAlarmSelection.ringSoon(listOf(now - clear), now))
    }
}
