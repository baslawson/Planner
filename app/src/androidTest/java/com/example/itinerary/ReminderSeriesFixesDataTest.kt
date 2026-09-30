package com.example.itinerary

import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.example.itinerary.data.*
import com.example.itinerary.reminders.ReminderAlarms
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.LocalTime

// Move to tomorrow, reminder snoozes, time-zone deliveries, task snoozes and entire-series saves (repository level).
class ReminderSeriesFixesDataTest {
    private class Alarms : ReminderAlarms {
        val scheduled = mutableListOf<Long>()
        override fun cancel(reminderId: Long) {}
        override fun schedule(item: ItineraryItem, reminder: Reminder) { scheduled += reminder.id }
        override fun reconcile(item: ItineraryItem, reminder: Reminder) { scheduled += reminder.id }
    }
    private fun fixture(test: suspend (Repository, Alarms, AttachmentStore) -> Unit) = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(base.cacheDir, "reminder-series-fixes").apply { mkdirs() }
        val context = object : ContextWrapper(base) { override fun getFilesDir() = dir }
        val db = Room.inMemoryDatabaseBuilder(base, AppDatabase::class.java).build()
        val alarms = Alarms()
        val store = AttachmentStore(context)
        try { test(Repository(db, store, alarms), alarms, store) }
        finally { db.close(); dir.deleteRecursively() }
    }
    private fun event(date: LocalDate = LocalDate.now().plusDays(3)) = ItineraryItem(tripId = 0, date = date, startTime = LocalTime.of(9, 0), title = "Dentist")
    private fun reminder() = Reminder(itemId = 0, amount = 1, unit = ReminderUnit.HOURS)

    @Test fun moveToTomorrowAndUndoClearTheSnooze() = fixture { repo, _, _ ->
        repo.saveItem(event(LocalDate.now()), addedReminders = listOf(reminder()))
        val item = repo.snapshot().items.single()
        val alarm = repo.snapshot().reminders.single()
        repo.snoozeReminder(alarm.id, System.currentTimeMillis() + 600_000)
        repo.moveToTomorrow(item.id)
        assertNull(repo.snapshot().reminders.single().snoozedUntil)
        repo.snoozeReminder(alarm.id, System.currentTimeMillis() + 600_000)
        assertTrue(repo.undoMove(repo.pendingMoves.value.single().token))
        assertNull(repo.snapshot().reminders.single().snoozedUntil)
    }

    @Test fun reminderReaddedFromEditorDropsItsSnooze() = fixture { repo, _, _ ->
        repo.saveItem(event(), addedReminders = listOf(reminder()))
        val item = repo.snapshot().items.single()
        val alarm = repo.snapshot().reminders.single()
        repo.snoozeReminder(alarm.id, System.currentTimeMillis() + 600_000)
        val snoozed = repo.snapshot().reminders.single()
        // What the editor does when "ring until dismissed" is toggled.
        repo.saveItem(item, addedReminders = listOf(snoozed.copy(id = 0, itemId = 0, ringUntilDismissed = true)), removedReminders = listOf(snoozed))
        val saved = repo.snapshot().reminders.single()
        assertTrue(saved.ringUntilDismissed); assertNull(saved.snoozedUntil)
    }

    @Test fun deliveredReminderIsNotScheduledAgain() = fixture { repo, alarms, _ ->
        repo.saveItem(event(), addedReminders = listOf(reminder()))
        val item = repo.snapshot().items.single()
        val alarm = repo.snapshot().reminders.single()
        val trigger = reminderTrigger(item.date, item.startTime, alarm.offsetMinutes).toInstant().toEpochMilli()
        var shown = 0
        // A later time: as after an eastward time-zone change, when the old alarm fires after the recomputed time.
        repo.deliverReminder(alarm.id, trigger + 3_600_000) { _, _ -> shown++ }
        assertEquals(0, shown) // Still in the future: never early.
        repo.deliverReminder(alarm.id, trigger) { _, _ -> shown++ }
        assertEquals(1, shown)
        alarms.scheduled.clear()
        repo.rescheduleAllReminders()
        repo.saveItem(repo.snapshot().items.single().copy(title = "Dentist (moved room)"))
        assertEquals(emptyList<Long>(), alarms.scheduled)
        repo.saveItem(repo.snapshot().items.single().copy(startTime = LocalTime.of(10, 0)))
        assertEquals(listOf(alarm.id), alarms.scheduled)
    }

    @Test fun taskSnoozeKeepsTheBaseReminder() = fixture { repo, _, _ ->
        val due = LocalDate.now()
        val base = System.currentTimeMillis() - 60_000
        repo.saveTask(PlannerTask(id = "t", title = "Pills", dueDate = due, repeat = "DAILY", reminderAt = base))
        val until = System.currentTimeMillis() + 3_600_000
        assertTrue(repo.actOnTaskReminder("t", base, until))
        val snoozed = repo.snapshot().tasks.single()
        assertEquals(base, snoozed.reminderAt); assertEquals(until, snoozed.snoozedUntil)
        // The old alarm is gone; the snoozed one is the one that counts.
        assertFalse(repo.actOnTaskReminder("t", base))
        repo.saveTask(snoozed.copy(title = "Pills (morning)"), create = false)
        assertEquals(until, repo.snapshot().tasks.single().snoozedUntil)
        repo.setTaskDone("t", true)
        val next = repo.snapshot().tasks.single { it.id != "t" }
        assertEquals(base + 86_400_000, next.reminderAt); assertNull(next.snoozedUntil)
    }

    @Test fun entireSeriesSaveLeavesOtherOccurrencesOwnChildren() = fixture { repo, _, store ->
        repo.saveItem(event(), addedReminders = listOf(reminder()), options = EventSaveOptions(RepeatRule.WEEKLY, 3))
        val items = repo.snapshot().items.sortedBy { it.date }
        val receipt = Attachment(itemId = items[2].id, name = "receipt.pdf", fileName = "receipt-${System.nanoTime()}.pdf", mimeType = "application/pdf")
        store.writableFileFor(receipt.fileName).writeText("receipt")
        repo.saveItem(items[2], added = listOf(receipt))
        repo.saveItem(items[0].copy(title = "Dentist (new clinic)"), options = EventSaveOptions(entireSeries = true))
        val after = repo.snapshot()
        assertTrue(after.items.all { it.title == "Dentist (new clinic)" })
        assertEquals(listOf(items[2].id), after.attachments.map { it.itemId })
        assertTrue(store.fileFor(receipt.fileName).exists())
        assertEquals(3, after.reminders.size)
    }

    @Test fun seriesBillWithPaymentsKeepsItsAmountWhenTheSeriesChanges() = fixture { repo, _, _ ->
        repo.saveItem(event().copy(category = "Bills", billAmountMinor = 1000), options = EventSaveOptions(RepeatRule.MONTHLY, 3))
        val items = repo.snapshot().items.sortedBy { it.date }
        repo.setPaid(items[1].id, true)
        for (edit in listOf(items[0].copy(billAmountMinor = 1500), items[0].copy(billAmountMinor = 500), items[0].copy(billCurrency = "EUR"))) {
            val opened = repo.snapshot().items.single { it.id == items[0].id }
            repo.saveItem(edit.copy(payments = opened.payments, paid = opened.paid), options = EventSaveOptions(entireSeries = true))
            val paid = repo.snapshot().items.single { it.id == items[1].id }
            assertTrue(paid.paid); assertEquals(1000L, paid.billAmountMinor); assertEquals("AUD", paid.billCurrency)
        }
    }

    @Test fun timeBlockErrorsSayWhatIsWrong() = fixture { repo, _, _ ->
        repo.saveTask(PlannerTask(id = "t", title = "Write report"))
        val noDuration = runCatching { repo.saveItem(event().copy(linkedTaskId = "t")) }.exceptionOrNull()
        assertEquals("A time block needs a start time and a duration.", noDuration?.message)
        val missing = runCatching { repo.saveItem(event().copy(linkedTaskId = "gone", durationMinutes = 30)) }.exceptionOrNull()
        assertEquals("The task this time block belongs to was deleted.", missing?.message)
    }
}
