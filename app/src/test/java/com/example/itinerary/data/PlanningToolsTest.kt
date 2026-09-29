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
