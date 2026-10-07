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

    // P3: the user's own choices for the Reminders category in Android's settings win over the chime.
    @Test fun androidsCategorySettingsDecideWhetherTheChimePlays() {
        val high = NotificationManager.IMPORTANCE_HIGH
        assertTrue(ReminderChime.chimes(high, reminderHasSound = true, chimeImportance = high))
        assertTrue("channels not made yet", ReminderChime.chimes(null, reminderHasSound = true, chimeImportance = null))
        assertFalse("Reminders set to Silent", ReminderChime.chimes(NotificationManager.IMPORTANCE_LOW, true, high))
        assertFalse("Reminders' sound turned off", ReminderChime.chimes(high, reminderHasSound = false, chimeImportance = high))
        assertFalse("chime category turned off", ReminderChime.chimes(high, true, NotificationManager.IMPORTANCE_NONE))
    }
}
