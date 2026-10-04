package com.example.itinerary

import android.content.Intent
import org.junit.Assert.*
import org.junit.Test

// C10: reopening Planner from Recents re-delivers the root intent; it must not stop a newer ringing alarm or replay a share.
class LaunchIntentTest {
    @Test fun freshLaunchActsOnItsIntent() {
        assertTrue(actsOnLaunchIntent(0, savedStateNull = true))
        assertTrue(actsOnLaunchIntent(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP, savedStateNull = true))
    }
    // A6-4: a notification tap opens Planner as a shortcut does (its open window, clearing the App lock screen above it),
    // and is still acted on.
    @Test fun notificationTapsOpenThePlannerWindowThatIsOpen() {
        val flags = com.example.itinerary.reminders.OPEN_PLANNER_FLAGS
        assertEquals(EntryShortcuts.FORWARD_FLAGS, flags or Intent.FLAG_ACTIVITY_NEW_TASK)
        assertTrue(actsOnLaunchIntent(flags or Intent.FLAG_ACTIVITY_NEW_TASK, savedStateNull = true))
    }
    @Test fun rotationDoesNot() = assertFalse(actsOnLaunchIntent(0, savedStateNull = false))
    @Test fun reopenedFromRecentsDoesNot() {
        assertFalse(actsOnLaunchIntent(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY, savedStateNull = true))
        assertFalse(actsOnLaunchIntent(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY, savedStateNull = true))
    }
    // Q-6: a calendar file is read from a content: URI only, never a file: path (which could be Planner's own files).
    @Test fun calendarFilesComeAsContentOnly() {
        assertTrue(opensCalendarFile("content"))
        assertFalse(opensCalendarFile("file"))
        assertFalse(opensCalendarFile("https"))
        assertFalse(opensCalendarFile(null))
    }
    // SR-1: Open Planner on applies to opening Planner itself, not to a reminder or alarm tap after Android closed it.
    @Test fun onlyAPlainStartOpensOnTheStartScreen() {
        assertTrue(opensOnStartScreen(Intent.ACTION_MAIN, fromNotification = false))
        assertTrue(opensOnStartScreen(null, fromNotification = false))
        assertFalse(opensOnStartScreen(null, fromNotification = true))
        assertFalse(opensOnStartScreen(Intent.ACTION_MAIN, fromNotification = true))
        assertFalse(opensOnStartScreen(Intent.ACTION_SEND, fromNotification = false))
        assertFalse(opensOnStartScreen(com.example.itinerary.widget.TodayWidget.OPEN_TODAY, fromNotification = false))
    }
}
