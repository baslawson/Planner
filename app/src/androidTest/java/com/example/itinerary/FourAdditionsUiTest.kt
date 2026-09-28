package com.example.itinerary

import androidx.activity.compose.setContent
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
class FourAdditionsUiTest {
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
        val dir=File(context.cacheDir,"qa-four-additions-evidence").apply { mkdirs() }
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
                    val before=nodes().map { it.text?.toString() }
                    val bounds=android.graphics.Rect()
                    nodes().firstOrNull { it.isScrollable && !it.isEditable }?.getBoundsInScreen(bounds)
                    if(!bounds.isEmpty) {
                        val delta=bounds.height()/6
                        val from=bounds.centerY()+if(forward)delta else -delta
                        val to=bounds.centerY()-if(forward)delta else -delta
                        ins.uiAutomation.executeShellCommand("input swipe ${bounds.left+12} $from ${bounds.left+12} $to 200").use {
                            java.io.FileInputStream(it.fileDescriptor).use { stream -> stream.readBytes() }
                        }
                    }
                    Thread.sleep(350)
                    if(nodes().map { it.text?.toString() }==before)forward=!forward
                };false
            }
        }
    }
    private fun click(text:String) {
        if (text == "Settings" && find(text) == null && find("More options") != null) click("More options")
        if (text == "Add bill") {
            if (find("Add task") == null && find("Bill payment") == null) click("Add menu")
            if (find("Bill payment") == null) click("Add task")
            click("Bill payment")
            return
        }
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

    @HarnessStage @Test fun stage1RecordPartialPaymentAndDelete()=runBlocking {
        EditorDraftStore(context).clear()
        data().items.filter { it.title == "QA cold recovery bill" }.forEach { app.repository.deleteWithUndo(it) }
        data().deleted.filter { it.label == "QA cold recovery bill" }.forEach { app.repository.permanentlyDelete(it.id) }
        app.repository.saveItem(ItineraryItem(tripId=0,date=LocalDate.of(2000,1,1),startTime=null,
            title="QA cold recovery bill",category="Bills",billAmountMinor=10000))
        val item=data().items.single { it.title=="QA cold recovery bill" }
        val activity=ins.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        ins.runOnMainSync { activity.setContent {
            com.example.itinerary.ui.theme.ItineraryTheme {
                com.example.itinerary.ui.ItemEditorSheet(item,emptyList(),emptyList(),emptyMap(),emptySet(),{},{},{},
                    onSave={ event,added,removed,reminders,removedReminders,options -> app.repository.saveItem(event,added,removed,reminders,removedReminders,options) },
                    onDelete={_,_->})
            }
        } }
        click("Record payment")
        setText("","30.00")
        click("Add payment")
        reveal { nodes().any { it.text?.contains("70.00 remaining")==true } }
        screenshot("partial-payment")
        click("Save")
        await { data().items.single { it.id==item.id }.payments.size==1 }
        val partial=data().items.single { it.id==item.id }
        assertEquals(3000L,Payments.total(partial.payments));assertFalse(partial.paid)
        app.repository.deleteWithUndo(partial)
        app.repository.finishDeletion(app.repository.pendingDeletions.value.single().token)
        assertTrue(data().items.none { it.id==item.id })
        ins.runOnMainSync { activity.finish() }
    }
    @HarnessStage @Test fun stage2RestoreThroughSettingsAfterForceStop()=runBlocking {
        assertTrue(data().items.none { it.title=="QA cold recovery bill" })
        assertTrue(data().deleted.any { it.label=="QA cold recovery bill" })
        open();click("Settings");click("Recently deleted")
        await { find("QA cold recovery bill")!=null }
        screenshot("recently-deleted")
        click("Delete forever")
        await { find("Delete forever?")!=null }
        click("Cancel")
        click("Restore")
        await { data().items.any { it.title=="QA cold recovery bill" } }
        val restored=data().items.single { it.title=="QA cold recovery bill" }
        assertEquals(3000L,Payments.total(restored.payments))
        assertEquals(7000L,Payments.remaining(restored.billAmountMinor,restored.paid,restored.payments))
        assertTrue(data().deleted.none { it.label=="QA cold recovery bill" })
        click("Close")
        screenshot("restored")
    }
    @Test fun scanReviewShowsFieldSpecificUncertainty() {
        val activity=ins.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        ins.runOnMainSync { activity.setContent {
            com.example.itinerary.ui.theme.ItineraryTheme {
                com.example.itinerary.ui.BillSuggestionDialog(
                    Attachment(itemId=0,name="Unclear scan",fileName="qa-unclear.txt",mimeType="text/plain",
                        recognizedText="Power company\nAmount due: $100.00\nTotal: $200.00\nDue date: 03/04/2026",textStatus="PARTIAL"),
                    "","","AUD",{}, {_,_,_,_->})
            }
        } }
        try {
            reveal { nodes().any { it.text?.contains("Only part of this document")==true } }
            screenshot("scan-partial-warning")
            reveal { nodes().any { it.text?.contains("unclear or ambiguous")==true } }
            screenshot("scan-date-warning")
            reveal { nodes().any { it.text?.contains("Several different totals")==true } }
            reveal { nodes().any { it.text?.contains("Currency is uncertain")==true } }
            screenshot("scan-amount-currency-warning")
        } finally { ins.runOnMainSync { activity.finish() } }
    }

}
