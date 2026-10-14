package com.example.itinerary.data

import com.example.itinerary.ui.EditorRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

// Bug hunt 27 (2026-10-14).
class BugHunt27Test {
    private val bill = ItineraryItem(id = 7, tripId = 0, date = LocalDate.of(2026, 10, 20), startTime = LocalTime.of(9, 0),
        title = "Power", category = "Bills", billAmountMinor = 12_345)
    private val reminder = Reminder(id = 3, itemId = 7, amount = 1, unit = ReminderUnit.DAYS)

    // R1: the sound (and, for a bill, notes, checklist or colour) changing doesn't make Snooze or Mark paid stale; what is
    // paid or when it rings does.
    @Test fun remindersKeepTheirTokenWhenOnlyTheSoundOrNotesChange() {
        val louder = reminder.copy(ringUntilDismissed = true, ringSeconds = 60)
        assertEquals(eventReminderToken(bill, reminder), eventReminderToken(bill, louder))
        assertEquals(billReminderToken(bill, reminder), billReminderToken(bill.copy(notes = "Account 42", colorIndex = 3,
            checklist = listOf(ChecklistEntry("a", "Check meter", true))), louder))
        assertNotEquals(billReminderToken(bill, reminder), billReminderToken(bill.copy(billAmountMinor = 555), reminder))
        assertNotEquals(billReminderToken(bill, reminder), billReminderToken(bill.copy(paid = true), reminder))
        assertNotEquals(eventReminderToken(bill, reminder), eventReminderToken(bill.copy(date = bill.date.plusDays(1)), reminder))
        assertNotEquals(eventReminderToken(bill, reminder), eventReminderToken(bill, reminder.copy(snoozedUntil = 1L)))
        assertNotEquals(eventReminderToken(bill, reminder), eventReminderToken(bill, reminder.copy(amount = 2)))
    }

    // E2/E4: shared text is cut to the limit without splitting an emoji, and how much fitted is told.
    @Test fun sharedTextIsCutCleanlyAndSaysHowMuchFitted() {
        val emoji = "😀" // one emoji, two chars
        val cut = ShareTargets.withText("", "abc$emoji", 4)
        assertEquals("abc", cut)
        assertEquals(ShareTargets.TextFit.PART, ShareTargets.textFit("", "abc$emoji", 4))
        assertEquals(ShareTargets.TextFit.ALL, ShareTargets.textFit("notes", "more", 100))
        assertEquals(ShareTargets.TextFit.NONE, ShareTargets.textFit("x".repeat(10), "more", 10))
        assertEquals(20_000, Tasks.MAX_NOTES)
        assertTrue(ShareTargets.withText("n".repeat(5_000), "e".repeat(16_000), Tasks.MAX_NOTES).length <= Tasks.MAX_NOTES)
    }

    // E8: a short month name before a year is a month, as the full name is; alone it stays a word.
    @Test fun searchReadsShortMonthNamesWithAYear() {
        val today = LocalDate.of(2026, 10, 14)
        val plans = listOf(Trip(id = 1, name = "Plan", destination = "", startDate = today, endDate = today))
        val events = listOf(
            ItineraryItem(id = 1, tripId = 1, date = LocalDate.of(2026, 12, 3), startTime = null, title = "Dentist"),
            ItineraryItem(id = 2, tripId = 1, date = LocalDate.of(2026, 11, 3), startTime = null, title = "Dentist"),
            ItineraryItem(id = 3, tripId = 1, date = LocalDate.of(2026, 9, 9), startTime = null, title = "Gym"),
        )
        fun hits(query: String) = Search.run(query, emptySet(), plans, events, emptyList(), today).hits.map { it.item.id }.toSet()
        assertEquals(setOf(1L), hits("dec 2026"))
        assertEquals(hits("december 2026"), hits("dec 2026"))
        assertEquals(setOf(3L), hits("sept 2026"))
    }

    // S7: an exclusion written as a whole day on a timed series, or as UTC midnight on an all-day series, still excludes.
    @Test fun calendarFileExclusionsWrittenAsADayStillExclude() {
        fun ics(event: String) = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VEVENT\r\n$event\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"
        val today = LocalDate.of(2026, 9, 29)
        val timed = CalendarFileImport.read(ics("UID:t\r\nDTSTART:20261001T090000\r\nDTEND:20261001T100000\r\n" +
            "RRULE:FREQ=WEEKLY;COUNT=3\r\nEXDATE;VALUE=DATE:20261008\r\nSUMMARY:Gym"), ZoneOffset.UTC, today).entries.single()
        assertEquals(listOf(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 15)), timed.dates)
        val allDay = CalendarFileImport.read(ics("UID:a\r\nDTSTART;VALUE=DATE:20261001\r\nDTEND;VALUE=DATE:20261002\r\n" +
            "RRULE:FREQ=WEEKLY;COUNT=3\r\nEXDATE:20261008T000000Z\r\nSUMMARY:Bins"), java.time.ZoneId.of("Australia/Perth"), today).entries.single()
        assertEquals(listOf(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 15)), allDay.dates)
    }

    // E1: a file added to a task meanwhile counts as a change elsewhere (Save would drop it).
    @Test fun aFileAddedMeanwhileCountsAsAChangeElsewhere() {
        val task = PlannerTask(id = "t", title = "Passport")
        val photo = Attachment(itemId = 0, name = "photo.jpg", fileName = "p.jpg", mimeType = "image/jpeg")
        assertTrue(EditorRules.taskChangedElsewhere(task, task.copy(attachments = listOf(photo))))
        assertEquals(false, EditorRules.taskChangedElsewhere(task, task.copy(done = true)))
    }
}
