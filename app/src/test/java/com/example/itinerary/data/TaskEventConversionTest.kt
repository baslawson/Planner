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
        val one = TaskEventConversion.toTask(event, reminders, listOf(file), wholeSeries = false, zone = zone, now = at(today, 0))
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

    // TE-2: across a clock change the event's reminder still fires when the task's did (Sydney goes forward on 4 Oct 2026).
    @Test fun aClockChangeDoesNotMoveTheReminder() {
        val sydney = ZoneId.of("Australia/Sydney")
        val day = LocalDate.of(2026, 10, 5)
        listOf(LocalDateTime.of(2026, 10, 3, 8, 0), LocalDateTime.of(2026, 10, 2, 9, 0), LocalDateTime.of(2026, 10, 2, 20, 0)).forEach { time ->
            val at = time.atZone(sydney).toInstant().toEpochMilli()
            val r = TaskEventConversion.reminderBefore(day, null, at, sydney)!!
            assertEquals(time.toString(), at, reminderTrigger(day, null, r.offsetMinutes, sydney).toInstant().toEpochMilli())
        }
    }

    // TE-10, TE-12, CV-2: a reminder already gone is left out and said; a whole series is due on its next occurrence and
    // takes its reminder from that one; it says what goes; the id can be fixed.
    @Test fun whatAnEventToTaskSays() {
        val day = LocalDate.of(2026, 10, 9)
        val event = ItineraryItem(id = 3, tripId = 1, date = day, startTime = null, title = "Bins", repeatRule = "WEEKLY", seriesId = "s",
            linkedTaskId = "t1")
        val reminders = listOf(Reminder(itemId = 3, amount = 1, unit = ReminderUnit.DAYS))
        val gone = TaskEventConversion.toTask(event, reminders, emptyList(), wholeSeries = false, zone = zone, now = at(day, 0))
        assertNull(gone.result.reminderAt)
        assertTrue(gone.dropped.contains("The reminder: its time has passed."))
        val late = TaskEventConversion.toTask(event, reminders, emptyList(), wholeSeries = true, zone = zone, now = at(day, 0),
            seriesCount = 4, occurrence = event.copy(id = 4, date = day.plusDays(7)), idSeed = "seed")
        assertEquals(at(day.plusDays(6), 9), late.result.reminderAt)
        assertTrue(late.dropped.contains("All 4 events of the series go to Recently deleted, past ones too."))
        assertTrue(late.dropped.contains("Its link to the task it was time for."))
        assertEquals(day.plusDays(7), late.result.dueDate)
        // A multi-day series is due on the last day of its next occurrence, as the notice says.
        val trip = event.copy(endDate = day.plusDays(2))
        assertEquals(day.plusDays(9), TaskEventConversion.toTask(trip, emptyList(), emptyList(), true, zone = zone,
            occurrence = trip.copy(date = day.plusDays(7), endDate = day.plusDays(9))).result.dueDate)
        assertEquals(late.result.id, TaskEventConversion.toTask(event, reminders, emptyList(), true, zone = zone, idSeed = "seed").result.id)
        val early = TaskEventConversion.toTask(event, reminders, emptyList(), wholeSeries = false, zone = zone, now = at(today, 0))
        assertEquals(at(day.minusDays(1), 9), early.result.reminderAt)
        assertTrue(TaskEventConversion.toEvent(PlannerTask(title = "x", dueDate = day), today, zone, hasTimeBlocks = true).dropped
            .contains("Its time blocks stay in the calendar, without their task."))
    }

    // CV-1: when clocks go back, a time that can't be said exactly takes the nearest offset, not one an hour early.
    @Test fun clocksGoingBackTakeTheNearest() {
        val ny = ZoneId.of("America/New_York")
        val day = LocalDate.of(2026, 11, 1)
        val at = LocalDateTime.of(2026, 10, 31, 10, 0).atZone(ny).toInstant().toEpochMilli()
        val r = TaskEventConversion.reminderBefore(day, null, at, ny)!!
        val fires = reminderTrigger(day, null, r.offsetMinutes, ny).toInstant().toEpochMilli()
        assertTrue("fires ${(fires - at) / 60_000} min off", kotlin.math.abs(fires - at) <= 60_000)
        // Times that can be said exactly still are.
        val nine = LocalDateTime.of(2026, 10, 31, 9, 0).atZone(ny).toInstant().toEpochMilli()
        assertEquals(Reminder(itemId = 0, amount = 1, unit = ReminderUnit.DAYS), TaskEventConversion.reminderBefore(day, null, nine, ny))
    }
}
