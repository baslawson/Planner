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
import com.example.itinerary.ui.fullLabel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.LocalDate

/** Real application UI; run only with an external backup/restore harness. */
@Suppress("DEPRECATION")
class FreeTimeReturnUiTest {
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
        val dir=File(context.cacheDir,"qa-free-time-return").apply { mkdirs() }
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
                    hideQuickTestKeyboard(ins)
                    // Gesture targets the foreground surface, never the Agenda or a nested text field's semantics.
                    if (tries % 20 == 0) forward = !forward
                    val metrics = context.resources.displayMetrics
                    val x = metrics.widthPixels * .65f
                    val from = metrics.heightPixels * (if (forward) .72f else .3f)
                    val to = metrics.heightPixels * (if (forward) .3f else .72f)
                    val down = SystemClock.uptimeMillis()
                    for (step in 0..12) {
                        val action = when (step) { 0 -> android.view.MotionEvent.ACTION_DOWN; 12 -> android.view.MotionEvent.ACTION_UP; else -> android.view.MotionEvent.ACTION_MOVE }
                        val event = android.view.MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, from + (to - from) * step / 12, 0)
                        ins.uiAutomation.injectInputEvent(event, true);event.recycle();Thread.sleep(20)
                    }
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
        ins.startActivitySync(Intent(context,MainActivity::class.java).setAction(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        await {
            when(action) {
                EntryShortcuts.SCAN -> find("How would you like to save the scan?")!=null
                null -> find("Add menu")!=null || find("Close")!=null
                else -> find("Close")!=null
            }
        }
    }


    private fun start() {
        QuickDraftStore(context).clear()
        EditorDraftStore(context).clear()
        TaskDraftStore(context).clear("new")
        app.settings.lastViewCalendar=false
        app.settings.setAgendaRange(AgendaRange.ALL)
        app.settings.setAgendaTypes(AgendaType.entries.toSet())
        open();click("Quick entry")
    }

    private var originalPending=emptySet<String>()
    private var originalItems=emptySet<Long>()
    private var originalTasks=emptySet<String>()
    @org.junit.Before fun rememberOriginalIds() { originalPending=app.repository.pendingDeletions.value.map { it.token }.toSet();originalItems=data().items.map { it.id }.toSet();originalTasks=data().tasks.map { it.id }.toSet() }
    @org.junit.After fun removeTestAlarms()=runBlocking {
        app.repository.deleteEventsWithUndo(data().items.map { it.id }.toSet()-originalItems)
        data().tasks.filter { it.id !in originalTasks }.forEach { app.repository.deleteTask(it.id) }
        app.repository.pendingDeletions.value.filter { it.token !in originalPending }.forEach { app.repository.finishDeletion(it.token) }
    }

    @Test fun discardRestoresAgendaSearchAndSaveFinishesIt() = checkReturn(false)
    @Test fun discardRestoresCalendarSearchAndSaveFinishesIt() = checkReturn(true)

    private fun checkReturn(calendar: Boolean) {
        EditorDraftStore(context).clear()
        app.settings.lastViewCalendar=false
        open()
        if (calendar) { click("Switch to Calendar view");await { find("Switch to Agenda view")!=null } }
        click("More options");click("Find free time")
        setText("60","45");hideQuickTestKeyboard(ins);click("Find gaps")
        // The last visible gap, on a later day: today's first gap starts "now", and the clock could pass it between the
        // two visits below, which the app then rightly refuses as a start in the past.
        fun gapNode() = nodes().lastOrNull { it.isVisibleToUser && it.text?.toString()?.contains("–")==true && it.text?.toString()?.contains(" · ")==true }
        await { gapNode()!=null }
        val gap=gapNode()!!.text.toString()
        val summary=nodes().first { it.isVisibleToUser && it.text?.toString()?.matches(Regex("[0-9]+ available gaps?"))==true }.text.toString()
        ins.waitForIdleSync();Thread.sleep(500)
        val before=android.graphics.Rect();find(gap)!!.getBoundsInScreen(before)
        screenshot("before-gap")
        val idsBefore=data().items.map { it.id }.toSet()
        click(gap);click("Set time");await { find("New event")!=null }
        click("Close") // nothing typed, so the editor closes at once, back to Find free time
        await { find("Find free time")!=null && find(gap)!=null && find(summary)!=null }
        ins.waitForIdleSync();Thread.sleep(500)
        val after=android.graphics.Rect();find(gap)!!.getBoundsInScreen(after)
        screenshot("after-discard")
        assertTrue("Search scroll changed: $before -> $after", kotlin.math.abs(before.top-after.top)<=4)
        assertNull(find("Set time"));assertNull(EditorDraftStore(context).read())
        assertEquals(idsBefore,data().items.map { it.id }.toSet())
        screenshot(if(calendar) "calendar-discard-return" else "agenda-discard-return")
        click(gap);click("Set time");await { find("New event")!=null }
        val title="QA free time return " + if(calendar) "calendar" else "agenda"
        reveal { nodes().any { it.isVisibleToUser && it.isEditable && (it.text.isNullOrEmpty() || it.text.toString()=="What are you doing?") } }
        val field=nodes().first { it.isVisibleToUser && it.isEditable && (it.text.isNullOrEmpty() || it.text.toString()=="What are you doing?") }
        assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,title)
        }))
        hideQuickTestKeyboard(ins);click("Save")
        if(find("Add anyway")!=null)click("Add anyway")
        click("Close") // Save keeps the editor open; once saved, Close leaves without going back to Find free time
        await { data().items.any { it.title==title } }
        await { find(if(calendar) "Switch to Agenda view" else "Switch to Calendar view")!=null }
        assertNull(find("Find free time"))
        assertEquals(45,data().items.single { it.title==title }.durationMinutes)
        screenshot(if(calendar) "calendar-after-save" else "agenda-after-save")
        click("More options");click("Find free time")
        await { nodes().count { it.isEditable && it.text?.toString()=="60" }==2 }
        assertTrue(nodes().none { it.text?.toString()?.matches(Regex("[0-9]+ available gaps?"))==true })
        click("Back")
    }
}
