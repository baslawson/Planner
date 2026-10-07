package com.example.itinerary.reminders

import android.app.NotificationManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReminderChimeTest {
    @Test fun doNotDisturbDecidesWhetherTheChimePlays() {
        val alarms = NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS
        assertTrue(ReminderChime.alarmsAllowed(NotificationManager.INTERRUPTION_FILTER_ALL, null))
        assertTrue("alarms only", ReminderChime.alarmsAllowed(NotificationManager.INTERRUPTION_FILTER_ALARMS, null))
        assertFalse("total silence", ReminderChime.alarmsAllowed(NotificationManager.INTERRUPTION_FILTER_NONE, alarms))
        assertTrue("priority that lets alarms through", ReminderChime.alarmsAllowed(NotificationManager.INTERRUPTION_FILTER_PRIORITY, alarms))
        assertFalse("priority without alarms", ReminderChime.alarmsAllowed(NotificationManager.INTERRUPTION_FILTER_PRIORITY, 0))
        assertTrue("priority, policy unknown", ReminderChime.alarmsAllowed(NotificationManager.INTERRUPTION_FILTER_PRIORITY, null))
    }
}
