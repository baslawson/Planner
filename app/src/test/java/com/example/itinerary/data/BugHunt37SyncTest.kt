package com.example.itinerary.data

import org.junit.Assert.*
import org.junit.Test

// Sync items from bug hunt 37, as approved. The note pass itself (N1) is exercised in the instrumented BugHunt37NoteSyncTest.
class BugHunt37SyncTest {
    private val key = "https://cloud.example/|me"
    private fun calendar(href: String, name: String = href, sendHere: Boolean = false, tasksHere: Boolean = false, enabled: Boolean = false) =
        CalendarSource(account = key, href = href, name = name, enabled = enabled, sendHere = sendHere, tasksHere = tasksHere)

    // N1: a note is readable unless the Notes app says otherwise.
    @Test fun aRemoteNoteIsReadableByDefault() {
        assertFalse(RemoteNote(1, "e", "Shopping", "milk", "", false).error)
        assertTrue(RemoteNote(1, "e", "Shopping", "Error: OCA\\Notes\\Service\\NoteNotFoundException", "", false, readonly = true, error = true).error)
    }

    // N2: any conflict whose file has gone shows Nextcloud's side as deleted, not the old version it held.
    @Test fun aConflictGoneFromNextcloudIsMarkedGone() {
        val row = SentEvent(id = 7, itemId = 3, account = key, calendar = "/cal/", uid = "u", fingerprint = "f",
            problem = SentEvent.CONFLICT, conflict = "BEGIN:VCALENDAR\nOLD\nEND:VCALENDAR")
        assertEquals(row.copy(conflict = ""), CalendarSync.goneConflict(row, deletedHere = false))
        // A restored one (no Nextcloud side yet) the same.
        assertEquals("", CalendarSync.goneConflict(row.copy(conflict = null), deletedHere = false)?.conflict)
        // Deleted in Planner too (its Undo gone): nothing is left to choose.
        assertNull(CalendarSync.goneConflict(row, deletedHere = true))
    }

    // N4: a listing that can't be trusted doesn't remove the user's calendars or sync choices.
    @Test fun anEmptyOrIncompleteListingKeepsTheCalendars() {
        val work = calendar("/work/", "Work", enabled = true)
        val home = calendar("/home/", "Home", sendHere = true, enabled = true)
        val todo = calendar("/todo/", "To do", tasksHere = true)
        // Nothing synced yet: nothing to lose.
        assertNull(CalendarSync.untrustedListing(emptyList(), emptySet()))
        // Came back empty.
        assertNotNull(CalendarSync.untrustedListing(listOf(work), emptySet()))
        // All there, or only a calendar that is just ticked missing (it goes, as before).
        assertNull(CalendarSync.untrustedListing(listOf(work, home, todo), setOf("/work/", "/home/", "/todo/")))
        assertNull(CalendarSync.untrustedListing(listOf(work, home, todo), setOf("/home/", "/todo/")))
        // The calendar kept in sync, or the task list, missing.
        assertTrue(CalendarSync.untrustedListing(listOf(work, home, todo), setOf("/work/", "/todo/"))!!.contains("Home"))
        assertTrue(CalendarSync.untrustedListing(listOf(work, home, todo), setOf("/work/", "/home/"))!!.contains("To do"))
    }
}
