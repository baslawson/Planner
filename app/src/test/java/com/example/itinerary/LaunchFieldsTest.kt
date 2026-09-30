package com.example.itinerary

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

// E5: a new intent while Planner is open only replaces what it carries.
class LaunchFieldsTest {
    private val shareOpen = LaunchFields<String>(sharedText = "Dinner Friday 7pm", sharedSubject = "Plans")

    @Test fun reminderNotificationKeepsAnOpenShareReview() {
        assertEquals(shareOpen, shareOpen.mergedWith(LaunchFields()))
    }

    @Test fun newShareReplacesTheOldOneWithItsOwnSubject() {
        val merged = shareOpen.mergedWith(LaunchFields(sharedText = "Lunch Monday"))
        assertEquals("Lunch Monday", merged.sharedText)
        assertNull(merged.sharedSubject)
        // An empty share is still a share.
        assertEquals("", shareOpen.mergedWith(LaunchFields(sharedText = "")).sharedText)
    }

    @Test fun widgetTaskSetsTheTaskAndLeavesTheShare() {
        val merged = shareOpen.mergedWith(LaunchFields(widgetTaskId = "task-b"))
        assertEquals("task-b", merged.widgetTaskId)
        assertEquals("Dinner Friday 7pm", merged.sharedText)
        assertEquals("Plans", merged.sharedSubject)
        assertEquals("task-c", merged.mergedWith(LaunchFields(widgetTaskId = "task-c")).widgetTaskId)
    }

    @Test fun calendarFileShortcutAndWidgetDayAreKeptUntilReplaced() {
        val open = LaunchFields<String>(calendarUri = "content://a.ics", entryAction = "shortcut", widgetDate = LocalDate.of(2026, 10, 1))
        assertEquals(open, open.mergedWith(LaunchFields()))
        val merged = open.mergedWith(LaunchFields(calendarUri = "content://b.ics", widgetDate = LocalDate.of(1895, 6, 1)))
        assertEquals("content://b.ics", merged.calendarUri)
        assertEquals("shortcut", merged.entryAction)
        assertEquals(LocalDate.of(1895, 6, 1), merged.widgetDate)
    }
}
