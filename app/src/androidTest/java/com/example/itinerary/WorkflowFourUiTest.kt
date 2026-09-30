package com.example.itinerary

import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import com.example.itinerary.ui.*
import com.example.itinerary.ui.theme.ItineraryTheme
import java.time.LocalTime
import android.net.Uri
import android.graphics.Bitmap
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

/** Run only with an external backup/restore harness for the shared emulator. */
@Suppress("DEPRECATION")
class WorkflowFourUiTest {
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
        val dir=File(context.cacheDir,"qa-workflow-four").apply { mkdirs() }
        ins.uiAutomation.takeScreenshot()?.let { b -> File(dir,"$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG,100,it) };b.recycle() }
    }
    private fun await(timeout:Long=45000,condition:()->Boolean) {
        val end=SystemClock.uptimeMillis()+timeout
        while(SystemClock.uptimeMillis()<end) { if(condition())return;Thread.sleep(150) }
        screenshot("failure");fail("Timed out: "+nodes().mapNotNull { it.text }.joinToString(" | "))
    }
    private fun reveal(test:()->Boolean) {
        var tries=0;var forward=true;var stationary=0
        await {
            if(test())true else {
                if(++tries>5) {
                    hideQuickTestKeyboard(ins) // a swipe across the keyboard would type words
                    fun positions()=nodes().map { n ->
                        val r=android.graphics.Rect();n.getBoundsInScreen(r);"${n.text}:$r"
                    }
                    val before=positions()
                    val bounds=android.graphics.Rect()
                    nodes().filter { it.isScrollable && !it.isEditable }.maxByOrNull {
                        val r=android.graphics.Rect();it.getBoundsInScreen(r);r.height()
                    }?.getBoundsInScreen(bounds)
                    if(!bounds.isEmpty) {
                        val delta=bounds.height()/6
                        val from=bounds.centerY()+if(forward)delta else -delta
                        val to=bounds.centerY()-if(forward)delta else -delta
                        ins.uiAutomation.executeShellCommand("input swipe ${bounds.left+12} $from ${bounds.left+12} $to 200").use {
                            java.io.FileInputStream(it.fileDescriptor).use { stream -> stream.readBytes() }
                        }
                    }
                    Thread.sleep(350)
                    if(positions()==before)stationary++ else stationary=0
                    if(stationary>=3) { forward=!forward;stationary=0 }
                };false
            }
        }
    }
    private fun click(text:String) {
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
        ins.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        await { find("AGENDA")!=null }
    }


    // Save keeps the editor open on the saved record; Close then leaves at once, as nothing is unsaved.
    private fun saveAndClose() { click("Save");click("Close") }
    // Close with unsaved changes asks first; Discard abandons them.
    private fun back() {
        ins.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        Thread.sleep(350)
    }
    private fun cleanFixture(prefix:String)=runBlocking {
        data().tasks.filter { it.title.startsWith(prefix) }.forEach { app.repository.deleteTask(it.id) }
        data().deleted.filter { it.label.startsWith(prefix) }.forEach { app.repository.permanentlyDelete(it.id) }
    }
    private fun field(label: String, value: String) {
        reveal {
            val node=nodes().firstOrNull { it.isVisibleToUser && it.isEditable &&
                (it.hintText?.toString()==label || it.text?.toString()==label || descendants(it).any { n -> n.text?.toString()==label }) }
            node?.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,value)
            })==true
        }
        await { nodes().any { it.isEditable && it.text?.toString()==value } }
        Thread.sleep(350)
    }
    private fun descendants(n: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = listOf(n) +
        (0 until n.childCount).flatMap { n.getChild(it)?.let(::descendants).orEmpty() }
    private fun fresh() {
        val info=ins.uiAutomation.serviceInfo
        info.flags=info.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        ins.uiAutomation.serviceInfo=info
        TaskDraftStore(context).clear("new")
        EditorDraftStore(context).clear()
        app.settings.setAgendaRange(AgendaRange.ALL); app.settings.setAgendaTypes(AgendaType.entries.toSet())
    }
    @Test fun incomingSharePreservesDraftAndSurvivesRecreation() = runBlocking {
        fresh()
        val shareIntent = Intent(Intent.ACTION_SEND).setType("text/plain").setPackage(context.packageName)
            .putExtra(Intent.EXTRA_TEXT, "https://example.test/recover")
        assertTrue(context.packageManager.queryIntentActivities(shareIntent, 0).any { it.activityInfo.name.endsWith("MainActivity") })
        val activity = ins.startActivitySync(shareIntent.setClass(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        val existing = org.json.JSONObject().put("id", "unfinished-share-test").put("title", "Keep my unfinished task")
        try {
            TaskDraftStore(context).write("new", existing)
            click("Add task")
            await { nodes().any { it.text?.toString()?.startsWith("You have an unfinished task.") == true } }
            assertEquals(existing.toString(), TaskDraftStore(context).read("new").toString())
            TaskDraftStore(context).clear("new")
            ins.runOnMainSync { activity.recreate() }
            click("Add task")
            await { find("https://example.test/recover") != null }
            click("Close") // the shared text is what the editor opened with, so nothing is unsaved
            await { find("Add to Planner") == null }
            assertFalse(data().tasks.any { it.notes == "https://example.test/recover" })
        } finally { TaskDraftStore(context).clear("new") }
    }
    @Test fun sharingBuffersPrerequisitesAndSavedSearches() = runBlocking {
        fresh()
        app.settings.setSavedSearches(emptyList())
        fun share(body: String, subject: String): MainActivity = ins.startActivitySync(
            Intent(context, MainActivity::class.java).setAction(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, body).putExtra(Intent.EXTRA_SUBJECT, subject)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        var activity = share("https://example.test/booking", "QA confirm dates")
        try {
            click("Add task")
            await { find("Task title") != null || find("QA confirm dates") != null }
            screenshot("shared-task")
            saveAndClose()
            await { data().tasks.any { it.title == "QA confirm dates" } }
            val prerequisite = data().tasks.single { it.title == "QA confirm dates" }
            assertEquals("https://example.test/booking", prerequisite.notes)
            activity = share("Meet at the station", "QA buffered appointment")
            click("Add event")
            click("All day")
            field("Before (min)", "20")
            field("After (min)", "15")
            field("Duration in minutes (optional)", "60")
            screenshot("event-buffers")
            saveAndClose()
            await { data().items.any { it.title == "QA buffered appointment" } }
            val event = data().items.single { it.title == "QA buffered appointment" }
            assertEquals(20, event.bufferBeforeMinutes); assertEquals(15, event.bufferAfterMinutes)
            assertEquals("Meet at the station", event.notes)
            val dependent = PlannerTask(title = "QA book hotel", dueDate = LocalDate.now())
            app.repository.saveTask(dependent)
            ins.runOnMainSync { activity.setContent { ItineraryTheme { TaskEditor(dependent, false) {} } } }
            click("Add prerequisite")
            click("QA confirm dates")
            screenshot("task-prerequisite")
            click("Save")
            await { data().tasks.single { it.id == dependent.id }.prerequisiteIds == listOf(prerequisite.id) }
            com.example.itinerary.widget.TodayWidget.completeTaskIntent(context, dependent.id).send()
            Thread.sleep(1000)
            assertFalse(data().tasks.single { it.id == dependent.id }.done)
            app.repository.setTaskDone(prerequisite.id, true)
            com.example.itinerary.widget.TodayWidget.completeTaskIntent(context, dependent.id).send()
            await { data().tasks.single { it.id == dependent.id }.done }
            // Use the normal Search destination, including save, reopen and remove.
            ins.runOnMainSync { activity.finish() }
            open()
            click("Search")
            field("Search events and tasks", "today")
            click("Save search")
            field("Search name", "QA daily search")
            click("Save")
            await { app.settings.savedSearches.value.any { it.name == "QA daily search" } }
            field("Search events and tasks", "nothing matches")
            click("Saved searches (1)")
            screenshot("saved-searches")
            click("QA daily search")
            await { nodes().any { it.isEditable && it.text?.toString() == "today" } }
            click("Saved searches (1)")
            click("Remove")
            await { app.settings.savedSearches.value.isEmpty() }
            click("Close")
        } finally {
            ins.runOnMainSync { activity.finish() }
            EditorDraftStore(context).clear(); TaskDraftStore(context).clear("new")
        }
    }
}
