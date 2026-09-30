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
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Nothing pops up after a save or an add: Save says "Saved" on the button; Quick entry's "Add another" says "Added: …"
 * in the dialog; its "Add" scrolls the agenda to the new entry, and only a view that can't show it says so in the bar.
 * Real application UI; run only with an external backup/restore harness.
 */
@Suppress("DEPRECATION")
class QuietConfirmationsUiTest {
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
        val dir=File(context.cacheDir,"qa-quiet-confirmations").apply { mkdirs() }
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


    private fun text(prefix:String)=nodes().firstOrNull { it.isVisibleToUser && it.text?.toString()?.startsWith(prefix)==true }
    private fun button(text:String):AccessibilityNodeInfo? {
        var node=find(text)
        while(node!=null && !node.isClickable)node=node.parent
        return node
    }
    private fun enabled(text:String)=button(text)?.isEnabled==true
    private fun reset(range:AgendaRange=AgendaRange.ALL, calendar:Boolean=false) {
        QuickDraftStore(context).clear();EditorDraftStore(context).clear();TaskDraftStore(context).clear("new")
        app.settings.lastViewCalendar=calendar
        app.settings.setAgendaRange(range)
        app.settings.setAgendaTypes(AgendaType.entries.toSet())
    }
    private fun named(date:LocalDate)=date.format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH))
    private fun quickAdd(entry:String, button:String="Add event") {
        click("Quick entry");setText("",entry);click(button)
        if (find("Add anyway")!=null) click("Add anyway")
    }

    @Test fun eventSaveSaysSavedOnTheButton()=runBlocking {
        reset()
        app.repository.saveItemId(ItineraryItem(tripId=0,date=LocalDate.now(),startTime=LocalTime.of(10,0),title="QA quiet event"))
        open()
        click("QA quiet event")
        if (find("Edit event")==null) { Thread.sleep(700); if (find("Edit event")==null) click("QA quiet event") }
        await { find("Edit event")!=null }
        setText("QA quiet event","QA quiet event saved")
        click("Save")
        await { data().items.any { it.title=="QA quiet event saved" } && find("Saved")!=null && find("Save")==null }
        assertFalse("Saved is greyed out", enabled("Saved"))
        screenshot("event-saved")
        setText("QA quiet event saved","QA quiet event changed")
        await { enabled("Save") && find("Saved")==null }
    }

    @Test fun taskSaveSaysSavedOnTheButton()=runBlocking {
        reset()
        app.repository.saveTask(PlannerTask(title="QA quiet task", dueDate=LocalDate.now()))
        open();click("QA quiet task");await { find("Edit task")!=null }
        setText("QA quiet task","QA quiet task saved")
        click("Save")
        await { data().tasks.any { it.title=="QA quiet task saved" } && find("Saved")!=null && find("Save")==null }
        assertFalse(enabled("Saved"))
        screenshot("task-saved")
        setText("QA quiet task saved","QA quiet task changed")
        await { enabled("Save") }
    }

    @Test fun addAnotherSaysAddedInTheDialog()=runBlocking {
        reset();open()
        click("Quick entry");setText("","QA quiet another tomorrow 3pm");click("Add another")
        if (find("Add anyway")!=null) click("Add anyway")
        await { data().items.any { it.title=="QA quiet another" } && text("Added: QA quiet another, ")!=null }
        screenshot("add-another")
        click("Close")
    }

    @Test fun addScrollsTheAgendaToTheNewDay()=runBlocking {
        reset()
        // A long agenda: the new day is far below the first screen.
        for (n in 0 until 30) app.repository.saveItemId(ItineraryItem(tripId=0,date=LocalDate.now().plusDays(n.toLong()),
            startTime=LocalTime.of(9,0),title="QA quiet filler $n"))
        open();await { find("QA quiet filler 0")!=null }
        assertNull(find("QA quiet filler 29"))
        quickAdd("QA quiet far ${named(LocalDate.now().plusDays(45))} 10am")
        await { data().items.any { it.title=="QA quiet far" } && find("QA quiet far")!=null }
        Thread.sleep(2000)
        assertNull("No bar when the list shows it", text("Added: "))
        screenshot("scrolled-to-new-day")
    }

    @Test fun addOutsideTheViewSaysItInTheBar()=runBlocking {
        reset(AgendaRange.THIS_WEEK);open()
        quickAdd("QA quiet hidden ${named(LocalDate.now().plusDays(60))} 10am")
        await { data().items.any { it.title=="QA quiet hidden" } && text("Added: QA quiet hidden, ")!=null }
        assertNull(find("QA quiet hidden"))
        screenshot("bar-outside-view")
    }

    @Test fun aTaskAddedOnTheCalendarIsSaidInTheBar()=runBlocking {
        reset(calendar=true);open()
        click("Quick entry");click("Task");setText("","QA quiet calendar task tomorrow");click("Add task")
        if (find("Add anyway")!=null) click("Add anyway")
        await { data().tasks.any { it.title=="QA quiet calendar task" } && text("Added: QA quiet calendar task, ")!=null }
        screenshot("calendar-task-bar")
    }
}
