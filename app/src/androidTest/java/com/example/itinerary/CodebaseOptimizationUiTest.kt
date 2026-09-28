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

/** Real application UI; run only with an external backup/restore harness. */
@Suppress("DEPRECATION")
class CodebaseOptimizationUiTest {
    private val ins get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=ins.targetContext
    private val app get()=context.applicationContext as ItineraryApp
    private fun data()=runBlocking { app.repository.snapshot() }
    private fun nodes():List<AccessibilityNodeInfo> {
        val result=mutableListOf<AccessibilityNodeInfo>()
        fun visit(n:AccessibilityNodeInfo) { result+=n;for(i in 0 until n.childCount)n.getChild(i)?.let(::visit) }
        ins.uiAutomation.freshRoot?.let(::visit);return result
    }
    private fun find(text:String)=nodes().firstOrNull { it.isVisibleToUser && !it.isEditable && (it.text?.toString()==text || it.contentDescription?.toString()==text) }
    private fun screenshot(name:String) {
        val dir=File(context.cacheDir,"qa-codebase-evidence").apply { mkdirs() }
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
                    // The event list comes after the month pager; scrolling the first container
                    // changes calendar months instead of revealing an off-screen event.
                    if(nodes().lastOrNull { it.isVisibleToUser && it.isScrollable && !it.isEditable && it.actionList.any { a -> a.id == action } }?.performAction(action)!=true)forward=!forward
                    Thread.sleep(350)
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
        if (text in setOf("Add event", "Add bill", "Quick entry") && find(text)==null && find("Add menu")!=null) click("Add menu")
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
    private fun open(action:String?=null) {
        app.settings.lastViewCalendar=false // open() expects the agenda
        ins.startActivitySync(Intent(context,MainActivity::class.java).setAction(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        await {
            when(action) {
                EntryShortcuts.SCAN -> find("How would you like to save the scan?")!=null
                null -> find("AGENDA")!=null || find("Discard")!=null
                else -> find("Discard")!=null
            }
        }
    }


    private fun longTouch(text: String) {
        reveal { find(text) != null }
        var node=find(text)
        while(node!=null && !node.isLongClickable) node=node.parent
        assertTrue("No long-click action for $text", node?.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)==true)
        await { find("1 selected") != null }
    }

    @Test fun agendaCalendarAndSearchSelectionRefreshAfterChanges() = runBlocking {
        assertNull(EditorDraftStore(context).read())
        app.settings.setAgendaRange(AgendaRange.TODAY)
        app.settings.setAgendaTypes(setOf(AgendaType.EVENTS))
        val day=LocalDate.now()
        for (title in listOf("QA optimization alpha","QA optimization beta")) {
            app.repository.saveItem(ItineraryItem(tripId=0,date=day,startTime=null,title=title))
        }
        app.repository.saveItem(ItineraryItem(tripId=0,date=day.plusDays(1),startTime=null,title="QA optimization future"))
        app.repository.saveItem(ItineraryItem(tripId=0,date=day,startTime=null,title="QA optimization bill",category="Bills"))
        val before=data()
        open()
        click("Show bills");click("Show events")
        reveal { find("QA optimization bill")!=null }
        assertNull(find("QA optimization alpha"))
        click("Show events");click("Show bills")
        longTouch("QA optimization alpha");click("QA optimization beta")
        await { find("2 selected")!=null }
        click("Delete");click("Keep events")
        assertEquals(before,data())
        click("Delete");click("Delete 2 events")
        await { data().items.none { it.title in setOf("QA optimization alpha","QA optimization beta") } }
        click("Undo");await { data()==before }
        screenshot("agenda-undo")
        click("All");longTouch("QA optimization future");click("Today")
        await { find("1 selected")==null }
        click("QA optimization alpha");await { find("Calendar")!=null }
        longTouch("QA optimization alpha");click("QA optimization beta")
        await { find("2 selected")!=null }
        click("Delete");click("Keep events");click("Cancel")
        screenshot("calendar")
        click("Search");await { find("Search events and tasks")!=null }
        setText("","QA optimization")
        longTouch("QA optimization alpha");click("QA optimization beta")
        await { find("2 selected")!=null }
        click("Delete");click("Keep events")
        setText("QA optimization","QA optimization bill")
        await { find("2 selected")==null && find("1 selected")==null }
        reveal { find("QA optimization bill")!=null }
        screenshot("search-filter")
        assertEquals(before,data())
    }
}
