package com.example.itinerary.ui

import com.example.itinerary.data.SentEvent
import com.example.itinerary.data.SyncMark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// What the cards say: the sync line under a Planner event, and the Undo bar after deleting a selection.
class CardMarksTest {
    @Test fun syncLineFollowsTheSentRow() {
        assertEquals(SyncMark.SYNCED, SyncMark.of("u1", null))
        assertEquals(SyncMark.SYNCED, SyncMark.of("u1", SentEvent.PENDING))
        listOf(SentEvent.CONFLICT, SentEvent.CHANGED, SentEvent.DELETED).forEach { assertEquals(it, SyncMark.PROBLEM, SyncMark.of("u1", it)) }
        // Only noted (never sent), or no longer synced: no line.
        assertNull(SyncMark.of(null, null))
        assertNull(SyncMark.of("u1", SentEvent.DETACHED))
    }

    @Test fun undoBarNamesWhatWasDeleted() {
        assertEquals("Event deleted", deletedMessage(1, 0))
        assertEquals("3 events deleted", deletedMessage(3, 0))
        assertEquals("Task deleted", deletedMessage(0, 1))
        assertEquals("Note deleted", deletedMessage(0, 0, 1))
        assertEquals("2 notes deleted", deletedMessage(0, 0, 2))
        assertEquals("2 items deleted", deletedMessage(0, 1, 1))
        assertEquals("2 tasks deleted", deletedMessage(0, 2))
        assertEquals("2 items deleted", deletedMessage(1, 1))
    }
}
