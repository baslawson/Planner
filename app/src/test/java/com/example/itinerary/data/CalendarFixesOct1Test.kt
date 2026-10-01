package com.example.itinerary.data

import org.junit.Assert.*
import org.junit.Test
import java.time.*

// Calendar sync and calendar files: review fixes of 1 October 2026.
class CalendarFixesOct1Test {
    private val utc = ZoneOffset.UTC
    private val sydney = ZoneId.of("Australia/Sydney")
    private fun ics(vararg events: String) = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Test//EN\r\n" +
        events.joinToString("") { "BEGIN:VEVENT\r\n$it\r\nEND:VEVENT\r\n" } + "END:VCALENDAR\r\n"

    // S2: Outlook/Exchange write Windows zone names, older Thunderbird a "/mozilla.org/…" prefix. Both are known zones.
    @Test fun windowsAndPrefixedZoneNamesAreRead() {
        val berlin = "UID:w\r\nDTSTART;TZID=W. Europe Standard Time:20261005T090000\r\nDTEND;TZID=W. Europe Standard Time:20261005T100000\r\nSUMMARY:Meeting"
        val synced = ServerEvents.parse(ics(berlin), utc).item
        assertNotNull("A Windows zone name keeps the event editable", synced)
        assertEquals(LocalTime.of(7, 0), synced!!.startTime) // 09:00 in Berlin (summer time) is 07:00 UTC
        assertEquals(60, synced.durationMinutes)
        val aus = "UID:a\r\nDTSTART;TZID=\"AUS Eastern Standard Time\":20261005T090000\r\nDURATION:PT30M\r\nSUMMARY:Call"
        assertEquals(LocalTime.of(9, 0), ServerEvents.parse(ics(aus), sydney).item!!.startTime)
        val mozilla = "UID:m\r\nDTSTART;TZID=/mozilla.org/20050126_1/America/New_York:20261005T090000\r\nDURATION:PT1H\r\nSUMMARY:Lunch"
        assertEquals(LocalTime.of(13, 0), ServerEvents.parse(ics(mozilla), utc).item!!.startTime)
        // A subscribed calendar (link) and a file import read them too, instead of skipping every timed event.
        val window = CalendarFileImport.window(ics(berlin, aus, mozilla), utc, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31))
        assertEquals(0, window.skipped)
        assertEquals(listOf(LocalTime.of(7, 0), LocalTime.of(13, 0), LocalTime.of(22, 0)), window.events.map { it.startTime }.sortedBy { it })
        val imported = CalendarFileImport.read(ics(berlin, mozilla), utc, LocalDate.of(2026, 10, 1))
        assertEquals(0, imported.skipped)
        assertEquals(2, imported.entries.size)
        // Still unknown: read-only, and skipped by an import, as before.
        assertNull(ServerEvents.parse(ics("UID:z\r\nDTSTART;TZID=Customized Time Zone:20261005T090000\r\nSUMMARY:Where"), utc).item)
        assertEquals(1, CalendarFileImport.read(ics(berlin, "UID:z\r\nDTSTART;TZID=Imaginary/Zone:20261005T090000\r\nSUMMARY:Where"), utc,
            LocalDate.of(2026, 10, 1)).skipped)
    }

    // S5: Nextcloud (sabre/dav) writes an apostrophe in a path as %27, OkHttp builds it as '. The same folder either way.
    @Test fun anApostropheInTheUsernameIsTheSameFolder() {
        val account = NextcloudAccount.create("https://cloud.example.com/", "o'brien", "app-password")
        val home = account.calendarsRoot
        val listed = home.resolve("/remote.php/dav/calendars/o%27brien/personal/")!!
        assertTrue(NextcloudClient.inside(listed, home))
        assertFalse(NextcloudClient.inside(home.resolve("/remote.php/dav/calendars/o%27brien/")!!, home)) // the home itself
        assertFalse(NextcloudClient.inside(home.resolve("/remote.php/dav/calendars/someone/personal/")!!, home))
        assertFalse(NextcloudClient.inside(home.resolve("/remote.php/dav/calendars/o%27brien2/personal/")!!, home))
        assertTrue(NextcloudClient.samePath(home.resolve("/remote.php/dav/files/o%27brien/")!!, account.filesRoot))
        assertFalse(NextcloudClient.samePath(home.resolve("/remote.php/dav/files/o%27brien/x/")!!, account.filesRoot))
    }

    // T2: with two-way sync off (its rows are kept on purpose), the cards say nothing about Nextcloud.
    @Test fun cardsAreMarkedOnlyWhileTheirCalendarIsKeptInSync() {
        val rows = listOf(SentMarkRow(1, "u1", null, "acc", "/cal/planner/"), SentMarkRow(2, "u2", SentEvent.CONFLICT, "acc", "/cal/planner/"),
            SentMarkRow(3, "u3", null, "other", "/cal/planner/"))
        val on = CalendarSource(id = 7, account = "acc", href = "/cal/planner/", name = "Planner", enabled = true, sendHere = true)
        assertEquals(mapOf(1L to SyncMark.SYNCED, 2L to SyncMark.PROBLEM), SyncMark.forCards(rows, listOf(on)))
        assertEquals(emptyMap<Long, SyncMark>(), SyncMark.forCards(rows, listOf(on.copy(sendHere = false, enabled = false))))
        assertEquals(emptyMap<Long, SyncMark>(), SyncMark.forCards(rows, emptyList()))
    }

    // S4: an event deleted in Planner whose copy changed on Nextcloud first keeps its row (CHANGED), so the next pull makes it a
    // conflict ("deleted in Planner, changed on Nextcloud") instead of bringing the file in as a new event.
    @Test fun aDeleteRefusedBecauseNextcloudChangedKeepsTheRow() {
        val row = SentEvent(id = 4, itemId = 9, account = "acc", calendar = "/cal/", uid = "planner-1@planner", etag = "\"e1\"", fingerprint = "u1:x")
        assertTrue(CalendarSync.deletable(row))
        assertNull(CalendarSync.afterDelete(row, WriteResult.Ok(null)))
        assertNull(CalendarSync.afterDelete(row, WriteResult.Missing))
        assertEquals(row.copy(problem = SentEvent.CHANGED), CalendarSync.afterDelete(row, WriteResult.Changed))
        // Already known to have changed there: not deleted, and kept.
        val changed = row.copy(problem = SentEvent.CHANGED)
        assertFalse(CalendarSync.deletable(changed))
        assertEquals(changed, CalendarSync.afterDelete(changed, null))
        // A pending write is deleted as before; refused, it's kept too.
        assertTrue(CalendarSync.deletable(row.copy(problem = SentEvent.PENDING)))
        assertEquals(row.copy(problem = SentEvent.CHANGED), CalendarSync.afterDelete(row.copy(problem = SentEvent.PENDING), WriteResult.Changed))
        // Gone there already, read-only there, or never sent: forgotten, as before.
        listOf(row.copy(problem = SentEvent.DELETED), row.copy(problem = SentEvent.DETACHED), row.copy(uid = null)).forEach {
            assertFalse(CalendarSync.deletable(it)); assertNull(CalendarSync.afterDelete(it, null))
        }
    }

    // S3: a repeating event whose start is in UTC repeats on UTC dates (RFC 5545), each then shown on the phone's clock.
    @Test fun aRepeatingUtcEventRepeatsOnUtcDates() {
        val weekly = "UID:u\r\nDTSTART:20260105T230000Z\r\nDTEND:20260106T000000Z\r\nRRULE:FREQ=WEEKLY;BYDAY=MO;COUNT=4\r\nSUMMARY:Standup"
        val entry = CalendarFileImport.read(ics(weekly), sydney, LocalDate.of(2026, 1, 1)).entries.single()
        assertEquals((6..27 step 7).map { LocalDate.of(2026, 1, it) }, entry.dates) // Tuesdays in Sydney
        assertEquals(LocalTime.of(10, 0), entry.item.startTime)
        assertEquals(60, entry.item.durationMinutes)
        val shown = CalendarFileImport.window(ics(weekly), sydney, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31)).events
        assertEquals((6..27 step 7).map { LocalDate.of(2026, 1, it) to LocalTime.of(10, 0) }, shown.map { it.date to it.startTime })
        // The clock follows the instant across a daylight-saving change (Sydney's ends on 5 April 2026): 10:00, then 09:00.
        val acrossDst = "UID:d\r\nDTSTART:20260329T230000Z\r\nDURATION:PT1H\r\nRRULE:FREQ=WEEKLY;COUNT=2\r\nSUMMARY:Call"
        assertEquals(listOf(LocalDate.of(2026, 3, 30) to LocalTime.of(10, 0), LocalDate.of(2026, 4, 6) to LocalTime.of(9, 0)),
            CalendarFileImport.window(ics(acrossDst), sydney, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 4, 30)).events.map { it.date to it.startTime })
        // The series Planner saves never names weekdays its dates don't fall on (Monday and Wednesday in UTC are Tuesday and
        // Thursday in Sydney); a rule that still fits is kept.
        val twice = CalendarFileImport.read(ics(weekly.replace("BYDAY=MO;COUNT=4", "BYDAY=MO,WE;COUNT=4")), sydney, LocalDate.of(2026, 1, 1)).entries.single()
        assertEquals(listOf(6, 8, 13, 15).map { LocalDate.of(2026, 1, it) }, twice.dates)
        assertEquals(RepeatRule.NONE, twice.repeat)
        assertEquals(RepeatRule.WEEKLY, entry.repeat)
        // An excluded date in UTC removes that occurrence.
        val skipped = weekly.replace("SUMMARY", "EXDATE:20260112T230000Z\r\nSUMMARY")
        assertEquals(listOf(6, 20, 27).map { LocalDate.of(2026, 1, it) },
            CalendarFileImport.window(ics(skipped), sydney, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31)).events.map { it.date })
    }
}
