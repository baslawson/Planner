package com.example.itinerary.ui

import com.example.itinerary.data.CalendarSource
import com.example.itinerary.data.OutsideCalendars
import org.junit.Assert.*
import org.junit.Test

class SyncIndicatorTest {
    private fun source(kind: String = OutsideCalendars.KIND_NEXTCLOUD, sendHere: Boolean = true) =
        CalendarSource(account = "a", href = "/cal/", name = "Personal", enabled = true, kind = kind, sendHere = sendHere)
    private fun of(sources: List<CalendarSource> = listOf(source()), running: Boolean = false, error: Boolean = false, conflicts: Int = 0) =
        SyncIndicatorState.of(sources, running, error, conflicts)

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
}
