package com.example.itinerary.data

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class CalendarRecurrenceIdentityTest {
    private val utc = ZoneId.of("UTC")
    private val today = LocalDate.of(2026, 10, 5)
    private fun file(vararg events: String) = "BEGIN:VCALENDAR\nVERSION:2.0\n" +
        events.joinToString("\n") { "BEGIN:VEVENT\n$it\nEND:VEVENT" } + "\nEND:VCALENDAR\n"
    private fun read(vararg events: String) = CalendarFileImport.read(file(*events), utc, today)
    private fun instances(result: CalendarFileImport.Result) = CalendarFileImport.events(result.entries, today, true)
        .map { it.date to it.startTime }.toSet()

    @Test fun additionalDatesKeepBothTimesOnTheSameDay() {
        val result = read("UID:r\nSUMMARY:Meeting\nDTSTART:20261006T090000Z\nRDATE:20261007T140000Z,20261007T160000Z")
        assertEquals(setOf(today.plusDays(1) to LocalTime.of(9, 0), today.plusDays(2) to LocalTime.of(14, 0),
            today.plusDays(2) to LocalTime.of(16, 0)), instances(result))
        assertEquals(0, result.skipped)
    }

    @Test fun exclusionsMatchAnInstantInsteadOfDeletingEveryTimeOnItsDate() {
        val result = read("UID:r\nSUMMARY:Meeting\nDTSTART:20261006T090000Z\nRRULE:FREQ=DAILY;COUNT=3\n" +
            "RDATE:20261007T140000Z,20261007T160000Z\nEXDATE;TZID=Europe/Berlin:20261007T160000")
        // Berlin 16:00 is UTC 14:00: only that extra occurrence is excluded.
        assertEquals(setOf(today.plusDays(1) to LocalTime.of(9, 0), today.plusDays(2) to LocalTime.of(9, 0),
            today.plusDays(2) to LocalTime.of(16, 0), today.plusDays(3) to LocalTime.of(9, 0)), instances(result))
    }

    @Test fun anUnmatchedExclusionKeepsTheScheduledTime() {
        val result = read("UID:r\nSUMMARY:Meeting\nDTSTART:20261006T090000Z\nRRULE:FREQ=DAILY;COUNT=3\nEXDATE:20261007T140000Z")
        assertEquals(3, instances(result).size)
        assertTrue(today.plusDays(2) to LocalTime.of(9, 0) in instances(result))
    }

    @Test fun brokenOverridesDoNotHideLaterReadableOverrides() {
        val result = read("UID:r\nSUMMARY:Meeting\nDTSTART:20261006T090000Z\nRRULE:FREQ=DAILY;COUNT=3",
            "UID:r\nRECURRENCE-ID:20261007T090000Z\nDTSTART:BAD",
            "UID:r\nRECURRENCE-ID:20261008T090000Z\nDTSTART:20261008T100000Z\nSUMMARY:Good moved")
        assertEquals(1, result.skipped)
        assertEquals(setOf(today.plusDays(1) to LocalTime.of(9, 0), today.plusDays(3) to LocalTime.of(10, 0)), instances(result))
    }

    @Test fun readableOverrideSurvivesBrokenOrEmptyMaster() {
        val moved = "UID:r\nRECURRENCE-ID:20261006T090000Z\nDTSTART:20261007T100000Z\nSUMMARY:Moved"
        val broken = read("UID:r\nDTSTART:BAD", moved)
        assertEquals(1, broken.skipped)
        assertEquals(setOf(today.plusDays(2) to LocalTime.of(10, 0)), instances(broken))
        val empty = read("UID:r\nDTSTART:20261006T090000Z\nRRULE:FREQ=DAILY;COUNT=1", moved)
        assertEquals(0, empty.skipped)
        assertEquals(instances(broken), instances(empty))
    }

    @Test fun subscriptionKeepsAnOngoingLongTimedOccurrence() {
        val result = CalendarFileImport.window(file("UID:r\nSUMMARY:Visit\nDTSTART:20261001T090000Z\n" +
            "DTEND:20261011T090000Z\nRRULE:FREQ=WEEKLY;COUNT=2"), utc, today, today.plusDays(15))
        assertEquals(listOf(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 8)), result.events.map { it.date })
        assertEquals(LocalDate.of(2026, 10, 11), result.events.first().endDate)
    }

    @Test fun allDayExclusionSurvivesAMidnightDaylightSavingGap() {
        val zone = ZoneId.of("America/Sao_Paulo")
        val result = CalendarFileImport.read(file("UID:r\nDTSTART;VALUE=DATE:20181104\n" +
            "RRULE:FREQ=DAILY;COUNT=3\nEXDATE;VALUE=DATE:20181105"), zone, LocalDate.of(2018, 11, 4))
        assertEquals(listOf(LocalDate.of(2018, 11, 4), LocalDate.of(2018, 11, 6)), result.entries.single().dates)
    }
}
