package com.example.itinerary

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.example.itinerary.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.YearMonth

/** Real application UI; run only with an external backup/restore harness. */
@Suppress("DEPRECATION")
class SeventhFeaturesUiTest {
    private val ins get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=ins.targetContext
    private val app get()=context.applicationContext as ItineraryApp
    private fun data()=runBlocking { app.repository.snapshot() }
    private fun nodes():List<AccessibilityNodeInfo> {
        val result=mutableListOf<AccessibilityNodeInfo>()
        fun visit(n:AccessibilityNodeInfo) { result+=n;for(i in 0 until n.childCount)n.getChild(i)?.let(::visit) }
        ins.uiAutomation.freshRoot?.let(::visit);return result
    }
    private fun find(text:String)=nodes().firstOrNull { it.isVisibleToUser && (it.text?.toString()==text || it.contentDescription?.toString()==text) }
    private fun screenshot(name:String) {
        val dir=File(context.cacheDir,"qa-seventh-evidence").apply { mkdirs() }
        ins.uiAutomation.takeScreenshot()?.let { b -> File(dir,"$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG,100,it) };b.recycle() }
    }
    private fun await(timeout:Long=45000,condition:()->Boolean) {
        val end=SystemClock.uptimeMillis()+timeout
        while(SystemClock.uptimeMillis()<end) { if(condition())return;Thread.sleep(150) }
        screenshot("failure");fail("Timed out: "+nodes().mapNotNull { it.text }.joinToString(" | "))
    }
    private fun reveal(test:()->Boolean) {
        var tries=0;var forward=true
        await {
            if(test())true else {
                if(++tries>5) {
                    hideQuickTestKeyboard(ins) // a swipe across the keyboard would type words
                    val action=if(forward)AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                    if(nodes().lastOrNull { it.isScrollable && !it.isEditable }?.performAction(action)!=true)forward=!forward
                    Thread.sleep(350)
                };false
            }
        }
    }
    private fun click(text:String) {
        if (text == "Settings" && find(text) == null && find("More options") != null) click("More options")
        reveal {
            var node=find(text)
            while(node!=null && !node.isClickable)node=node.parent
            node?.takeIf { it.isEnabled }?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true
        };Thread.sleep(350)
    }
    private fun setText(old:String,value:String) {
        reveal { pickEditable(nodes(),old)!=null }
        val node=pickEditable(nodes(),old)!!
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,value)
        }));Thread.sleep(350)
    }
    private fun open() {
        app.settings.lastViewCalendar=false // open() expects the agenda
        ins.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        await { find("AGENDA")!=null }
    }

    @Test fun saveApplyTemplateEnterAmountSkipRestoreAndPayUpdateMonthlyTotals()=runBlocking {
        app.settings.setAgendaRange(AgendaRange.ALL)
        val today=LocalDate.now()
        val base=data().items.filter { it.category=="Bills" && !it.paid && !it.skipped && it.billCurrency=="AUD" && YearMonth.from(it.date)==YearMonth.from(today) }.sumOf { it.billAmountMinor?:0 }
        app.repository.saveItem(ItineraryItem(tripId=0,date=today,startTime=null,title="QA power bill",category="Bills",billAmountMinor=12345,
            checklist=listOf(ChecklistEntry(text="Receipt filed",done=true))),
            addedReminders=listOf(Reminder(itemId=0,amount=3,unit=ReminderUnit.DAYS)),options=EventSaveOptions(RepeatRule.FORTNIGHTLY,3))
        open();click("QA power bill");click("QA power bill")
        click("Save as template");setText("QA power bill","Power bill preset");click("Save template")
        await { data().templates.any { it.name=="Power bill preset" } }
        click("Discard") // a bill opens from the agenda, so Discard returns there
        // Bill templates are offered only in the bill editor.
        click("Add menu");click("Add task");click("Bill payment");click("Use template");click("Apply Power bill preset")
        setText("QA power bill","QA templated bill");setText("123.45","222.22")
        screenshot("template-applied")
        click("Save")
        await { data().items.count { it.title=="QA templated bill" }==3 }
        val copies=data().items.filter { it.title=="QA templated bill" }
        assertTrue(copies.all { it.billAmountMinor==22222L && !it.paid && !it.skipped && it.checklist.none { task->task.done } })
        assertEquals(3,data().reminders.count { r ->copies.any { it.id==r.itemId } })
        reveal { find(Bills.format(base+34567,"AUD"))!=null }
        click("Actions for QA templated bill");click("Skip this occurrence")
        await { data().items.count { it.title=="QA templated bill" && it.skipped }==1 }
        reveal { find(Bills.format(base+12345,"AUD"))!=null };screenshot("skipped-total")
        click("Show completed tasks") // a skipped bill is hidden with the completed ones
        click("Actions for QA templated bill");click("Restore occurrence")
        await { data().items.none { it.title=="QA templated bill" && it.skipped } }
        reveal { find(Bills.format(base+34567,"AUD"))!=null }
        click("Actions for QA templated bill");click("Mark paid")
        await { data().items.count { it.title=="QA templated bill" && it.paid }==1 }
        reveal { find(Bills.format(base+12345,"AUD"))!=null };screenshot("paid-total")
    }

    @Test fun existingDocumentReadDiscardPreservesFileThenSaveMakesItSearchable()=runBlocking {
        app.settings.setAgendaRange(AgendaRange.ALL)
        val file=app.attachmentStore.writableFileFor("qa-orchid-document.jpg")
        val bitmap=Bitmap.createBitmap(1000,1300,Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.WHITE)
            drawText("ORCHID 7328",100f,250f,Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.BLACK;textSize=72f;typeface=Typeface.create(Typeface.MONOSPACE,Typeface.BOLD) })
        }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG,95,it) };bitmap.recycle()
        app.repository.saveItem(ItineraryItem(tripId=0,date=LocalDate.now(),startTime=null,title="QA receipt"),
            added=listOf(Attachment(itemId=0,name="Orchid receipt",fileName=file.name,mimeType="image/jpeg")))
        val item=data().items.single { it.title=="QA receipt" };val original=file.readBytes()
        open();click("QA receipt");click("QA receipt");click("Read text")
        click("View text");await { nodes().any { it.text?.toString()?.contains("ORCHID")==true } };screenshot("recognised-text")
        click("Close");click("Discard")
        assertTrue(file.exists());assertArrayEquals(original,file.readBytes())
        assertTrue(data().attachments.single { it.itemId==item.id }.recognizedText.isEmpty())
        click("QA receipt");click("Read text");click("View text");click("Close");click("Save")
        await { data().attachments.single { it.itemId==item.id }.recognizedText.contains("ORCHID") }
        assertArrayEquals(original,file.readBytes())
        click("Switch to Agenda view");click("Search") // saved from the calendar, which has no Back button
        await { nodes().any { it.isEditable } };setText("","ORCHID")
        await { find("QA receipt")!=null && find("Matches document: Orchid receipt")!=null };screenshot("document-search")
        click("Back")
    }

    @Test fun settingsShowsLastSuccessfulFileBackupAndPersistentFailureWarning()=runBlocking {
        app.backup.export(Uri.fromFile(File(context.cacheDir,"qa-seventh-backup.zip")))
        val success=app.backup.status.state.value.lastSuccess
        try { app.backup.export(Uri.fromFile(File(context.cacheDir,"no-such-folder/failed.zip")));fail("Expected failure") } catch (_:BackupException) {}
        assertEquals(success,app.backup.status.state.value.lastSuccess)
        open();await { find("Last backup failed or was interrupted · View backup status")!=null }
        click("Settings")
        await(120000) {
            if (nodes().any { it.isVisibleToUser && it.text?.toString()?.contains("Last successful backup:")==true } && find("The last File backup failed. Please retry.")!=null) true
            else {
                scrollStep(nodes(), true)
                Thread.sleep(3000)
                ins.uiAutomation.waitForIdle(1500,10000)
                ins.uiAutomation.freshRoot?.refresh()
                false
            }
        }
        assertNotNull(find("The last File backup failed. Please retry."));screenshot("backup-status")
        assertEquals("FAILED",BackupStatusStore(context).state.value.outcome)
        click("Back")
    }
}
