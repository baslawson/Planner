package com.example.itinerary.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

// Bug hunt 4 Oct (b): calendar file import (S6-2, S6-3), the files an event editor keeps (S6-4, S6-8) and sync's own
// writes (S6-5).
class SyncFixesOct4bTest {
    private val today = LocalDate.of(2026, 10, 4)
    private fun ics(vararg events: String) = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\n" +
        events.joinToString("") { "BEGIN:VEVENT\r\n$it\r\nEND:VEVENT\r\n" } + "END:VCALENDAR\r\n"
    private fun daily(start: String) = CalendarFileImport.read(ics("UID:d\r\nDTSTART:${start}T090000\r\nRRULE:FREQ=DAILY\r\nSUMMARY:Daily"),
        ZoneOffset.UTC, today).entries.single()

    // S6-2: past events included never import less than without, and keep today; the past dates nearest today fill
    // what is left of the 365.
    @Test fun includingPastEventsKeepsTodayAndNeverImportsLess() {
        for (start in listOf("20241004", "20260924", "20261004")) {
            val series = daily(start)
            val without = series.datesFor(today, includePast = false)
            val with = series.datesFor(today, includePast = true)
            assertEquals(today, without.first())
            assertTrue(start, today in with)
            assertTrue(start, with.containsAll(without))
            assertEquals(365, with.size)
        }
        // A weekly series with 30 past dates and 20 to come: all 50.
        val weekly = CalendarFileImport.read(ics("UID:w\r\nDTSTART:20260301T090000\r\nRRULE:FREQ=WEEKLY;COUNT=50\r\nSUMMARY:Weekly"),
            ZoneOffset.UTC, today).entries.single()
        val past = weekly.dates.count { it < today }
        assertEquals(weekly.dates.size - past, weekly.datesFor(today, includePast = false).size)
        assertEquals(weekly.dates, weekly.datesFor(today, includePast = true))
        // More dates than fit, few of them to come: those to come, then the nearest past ones.
        val dates = (0L until 400).map { today.minusDays(390).plusDays(it) } // 390 past, 10 from today on
        val entry = CalendarFileImport.Entry(0, ItineraryItem(tripId = 0, date = dates.first(), startTime = null, title = "D"), dates, RepeatRule.DAILY)
        val kept = entry.datesFor(today, includePast = true)
        assertEquals(365, kept.size)
        assertEquals(today.minusDays(355), kept.first())
        assertEquals(dates.last(), kept.last())
        assertEquals(kept.sorted(), kept)
    }

    // S6-3: an event left open at the end of the file, and raw BEGIN/END lines, cost only their own event.
    @Test fun brokenEndingsCostOnlyTheirEvent() {
        val unfinished = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VEVENT\r\nDTSTART:20261005T100000\r\nSUMMARY:Good\r\nEND:VEVENT\r\n" +
            "BEGIN:VEVENT\r\nDTSTART:20261006T100000\r\nSUMMARY:Never ended\r\nEND:VCALENDAR\r\n"
        CalendarFileImport.read(unfinished, ZoneOffset.UTC, today).let {
            assertEquals(listOf("Good"), it.entries.map { e -> e.item.title }); assertEquals(1, it.skipped)
        }
        // Left open inside its alarm too.
        val inAlarm = "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nDTSTART:20261005T100000\r\nSUMMARY:Good\r\nEND:VEVENT\r\n" +
            "BEGIN:VEVENT\r\nDTSTART:20261006T100000\r\nSUMMARY:Cut\r\nBEGIN:VALARM\r\nTRIGGER:-PT5M\r\nEND:VCALENDAR\r\n"
        CalendarFileImport.read(inAlarm, ZoneOffset.UTC, today).let {
            assertEquals(listOf("Good"), it.entries.map { e -> e.item.title }); assertEquals(1, it.skipped)
        }
        // Raw "Begin:Word" / "End:Word" lines outside any event are text.
        val header = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBegin:Notes\r\nEnd:Monday\r\nBEGIN:VEVENT\r\nDTSTART:20261005T100000\r\nSUMMARY:Good\r\n" +
            "END:VEVENT\r\nEnd:Shift\r\nEND:VCALENDAR\r\n"
        CalendarFileImport.read(header, ZoneOffset.UTC, today).let {
            assertEquals(listOf("Good"), it.entries.map { e -> e.item.title }); assertEquals(0, it.skipped)
        }
        // A raw "End:Vevent" in a description closes its event early: that event is skipped, the next one read.
        val early = ics("DTSTART:20261005T100000\r\nDESCRIPTION:Shift notes\r\nEnd:Vevent\r\nSUMMARY:Cut early",
            "DTSTART:20261006T100000\r\nSUMMARY:Next")
        CalendarFileImport.read(early, ZoneOffset.UTC, today).let {
            assertEquals(listOf("Next"), it.entries.map { e -> e.item.title }); assertEquals(1, it.skipped)
        }
        // AS-4: so does a raw "Begin:Lunch" further on in the same text.
        val earlyThenBegin = ics("DTSTART:20261005T100000\r\nDESCRIPTION:line1\r\nEnd:Vevent\r\nBegin:Lunch\r\nLOCATION:Canteen",
            "DTSTART:20261006T100000\r\nSUMMARY:Next")
        CalendarFileImport.read(earlyThenBegin, ZoneOffset.UTC, today).let {
            assertEquals(listOf("Next"), it.entries.map { e -> e.item.title }); assertEquals(1, it.skipped)
        }
        // A subscribed calendar link is read the same way.
        assertEquals(listOf("Good"), CalendarFileImport.window(unfinished, ZoneOffset.UTC, today, today.plusDays(5)).events.map { it.title })
        // A file cut off before its END:VCALENDAR is still incomplete, and a server's reply is still strict.
        assertThrows(IllegalArgumentException::class.java) { CalendarFileImport.read(unfinished.removeSuffix("END:VCALENDAR\r\n"), ZoneOffset.UTC, today) }
        assertThrows(IllegalArgumentException::class.java) { Ics.events(Ics.lines(unfinished), 50, "Too many events.") }
    }

    // S6-8: an editor draft that can't be read holds no files instead of failing the clean-up. S6-4: an open editor's
    // files are kept while it is open, with or without a draft.
    @Test fun anUnreadableDraftHoldsNoFilesAndAnOpenEditorHoldsItsOwn() {
        assertEquals(emptySet<String>(), EditorDraftStore.files { throw IllegalStateException("unreadable draft") })
        assertEquals(emptySet<String>(), EditorDraftStore.files { null })
        val editor = Any()
        EditorDraftStore.holdFiles(editor, setOf("photo.jpg", "bill.pdf"))
        try {
            assertEquals(setOf("photo.jpg", "bill.pdf"), EditorDraftStore.files { throw IllegalStateException("unreadable draft") })
            EditorDraftStore.holdFiles(editor, setOf("photo.jpg"))
            assertEquals(setOf("photo.jpg"), EditorDraftStore.files { null })
        } finally { EditorDraftStore.releaseFiles(editor) }
        assertEquals(emptySet<String>(), EditorDraftStore.files { null })
    }

    // S6-5: sync's writes are marked as such all the way down (other threads, locks), and nothing else is.
    @Test fun syncWritesAreMarkedAndNothingElse() = runBlocking {
        assertFalse(SyncWrite.active())
        val marked = SyncWrite.of {
            val lock = Mutex()
            listOf(SyncWrite.active(), withContext(Dispatchers.IO) { SyncWrite.active() }, lock.withLock { SyncWrite.active() })
        }
        assertEquals(listOf(true, true, true), marked)
        assertFalse(SyncWrite.active())
        assertFalse(withContext(Dispatchers.IO) { SyncWrite.active() })
    }
}
