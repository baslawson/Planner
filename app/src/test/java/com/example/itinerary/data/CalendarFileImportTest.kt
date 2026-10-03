package com.example.itinerary.data

import org.junit.Assert.*
import org.junit.Test
import java.time.*

// Import calendar file (calendar sync step 2): reading exports and invitations, repeats, and what gets saved.
class CalendarFileImportTest {
    private val perth = ZoneId.of("Australia/Perth")
    private val today = LocalDate.of(2026, 9, 29) // a Tuesday
    private fun ics(vararg events: String) = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\n" +
        events.joinToString("") { "BEGIN:VEVENT\r\n$it\r\nEND:VEVENT\r\n" } + "END:VCALENDAR\r\n"
    private fun read(vararg events: String, zone: ZoneId = ZoneOffset.UTC) = CalendarFileImport.read(ics(*events), zone, today)
    private fun single(vararg events: String, zone: ZoneId = ZoneOffset.UTC) = read(*events, zone = zone).entries.single()
    private fun dates(rule: String, start: String = "20260929T090000", extra: String = "") =
        single("UID:r\r\nDTSTART:$start\r\nDTEND:${start.dropLast(6)}100000\r\nRRULE:$rule\r\nSUMMARY:R$extra").dates

    // From the old invitation importer: the same results for a single invitation.
    @Test fun utcTimezoneFoldedAndEscapedText() {
        val invite = single("DTSTART:20260928T010000Z\r\nDTEND:20260928T023000Z\r\nSUMMARY:Dentist\\, check\r\n up\r\nDESCRIPTION:One\\nTwo\\;three", zone = perth).item
        assertEquals(LocalTime.of(9, 0), invite.startTime)
        assertEquals(90, invite.durationMinutes)
        assertEquals("Dentist, checkup", invite.title)
        assertEquals("One\nTwo;three", invite.notes)
        assertEquals(LocalTime.of(17, 0), single("DTSTART;TZID=Europe/London:20260928T100000\r\nDURATION:PT30M", zone = perth).item.startTime)
    }

    @Test fun semicolonInsideAQuotedParameterIsRead() {
        val p = Ics.property("ATTENDEE;CN=\"Smith; Jane\";ROLE=REQ-PARTICIPANT:mailto:jane@example.com")
        assertEquals(mapOf("CN" to "Smith; Jane", "ROLE" to "REQ-PARTICIPANT"), p.params)
        assertEquals("mailto:jane@example.com", p.value)
        assertEquals("Lunch", single("DTSTART:20260928T100000\r\nSUMMARY:Lunch\r\nATTENDEE;CN=\"Smith; Jane\":mailto:jane@example.com").item.title)
        // The parameter after the quoted one still counts: 10:00 in London (summer time) is 09:00 UTC.
        assertEquals(LocalTime.of(9, 0), single("DTSTART;X-NOTE=\"a;b:c\";TZID=Europe/London:20260928T100000").item.startTime)
    }

    @Test fun exclusiveAllDayEndAndFloatingTime() {
        val trip = single("DTSTART;VALUE=DATE:20260928\r\nDTEND;VALUE=DATE:20260930").item
        assertEquals(LocalDate.of(2026, 9, 28), trip.date); assertEquals(LocalDate.of(2026, 9, 29), trip.endDate); assertNull(trip.startTime)
        assertNull(single("DTSTART;VALUE=DATE:20260928\r\nDTEND;VALUE=DATE:20260929").item.endDate)
        assertEquals(LocalTime.of(10, 0), single("DTSTART:20260928T100000").item.startTime)
    }

    @Test fun brokenFilesAndEventsAreRefusedOrSkipped() {
        listOf("DTSTART:20260928T100000\r\nDTEND:20260928T090000", "DTSTART;TZID=Imaginary/Zone:20260928T100000",
            "DTSTART;VALUE=DATE:20260230", "SUMMARY:Missing start", "DTSTART:20260928T100000\r\nSTATUS:CANCELLED").forEach {
            assertThrows("Must refuse a file with only: $it", Exception::class.java) { read(it) }
        }
        assertThrows(Exception::class.java) { CalendarFileImport.read("not a calendar") }
        assertThrows(Exception::class.java) { CalendarFileImport.read("x".repeat(CalendarFileImport.MAX_BYTES + 1)) }
        assertThrows(Exception::class.java) { CalendarFileImport.read(ics("DTSTART:20261001T100000") + "METHOD:CANCEL\r\n") }
        // One bad event among good ones: the good ones stay, the bad one is counted.
        val mixed = read("DTSTART:20261001T100000\r\nSUMMARY:Good", "DTSTART;TZID=Imaginary/Zone:20261001T100000\r\nSUMMARY:Bad",
            "DTSTART:20261002T100000\r\nSTATUS:CANCELLED\r\nSUMMARY:Off")
        assertEquals(listOf("Good"), mixed.entries.map { it.item.title })
        assertEquals(1, mixed.skipped)
    }

    @Test fun timedEventsLongerThanADayBecomeAllDayWithTheirTimesNoted() {
        val item = single("DTSTART:20261005T090000\r\nDURATION:PT49H\r\nSUMMARY:Conference\r\nDESCRIPTION:Hall B").item
        assertNull(item.startTime)
        assertEquals(LocalDate.of(2026, 10, 7), item.endDate)
        assertEquals("Originally from 09:00 on the first day to 10:00 on the last day.\n\nHall B", item.notes)
        MultiDay.validate(item)
    }

    @Test fun commonRepeatPatterns() {
        assertEquals((0L until 5).map { today.plusDays(it) }, dates("FREQ=DAILY;COUNT=5"))
        assertEquals(listOf(29, 1, 3, 5, 7).map { if (it > 28) today else LocalDate.of(2026, 10, it) }, dates("FREQ=DAILY;INTERVAL=2;COUNT=5"))
        // Weekdays: Tue 29 Sep … Mon 5 Oct, skipping the weekend.
        assertEquals(listOf(29, 30).map { LocalDate.of(2026, 9, it) } + listOf(1, 2, 5).map { LocalDate.of(2026, 10, it) },
            dates("FREQ=DAILY;BYDAY=MO,TU,WE,TH,FR;COUNT=5"))
        assertEquals(listOf(LocalDate.of(2026, 9, 29), LocalDate.of(2026, 10, 6), LocalDate.of(2026, 10, 13)), dates("FREQ=WEEKLY;COUNT=3"))
        assertEquals(listOf(LocalDate.of(2026, 9, 29), LocalDate.of(2026, 10, 13), LocalDate.of(2026, 10, 27)), dates("FREQ=WEEKLY;INTERVAL=2;COUNT=3"))
        // Tuesdays and Thursdays until 8 Oct (the end is inclusive).
        assertEquals(listOf(LocalDate.of(2026, 9, 29), LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 6), LocalDate.of(2026, 10, 8)),
            dates("FREQ=WEEKLY;BYDAY=TU,TH;UNTIL=20261008T235959Z"))
        // Monthly on the 31st skips months without one; the first Monday; the last Friday.
        assertEquals(listOf(LocalDate.of(2026, 10, 31), LocalDate.of(2026, 12, 31), LocalDate.of(2027, 1, 31)),
            dates("FREQ=MONTHLY;COUNT=3", start = "20261031T090000"))
        assertEquals(listOf(LocalDate.of(2026, 10, 5), LocalDate.of(2026, 11, 2), LocalDate.of(2026, 12, 7)),
            dates("FREQ=MONTHLY;BYDAY=1MO;COUNT=3", start = "20261005T090000"))
        assertEquals(listOf(LocalDate.of(2026, 10, 30), LocalDate.of(2026, 11, 27)), dates("FREQ=MONTHLY;BYDAY=-1FR;COUNT=2", start = "20261030T090000"))
        assertEquals(listOf(LocalDate.of(2026, 10, 15), LocalDate.of(2026, 11, 15)), dates("FREQ=MONTHLY;BYMONTHDAY=15;COUNT=2", start = "20261015T090000"))
        // Yearly, and the 4th Thursday of November.
        assertEquals(listOf(LocalDate.of(2025, 1, 15), LocalDate.of(2026, 1, 15), LocalDate.of(2027, 1, 15)), dates("FREQ=YEARLY", start = "20250115T090000"))
        // 29 February only in leap years: 2025–2027 have none, and 2028 is past the 12-month limit.
        assertEquals(listOf(LocalDate.of(2024, 2, 29)), dates("FREQ=YEARLY", start = "20240229T090000"))
        assertEquals(listOf(LocalDate.of(2026, 11, 26)), dates("FREQ=YEARLY;BYMONTH=11;BYDAY=4TH", start = "20261126T090000"))
    }

    @Test fun repeatsStopAtTwelveMonthsAndKeepSkippedAndMovedDatesRight() {
        val weekly = dates("FREQ=WEEKLY")
        assertEquals(today, weekly.first())
        assertTrue(weekly.last() <= today.plusMonths(12))
        assertEquals(53, weekly.size)
        // A skipped date (EXDATE) and a moved one (an event with the same UID and a RECURRENCE-ID) come out right.
        val file = read("UID:gym\r\nDTSTART:20261001T070000\r\nDTEND:20261001T080000\r\nRRULE:FREQ=WEEKLY;COUNT=4\r\nEXDATE:20261008T070000\r\nSUMMARY:Gym",
            "UID:gym\r\nRECURRENCE-ID:20261015T070000\r\nDTSTART:20261016T180000\r\nDTEND:20261016T190000\r\nSUMMARY:Gym (moved)")
        val series = file.entries.single { it.item.title == "Gym" }
        assertEquals(listOf(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 22)), series.dates)
        assertEquals(RepeatRule.WEEKLY, series.repeat)
        assertEquals(LocalDate.of(2026, 10, 16), file.entries.single { it.item.title == "Gym (moved)" }.item.date)
        // An unusual rule keeps only its first date, with a note.
        val odd = single("DTSTART:20261001T070000\r\nRRULE:FREQ=HOURLY;COUNT=3\r\nSUMMARY:Odd")
        assertEquals(1, odd.dates.size)
        assertNotNull(odd.note)
        // Dates listed with RDATE are added.
        assertEquals(2, single("DTSTART:20261001T070000\r\nRDATE:20261003T070000\r\nSUMMARY:Extra").dates.size)
    }

    @Test fun daylightSavingDifferencesSplitASeriesByClockTime() {
        // 09:00 London every week; Perth has no daylight saving, so the time in Perth moves when London's clocks change.
        val rows = read("UID:call\r\nDTSTART;TZID=Europe/London:20261020T090000\r\nDURATION:PT30M\r\nRRULE:FREQ=WEEKLY;COUNT=3\r\nSUMMARY:Call", zone = perth).entries
        assertEquals(listOf(LocalTime.of(16, 0), LocalTime.of(17, 0)), rows.map { it.item.startTime }.sortedBy { it })
        assertEquals(3, rows.sumOf { it.dates.size })
    }

    @Test fun pastEventsDuplicatesAndWhatGetsSaved() {
        val file = read("DTSTART:20250101T090000\r\nSUMMARY:Old", "DTSTART:20261001T090000\r\nSUMMARY:Soon",
            "UID:w\r\nDTSTART:20260901T090000\r\nDURATION:PT1H\r\nRRULE:FREQ=WEEKLY;COUNT=8\r\nSUMMARY:Weekly")
        val old = file.entries.single { it.item.title == "Old" }
        val weekly = file.entries.single { it.item.title == "Weekly" }
        assertTrue(old.past(today))
        assertFalse(weekly.past(today))
        assertEquals(4, weekly.datesFor(today, includePast = false).size) // 6, 13, 20 and 27 Oct
        assertEquals(8, weekly.datesFor(today, includePast = true).size)
        val existing = CalendarFileImport.existingKeys(listOf(ItineraryItem(tripId = 1, date = LocalDate.of(2026, 10, 1), startTime = LocalTime.of(9, 0), title = "Soon")))
        assertTrue(CalendarFileImport.duplicate(file.entries.single { it.item.title == "Soon" }, existing, today, false))
        assertFalse(CalendarFileImport.duplicate(weekly, existing, today, false))
        // "Select all" never ticks one already in Planner (it would be added twice), nor a past one unless included.
        val soon = file.entries.single { it.item.title == "Soon" }
        assertEquals(setOf(weekly.id), CalendarFileImport.selectAll(file.entries, today, includePast = false, duplicates = setOf(soon.id)))
        assertEquals(setOf(weekly.id, old.id), CalendarFileImport.selectAll(file.entries, today, includePast = true, duplicates = setOf(soon.id)))
        val saved = CalendarFileImport.events(listOf(weekly, old), today, includePast = false)
        assertEquals(4, saved.size) // the past event has nothing to import unless past events are included
        assertEquals(1, saved.map { it.seriesId }.distinct().size)
        assertNotNull(saved.first().seriesId)
        assertTrue(saved.all { it.repeatRule == "WEEKLY" && it.id == 0L && it.tripId == 0L && it.durationMinutes == 60 })
        val one = CalendarFileImport.events(listOf(old), today, includePast = true).single()
        assertNull(one.seriesId); assertEquals("NONE", one.repeatRule)
        // A series keeps at most 365 dates: the latest ones.
        val daily = single("DTSTART:20250101T090000\r\nRRULE:FREQ=DAILY\r\nSUMMARY:Daily")
        assertEquals(365, daily.datesFor(today, includePast = true).size)
        assertEquals(today.plusMonths(12), daily.datesFor(today, includePast = true).last())
    }

    // RB-1: a daily event since 2010 with no end still reaches today and 12 months ahead; the 5000-date cap keeps the
    // newest dates, not the oldest.
    @Test fun aLongRunningRepeatReachesTodayDespiteTheDateCap() {
        val daily = single("DTSTART:20100101T090000\r\nDURATION:PT1H\r\nRRULE:FREQ=DAILY\r\nSUMMARY:Since 2010")
        assertFalse(daily.past(today))
        // today to 12 months ahead is 366 dates; a series from today keeps today (R5-5) and drops the far end.
        assertEquals(today, daily.datesFor(today, includePast = false).first())
        assertEquals(365, daily.datesFor(today, includePast = false).size)
        assertEquals(today.plusMonths(12).minusDays(1), daily.datesFor(today, includePast = false).last())
        // A daily series starting today with past events included has no past dates either: today stays.
        val fromToday = single("DTSTART:20260929T090000\r\nRRULE:FREQ=DAILY\r\nSUMMARY:From today")
        assertEquals(today, fromToday.datesFor(today, includePast = true).first())
        assertTrue(today in daily.dates)
        assertEquals(365, daily.datesFor(today, includePast = true).size)
        // A series that ended long ago is still listed (as past), with its own dates.
        val old = single("DTSTART:20100101T090000\r\nRRULE:FREQ=WEEKLY;COUNT=3\r\nSUMMARY:Old")
        assertTrue(old.past(today)); assertEquals(3, old.dates.size)
    }

    // RB-2: one malformed line (no ':', or a parameter without '=') makes only its event unreadable, not the file.
    @Test fun aMalformedLineSkipsOnlyItsEvent() {
        val file = read("DTSTART:20261001T100000\r\nSUMMARY:Good",
            "DTSTART:20261002T100000\r\nDESCRIPTION:first part\r\nraw second line without a colon\r\nSUMMARY:Broken",
            "DTSTART;BADPARAM:20261003T100000\r\nSUMMARY:Broken too")
        assertEquals(listOf("Good"), file.entries.map { it.item.title })
        assertEquals(2, file.skipped)
        // Outside an event (the calendar's header) such a line is ignored.
        val header = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nnonsense\r\nBEGIN:VEVENT\r\nDTSTART:20261001T100000\r\nSUMMARY:Good\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"
        assertEquals(0, CalendarFileImport.read(header, ZoneOffset.UTC, today).skipped)
        // A subscribed calendar link is read the same way.
        val window = CalendarFileImport.window(ics("DTSTART:20261001T100000\r\nSUMMARY:Good", "DTSTART:20261002T100000\r\nbroken\r\nSUMMARY:Bad"),
            ZoneOffset.UTC, today, today.plusDays(10))
        assertEquals(listOf("Good"), window.events.map { it.title }); assertEquals(1, window.skipped)
    }

    // R5-1: a raw line break whose next line starts "End:" or "Begin:" (a shift's times in its description) costs only its
    // event, as does an event cut short before the next BEGIN:VEVENT; a server's single item is still refused whole.
    @Test fun aRawBeginOrEndLineSkipsOnlyItsEvent() {
        val file = read("DTSTART:20261001T100000\r\nSUMMARY:Good",
            "DTSTART:20261002T100000\r\nDESCRIPTION:Shift\r\nStart: 9:00\r\nEnd: 17:00\r\nSUMMARY:Shift",
            "DTSTART:20261003T100000\r\nDESCRIPTION:Plan\r\nBegin: 9am\r\nSUMMARY:Begins",
            "DTSTART:20261004T100000\r\nDESCRIPTION:Plan\r\nBegin:Shift\r\nSUMMARY:Named begin",
            "DTSTART:20261005T100000\r\nDESCRIPTION:Plan\r\nEnd:Shift\r\nSUMMARY:Named end",
            "DTSTART:20261006T100000\r\nSUMMARY:Also good")
        assertEquals(listOf("Good", "Also good"), file.entries.map { it.item.title })
        assertEquals(4, file.skipped)
        val cut = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VEVENT\r\nDTSTART:20261001T100000\r\nSUMMARY:Cut short\r\n" +
            "BEGIN:VEVENT\r\nDTSTART:20261002T100000\r\nSUMMARY:Next\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"
        val read = CalendarFileImport.read(cut, ZoneOffset.UTC, today)
        assertEquals(listOf("Next"), read.entries.map { it.item.title }); assertEquals(1, read.skipped)
        // Cut short inside its alarm: still only that event.
        val alarm = "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nDTSTART:20261001T100000\r\nSUMMARY:Alarm cut\r\nBEGIN:VALARM\r\nTRIGGER:-PT5M\r\n" +
            "BEGIN:VEVENT\r\nDTSTART:20261002T100000\r\nSUMMARY:Next\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"
        val readAlarm = CalendarFileImport.read(alarm, ZoneOffset.UTC, today)
        assertEquals(listOf("Next"), readAlarm.entries.map { it.item.title }); assertEquals(1, readAlarm.skipped)
        // Without the callback (a server's reply) such an item is refused, as before.
        val one = Ics.lines("BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nDTSTART:20261002T100000\r\nEnd: 17:00\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n")
        assertThrows(IllegalArgumentException::class.java) { Ics.events(one, 50, "Too many events.") }
    }

    // E9: a one-time import of an all-day event longer than Planner holds keeps the first MultiDay.MAX_DAYS and says so;
    // a subscribed (read-only) calendar shows those days too.
    @Test fun anAllDayEventLongerThanPlannerHoldsIsCutWithANote() {
        val entry = single("UID:l\r\nDTSTART;VALUE=DATE:20261001\r\nDTEND;VALUE=DATE:20281001\r\nSUMMARY:Posting")
        assertEquals(LocalDate.of(2026, 10, 1).plusDays(MultiDay.MAX_DAYS - 1L), entry.item.endDate)
        assertEquals("Lasts 731 days; Planner imports the first ${MultiDay.MAX_DAYS}.", entry.note)
        assertNull(single("UID:s\r\nDTSTART;VALUE=DATE:20261001\r\nDTEND;VALUE=DATE:20261005\r\nSUMMARY:Short").note)
        val shown = CalendarFileImport.window(ics("UID:l\r\nDTSTART;VALUE=DATE:20261001\r\nDTEND;VALUE=DATE:20281001\r\nSUMMARY:Posting"),
            ZoneOffset.UTC, LocalDate.of(2026, 7, 1), LocalDate.of(2027, 10, 31)).events.single()
        assertEquals(LocalDate.of(2026, 10, 1).plusDays(MultiDay.MAX_DAYS - 1L), shown.endDate)
    }

    // U1: a row saved through Edit (perhaps with a new title or time, so no longer a duplicate) is never added again by
    // Add, even while still ticked, and Select all leaves it out.
    @Test fun aRowSavedThroughEditIsNotAddedAgain() {
        val file = read("UID:a\r\nDTSTART:20261001T090000\r\nSUMMARY:Edited", "UID:b\r\nDTSTART:20261002T090000\r\nSUMMARY:Kept")
        val edited = file.entries.single { it.item.title == "Edited" }
        val kept = file.entries.single { it.item.title == "Kept" }
        val all = file.entries.mapTo(HashSet()) { it.id }
        assertEquals(listOf(kept), CalendarFileImport.chosen(file.entries, all, reviewed = setOf(edited.id)))
        assertEquals(file.entries, CalendarFileImport.chosen(file.entries, all, reviewed = emptySet()))
        assertEquals(setOf(kept.id), CalendarFileImport.selectAll(file.entries, today, includePast = false, duplicates = setOf(edited.id)))
    }

    // U-N7: a row saved through Edit and then deleted again (in that editor) is no longer "Added", so it can be added;
    // Undo of that deletion brings the event, and "Added", back.
    @Test fun aSavedRowIsReviewedOnlyWhileItsEventExists() {
        val event = ItineraryItem(id = 41, tripId = 0, date = LocalDate.of(2026, 10, 2), startTime = null, title = "Saved")
        val other = event.copy(id = 7, title = "Other")
        val saved = mapOf(3 to 41L)
        assertEquals(setOf(3), CalendarFileImport.stillSaved(saved, listOf(other, event)))
        assertEquals(emptySet<Int>(), CalendarFileImport.stillSaved(saved, listOf(other)))
        assertEquals(emptySet<Int>(), CalendarFileImport.stillSaved(emptyMap(), listOf(event)))
    }
}
