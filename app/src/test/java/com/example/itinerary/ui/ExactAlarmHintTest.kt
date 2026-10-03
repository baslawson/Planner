package com.example.itinerary.ui

import com.example.itinerary.data.Reminder
import com.example.itinerary.data.ReminderUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// R-2: an event's or bill's reminders warn while exact alarms are off, as a task's do; "Ring until I stop it" can't ring then.
class ExactAlarmHintTest {
    private val plain = Reminder(id = 1, itemId = 1, amount = 15, unit = ReminderUnit.MINUTES)

    @Test fun warnsOnlyWithoutExactAlarms() {
        assertNull(exactAlarmHint(true, listOf(plain.copy(ringUntilDismissed = true))))
        assertNull(exactAlarmHint(false, emptyList()))
        assertEquals(LATE_REMINDER_HINT, exactAlarmHint(false, listOf(plain)))
        assertTrue(exactAlarmHint(false, listOf(plain, plain.copy(id = 2, ringUntilDismissed = true)))!!.contains("Ring until I stop it"))
    }

    // DU-5: the note and quick-entry editors show this constant now instead of their own copy of the text.
    @Test fun lateReminderHintText() {
        assertEquals("Android may deliver this reminder late. Enable Alarms & reminders in app settings for precise timing.", LATE_REMINDER_HINT)
    }
}
