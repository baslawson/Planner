package com.example.itinerary.ui

import com.example.itinerary.data.Reminder
import com.example.itinerary.data.ReminderSound
import com.example.itinerary.data.ReminderUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// R-2: an event's or bill's reminders warn while exact alarms are off, as a task's do; one set to ring can't ring then.
class ExactAlarmHintTest {
    private val plain = Reminder(id = 1, itemId = 1, amount = 15, unit = ReminderUnit.MINUTES)
    private val quiet = plain.copy(ringSeconds = ReminderSound.NOTIFICATION.seconds)

    @Test fun warnsOnlyWithoutExactAlarms() {
        val notificationOnly = ReminderSound.NOTIFICATION
        assertNull(exactAlarmHint(true, listOf(plain.copy(ringUntilDismissed = true)), notificationOnly))
        assertNull(exactAlarmHint(false, emptyList(), notificationOnly))
        assertEquals(LATE_REMINDER_HINT, exactAlarmHint(false, listOf(plain), notificationOnly))
        assertTrue(exactAlarmHint(false, listOf(plain, plain.copy(id = 2, ringUntilDismissed = true)), notificationOnly)!!.contains("can't ring"))
    }

    // Reminder sound: Default rings when the Settings choice rings (10 s, the default); one set to the notification sound
    // only doesn't, whatever the default; one with its own length always does.
    @Test fun aReminderLeftAtDefaultFollowsTheSetting() {
        assertTrue(exactAlarmHint(false, listOf(plain), ReminderSound.SECONDS_10)!!.contains("can't ring"))
        assertEquals(LATE_REMINDER_HINT, exactAlarmHint(false, listOf(quiet), ReminderSound.UNTIL_STOPPED))
        assertTrue(exactAlarmHint(false, listOf(plain.copy(ringSeconds = 30)), ReminderSound.NOTIFICATION)!!.contains("can't ring"))
        assertTrue(rings(false, 0, ReminderSound.MINUTE))
        assertFalse(rings(false, 0, ReminderSound.NOTIFICATION))
        assertTrue(rings(true, 0, ReminderSound.NOTIFICATION))
    }

    // DU-5: the note and quick-entry editors show this constant now instead of their own copy of the text.
    @Test fun lateReminderHintText() {
        assertEquals("Android may deliver this reminder late. Enable Alarms & reminders in app settings for precise timing.", LATE_REMINDER_HINT)
    }
}
