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

class ReviewFindingsDataTest {
    private class Alarms : ReminderAlarms {
        val calls = mutableListOf<String>()
        override fun cancel(reminderId: Long) { calls += "cancel:$reminderId" }
        override fun schedule(item: ItineraryItem, reminder: Reminder) { calls += "schedule:${reminder.id}" }
        override fun reconcile(item: ItineraryItem, reminder: Reminder) { calls += "reconcile:${reminder.id}" }
    }
    private fun fixture(test: suspend (Repository, Alarms) -> Unit) = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(base.cacheDir, "review-findings-data").apply { mkdirs() }
        val context = object : ContextWrapper(base) { override fun getFilesDir() = dir }
        val db = Room.inMemoryDatabaseBuilder(base, AppDatabase::class.java).build()
        val alarms = Alarms()
        try { test(Repository(db, AttachmentStore(context), alarms), alarms) }
        finally { db.close(); dir.deleteRecursively() }
    }
    private fun bill() = ItineraryItem(tripId=0, date=LocalDate.now().plusDays(5), startTime=LocalTime.of(9,0),
        title="Power", category="Bills", billAmountMinor=1000)
    private fun reminder() = Reminder(itemId=0, amount=0, unit=ReminderUnit.MINUTES)

    @Test fun notificationPaymentSurvivesStaleEditorAndRecoveredDraft() = fixture { repo, _ ->
        repo.saveItem(bill(), addedReminders=listOf(reminder()))
        val opened = repo.snapshot().items.single()
        // Exercise the same serialized original state supplied by recovered editors.
        val recovered = DraftCodec.item(DraftCodec.item(opened))
        val alarm = repo.snapshot().reminders.single()
        assertTrue(repo.payFromReminder(alarm.id, billReminderToken(opened, alarm)))
        val paid = repo.snapshot().items.single()
        repo.saveItem(recovered.copy(title="Edited title", notes="Edited notes"),
            options=EventSaveOptions(paymentBaseline=PaymentState.of(recovered)))
        val saved = repo.snapshot().items.single()
        assertTrue(saved.paid); assertEquals(paid.payments, saved.payments)
        assertEquals("Edited title", saved.title); assertEquals("Edited notes", saved.notes)
        assertEquals(1, repo.pendingPayments.value.size)
    }
    @Test fun conflictingPaymentsAndAmountsRollBackWholeEdit() = fixture { repo, _ ->
        repo.saveItem(bill(), addedReminders=listOf(reminder()))
        val opened = repo.snapshot().items.single()
        repo.setPaid(opened.id, true)
        val saved = repo.snapshot()
        for (edit in listOf(opened.copy(payments=listOf(BillPayment(amount=200))), opened.copy(billAmountMinor=2000), opened.copy(category="Other"))) {
            val failure = runCatching { repo.saveItem(edit.copy(title="Must not save"),
                addedReminders=listOf(reminder()), options=EventSaveOptions(paymentBaseline=PaymentState.of(opened))) }.exceptionOrNull()
            assertTrue(failure is PaymentUpdateException)
            assertEquals(saved, repo.snapshot())
        }
    }
    @Test fun freshFinancialEditAndSeriesPreserveOtherOccurrencePayments() = fixture { repo, _ ->
        repo.saveItem(bill(), options=EventSaveOptions(RepeatRule.MONTHLY, 3))
        val opened = repo.snapshot().items.minBy { it.date }
        val other = repo.snapshot().items.maxBy { it.date }
        repo.setPaid(other.id, true)
        repo.saveItem(Payments.setPaid(opened, true).copy(title="Series title"),
            options=EventSaveOptions(entireSeries=true, paymentBaseline=PaymentState.of(opened)))
        val saved = repo.snapshot().items
        assertEquals(2, saved.count { it.paid }); assertTrue(saved.all { it.title == "Series title" })
        assertEquals(1000L, Payments.total(saved.single { it.id==other.id }.payments))
    }
    @Test fun scheduleEditCancelsBeforeReschedulingAndClearsSnooze() = fixture { repo, alarms ->
        repo.saveItem(bill().copy(category="Other"), addedReminders=listOf(reminder()))
        var item = repo.snapshot().items.single()
        val r = repo.snapshot().reminders.single()
        repo.snoozeReminder(r.id, System.currentTimeMillis()+3_600_000)
        val stale = eventReminderToken(item, repo.snapshot().reminders.single())
        alarms.calls.clear()
        repo.saveItem(item.copy(date=item.date.plusDays(1)))
        assertEquals(listOf("cancel:${r.id}", "reconcile:${r.id}"), alarms.calls)
        assertNull(repo.snapshot().reminders.single().snoozedUntil)
        assertFalse(repo.snoozeReminder(r.id, System.currentTimeMillis()+3_600_000, stale))
        item = repo.snapshot().items.single()
        alarms.calls.clear()
        repo.saveItem(item.copy(startTime=LocalTime.NOON))
        assertEquals(listOf("cancel:${r.id}", "reconcile:${r.id}"), alarms.calls)
    }
    @Test fun reconciliationAndTitleOnlyEditPreserveNotificationButSnoozeCannotBeReused() = fixture { repo, alarms ->
        repo.saveItem(bill().copy(category="Other"), addedReminders=listOf(reminder()))
        val item = repo.snapshot().items.single(); val r = repo.snapshot().reminders.single()
        val token = eventReminderToken(item, r)
        alarms.calls.clear(); repo.rescheduleAllReminders()
        assertEquals(listOf("reconcile:${r.id}"), alarms.calls)
        alarms.calls.clear(); repo.saveItem(item.copy(title="New title"))
        assertEquals(listOf("reconcile:${r.id}"), alarms.calls)
        assertTrue(repo.snoozeReminder(r.id, System.currentTimeMillis()+3_600_000, token))
        assertFalse(repo.snoozeReminder(r.id, System.currentTimeMillis()+7_200_000, token))
    }
    @Test fun paymentLimitFailureLeavesRoomReadableAndReminderUnchanged() = fixture { repo, alarms ->
        repo.saveItem(bill().copy(paid=true, payments=List(1000) { BillPayment(id="entry-$it", amount=1) }),
            addedReminders=listOf(reminder()))
        val id = repo.snapshot().items.single().id
        repo.setPaid(id, false)
        val before = repo.snapshot(); val r = before.reminders.single(); val item = before.items.single()
        alarms.calls.clear()
        assertTrue(runCatching { repo.setPaid(id, true) }.exceptionOrNull() is PaymentUpdateException)
        assertTrue(runCatching { repo.payFromReminder(r.id, billReminderToken(item,r)) }.exceptionOrNull() is PaymentUpdateException)
        assertEquals(before, repo.snapshot()); assertTrue(alarms.calls.isEmpty())
        assertEquals(item.payments, Payments.decode(Payments.encode(item.payments)))
        assertTrue(runCatching { Payments.encode(item.payments + BillPayment(amount=1)) }.exceptionOrNull() is PaymentUpdateException)
    }
}
