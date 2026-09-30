package com.example.itinerary.ui

import com.example.itinerary.data.Attachment
import com.example.itinerary.data.ChecklistEntry
import com.example.itinerary.data.ItineraryItem
import com.example.itinerary.data.PlannerTask
import com.example.itinerary.data.Reminder
import com.example.itinerary.data.ReminderUnit
import com.example.itinerary.data.TaskPriority
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

// The editors' "anything unsaved?" rule: Save greys out and Close closes at once only when it says no.
class UnsavedChangesTest {
    private val item = ItineraryItem(id = 7, tripId = 1, date = LocalDate.of(2026, 10, 3), startTime = LocalTime.of(9, 0), title = "Dentist")
    private val file = Attachment(id = 3, itemId = 7, name = "Letter", fileName = "a.pdf", mimeType = "application/pdf")
    private val threeDays = Reminder(id = 5, itemId = 7, amount = 3, unit = ReminderUnit.DAYS)
    private val saved = EditorRules.EventEdit(item, emptyList(), emptyList(), listOf(threeDays), repeat = "NONE", count = "", duplicating = false,
        texts = listOf("60", "0", "0", ""))

    @Test fun theSavedStateHasNothingUnsaved() {
        assertFalse(EditorRules.eventUnsaved(saved, saved.copy()))
    }

    @Test fun anyStoredFieldIsAChange() {
        assertTrue(EditorRules.eventUnsaved(saved, saved.copy(item = item.copy(title = "Dentist 2"))))
        assertTrue(EditorRules.eventUnsaved(saved, saved.copy(item = item.copy(startTime = null))))
        assertTrue(EditorRules.eventUnsaved(saved, saved.copy(item = item.copy(checklist = listOf(ChecklistEntry("c", "Bring card", false))))))
        assertTrue(EditorRules.eventUnsaved(saved, saved.copy(item = item.copy(paid = true))))
        assertTrue(EditorRules.eventUnsaved(saved, saved.copy(repeat = "WEEKLY", count = "12")))
        assertTrue(EditorRules.eventUnsaved(saved, saved.copy(duplicating = true)))
    }

    @Test fun rawTextCountsEvenWhenItParsesTheSame() {
        // "0" cleared to "" saves the same buffer, but the user did type; an unreadable amount must not be dropped quietly.
        assertTrue(EditorRules.eventUnsaved(saved, saved.copy(texts = listOf("60", "", "0", ""))))
        assertTrue(EditorRules.eventUnsaved(saved, saved.copy(texts = listOf("60", "0", "0", "12.345"))))
    }

    @Test fun attachmentsAddedRemovedOrReReadAreChanges() {
        assertTrue(EditorRules.eventUnsaved(saved, saved.copy(removed = listOf(file))))
        assertTrue(EditorRules.eventUnsaved(saved, saved.copy(added = listOf(file.copy(id = 0, itemId = 0, fileName = "b.jpg")))))
        // Reading text replaces the saved record with a new copy holding the text.
        assertTrue(EditorRules.eventUnsaved(saved, saved.copy(removed = listOf(file),
            added = listOf(file.copy(id = 0, itemId = 0, recognizedText = "Due 5 Oct", textStatus = "READY")))))
        // A file added and removed again leaves nothing to save.
        val photo = file.copy(id = 0, itemId = 0, fileName = "c.jpg")
        assertFalse(EditorRules.eventUnsaved(saved, saved.copy(added = listOf(photo)).copy(added = emptyList())))
        // Removed records compare by id: the saved record changing meanwhile (text read in the background) is no edit.
        val gone = saved.copy(removed = listOf(file))
        assertFalse(EditorRules.eventUnsaved(gone, gone.copy(removed = listOf(file.copy(recognizedText = "Due 5 Oct")))))
        assertFalse(EditorRules.eventUnsaved(saved.copy(added = listOf(file.copy(id = 9))), saved.copy(added = listOf(file.copy(id = 0, itemId = 0)))))
    }

    @Test fun remindersCompareByWhatTheyDo() {
        assertTrue(EditorRules.eventUnsaved(saved, saved.copy(reminders = emptyList())))
        assertTrue(EditorRules.eventUnsaved(saved, saved.copy(reminders = listOf(threeDays, Reminder(itemId = 0, amount = 1, unit = ReminderUnit.HOURS)))))
        assertTrue(EditorRules.eventUnsaved(saved, saved.copy(reminders = listOf(threeDays.copy(id = 0, ringUntilDismissed = true)))))
        // Ring switched on and off again leaves a replacement copy that does the same thing, and a snooze isn't an edit.
        assertFalse(EditorRules.eventUnsaved(saved, saved.copy(reminders = listOf(threeDays.copy(id = 0, itemId = 0)))))
        assertFalse(EditorRules.eventUnsaved(saved, saved.copy(reminders = listOf(threeDays.copy(snoozedUntil = 99L)))))
        // Order doesn't matter.
        val two = saved.copy(reminders = listOf(threeDays, Reminder(id = 6, itemId = 7, amount = 1, unit = ReminderUnit.HOURS)))
        assertFalse(EditorRules.eventUnsaved(two, two.copy(reminders = two.reminders.reversed())))
    }

    @Test fun aRecoveredDraftIsAlwaysUnsaved() {
        assertTrue(EditorRules.eventUnsaved(saved, saved.copy(), recovered = true))
        val task = PlannerTask(id = "t", title = "Pay rent")
        assertTrue(EditorRules.taskUnsaved(task, task, recovered = true))
    }

    @Test fun taskChangesAndWhitespace() {
        val task = PlannerTask(id = "t", title = "Pay rent", notes = "Online", checklist = listOf(ChecklistEntry("c", "Log in", false)))
        assertFalse(EditorRules.taskUnsaved(task, task.copy()))
        // Save trims these, so a trailing space alone stores nothing new.
        assertFalse(EditorRules.taskUnsaved(task, task.copy(title = "Pay rent ", notes = "Online\n",
            checklist = listOf(ChecklistEntry("c", "Log in ", false)))))
        assertTrue(EditorRules.taskUnsaved(task, task.copy(title = "Pay the rent")))
        assertTrue(EditorRules.taskUnsaved(task, task.copy(dueDate = LocalDate.of(2026, 10, 3))))
        assertTrue(EditorRules.taskUnsaved(task, task.copy(priority = TaskPriority.HIGH)))
        assertTrue(EditorRules.taskUnsaved(task, task.copy(reminderAt = 1L)))
        assertTrue(EditorRules.taskUnsaved(task, task.copy(checklist = listOf(ChecklistEntry("c", "Log in", true)))))
        assertTrue(EditorRules.taskUnsaved(task, task.copy(attachments = listOf(file))))
        assertTrue(EditorRules.taskUnsaved(task, task.copy(prerequisiteIds = listOf("x"))))
        assertTrue(EditorRules.taskUnsaved(task, task.copy(repeat = "WEEKLY")))
        assertTrue(EditorRules.taskUnsaved(task, task.copy(repeatDays = -1)))
    }
}
