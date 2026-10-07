package com.example.itinerary.data

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

// Calendar items from bug hunt 18 (R18-S1), as approved.
class HuntEighteenCalendarTest {
    private fun ics(vararg events: String) = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\n" +
        events.joinToString("") { "BEGIN:VEVENT\r\n$it\r\nEND:VEVENT\r\n" } + "END:VCALENDAR\r\n"
    private val from = LocalDate.of(2026, 7, 1)
    private val until = LocalDate.of(2027, 10, 31)

    // R18-S1: a subscribed calendar's repeating events whose rule Planner can't follow are counted (and as skipped), not
    // dropped without a word; the ones it can follow, and single events, aren't.
    @Test fun unreadableRepeatsAreCounted() {
        val window = CalendarFileImport.window(ics(
            "UID:a\r\nDTSTART:20250106T090000Z\r\nRRULE:FREQ=MONTHLY;BYDAY=MO;BYSETPOS=1\r\nSUMMARY:First Monday",
            "UID:b\r\nDTSTART:20250106T090000Z\r\nRRULE:FREQ=MONTHLY;BYDAY=1MO,3MO\r\nSUMMARY:First and third",
            "UID:c\r\nDTSTART:20250101T090000Z\r\nRRULE:FREQ=MONTHLY;BYMONTHDAY=1,15\r\nSUMMARY:Twice a month",
            "UID:d\r\nDTSTART:20261001T090000Z\r\nRRULE:FREQ=DAILY;BYHOUR=9,17\r\nSUMMARY:Twice a day",
            "UID:e\r\nDTSTART:20250106T090000Z\r\nRRULE:FREQ=WEEKLY\r\nSUMMARY:Weekly",
            "UID:f\r\nDTSTART:20261002T090000Z\r\nSUMMARY:Once"), ZoneOffset.UTC, from, until)
        assertEquals(4, window.unreadRepeats)
        assertEquals(4, window.skipped)
        // What can be shown still is: the weekly one, the single one, and the first date of the one starting in the window.
        assertTrue(window.events.any { it.title == "Weekly" })
        assertEquals(listOf(LocalDate.of(2026, 10, 1)), window.events.filter { it.title == "Twice a day" }.map { it.date })
        assertEquals(1, window.events.count { it.title == "Once" })
        assertTrue(window.events.none { it.title in setOf("First Monday", "First and third", "Twice a month") })
    }

    @Test fun aCalendarWithEveryRepeatReadableHasNothingToSay() {
        val window = CalendarFileImport.window(ics("UID:e\r\nDTSTART:20250106T090000Z\r\nRRULE:FREQ=WEEKLY\r\nSUMMARY:Weekly"),
            ZoneOffset.UTC, from, until)
        assertEquals(0, window.unreadRepeats); assertEquals(0, window.skipped)
        assertNull(CalendarSync.unreadRepeatsNote(0))
    }

    @Test fun theNoteOnTheCalendarsRow() {
        assertEquals("1 repeating event repeats in a way Planner can't show.", CalendarSync.unreadRepeatsNote(1))
        assertEquals("3 repeating events repeat in a way Planner can't show.", CalendarSync.unreadRepeatsNote(3))
    }
}
