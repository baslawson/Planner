package com.example.itinerary.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// Q-4: a calendar file opened from another app while the import dialog is up waits for it to close, then opens afresh.
class CalendarImportQueueTest {
    @Test fun aSecondFileOpensAfterTheFirstCloses() {
        assertEquals("content://b.ics", calendarFileAfterClose("content://a.ics", "content://b.ics"))
    }

    @Test fun closingTheOnlyFileIsDone() {
        assertNull(calendarFileAfterClose("content://a.ics", "content://a.ics"))
        // Opened from Settings with no file from another app.
        assertNull(calendarFileAfterClose<String>(null, null))
    }
}
