package com.example.itinerary

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.example.itinerary.data.*
import com.example.itinerary.reminders.ReminderAlarms
import com.example.itinerary.scanner.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.util.zip.ZipFile
import org.json.JSONObject

class SeventhFeaturesTest {
    private fun fixture(test: suspend (Repository, AttachmentStore, Context, BackupManager) -> Unit) = runBlocking {
        val base=InstrumentationRegistry.getInstrumentation().targetContext
        val dir=File(base.cacheDir,"seventh-tests").apply { mkdirs() }
        val context=object:ContextWrapper(base) {
            override fun getFilesDir()=File(dir,"files").apply { mkdirs() }
            override fun getCacheDir()=File(dir,"cache").apply { mkdirs() }
            override fun getSharedPreferences(name:String,mode:Int)=base.getSharedPreferences("seventh_test_$name",mode)
        }
        context.getSharedPreferences("backup_status",0).edit().clear().commit()
        val db=Room.inMemoryDatabaseBuilder(base,AppDatabase::class.java).build()
        val store=AttachmentStore(context)
        val repo=Repository(db,store,object:ReminderAlarms {
            override fun schedule(item:ItineraryItem,reminder:Reminder) { assertFalse(item.paid || item.skipped) }
            override fun cancel(reminderId:Long) {}
        })
        try { test(repo,store,context,BackupManager(context,repo,store,SettingsRepository(context))) }
        finally { db.close();dir.deleteRecursively();base.deleteSharedPreferences("seventh_test_settings");base.deleteSharedPreferences("seventh_test_backup_status") }
    }
    private fun bill()=ItineraryItem(tripId=0,date=LocalDate.of(2030,1,31),startTime=null,title="Electricity",category="Bills",billAmountMinor=12345)

    @Test fun skipCancelsDeliveryAndSnoozeWithoutDeletingSeriesAndCanBeRestored() = fixture { repo,_,_,_ ->
        repo.saveItem(bill(),addedReminders=listOf(Reminder(itemId=0,amount=3,unit=ReminderUnit.DAYS)),options=EventSaveOptions(RepeatRule.MONTHLY,3))
        val before=repo.snapshot();val item=before.items[1];val reminder=before.reminders.single { it.itemId==item.id }
        assertTrue(repo.snoozeReminder(reminder.id,System.currentTimeMillis()+3_600_000))
        repo.setSkipped(item.id,true);repo.rescheduleAllReminders()
        var delivered=false;repo.deliverReminder(reminder.id,0) { _,_-> delivered=true }
        assertFalse(delivered);assertFalse(repo.snoozeReminder(reminder.id,System.currentTimeMillis()+3_600_000))
        val skipped=repo.snapshot()
        assertEquals(before.items.map { it.id to it.date },skipped.items.map { it.id to it.date })
        assertEquals(1,skipped.items.count { it.skipped });assertNull(skipped.reminders.single { it.id==reminder.id }.snoozedUntil)
        repo.saveItem(skipped.items.first().copy(title="Changed series"),options=EventSaveOptions(entireSeries=true))
        assertTrue(repo.snapshot().items.single { it.id==item.id }.skipped)
        repo.setSkipped(item.id,false)
        val fresh=repo.snapshot().reminders.single { it.itemId==item.id }
        repo.deliverReminder(fresh.id,0) { _,_->delivered=true };assertTrue(delivered)
        assertTrue(repo.snapshot().items.none { it.skipped })
    }

    @Test fun templatesCopyUsefulDefaultsButResetIdentityDatePaymentAndChecklistProgress() = fixture { repo,_,_,_ ->
        val source=bill().copy(id=99,tripId=88,paid=true,skipped=true,seriesId="series",draftToken="old",checklist=listOf(ChecklistEntry(text="Receipt",done=true)))
        val content=TemplateContent(source,listOf(Reminder(10,99,3,ReminderUnit.DAYS,true,1234)),RepeatRule.FORTNIGHTLY,3)
        repo.saveTemplate("Electricity",content)
        val saved=repo.snapshot().templates.single();val decoded=TemplateContent.decode(saved.payload)
        val copied=decoded.forDate(LocalDate.of(2031,6,2))
        assertEquals(0L,copied.id);assertEquals(0L,copied.tripId);assertEquals(LocalDate.of(2031,6,2),copied.date)
        assertFalse(copied.paid);assertFalse(copied.skipped);assertNull(copied.seriesId);assertNull(copied.draftToken)
        assertTrue(copied.checklist.none { it.done });assertEquals(12345L,copied.billAmountMinor)
        assertNull(decoded.reminders.single().snoozedUntil);assertEquals(0L,decoded.reminders.single().id)
        repo.saveTemplate("electricity",content.copy(item=source.copy(billAmountMinor=15000)))
        assertEquals(saved.id,repo.snapshot().templates.single().id)
        repo.saveItem(copied,addedReminders=decoded.reminders,options=EventSaveOptions(decoded.repeat,decoded.count))
        repo.deleteTemplate(repo.snapshot().templates.single())
        assertTrue(repo.snapshot().templates.isEmpty());assertEquals(3,repo.snapshot().items.size)
    }

    @Test fun backupRoundTripPreservesAmountsSkippedTextAndTemplates() = fixture { repo,store,context,backup ->
        store.writableFileFor("receipt.txt").writeText("receipt")
        val attachment=Attachment(itemId=0,name="Receipt",fileName="receipt.txt",mimeType="text/plain",recognizedText="ORCHID 7328",textStatus="READY")
        repo.saveItem(bill(),added=listOf(attachment),options=EventSaveOptions(RepeatRule.MONTHLY,2))
        repo.setSkipped(repo.snapshot().items.last().id,true)
        repo.saveTemplate("Bill",TemplateContent(bill(),emptyList(),RepeatRule.YEARLY,2))
        val before=repo.snapshot();val uri=Uri.fromFile(File(context.cacheDir,"backup.zip"))
        backup.export(uri)
        ZipFile(File(uri.path!!)).use { archive ->
            val json=JSONObject(archive.getInputStream(archive.getEntry("data.json")).bufferedReader().readText())
            assertEquals(8,json.getInt("formatVersion"));assertEquals(1,json.getJSONArray("templates").length())
        }
        repo.replaceAll(DataSnapshot(emptyList(),emptyList(),emptyList(),emptyList()))
        backup.restore(backup.stage(uri));assertEquals(before,repo.snapshot())
        assertEquals("ORCHID 7328",repo.snapshot().attachments.first().recognizedText)
    }

    @Test fun backupStatusKeepsLastSuccessWhenNextAttemptFailsAndRecognizesInterruption() = fixture { _,_,context,backup ->
        backup.export(Uri.fromFile(File(context.cacheDir,"good.zip")))
        val success=backup.status.state.value
        assertEquals("SUCCESS",success.outcome);assertEquals("File",success.destination);assertNotNull(success.lastSuccess)
        try { backup.export(Uri.fromFile(File(context.cacheDir,"missing/fail.zip")));fail("Expected failure") } catch (_:BackupException) {}
        assertEquals("FAILED",backup.status.state.value.outcome);assertEquals(success.lastSuccess,backup.status.state.value.lastSuccess)
        assertEquals("FAILED",BackupStatusStore(context).state.value.outcome)
        context.getSharedPreferences("backup_status",0).edit().putString("outcome","RUNNING").commit()
        assertEquals("INTERRUPTED",BackupStatusStore(context).state.value.outcome)
    }

    @Test fun croppedScanOcrMakesBothPdfAndJpegSearchable() = fixture { repo,store,context,_ ->
        val source=File(context.cacheDir,"document.jpg")
        val bitmap=Bitmap.createBitmap(1000,1300,Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.WHITE)
            val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.BLACK;textSize=72f;typeface=Typeface.create(Typeface.MONOSPACE,Typeface.BOLD) }
            drawText("ORCHID 7328",100f,250f,paint);drawText("Total 123.45",100f,400f,paint)
        }
        source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG,95,it) };bitmap.recycle()
        for(pdf in listOf(false,true)) {
            val attachment=ScanImages.export(listOf(ScanImages.Page(source)),pdf,store).single()
            assertEquals(attachment.recognizedText,"READY",attachment.textStatus)
            assertTrue(attachment.recognizedText,attachment.recognizedText.contains("ORCHID",true))
            repo.saveItem(bill(),added=listOf(attachment))
        }
        val data=repo.snapshot()
        val matches=Search.run("ORCHID",emptySet(),data.trips,data.items,data.attachments)
        assertEquals(2,matches.hits.size);assertTrue(matches.hits.all { it.documentName!=null })
    }

    @Test fun failedOcrDoesNotDiscardAttachmentAndDraftRetainsNewFields() = fixture { _,store,_,_ ->
        val missing=Attachment(itemId=0,name="Missing",fileName="missing.pdf",mimeType="application/pdf")
        assertEquals("FAILED",DocumentText.index(missing,store).textStatus)
        val item=bill().copy(skipped=true,billCurrency="USD")
        assertEquals(item,DraftCodec.item(DraftCodec.item(item)))
        val document=missing.copy(recognizedText="ORCHID",textStatus="READY")
        assertEquals(listOf(document),DraftCodec.attachments(DraftCodec.attachments(listOf(document))))
    }
}
