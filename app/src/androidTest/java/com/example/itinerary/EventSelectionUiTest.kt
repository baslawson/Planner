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
class EventSelectionUiTest {
    private val ins get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=ins.targetContext
    private val app get()=context.applicationContext as ItineraryApp
    private fun data()=runBlocking { app.repository.snapshot() }
    private fun nodes():List<AccessibilityNodeInfo> {
        if (android.os.Build.VERSION.SDK_INT >= 33) ins.uiAutomation.clearCache()
        val result=mutableListOf<AccessibilityNodeInfo>()
        fun visit(n:AccessibilityNodeInfo) { result+=n;for(i in 0 until n.childCount)n.getChild(i)?.let(::visit) }
        ins.uiAutomation.rootInActiveWindow?.let(::visit);return result
    }
    private fun find(text:String)=nodes().firstOrNull { it.isVisibleToUser && !it.isEditable && (it.text?.toString()==text || it.contentDescription?.toString()==text) }
    private fun screenshot(name:String) {
        val dir=File(context.cacheDir,"qa-selection-evidence").apply { mkdirs() }
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
        reveal { nodes().any { it.isVisibleToUser && it.isEditable && it.text?.toString()==old } }
        val node=nodes().first { it.isVisibleToUser && it.isEditable && it.text?.toString()==old }
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,value)
        }));Thread.sleep(350)
    }
    private fun open(action:String?=null) {
        ins.startActivitySync(Intent(context,MainActivity::class.java).setAction(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        await {
            when(action) {
                EntryShortcuts.SCAN -> find("How would you like to save the scan?")!=null
                null -> find("Agenda")!=null || find("Discard")!=null
                else -> find("Discard")!=null
            }
        }
    }


    private fun longTouch(text: String) {
        reveal { find(text) != null }
        val rect = android.graphics.Rect()
        find(text)!!.getBoundsInScreen(rect)
        val start = SystemClock.uptimeMillis()
        val down = android.view.MotionEvent.obtain(start, start, android.view.MotionEvent.ACTION_DOWN, rect.centerX().toFloat(), rect.centerY().toFloat(), 0)
        ins.uiAutomation.injectInputEvent(down, true); down.recycle()
        Thread.sleep(800)
        val up = android.view.MotionEvent.obtain(start, SystemClock.uptimeMillis(), android.view.MotionEvent.ACTION_UP, rect.centerX().toFloat(), rect.centerY().toFloat(), 0)
        ins.uiAutomation.injectInputEvent(up, true); up.recycle()
        await { find("1 selected") != null }
    }

    @Test fun selectMixedEventsConfirmCancelDeleteUndoAndUseOtherViews() = runBlocking {
        EditorDraftStore(context).clear()
        app.settings.setAgendaRange(AgendaRange.ALL)
        val day = LocalDate.now()
        app.repository.saveItem(ItineraryItem(tripId=0, date=day, startTime=java.time.LocalTime.NOON, title="QA select timed", category="Work"))
        app.repository.saveItem(ItineraryItem(tripId=0, date=day, startTime=null, title="QA select paid", category="Bills", paid=true, billAmountMinor=500))
        app.repository.saveItem(ItineraryItem(tripId=0, date=day, startTime=null, title="QA select repeat", skipped=true), options=EventSaveOptions(RepeatRule.DAILY,2))
        val repeat = data().items.first { it.title=="QA select repeat" }
        app.repository.saveItem(repeat.copy(skipped=true))
        app.repository.saveItem(ItineraryItem(tripId=0, date=day.minusDays(1), startTime=java.time.LocalTime.of(23,30), durationMinutes=120, title="QA select overnight"))
        val before = data()
        open();setText("", "QA select")
        longTouch("QA select overnight")
        click("QA select paid");await { find("2 selected")!=null }
        click("Delete");await { find("Delete 2 events?")!=null };screenshot("confirmation")
        assertEquals(before,data())
        click("Keep events");assertEquals(before,data())
        click("Cancel");await { find("Add menu")!=null }
        longTouch("QA select paid")
        click("QA select timed");await { find("2 selected")!=null };screenshot("agenda-selection")
        click("Delete");click("Delete 2 events")
        val deletedIds=before.items.filter { it.title in setOf("QA select paid","QA select timed") }.map { it.id }.toSet()
        await { data().items.none { it.id in deletedIds } }
        assertEquals(before.items.filterNot { it.id in deletedIds },data().items)
        click("Undo");await { data()==before }
        // Filter changes must not leave invisible events selected.
        longTouch("QA select overnight");setText("QA select","QA select timed")
        await { find("1 selected")==null };assertNull(find("Delete"))
        click("QA select timed");await { find("Calendar")!=null }
        longTouch("QA select paid");click("QA select repeat");await { find("2 selected")!=null }
        screenshot("calendar-selection");click("Delete");click("Keep events")
        ins.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        await { find("1 selected")==null && find("2 selected")==null }
        // Plain tap still opens the editor after leaving selection mode.
        click("QA select timed");await { find("Discard")!=null };click("Discard")
        click("Search");await { find("Search events and tasks")!=null };setText("", "QA select")
        longTouch("QA select overnight");click("QA select paid");await { find("2 selected")!=null }
        screenshot("search-selection");click("Delete");click("Keep events");click("Cancel")
        assertEquals(before,data())
    }
}
