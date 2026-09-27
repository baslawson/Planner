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

class BillConveniencesDataTest {
    private fun fixture(test: suspend (Repository) -> Unit) = runBlocking {
        val base=InstrumentationRegistry.getInstrumentation().targetContext
        val dir=File(base.cacheDir,"bill-conveniences-data").apply { mkdirs() }
        val context=object:ContextWrapper(base) { override fun getFilesDir()=dir }
        val db=Room.inMemoryDatabaseBuilder(base,AppDatabase::class.java).build()
        val repo=Repository(db,AttachmentStore(context),object:ReminderAlarms {
            override fun schedule(item:ItineraryItem,reminder:Reminder) { assertFalse(item.paid || item.skipped) }
            override fun cancel(reminderId:Long) {}
        })
        try { test(repo) } finally { db.close();dir.deleteRecursively() }
    }
    private fun bill()=ItineraryItem(tripId=0,date=LocalDate.now().plusDays(8),startTime=null,title="Power",category="Bills")
    private fun reminder()=Reminder(itemId=0,amount=3,unit=ReminderUnit.DAYS)

    @Test fun paymentUndoRestoresSnoozeButKeepsLaterUnrelatedEdits()=fixture { repo ->
        repo.saveItem(bill(),addedReminders=listOf(reminder()))
        val original=repo.snapshot();val item=original.items.single();val r=original.reminders.single()
        val until=System.currentTimeMillis()+7_200_000;repo.snoozeReminder(r.id,until)
        assertTrue(repo.setPaid(item.id,true));val token=repo.pendingPayments.value.single().token
        assertNull(repo.snapshot().reminders.single().snoozedUntil)
        repo.saveItem(repo.snapshot().items.single().copy(title="Edited power"))
        assertTrue(repo.undoPayment(token));val restored=repo.snapshot()
        assertFalse(restored.items.single().paid);assertEquals("Edited power",restored.items.single().title)
        assertEquals(until,restored.reminders.single().snoozedUntil)
    }
    @Test fun laterPaymentSupersedesUndoAndDeletedEventsAreNotResurrected()=fixture { repo ->
        repo.saveItem(bill());val item=repo.snapshot().items.single()
        repo.setPaid(item.id,true);val old=repo.pendingPayments.value.single().token
        repo.setPaid(item.id,false);assertFalse(repo.undoPayment(old));assertFalse(repo.snapshot().items.single().paid)
        repo.setPaid(item.id,true);val current=repo.pendingPayments.value.single().token
        repo.deleteWithUndo(repo.snapshot().items.single(),false)
        assertFalse(repo.undoPayment(current));assertTrue(repo.snapshot().items.isEmpty())
    }
    @Test fun editorPaymentChangesOfferUndoAndOnlyChangeOneOccurrence()=fixture { repo ->
        repo.saveItem(bill(),addedReminders=listOf(reminder()),options=EventSaveOptions(RepeatRule.MONTHLY,3))
        val before=repo.snapshot();val item=before.items.first()
        repo.saveItem(item.copy(paid=true,title="Updated"),options=EventSaveOptions(entireSeries=true))
        assertEquals(1,repo.snapshot().items.count { it.paid });assertEquals(1,repo.pendingPayments.value.size)
        assertTrue(repo.undoPayment(repo.pendingPayments.value.single().token))
        assertTrue(repo.snapshot().items.none { it.paid });assertTrue(repo.snapshot().items.all { it.title=="Updated" })
    }
    @Test fun staleNotificationCannotPayEditedSkippedOrDeletedBill()=fixture { repo ->
        repo.saveItem(bill(),addedReminders=listOf(reminder()),options=EventSaveOptions(RepeatRule.MONTHLY,2))
        val before=repo.snapshot();val item=before.items.first();val r=before.reminders.first { it.itemId==item.id }
        val token=billReminderToken(item,r)
        repo.saveItem(item.copy(billAmountMinor=555))
        assertFalse(repo.payFromReminder(r.id,token))
        var current=repo.snapshot().items.first { it.id==item.id }
        val valid=billReminderToken(current,repo.snapshot().reminders.first { it.id==r.id })
        assertTrue(repo.payFromReminder(r.id,valid));assertFalse(repo.payFromReminder(r.id,valid))
        assertEquals(1,repo.snapshot().items.count { it.paid })
        repo.setPaid(item.id,false);repo.setSkipped(item.id,true)
        current=repo.snapshot().items.first { it.id==item.id }
        assertFalse(repo.payFromReminder(r.id,billReminderToken(current,repo.snapshot().reminders.first { it.id==r.id })))
        repo.deleteWithUndo(current,false);assertFalse(repo.payFromReminder(r.id,valid))
    }
    @Test fun changedSnoozeIsNotOverwrittenByPaymentUndoAndRestoreClearsPendingActions()=fixture { repo ->
        repo.saveItem(bill().copy(paid=true),addedReminders=listOf(reminder()))
        val item=repo.snapshot().items.single();repo.setPaid(item.id,false)
        val token=repo.pendingPayments.value.single().token;val r=repo.snapshot().reminders.single()
        val until=System.currentTimeMillis()+3_600_000;repo.snoozeReminder(r.id,until)
        assertTrue(repo.undoPayment(token));assertEquals(until,repo.snapshot().reminders.single().snoozedUntil)
        repo.setPaid(item.id,false);val saved=repo.snapshot();repo.replaceAll(saved)
        assertTrue(repo.pendingPayments.value.isEmpty())
        repo.saveItem(repo.snapshot().items.single().copy(category="Other"))
        assertFalse(repo.setPaid(item.id,true));assertFalse(repo.snapshot().items.single().paid)
    }
}
