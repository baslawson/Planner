package com.example.itinerary.data

import org.junit.Assert.*
import org.junit.Test
import java.time.*

class PlanningToolsTest {
    private val day = LocalDate.of(2026, 9, 28)
    private fun event(hour: Int, minute: Int = 0, length: Int? = 60) = ItineraryItem(tripId = 1, date = day,
        startTime = LocalTime.of(hour, minute), durationMinutes = length, title = "Busy")
    private fun gaps(events: List<ItineraryItem>, allDay: Boolean = true, now: LocalDateTime = day.atStartOfDay()) =
        FreeTime.find(events, day, day, LocalTime.of(9, 0), LocalTime.of(17, 0), 60, true, allDay, 30, now)
    @Test fun mergedOverlapAndExactBoundaries() {
        val result = gaps(listOf(event(9, length = 120), event(10, length = 120), event(13)))
        assertEquals(listOf(FreeWindow(day.atTime(12, 0), day.atTime(13, 0)), FreeWindow(day.atTime(14, 0), day.atTime(17, 0))), result)
    }
    @Test fun overnightAllDayUnknownAndBills() {
        val overnight = event(23, length = 720).copy(date = day.minusDays(1))
        assertEquals(day.atTime(11, 0), gaps(listOf(overnight)).first().start)
        val allDay = event(9).copy(startTime = null, durationMinutes = null)
        assertTrue(gaps(listOf(allDay)).isEmpty())
        assertEquals(day.atTime(9, 0), gaps(listOf(allDay), false).first().start)
        assertEquals(day.atTime(9, 30), gaps(listOf(event(9, length = null))).first().start)
        assertEquals(day.atTime(9, 0), gaps(listOf(event(9).copy(category = "Bills"), event(9).copy(skipped = true))).first().start)
    }
    @Test fun pastAndWeekendsAndInvalidRanges() {
        assertEquals(day.atTime(10, 1), gaps(emptyList(), now = day.atTime(10, 0, 1)).first().start)
        val sunday = day.minusDays(1)
        assertTrue(FreeTime.find(emptyList(), sunday, sunday, LocalTime.of(9,0), LocalTime.of(17,0), 60, true, true, 60, sunday.atStartOfDay()).isEmpty())
        assertThrows(IllegalArgumentException::class.java) { FreeTime.find(emptyList(), day, day.plusDays(367), LocalTime.of(9,0), LocalTime.of(17,0), 60, true, true, 60) }
    }
    @Test fun freeWindowsMatchIndependentMinuteOccupancy() {
        val events = listOf(event(9,15,45), event(10,30,75), event(11,15,90), event(15,0,45))
        val found = FreeTime.find(events, day, day, LocalTime.of(9,0), LocalTime.of(17,0), 1, true, true, 60, day.atStartOfDay())
        for (minute in 540 until 1020) {
            val expected = events.none { minute >= it.startTime!!.toSecondOfDay()/60 && minute < it.startTime.toSecondOfDay()/60 + it.durationMinutes!! }
            val actual = found.any { day.atStartOfDay().plusMinutes(minute.toLong()) >= it.start && day.atStartOfDay().plusMinutes(minute.toLong()) < it.end }
            assertEquals("Minute $minute", expected, actual)
        }
    }
    private fun ics(body: String) = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VEVENT\r\n$body\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"
    @Test fun utcTimezoneFoldedAndEscapedText() {
        val invite = CalendarImport.parse(ics("DTSTART:20260928T010000Z\r\nDTEND:20260928T023000Z\r\nSUMMARY:Dentist\\, check\r\n up\r\nDESCRIPTION:One\\nTwo\\;three"), ZoneId.of("Australia/Perth")).single()
        assertEquals(LocalTime.of(9,0), invite.item.startTime)
        assertEquals(90, invite.item.durationMinutes)
        assertEquals("Dentist, checkup", invite.item.title)
        assertEquals("One\nTwo;three", invite.item.notes)
        val zoned = CalendarImport.parse(ics("DTSTART;TZID=Europe/London:20260928T100000\r\nDURATION:PT30M"), ZoneId.of("Australia/Perth")).single()
        assertEquals(LocalTime.of(17,0), zoned.item.startTime)
    }
    @Test fun exclusiveAllDayEndAndFloatingTime() {
        val days = CalendarImport.parse(ics("DTSTART;VALUE=DATE:20260928\r\nDTEND;VALUE=DATE:20260930"))
        assertEquals(listOf(day, day.plusDays(1)), days.map { it.item.date })
        assertTrue(days.all { it.item.startTime == null })
        assertEquals(LocalTime.of(10,0), CalendarImport.parse(ics("DTSTART:20260928T100000"), ZoneOffset.UTC).single().item.startTime)
    }
    @Test fun rejectUnsupportedOrBrokenInvitations() {
        listOf("DTSTART:20260928T100000\r\nRRULE:FREQ=WEEKLY", "DTSTART:20260928T100000\r\nDTEND:20260928T090000",
            "DTSTART;TZID=Imaginary/Zone:20260928T100000", "DTSTART;VALUE=DATE:20260230", "SUMMARY:Missing start",
            "DTSTART:20260928T100000\r\nSTATUS:CANCELLED", "DTSTART:20260928T100000\r\nDURATION:PT25H").forEach {
            assertThrows("Must reject $it", Exception::class.java) { CalendarImport.parse(ics(it)) }
        }
        assertThrows(Exception::class.java) { CalendarImport.parse("not a calendar") }
        assertThrows(Exception::class.java) { CalendarImport.parse("x".repeat(CalendarImport.MAX_BYTES + 1)) }
    }
    @Test fun duplicateResetsIdentityCompletionDatesAndReminder() {
        val task = PlannerTask(title = "Pack", done = true, dueDate = day, reminderAt = 123L, nextTaskId = "successor",
            checklist = listOf(ChecklistEntry("one", "Passport", true)), repeat = "WEEKLY", repeatAnchorDay = 28)
        val copy = task.duplicateForEditing()
        assertNotEquals(task.id, copy.id); assertEquals("Pack", copy.title)
        assertFalse(copy.done); assertFalse(copy.checklist.single().done); assertNotEquals("one", copy.checklist.single().id)
        assertNull(copy.dueDate); assertNull(copy.reminderAt); assertNull(copy.nextTaskId); assertEquals(0, copy.repeatAnchorDay)
        assertEquals("WEEKLY", copy.repeat)
        assertTrue(task.done)
    }
}
