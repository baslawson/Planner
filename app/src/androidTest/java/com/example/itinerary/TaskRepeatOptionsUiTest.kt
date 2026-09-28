package com.example.itinerary

import android.content.Intent
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
class TaskRepeatOptionsUiTest {
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
        val dir=File(context.cacheDir,"qa-task-repeat-options").apply { mkdirs() }
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
        app.settings.lastViewCalendar=false // open() expects the agenda
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
        open()
    }
    @Test fun taskRepeatOptionsSaveGenerateNextOccurrenceAndEdit() = runBlocking {
        fresh()
        for ((label,rule) in listOf("Fortnightly" to "FORTNIGHTLY", "Yearly" to "YEARLY")) {
            click("Add menu");click("Add task");click("To-do task")
            field("Task title","QA repeat options $label")
            click("Choose due date");click("Set date")
            click("Never")
            // The list is longer than the menu, so later choices need a scroll inside it.
            fun scrollMenu(forward:Boolean) { nodes().lastOrNull { it.isScrollable && it.isVisibleToUser }
                ?.performAction(if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);Thread.sleep(300) }
            for (option in listOf("Never","Daily","Weekly","Fortnightly","Monthly","Yearly","Every few days…","Every few weeks…",
                "On chosen weekdays…","Monthly on a weekday (e.g. first Monday)…","Days after completion")) {
                repeat(3) { if (find(option)==null) scrollMenu(true) }
                assertNotNull("Missing repeat choice $option",find(option))
            }
            screenshot("task-repeat-dropdown")
            repeat(3) { scrollMenu(false) }
            click(label);click("Save")
            await { data().tasks.any { it.title=="QA repeat options $label" } }
            val original=data().tasks.single { it.title=="QA repeat options $label" }
            assertEquals(rule,original.repeat)
            assertEquals(LocalDate.now(),original.dueDate)
            click("Mark ${original.title} done")
            await { data().tasks.count { it.title==original.title }==2 }
            val next=data().tasks.single { it.title==original.title && it.id!=original.id }
            assertEquals(if (rule=="FORTNIGHTLY") LocalDate.now().plusDays(14) else LocalDate.now().plusYears(1),next.dueDate)
            assertEquals(rule,next.repeat)
            assertEquals(next.id,data().tasks.single { it.id==original.id }.nextTaskId)
            click(next.title);reveal { find(label)!=null };screenshot("saved-${rule.lowercase()}")
            click("Discard")
        }
    }
    @Test fun eventsAndBillsStillOfferFortnightlyAndYearly() {
        fresh()
        for (action in listOf("Add event","Add bill")) {
            click("Add menu");click(action);click("Does not repeat")
            assertNotNull(find("Fortnightly"));assertNotNull(find("Yearly"))
            screenshot(if(action=="Add bill") "bill-repeat-dropdown" else "event-repeat-dropdown")
            click("Yearly");reveal { find("Yearly")!=null };click("Discard")
        }
    }
}
