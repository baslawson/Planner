package com.example.itinerary.reminders

import android.app.NotificationManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// A ringing alarm can be stopped only from its notification, so it rings only when that can show (B9).
class RingingAlarmTest {
    @Test fun ringsOnlyWhenItsNotificationCanShow() {
        assertTrue(ringsAsAlarm(true, NotificationManager.IMPORTANCE_HIGH))
        assertTrue(ringsAsAlarm(true, null))
        assertFalse(ringsAsAlarm(false, NotificationManager.IMPORTANCE_HIGH))
        assertFalse(ringsAsAlarm(true, NotificationManager.IMPORTANCE_NONE))
    }
}
