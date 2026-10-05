package com.example.itinerary.data

import org.junit.Assert.*
import org.junit.Test
import java.time.*

// Calendar sync: bug hunt 16 fixes of 6 October 2026. The sync itself is exercised in the instrumented CalendarTwoWayTest.
class CalendarFixesOct6Test {
    private val perth = ZoneId.of("Australia/Perth")
    private val march5 = LocalDate.of(2026, 3, 5)
    private fun inRanges(date: LocalDate, ranges: List<Pair<LocalDate, LocalDate>>) = ranges.any { (start, end) -> date >= start && date < end }

    // E16-1: a past event only noted (after reconnecting, choosing the calendar again or a restore) and then moved two or
    // more days is looked for on the days it was noted on too, where its file is, not only around its new days.
    @Test fun aNotedEventMovedFarIsStillLookedForWhereItsFileIs() {
        val noted = ItineraryItem(id = 1, tripId = 0, date = march5, startTime = LocalTime.of(9, 0), durationMinutes = 60, title = "Dentist")
        val row = SentEvent(id = 7, itemId = 1, account = "a", calendar = "/c/", uid = null, fingerprint = CalendarSync.fingerprint(noted),
            ics = CalendarSync.notedDays(noted))
        assertEquals(march5 to march5, CalendarSync.notedDays(row))
        // Moved later in March, or to next week (inside the pull window): the 5 March file is read either way.
        for (moved in listOf(noted.copy(date = LocalDate.of(2026, 3, 20)), noted.copy(date = LocalDate.of(2026, 10, 12)))) {
            val ranges = CalendarSync.lookupRanges(listOf(moved), listOfNotNull(CalendarSync.notedDays(row)))
            assertTrue("$ranges", inRanges(march5, ranges))
            assertTrue("$ranges", inRanges(moved.date, ranges))
        }
        // A multi-day one: all its noted days.
        val trip = noted.copy(startTime = null, durationMinutes = null, endDate = march5.plusDays(3))
        assertEquals(march5 to march5.plusDays(3), CalendarSync.notedDays(CalendarSync.notedDays(trip)))
        // Only a noted row has days; a synced row's text is its file. A row noted before days were kept has none (looked
        // for around its new days only, as before), and text that isn't two days in order is ignored.
        assertNull(CalendarSync.notedDays(row.copy(uid = "planner-1@planner")))
        assertNull(CalendarSync.notedDays(row.copy(ics = null)))
        listOf("BEGIN:VCALENDAR", "2026-03-05", "2026-03-05/2026-03-01", "2026-13-01/2026-13-02").forEach { assertNull(it, CalendarSync.notedDays(it)) }
    }

    // E16-2: an event in a zone Planner doesn't know (Exchange's "Customized Time Zone", defined only by the file's own
    // VTIMEZONE) shows in a subscribed link and among the synced calendar's read-only events at the phone's time, as a
    // read-only Nextcloud calendar shows it, instead of vanishing without a word.
    @Test fun anUnknownTimeZoneIsShownAtThePhonesTimeNotDropped() {
        val zone = "TZID=Customized Time Zone"
        val text = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Microsoft Corporation//Outlook 16.0 MIMEDIR//EN\r\n" +
            "BEGIN:VTIMEZONE\r\nTZID:Customized Time Zone\r\nBEGIN:STANDARD\r\nDTSTART:16010101T000000\r\nTZOFFSETFROM:+0800\r\nTZOFFSETTO:+0800\r\n" +
            "END:STANDARD\r\nEND:VTIMEZONE\r\n" +
            "BEGIN:VEVENT\r\nUID:board\r\nDTSTART;$zone:20261012T090000\r\nDTEND;$zone:20261012T100000\r\nSUMMARY:Board meeting\r\nEND:VEVENT\r\n" +
            "BEGIN:VEVENT\r\nUID:standup\r\nDTSTART;$zone:20261013T083000\r\nDTEND;$zone:20261013T084500\r\nRRULE:FREQ=WEEKLY;COUNT=3\r\n" +
            "EXDATE;$zone:20261020T083000\r\nSUMMARY:Stand-up\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"
        val window = CalendarFileImport.window(text, perth, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31))
        assertEquals(0, window.skipped)
        assertEquals(listOf(Triple(LocalDate.of(2026, 10, 12), LocalTime.of(9, 0), 60), Triple(LocalDate.of(2026, 10, 13), LocalTime.of(8, 30), 15),
            Triple(LocalDate.of(2026, 10, 27), LocalTime.of(8, 30), 15)), window.events.map { Triple(it.date, it.startTime, it.durationMinutes) }.sortedBy { it.first })
        // The same as a read-only Nextcloud calendar shows the single one.
        val board = window.events.single { it.title == "Board meeting" }
        val reader = OutsideEventReader.read(listOf(text.substringBefore("BEGIN:VEVENT\r\nUID:standup") + "END:VCALENDAR\r\n"), perth).events.single()
        assertEquals(reader, board)
        // In the synced calendar it stays read-only (Planner doesn't guess the time of an event it would write back).
        assertNull(ServerEvents.parse(text.substringBefore("BEGIN:VEVENT\r\nUID:standup") + "END:VCALENDAR\r\n", perth).item)
        // A file import still skips it and says so, as before.
        assertEquals(2, CalendarFileImport.read(text.replace("END:VCALENDAR", "BEGIN:VEVENT\r\nUID:ok\r\nDTSTART:20261014T010000Z\r\n" +
            "SUMMARY:Fine\r\nEND:VEVENT\r\nEND:VCALENDAR"), perth, LocalDate.of(2026, 10, 1)).skipped)
    }
}
