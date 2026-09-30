package com.example.itinerary.ui

import com.example.itinerary.data.CalendarSource
import com.example.itinerary.data.OutsideCalendars
import org.junit.Assert.*
import org.junit.Test

class SyncIndicatorTest {
    private fun source(kind: String = OutsideCalendars.KIND_NEXTCLOUD, sendHere: Boolean = true) =
        CalendarSource(account = "a", href = "/cal/", name = "Personal", enabled = true, kind = kind, sendHere = sendHere)
    private fun of(sources: List<CalendarSource> = listOf(source()), running: Boolean = false, error: Boolean = false, conflicts: Int = 0,
                   loggedIn: Boolean? = true) = SyncIndicatorState.of(sources, loggedIn, running, error, conflicts)

    @Test fun hiddenUnlessTwoWayNextcloudSyncIsOn() {
        assertNull(of(sources = emptyList()))
        assertNull(of(sources = listOf(source(sendHere = false))))
        assertNull(of(sources = listOf(source(kind = OutsideCalendars.KIND_LINK), source(kind = OutsideCalendars.KIND_PHONE))))
        assertEquals(SyncIndicatorState.Synced, of(sources = listOf(source(sendHere = false), source())))
    }
    @Test fun showsSyncingThenProblemsFirst() {
        assertEquals(SyncIndicatorState.Syncing, of(running = true))
        assertEquals(SyncIndicatorState.Failed, of(error = true))
        assertEquals(SyncIndicatorState.Failed, of(running = true, error = true))
        assertEquals(SyncIndicatorState.Conflicts(2), of(running = true, error = true, conflicts = 2))
        assertEquals("Sync: 1 conflict, open Calendars", of(conflicts = 1)!!.label)
        assertEquals("Sync: 2 conflicts, open Calendars", of(conflicts = 2)!!.label)
    }
    // C8: a restored backup brings the send-here calendar but not the login, so nothing can sync.
    @Test fun withoutANextcloudLoginItIsAProblemNotUpToDate() {
        assertEquals(SyncIndicatorState.Failed, of(loggedIn = false))
        assertEquals(SyncIndicatorState.Failed, of(loggedIn = false, running = true))
        assertEquals(SyncIndicatorState.Conflicts(1), of(loggedIn = false, conflicts = 1))
        assertNull(of(loggedIn = false, sources = listOf(source(sendHere = false))))
        // Not known yet (the login is read off the main thread): hidden rather than a guess.
        assertNull(of(loggedIn = null))
    }

    // E7: a failed download of the synced calendar is kept on it (lastError), so a send that works right after doesn't
    // turn the icon back to up to date; it clears when a download works. A read-only calendar's failure isn't this icon's.
    @Test fun aFailedDownloadOfTheSyncedCalendarIsAProblemUntilOneWorks() {
        val failed = source().copy(lastError = "Couldn't download this calendar.")
        assertEquals(SyncIndicatorState.Failed, of(sources = listOf(failed)))
        assertEquals(SyncIndicatorState.Failed, of(sources = listOf(failed), running = true))
        assertEquals(SyncIndicatorState.Conflicts(1), of(sources = listOf(failed), conflicts = 1))
        assertEquals(SyncIndicatorState.Synced, of(sources = listOf(source())))
        assertEquals(SyncIndicatorState.Synced, of(sources = listOf(source(), source(sendHere = false).copy(href = "/work/", lastError = "x"))))
    }

    // E7: Sync now says the calendar kept in sync couldn't be downloaded, even when every other part worked.
    @Test fun syncNowReportsAFailedDownloadOfTheSyncedCalendar() {
        val failed = source().copy(lastError = "Couldn't download this calendar.")
        val work = source(sendHere = false).copy(href = "/work/", name = "Work")
        assertEquals("Synced at 09:00, but Personal, the calendar kept in sync, couldn't be downloaded (see above)." to true,
            syncResult(listOf(failed, work), 0, emptyList(), "09:00"))
        assertEquals("Synced at 09:00, but 1 calendar couldn't be updated (see above)." to true,
            syncResult(listOf(source(), work.copy(lastError = "x")), 0, emptyList(), "09:00"))
        assertEquals("Synced just now · 09:00" to false, syncResult(listOf(source(), work), 0, emptyList(), "09:00"))
    }
    @Test fun aTapSyncsWhenAllIsWellAndOpensCalendarsWhenSomethingNeedsALook() {
        assertEquals(SyncIndicatorState.Tap.SYNC, SyncIndicatorState.Synced.tap)
        assertEquals(SyncIndicatorState.Tap.NOTHING, SyncIndicatorState.Syncing.tap)
        assertEquals(SyncIndicatorState.Tap.OPEN, SyncIndicatorState.Failed.tap)
        assertEquals(SyncIndicatorState.Tap.OPEN, SyncIndicatorState.Conflicts(2).tap)
        assertEquals("Sync now", SyncIndicatorState.Synced.tapLabel)
        assertEquals("Open Calendars", SyncIndicatorState.Failed.tapLabel)
    }
}
