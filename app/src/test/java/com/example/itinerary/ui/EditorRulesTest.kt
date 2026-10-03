package com.example.itinerary.ui

import com.example.itinerary.data.ItineraryItem
import com.example.itinerary.data.MultiDay
import com.example.itinerary.data.PaymentUpdateException
import com.example.itinerary.data.RepeatRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class EditorRulesTest {
    private val oct3 = LocalDate.of(2026, 10, 3)
    private val oct7 = LocalDate.of(2026, 10, 7)

    @Test fun spanEndKeepsAValidAllDaySpan() {
        assertEquals(oct7, EditorRules.spanEnd(oct3, oct7, null, "Travel"))
        assertNull(EditorRules.spanEnd(oct3, oct7, LocalTime.NOON, "Travel"))
        assertNull(EditorRules.spanEnd(oct3, oct7, null, "Bills"))
        assertNull(EditorRules.spanEnd(oct7, oct3, null, "Travel"))
    }

    @Test fun spanEndDropsASpanLongerThanTheLimit() {
        val start = LocalDate.of(2025, 9, 1)
        assertNull(EditorRules.spanEnd(start, oct7, null, "Travel"))
        val last = start.plusDays((MultiDay.MAX_DAYS - 1).toLong())
        assertEquals(last, EditorRules.spanEnd(start, last, null, "Travel"))
        assertNull(EditorRules.spanEnd(start, last.plusDays(1), null, "Travel"))
    }

    @Test fun movingTheStartKeepsTheHeldSpanLength() {
        val newStart = LocalDate.of(2025, 9, 1)
        assertEquals(LocalDate.of(2025, 9, 5), EditorRules.movedEndDate(oct3, newStart, oct7))
        assertEquals(LocalDate.of(2026, 10, 12), EditorRules.movedEndDate(oct3, LocalDate.of(2026, 10, 8), oct7))
        assertNull(EditorRules.movedEndDate(oct3, newStart, null))
        // A stale end on or before the start is not a span and is left alone.
        assertEquals(oct3, EditorRules.movedEndDate(oct3, newStart, oct3))
    }

    @Test fun nonRepeatingTemplateLeavesAUsableCount() {
        assertEquals("12", EditorRules.templateCount(RepeatRule.NONE, 1, "1"))
        assertEquals("12", EditorRules.templateCount(RepeatRule.NONE, 1, ""))
        assertEquals("20", EditorRules.templateCount(RepeatRule.NONE, 1, "20"))
        assertEquals("5", EditorRules.templateCount(RepeatRule.WEEKLY, 5, "12"))
    }

    @Test fun pickingARepeatResetsAnUnusableCount() {
        assertEquals("12", EditorRules.countForRepeat(RepeatRule.WEEKLY, "1"))
        assertEquals("12", EditorRules.countForRepeat(RepeatRule.WEEKLY, ""))
        assertEquals("30", EditorRules.countForRepeat(RepeatRule.WEEKLY, "30"))
        assertEquals("1", EditorRules.countForRepeat(RepeatRule.NONE, "1"))
    }

    @Test fun saveErrorShowsReadableReasonsOnly() {
        val generic = "Couldn't save the event. Your changes are still here; try again."
        assertEquals("Needs a duration", EditorRules.saveError(IllegalArgumentException("Needs a duration"), false))
        assertEquals("Paid twice", EditorRules.saveError(PaymentUpdateException("Paid twice"), true))
        assertEquals(generic, EditorRules.saveError(IllegalArgumentException("Failed requirement."), false))
        assertEquals(generic, EditorRules.saveError(IllegalStateException("disk"), false))
        assertEquals("Couldn't save the bill. Your changes are still here; try again.", EditorRules.saveError(RuntimeException(), true))
    }

    @Test fun followOnlyWhenTheEventLeavesTheSelectedDay() {
        val trip = ItineraryItem(tripId = 1, date = oct3, startTime = null, endDate = oct7, title = "Trip")
        assertNull(EditorRules.followDate(trip, LocalDate.of(2026, 10, 5)))
        assertEquals(oct3, EditorRules.followDate(trip, LocalDate.of(2026, 10, 8)))
        val overnight = ItineraryItem(tripId = 1, date = oct3, startTime = LocalTime.of(22, 0), durationMinutes = 180, title = "Flight")
        assertNull(EditorRules.followDate(overnight, oct3.plusDays(1)))
        assertEquals(oct3, EditorRules.followDate(overnight, oct3.plusDays(2)))
        val moved = ItineraryItem(tripId = 1, date = oct7, startTime = null, title = "Dinner")
        assertEquals(oct7, EditorRules.followDate(moved, oct3))
        assertNull(EditorRules.followDate(moved, oct7))
    }

    // B16: a snooze while the editor is open changes the record, not its id.
    @Test fun removedReminderStaysRemovedAfterASnooze() {
        val kept = com.example.itinerary.data.Reminder(id = 1, itemId = 9, amount = 1, unit = com.example.itinerary.data.ReminderUnit.DAYS)
        val removed = com.example.itinerary.data.Reminder(id = 2, itemId = 9, amount = 1, unit = com.example.itinerary.data.ReminderUnit.HOURS)
        val snoozed = removed.copy(snoozedUntil = 1_000L)
        assertEquals(listOf(kept), EditorRules.keptReminders(listOf(kept, snoozed), listOf(removed)))
        assertEquals(listOf(kept, snoozed), EditorRules.keptReminders(listOf(kept, snoozed), emptyList()))
    }
    @Test fun removedAttachmentStaysRemovedWhenItsRecordChanges() {
        val kept = com.example.itinerary.data.Attachment(id = 1, itemId = 9, name = "a.pdf", fileName = "a.pdf", mimeType = "application/pdf")
        val removed = com.example.itinerary.data.Attachment(id = 2, itemId = 9, name = "b.pdf", fileName = "b.pdf", mimeType = "application/pdf")
        val indexed = removed.copy(recognizedText = "invoice", textStatus = "INDEXED")
        assertEquals(listOf(kept), EditorRules.keptAttachments(listOf(kept, indexed), listOf(removed)))
        assertEquals(listOf(kept, indexed), EditorRules.keptAttachments(listOf(kept, indexed), emptyList()))
    }

    // E10: an open editor notices when a sync pull changed its event underneath it, in anything sync brings from
    // Nextcloud, and only then.
    @Test fun changedElsewhereIsWhatSyncBringsFromNextcloud() {
        val opened = ItineraryItem(id = 7, tripId = 0, date = oct3, startTime = LocalTime.of(9, 0), durationMinutes = 60, title = "Dentist",
            location = "Clinic", notes = "Bring card")
        assertEquals(false, EditorRules.changedElsewhere(opened, opened))
        assertEquals(false, EditorRules.changedElsewhere(opened, null)) // not read yet, or gone
        listOf(opened.copy(title = "Dentist (web)"), opened.copy(date = oct7), opened.copy(startTime = LocalTime.NOON),
            opened.copy(durationMinutes = 30), opened.copy(location = "Other"), opened.copy(notes = ""),
            opened.copy(startTime = null, durationMinutes = null, endDate = oct7)).forEach {
            assertEquals(it.toString(), true, EditorRules.changedElsewhere(opened, it))
        }
        // Planner-only details changed elsewhere (a bill paid from its notification…) aren't Nextcloud's changes.
        assertEquals(false, EditorRules.changedElsewhere(opened, opened.copy(paid = true, category = "Health", colorIndex = 3)))
        // A new event has nothing stored to change; another row is never compared.
        assertEquals(false, EditorRules.changedElsewhere(opened.copy(id = 0), opened.copy(id = 0, title = "x")))
        assertEquals(false, EditorRules.changedElsewhere(opened, opened.copy(id = 8, title = "x")))
    }

    // AG-2: gone once read as gone (also when already gone on opening); not before the first read, nor for a new event.
    @Test fun deletedElsewhereOnceTheStoredEventIsReadAsGone() {
        val opened = ItineraryItem(id = 7, tripId = 0, date = oct3, startTime = null, title = "Dentist")
        assertEquals(true, EditorRules.deletedElsewhere(opened, loaded = true, stored = null))
        assertEquals(false, EditorRules.deletedElsewhere(opened, loaded = false, stored = null))
        assertEquals(false, EditorRules.deletedElsewhere(opened, loaded = true, stored = opened))
        assertEquals(false, EditorRules.deletedElsewhere(opened.copy(id = 0), loaded = true, stored = null))
        assertEquals("This event was deleted elsewhere. Save keeps your version as a new event.", EditorRules.deletedElsewhereNote(bill = false))
        // The repository's own refusal (deleted between the check and the save) is readable, not "try again".
        val reason = "This event was deleted elsewhere. Save again to keep your version as a new event."
        assertEquals(reason, EditorRules.saveError(IllegalArgumentException(reason), false))
    }

    // U6: a date moved back by Undo in Planner counts as changed too, so the banner doesn't claim it came from Nextcloud.
    @Test fun changedElsewhereBannerDoesNotNameNextcloud() {
        val opened = ItineraryItem(id = 7, tripId = 0, date = oct3, startTime = LocalTime.of(9, 0), title = "Dentist")
        assertEquals(true, EditorRules.changedElsewhere(opened, opened.copy(date = oct3.plusDays(1))))
        assertEquals("This event was changed elsewhere", EditorRules.changedElsewhereBanner(bill = false))
        assertEquals("This bill was changed elsewhere", EditorRules.changedElsewhereBanner(bill = true))
    }

    // U-N1: a task changed underneath its open editor in what Save would write over; done and a snooze alone don't count.
    @Test fun taskChangedElsewhereIsWhatSaveWouldOverwrite() {
        val opened = com.example.itinerary.data.PlannerTask(id = "t", title = "Pay rent", dueDate = oct3, notes = "n")
        assertEquals(false, EditorRules.taskChangedElsewhere(opened, opened))
        assertEquals(false, EditorRules.taskChangedElsewhere(opened, null)) // gone: the deleted banner, not this one
        assertEquals(false, EditorRules.taskChangedElsewhere(opened, opened.copy(done = true, snoozedUntil = 5)))
        // R-5: a reminder moved underneath (a time-zone change), repeat or checklist changed: Save would write them back.
        listOf(opened.copy(title = "Pay rent today"), opened.copy(notes = "m"), opened.copy(dueDate = oct7),
            opened.copy(priority = com.example.itinerary.data.TaskPriority.HIGH), opened.copy(reminderAt = 4),
            opened.copy(repeat = "DAILY"), opened.copy(repeatDays = 3),
            opened.copy(checklist = listOf(com.example.itinerary.data.ChecklistEntry(text = "Bank")))).forEach {
            assertEquals(true, EditorRules.taskChangedElsewhere(opened, it))
        }
        assertEquals(false, EditorRules.taskChangedElsewhere(opened, opened.copy(id = "other", title = "x")))
        assertEquals("This task was changed elsewhere", EditorRules.taskChangedElsewhereBanner())
    }
}
