package com.example.itinerary.reminders

import android.app.NotificationManager
import android.media.AudioManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReminderChimeTest {
    private val high = NotificationManager.IMPORTANCE_HIGH

    // Hunt 20 R1: any Do Not Disturb mode means no chime (it would hide the notification, and lets alarms through by
    // default); the reminder then goes to the Reminders category, which Do Not Disturb handles as usual.
    @Test fun doNotDisturbMeansNoChime() {
        assertTrue(ReminderChime.chimes(high, true, high, NotificationManager.INTERRUPTION_FILTER_ALL))
        assertTrue("unknown (no access)", ReminderChime.chimes(high, true, high, NotificationManager.INTERRUPTION_FILTER_UNKNOWN))
        assertFalse("priority", ReminderChime.chimes(high, true, high, NotificationManager.INTERRUPTION_FILTER_PRIORITY))
        assertFalse("alarms only", ReminderChime.chimes(high, true, high, NotificationManager.INTERRUPTION_FILTER_ALARMS))
        assertFalse("total silence", ReminderChime.chimes(high, true, high, NotificationManager.INTERRUPTION_FILTER_NONE))
    }

    // R2: not during a call (or while one rings); R3: not when the phone's notification sound is None.
    @Test fun callsAndANoneSoundMeanNoChime() {
        assertFalse(ReminderChime.chimes(high, true, high, audioMode = AudioManager.MODE_IN_CALL))
        assertFalse(ReminderChime.chimes(high, true, high, audioMode = AudioManager.MODE_IN_COMMUNICATION))
        assertFalse(ReminderChime.chimes(high, true, high, audioMode = AudioManager.MODE_RINGTONE))
        assertFalse(ReminderChime.chimes(high, true, high, notificationSoundNone = true))
    }

    // P3 and R4: the user's own choices for both categories in Android's settings win over the chime.
    @Test fun androidsCategorySettingsDecideWhetherTheChimePlays() {
        assertTrue(ReminderChime.chimes(high, reminderHasSound = true, chimeImportance = high))
        assertTrue("channels not made yet", ReminderChime.chimes(null, reminderHasSound = true, chimeImportance = null))
        assertFalse("Reminders set to Silent", ReminderChime.chimes(NotificationManager.IMPORTANCE_LOW, true, high))
        assertFalse("Reminders' sound turned off", ReminderChime.chimes(high, reminderHasSound = false, chimeImportance = high))
        assertFalse("chime category turned off", ReminderChime.chimes(high, true, NotificationManager.IMPORTANCE_NONE))
        assertFalse("chime category set to Silent", ReminderChime.chimes(high, true, NotificationManager.IMPORTANCE_LOW))
    }
}
