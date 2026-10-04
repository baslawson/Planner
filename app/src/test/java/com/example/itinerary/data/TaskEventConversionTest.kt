package com.example.itinerary.data

import org.junit.Assert.*
import org.junit.Test
import java.time.*

// Wish list #1: a task made into an event and an event into a task — what carries over and what is said to be left.
class TaskEventConversionTest {
    private val zone = ZoneId.of("Australia/Perth")
    private val today = LocalDate.of(2026, 10, 4)
    private fun at(date: LocalDate, h: Int, m: Int = 0) = date.atTime(h, m).atZone(zone).toInstant().toEpochMilli()

    @Test fun aTaskBecomesAnAllDayEvent() {
        val due = LocalDate.of(2026, 10, 9)
        val list = listOf(ChecklistEntry(text = "milk"))
        val task = PlannerTask(title = "Shopping", notes = "list", dueDate = due, checklist = list, repeat = "WEEKLY",
            reminderAt = at(due.minusDays(1), 9))
        val c = TaskEventConversion.toEvent(task, today, zone)
        assertEquals(ItineraryItem(tripId = 0, date = due, startTime = null, title = "Shopping", notes = "list", checklist = list, repeatRule = "WEEKLY"), c.result)
        // The reminder a day before 09:00 is "1 day before" the all-day event.
        assertEquals(listOf(Reminder(itemId = 0, amount = 1, unit = ReminderUnit.DAYS)), c.reminders)
        assertEquals(emptyList<String>(), c.dropped)
    }

    @Test fun whatATaskLeavesBehind() {
        val task = PlannerTask(title = "Water plants", priority = TaskPriority.HIGH, repeat = "AFTER_COMPLETION", repeatDays = 5,
            prerequisiteIds = listOf("x"), reminderAt = at(today, 10))
        val c = TaskEventConversion.toEvent(task, today, zone)
        assertEquals(today, c.result.date); assertEquals("NONE", c.result.repeatRule)
        // 10:00 is after an all-day event's 09:00, so the reminder can't come along.
        assertTrue(c.reminders.isEmpty())
        assertEquals(5, c.dropped.size)
        assertTrue(c.dropped.first().startsWith("No due date"))
        assertTrue(c.dropped.any { it.startsWith("The repeat (Days after completion (5))") })
        assertTrue(c.dropped.any { it == "The high priority." })
    }

    @Test fun reminderUnits() {
        val day = LocalDate.of(2026, 10, 9)
        assertEquals(Reminder(itemId = 0, amount = 2, unit = ReminderUnit.HOURS), TaskEventConversion.reminderBefore(day, LocalTime.of(14, 0), at(day, 12), zone))
        assertEquals(Reminder(itemId = 0, amount = 90, unit = ReminderUnit.MINUTES), TaskEventConversion.reminderBefore(day, LocalTime.of(14, 0), at(day, 12, 30), zone))
        assertEquals(Reminder(itemId = 0, amount = 0, unit = ReminderUnit.DAYS), TaskEventConversion.reminderBefore(day, null, at(day, 9), zone))
        assertNull(TaskEventConversion.reminderBefore(day, null, at(day, 9, 1), zone))
    }

    @Test fun anEventBecomesATask() {
        val day = LocalDate.of(2026, 10, 9)
        val file = Attachment(id = 7, itemId = 3, name = "ticket.pdf", fileName = "f.pdf", mimeType = "application/pdf")
        val event = ItineraryItem(id = 3, tripId = 1, date = day, startTime = LocalTime.of(14, 0), durationMinutes = 60, title = "Dentist",
            notes = "bring card", location = "Clinic", repeatRule = "MONTHLY", seriesId = "s")
        val reminders = listOf(Reminder(itemId = 3, amount = 1, unit = ReminderUnit.DAYS), Reminder(itemId = 3, amount = 2, unit = ReminderUnit.HOURS, ringUntilDismissed = true))
        val one = TaskEventConversion.toTask(event, reminders, listOf(file), wholeSeries = false, zone = zone)
        assertEquals("Dentist", one.result.title); assertEquals("bring card", one.result.notes); assertEquals(day, one.result.dueDate)
        assertEquals("NONE", one.result.repeat)
        assertEquals(listOf(file.copy(id = 0, itemId = 0)), one.result.attachments)
        // The reminder nearest the start comes along as a time: 12:00 that day.
        assertEquals(at(day, 12), one.result.reminderAt)
        assertTrue(one.dropped.contains("The time and length: tasks have a due day only."))
        assertTrue(one.dropped.contains("The place (Clinic)."))
        assertTrue(one.dropped.contains("1 more reminder: a task has one."))
        assertTrue(one.dropped.contains("Ringing until stopped: a task's reminder is a notification."))
        val whole = TaskEventConversion.toTask(event, emptyList(), emptyList(), wholeSeries = true, zone = zone)
        assertEquals("MONTHLY", whole.result.repeat)
        assertTrue(whole.dropped.contains("The series' end: a repeating task goes on until you stop it."))
    }

    @Test fun aMultiDayEventIsDueOnItsLastDay() {
        val event = ItineraryItem(tripId = 0, date = LocalDate.of(2026, 10, 9), endDate = LocalDate.of(2026, 10, 11), startTime = null, title = "Trip")
        val c = TaskEventConversion.toTask(event, emptyList(), emptyList(), wholeSeries = false, zone = zone)
        assertEquals(LocalDate.of(2026, 10, 11), c.result.dueDate)
        assertEquals(listOf("The days before the last: the task is due on the last day."), c.dropped)
    }

    @Test fun whoCanConvert() {
        assertFalse(TaskEventConversion.canMakeEvent(PlannerTask(title = "x", done = true)))
        assertTrue(TaskEventConversion.canMakeEvent(PlannerTask(title = "x")))
        assertFalse(TaskEventConversion.canMakeTask(ItineraryItem(tripId = 0, date = today, startTime = null, title = "Power", category = "Bills")))
    }
}
