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
class PlanningFiveUiTest {
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
        ins.waitForIdleSync();Thread.sleep(600)
        val dir=File(context.cacheDir,"qa-planning-five").apply { mkdirs() }
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
    @Test fun freeTimeImportDuplicateScheduleAndWidgetCompletion() = runBlocking {
        fresh()
        data().items.filter { it.title == "QA planning task" || it.title == "QA imported meeting" }.forEach { app.repository.deleteWithUndo(it) }
        cleanFixture("QA planning task")
        data().deleted.filter { it.label == "QA imported meeting" }.forEach { app.repository.permanentlyDelete(it.id) }
        val task = PlannerTask(title = "QA planning task", dueDate = LocalDate.now(),
            checklist = listOf(ChecklistEntry("qa-one", "Pack passport", true)))
        app.repository.saveTask(task)
        val activity = ins.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            ins.runOnMainSync { activity.setContent { ItineraryTheme { TaskEditor(task, false) {} } } }
            click("Duplicate task")
            reveal { find("Choose due date") != null }
            screenshot("duplicate-task")
            click("Save")
            await { data().tasks.count { it.title == task.title } == 2 }
            val duplicate = data().tasks.single { it.title == task.title && it.id != task.id }
            assertNull(duplicate.dueDate); assertFalse(duplicate.checklist.single().done)
            assertEquals(task, data().tasks.single { it.id == task.id })
            click("Schedule time")
            screenshot("schedule-task")
            click("Review time block")
            reveal { find("Linked task: QA planning task · Not completed") != null }
            await { find("Save") != null }
            val saveBounds = android.graphics.Rect()
            find("Save")!!.getBoundsInScreen(saveBounds)
            screenshot("footer-bounds")
            assertTrue("Save must be visible without scrolling the editor: $saveBounds / ${context.resources.displayMetrics.heightPixels}", saveBounds.height() > 0 && saveBounds.bottom <= context.resources.displayMetrics.heightPixels)
            screenshot("linked-event-editor")
            click("Save")
            await { data().items.any { it.linkedTaskId == task.id } }
            assertEquals(60, data().items.single { it.linkedTaskId == task.id }.durationMinutes)
            assertEquals(task.dueDate, data().tasks.single { it.id == task.id }.dueDate)
            click("Discard")
            // A fixture invitation reaches the same review UI as the document picker and external VIEW intent.
            val file = File(context.cacheDir, "qa-planning-invite.ics")
            file.writeText("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VEVENT\r\nDTSTART:20261001T090000\r\nDURATION:PT30M\r\nSUMMARY:QA imported meeting\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n")
            ins.runOnMainSync { activity.setContent { ItineraryTheme {
                CalendarImportDialog({}, Uri.fromFile(file))
            } } }
            click("Review QA imported meeting")
            screenshot("import-editor")
            click("Save")
            await { data().items.any { it.title == "QA imported meeting" } }
            assertEquals(30, data().items.single { it.title == "QA imported meeting" }.durationMinutes)
            screenshot("import-duplicate-warning")
            assertNotNull(find("A matching appointment already exists. Review before adding another."))
            file.delete()
            var chosen: ItineraryItem? = null
            ins.runOnMainSync { activity.setContent { ItineraryTheme { FreeTimeDialog(emptyList(), {}, { chosen = it }) } } }
            click("Find gaps")
            reveal { nodes().any { it.isVisibleToUser && it.text?.toString()?.contains("–") == true && it.text?.toString()?.contains(" · ") == true } }
            screenshot("free-time-results")
            // The last visible gap, on a later day: today's first gap starts "now" and the clock can pass it.
            val gap = nodes().last { it.isVisibleToUser && it.text?.toString()?.contains("–") == true && it.text?.toString()?.contains(" · ") == true }.text.toString()
            click(gap); click("Set time")
            await { chosen != null }
            assertEquals(60, chosen!!.durationMinutes)
            // Dispatch the real immutable widget action twice: completion is idempotent.
            com.example.itinerary.widget.TodayWidget.completeTaskIntent(context, task.id).send()
            await { data().tasks.single { it.id == task.id }.done }
            com.example.itinerary.widget.TodayWidget.completeTaskIntent(context, task.id).send()
            Thread.sleep(500)
            assertEquals(2, data().tasks.count { it.title == task.title })
            assertEquals(1, data().items.count { it.linkedTaskId == task.id })
        } finally {
            ins.runOnMainSync { activity.finish() }
            EditorDraftStore(context).clear(); TaskDraftStore(context).clear(task.id); TaskDraftStore(context).clear("new")
            // The external harness restores the user's original files and rows after all checks.
        }
    }
    @Test fun freeTimeSearchRevealsResultsAndEmptyStateWithoutManualScrolling() {
        val activity = ins.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        var chosen: ItineraryItem? = null
        var dismissed=0
        fun resultHeading() = nodes().firstOrNull { it.isVisibleToUser && it.text?.toString()?.matches(Regex("[0-9]+ available gaps?"))==true }
        try {
            ins.runOnMainSync { activity.setContent { ItineraryTheme {
                key("available") { FreeTimeDialog(emptyList(), { dismissed++ }, { chosen=it }) }
            } } }
            await { find("Back")!=null };screenshot("full-screen-form")
            click("Back");await { dismissed==1 }
            click("Find gaps")
            await { resultHeading()!=null }
            await { nodes().any { it.isVisibleToUser && it.text?.toString()?.contains("–")==true && it.text?.toString()?.contains(" · ")==true } }
            assertNotNull(find("Find free time"));assertNotNull(find("Back"))
            screenshot("results-visible-after-search")
            assertNull(chosen)
            scrollToTop { nodes() }
            click("Find gaps")
            await { resultHeading()!=null };screenshot("repeat-search-visible")
            // The last visible gap, on a later day: today's first gap starts "now" and the clock can pass it.
            val gap=nodes().last { it.isVisibleToUser && it.text?.toString()?.contains("–")==true && it.text?.toString()?.contains(" · ")==true }.text.toString()
            click(gap);click("Set time");await { chosen!=null };assertEquals(60,chosen!!.durationMinutes)
            val blocked=(0L..6L).map { ItineraryItem(tripId=0,date=LocalDate.now().plusDays(it),startTime=null,title="Blocked") }
            ins.runOnMainSync { activity.setContent { ItineraryTheme {
                key("blocked") { FreeTimeDialog(blocked, { dismissed++ }, { fail("A blocked day must not produce an event") }) }
            } } }
            click("Find gaps")
            await { find("No available gaps")!=null }
            screenshot("empty-state-visible-after-search")
            assertNotNull(find("Try fewer minutes, wider daily hours, or different dates. Check whether all-day events block those days."))
            ins.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            await { dismissed==2 }
        } finally { ins.runOnMainSync { activity.finish() } }
    }

}
