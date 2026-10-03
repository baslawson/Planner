package com.example.itinerary.reminders

import org.junit.Assert.assertEquals
import org.junit.Test

// D6-1: which sounds a ringing alarm tries, in order. Strings stand in for Uris (the JVM tests have no android.net.Uri).
class AlarmSoundTest {
    private val chosen = "content://media/internal/audio/media/42"
    private val alarm = "content://settings/system/alarm_alert"
    private val ringtone = "content://settings/system/ringtone"
    private val bundled = "android.resource://planner/raw/alarm_fallback"

    @Test fun unlockedTriesTheChosenSoundFirstThenTheDefaultsThenTheBundledTone() {
        assertEquals(listOf(chosen, alarm, ringtone, bundled), AlarmSound.choices(unlocked = true, chosen, alarm, ringtone, bundled))
    }

    @Test fun beforeTheFirstUnlockTheChosenMediaSoundIsSkipped() {
        // It lives in credential-encrypted storage, which can't be read yet; the settings' cached copy can.
        assertEquals(listOf(alarm, ringtone, bundled), AlarmSound.choices(unlocked = false, chosen, alarm, ringtone, bundled))
    }

    @Test fun missingOnesAreLeftOutAndNoneIsTriedTwice() {
        assertEquals(listOf(alarm, bundled), AlarmSound.choices(unlocked = true, alarm, alarm, null, bundled))
        assertEquals(listOf(bundled), AlarmSound.choices(unlocked = true, null, null, null, bundled))
        assertEquals(listOf(bundled), AlarmSound.choices(unlocked = false, chosen, null, null, bundled))
    }
}
