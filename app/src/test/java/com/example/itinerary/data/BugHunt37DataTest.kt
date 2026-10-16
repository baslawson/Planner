package com.example.itinerary.data

import com.example.itinerary.reminders.MorningSummary
import com.example.itinerary.reminders.MorningSummary.Kind
import com.example.itinerary.reminders.SnoozeChoice
import com.example.itinerary.reminders.snoozeTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

// Bug hunt 37 (data and reminders): the parts that can be checked off the device.
class BugHunt37DataTest {
    private val day = LocalDate.of(2026, 10, 16)

    // R1: an evening summary held past midnight and not shown still comes, for yesterday, within 3 hours of its time.
    @Test fun eveningSummaryHeldPastMidnightCatchesUp() {
        val at = LocalTime.of(22, 30)
        val now = day.atTime(0, 5)
        assertEquals(now.plusMinutes(1) to day.minusDays(1), MorningSummary.nextAlarm(Kind.EVENING, now, at, day.minusDays(2).toString()))
        assertEquals(now.plusMinutes(1) to day.minusDays(1), MorningSummary.nextAlarm(Kind.EVENING, now, at, null))
        // Shown already: tonight's.
        assertEquals(day.atTime(at) to day, MorningSummary.nextAlarm(Kind.EVENING, now, at, day.minusDays(1).toString()))
        // More than 3 hours after yesterday's time (01:30): tonight's.
        assertEquals(day.atTime(at) to day, MorningSummary.nextAlarm(Kind.EVENING, day.atTime(1, 45), at, day.minusDays(2).toString()))
        // The morning's never catches up yesterday's, even at a late time.
        assertEquals(day.atTime(at) to day, MorningSummary.nextAlarm(Kind.MORNING, now, at, day.minusDays(2).toString()))
    }

    // R1: the same-day cases as before (bug hunt 35).
    @Test fun summarySameDayAsBefore() {
        val morning = LocalTime.of(7, 30)
        assertEquals(day.atTime(7, 46) to day, MorningSummary.nextAlarm(Kind.MORNING, day.atTime(7, 45), morning, null))
        assertEquals(day.plusDays(1).atTime(morning) to day.plusDays(1), MorningSummary.nextAlarm(Kind.MORNING, day.atTime(7, 45), morning, day.toString()))
        assertEquals(day.atTime(morning) to day, MorningSummary.nextAlarm(Kind.MORNING, day.atTime(7, 0), morning, day.minusDays(1).toString()))
        assertEquals(day.plusDays(1).atTime(morning) to day.plusDays(1), MorningSummary.nextAlarm(Kind.MORNING, day.atTime(11, 0), morning, day.minusDays(1).toString()))
        // A late evening catch-up a minute before midnight is still today's.
        val evening = LocalTime.of(22, 30)
        val late = day.atTime(23, 59, 30)
        assertEquals(late.plusMinutes(1) to day, MorningSummary.nextAlarm(Kind.EVENING, late, evening, day.minusDays(1).toString()))
        assertEquals(day.atTime(evening) to day, MorningSummary.nextAlarm(Kind.EVENING, day.atTime(20, 0), evening, day.minusDays(1).toString()))
    }

    // R2: Make it a task on an event with a web link and two files: the link goes into the notes and is said.
    @Test fun eventWithLinkBecomesAValidTask() {
        val event = ItineraryItem(id = 5, tripId = 0, date = day, startTime = null, title = "Concert", notes = "Row F")
        val attachments = listOf(
            Attachment(id = 1, itemId = 5, name = "Ticket", fileName = "ticket.pdf", mimeType = "application/pdf"),
            Attachment(id = 2, itemId = 5, name = "Venue", fileName = "", mimeType = "text/uri-list", url = "https://example.com/venue"),
            Attachment(id = 3, itemId = 5, name = "Map", fileName = "map.png", mimeType = "image/png"))
        val converted = TaskEventConversion.toTask(event, emptyList(), attachments, wholeSeries = false, zone = ZoneId.of("UTC"))
        val task = converted.result
        Tasks.validate(task)
        assertEquals(listOf("ticket.pdf", "map.png"), task.attachments.map { it.fileName })
        assertEquals("Row F\n\nhttps://example.com/venue", task.notes)
        assertTrue(converted.dropped.any { it.startsWith("The link:") })
        // Notes with no room left: the link is left out, and said.
        val full = TaskEventConversion.toTask(event.copy(notes = "x".repeat(Tasks.MAX_NOTES)), emptyList(), attachments, wholeSeries = false)
        Tasks.validate(full.result)
        assertEquals(Tasks.MAX_NOTES, full.result.notes.length)
        assertTrue(full.dropped.any { it.contains("no room") })
    }

    // R4: "Tomorrow at 9 am" in the small hours is this morning at 9, and says so.
    @Test fun snoozeToNineInTheSmallHours() {
        val zone = ZoneId.of("Europe/Amsterdam")
        val early = ZonedDateTime.of(day.atTime(0, 30), zone)
        val nine = ZonedDateTime.of(day.atTime(9, 0), zone).toInstant().toEpochMilli()
        assertEquals(nine, SnoozeChoice.TOMORROW.until(early))
        assertEquals(nine, snoozeTime(true, early))
        assertEquals("This morning at 9 am", SnoozeChoice.TOMORROW.label(early))
        val evening = ZonedDateTime.of(day.atTime(23, 30), zone)
        val tomorrowNine = ZonedDateTime.of(day.plusDays(1).atTime(9, 0), zone).toInstant().toEpochMilli()
        assertEquals(tomorrowNine, SnoozeChoice.TOMORROW.until(evening))
        assertEquals(tomorrowNine, snoozeTime(true, evening))
        assertEquals("Tomorrow at 9 am", SnoozeChoice.TOMORROW.label(evening))
        // From 04:00 it is tomorrow again; the other choices keep their labels.
        val four = ZonedDateTime.of(day.atTime(4, 0), zone)
        assertEquals(tomorrowNine, SnoozeChoice.TOMORROW.until(four))
        assertEquals("Tomorrow at 9 am", SnoozeChoice.TOMORROW.label(four))
        assertEquals("1 hour", SnoozeChoice.ONE_HOUR.label(early))
    }
}
