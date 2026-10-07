package com.example.itinerary.reminders

import android.app.NotificationManager
import android.media.AudioManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// The checks the 4-second chime had, kept for a timed ring (10 s to 1 min, timedRingAllowed): a normal reminder rings
// through silent and vibrate mode, but not over the user's own choices, Do Not Disturb or a call.
class ReminderRingTest {
    private val high = NotificationManager.IMPORTANCE_HIGH

    // Hunt 20 R1: any Do Not Disturb mode means no timed ring (the default one lets alarms through); the reminder is then a
    // plain notification on the Reminders category, which Do Not Disturb handles as usual.
    @Test fun doNotDisturbMeansNoTimedRing() {
        assertTrue(timedRingAllowed(high, true, NotificationManager.INTERRUPTION_FILTER_ALL))
        assertTrue("unknown (no access)", timedRingAllowed(high, true, NotificationManager.INTERRUPTION_FILTER_UNKNOWN))
        assertFalse("priority", timedRingAllowed(high, true, NotificationManager.INTERRUPTION_FILTER_PRIORITY))
        assertFalse("alarms only", timedRingAllowed(high, true, NotificationManager.INTERRUPTION_FILTER_ALARMS))
        assertFalse("total silence", timedRingAllowed(high, true, NotificationManager.INTERRUPTION_FILTER_NONE))
    }

    // Hunt 20 R2: not during a call, or while one rings.
    @Test fun callsMeanNoTimedRing() {
        assertFalse(timedRingAllowed(high, true, audioMode = AudioManager.MODE_IN_CALL))
        assertFalse(timedRingAllowed(high, true, audioMode = AudioManager.MODE_IN_COMMUNICATION))
        assertFalse(timedRingAllowed(high, true, audioMode = AudioManager.MODE_RINGTONE))
    }

    // Hunt 19 P3: the user's choice for Planner's Reminders category in Android's settings wins.
    @Test fun theRemindersCategoryDecides() {
        assertTrue(timedRingAllowed(high, true))
        assertTrue("category not made yet", timedRingAllowed(null, true))
        assertFalse("Reminders set to Silent", timedRingAllowed(NotificationManager.IMPORTANCE_LOW, true))
        assertFalse("Reminders' sound turned off", timedRingAllowed(high, false))
    }
}
