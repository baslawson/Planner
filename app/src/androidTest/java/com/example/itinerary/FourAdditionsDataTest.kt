package com.example.itinerary

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.net.Uri
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.example.itinerary.data.*
import com.example.itinerary.reminders.ReminderAlarms
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.LocalDate

class FourAdditionsDataTest {
    private fun fixture(test: suspend (Repository,AppDatabase,AttachmentStore,BackupManager,File,ReminderAlarms,MutableSet<Long>) -> Unit) = runBlocking {
        val base=InstrumentationRegistry.getInstrumentation().targetContext
        val dir=File(base.cacheDir,"four-additions-tests").apply { deleteRecursively();mkdirs() }
        val context=object:ContextWrapper(base) {
            override fun getFilesDir()=File(dir,"files").apply { mkdirs() }
            override fun getCacheDir()=File(dir,"cache").apply { mkdirs() }
            override fun getSharedPreferences(name:String,mode:Int):SharedPreferences=base.getSharedPreferences("four_additions_$name",mode)
        }
        val scheduled=mutableSetOf<Long>()
        val alarms=object:ReminderAlarms {
            override fun schedule(item:ItineraryItem,reminder:Reminder) { assertFalse(item.paid || item.skipped);scheduled.add(reminder.id) }
            override fun cancel(reminderId:Long) { scheduled.remove(reminderId) }
        }
        val db=Room.inMemoryDatabaseBuilder(base,AppDatabase::class.java).build()
        val store=AttachmentStore(context);val repo=Repository(db,store,alarms)
        try { test(repo,db,store,BackupManager(context,repo,store,SettingsRepository(context)),dir,alarms,scheduled) }
        finally { db.close();dir.deleteRecursively();context.getSharedPreferences("settings",Context.MODE_PRIVATE).edit().clear().commit() }
    }
    private fun bill()=ItineraryItem(tripId=0,date=LocalDate.of(2000,1,1),startTime=null,title="QA bill",
        category="Bills",billAmountMinor=10000,payments=listOf(BillPayment(amount=3000,note="Deposit")))
    private fun reminder()=Reminder(itemId=0,amount=1,unit=ReminderUnit.DAYS)
    private fun attachment(store:AttachmentStore):Attachment {
        store.writableFileFor("bill.txt").writeText("Original scan")
        return Attachment(itemId=0,name="Scan",fileName="bill.txt",mimeType="text/plain")
    }
    @Test fun partialPaymentRemindersSettlementUndoAndReversal()=fixture { repo,_,_,_,_,_,scheduled ->
        repo.saveItem(bill(),addedReminders=listOf(reminder()))
        val partial=repo.snapshot().items.single();assertFalse(partial.paid);assertEquals(1,scheduled.size)
        assertTrue(repo.setPaid(partial.id,true))
        val paid=repo.snapshot().items.single();assertEquals(10000L,Payments.total(paid.payments));assertTrue(scheduled.isEmpty())
        assertTrue(repo.undoPayment(repo.pendingPayments.value.single().token))
        assertEquals(partial,repo.snapshot().items.single());assertEquals(1,scheduled.size)
        repo.setPaid(partial.id,true);repo.setPaid(partial.id,false)
        assertEquals(2,repo.snapshot().items.single().payments.size)
        // Unticking reverses only the "Marked paid" entry: the deposit still counts.
        assertEquals(listOf(false,true),repo.snapshot().items.single().payments.map { it.reversed });assertEquals(1,scheduled.size)
        assertEquals(3000L,Payments.total(repo.snapshot().items.single().payments))
    }
    @Test fun recurrenceKeepsEachOccurrencesPaymentsAndCategoryChangeKeepsHistory()=fixture { repo,_,_,_,_,_,_ ->
        repo.saveItem(bill(),options=EventSaveOptions(RepeatRule.MONTHLY,3))
        val first=repo.snapshot().items.first();assertEquals(listOf(3000L,0L,0L),repo.snapshot().items.map { Payments.total(it.payments) })
        val second=repo.snapshot().items[1];repo.saveItem(second.copy(payments=listOf(BillPayment(amount=1000))))
        repo.saveItem(first.copy(title="Changed"),options=EventSaveOptions(entireSeries=true))
        assertEquals(listOf(3000L,1000L,0L),repo.snapshot().items.map { Payments.total(it.payments) })
        repo.removeCategories(listOf("Bills"))
        assertEquals(listOf(3000L,1000L,0L),repo.snapshot().items.map { Payments.total(it.payments) })
        assertTrue(repo.snapshot().items.all { it.category=="Other" && !it.paid })
    }
    @Test fun recentlyDeletedRestoresAfterNewRepositoryAndRetainsScansAndAlarms()=fixture { repo,db,store,_,_,alarms,scheduled ->
        repo.saveItem(bill(),added=listOf(attachment(store)),addedReminders=listOf(reminder()))
        val before=repo.snapshot();repo.deleteWithUndo(before.items.single());val token=repo.pendingDeletions.value.single().token
        repo.finishDeletion(token);assertTrue(scheduled.isEmpty());assertTrue(store.fileFor("bill.txt").exists())
        val fresh=Repository(db,store,alarms);assertTrue(fresh.pendingDeletions.value.isEmpty())
        fresh.restoreDeleted(token);assertEquals(before,fresh.snapshot());assertEquals(1,scheduled.size)
    }
    @Test fun planRestoreAndIdCollisionsPreserveBothRecords()=fixture { repo,db,store,_,_,_,_ ->
        repo.saveItem(bill(),added=listOf(attachment(store)),addedReminders=listOf(reminder()))
        val before=repo.snapshot();repo.deleteTrips(before.trips)
        assertTrue(repo.snapshot().trips.isEmpty());repo.restoreDeleted(repo.snapshot().deleted.single().id)
        assertEquals(before,repo.snapshot())
        repo.deleteWithUndo(before.items.single());val token=repo.snapshot().deleted.single().id
        db.itemDao().upsert(before.items.single().copy(title="Unrelated occupant",payments=emptyList()))
        repo.restoreDeleted(token)
        val restored=repo.snapshot();assertEquals(2,restored.items.size)
        val original=restored.items.single { it.title=="QA bill" };assertNotEquals(before.items.single().id,original.id)
        assertEquals(original.id,restored.attachments.single().itemId);assertEquals(original.id,restored.reminders.single().itemId)
    }
    @Test fun expiryAndPermanentDeletionRespectSharedFiles()=fixture { repo,db,store,_,_,_,_ ->
        repo.saveItem(bill(),added=listOf(attachment(store)),options=EventSaveOptions(RepeatRule.DAILY,2))
        repo.deleteWithUndo(repo.snapshot().items.first());repo.finishDeletion(repo.pendingDeletions.value.single().token)
        val expired=repo.snapshot().deleted.single().copy(deletedAt=System.currentTimeMillis()-TRASH_RETENTION_MS-1000)
        db.deletedDao().delete(expired.id);db.deletedDao().insert(expired)
        repo.rescheduleAllReminders();assertTrue(repo.snapshot().deleted.isEmpty());assertTrue(store.fileFor("bill.txt").exists())
        repo.deleteWithUndo(repo.snapshot().items.single());val token=repo.snapshot().deleted.single().id
        repo.permanentlyDelete(token);assertFalse(store.fileFor("bill.txt").exists())
        assertTrue(repo.snapshot().deleted.isEmpty())
    }
    @Test fun backupRoundTripPreservesLivePaymentsDeletedPaymentsAndScans()=fixture { repo,_,store,backup,dir,_,_ ->
        repo.saveItem(bill(),added=listOf(attachment(store)),addedReminders=listOf(reminder()))
        repo.deleteWithUndo(repo.snapshot().items.single());repo.finishDeletion(repo.pendingDeletions.value.single().token)
        repo.saveItem(bill().copy(title="Live partial bill"))
        val before=repo.snapshot();val zip=File(dir,"roundtrip.zip")
        backup.export(Uri.fromFile(zip));repo.permanentlyDelete(before.deleted.single().id)
        assertFalse(store.fileFor("bill.txt").exists())
        backup.restore(backup.stage(Uri.fromFile(zip)));assertEquals(before,repo.snapshot())
        assertEquals("Original scan",store.fileFor("bill.txt").readText())
        repo.restoreDeleted(before.deleted.single().id)
        assertEquals(listOf(3000L,3000L),repo.snapshot().items.map { Payments.total(it.payments) })
    }
}
