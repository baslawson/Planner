package com.example.itinerary

import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
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

/** Every card has a ⋮: tasks (Due tomorrow + Undo, Share, Delete + Undo), Search's event results, and events from other
 *  calendars (Copy to Planner, Share). Real application UI; run only with an external backup/restore harness. */
@Suppress("DEPRECATION")
class CardMenusUiTest {
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
        val dir=File(context.cacheDir,"qa-card-menus").apply { mkdirs() }
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
    private val today = LocalDate.now()
    private fun longPress(text:String) {
        await { button(text)?.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)==true };Thread.sleep(350)
    }
    private fun start() {
        app.settings.lastViewCalendar=false
        app.settings.setAgendaRange(AgendaRange.ALL)
        app.settings.setAgendaTypes(AgendaType.entries.toSet())
        open()
    }
    private fun catching(action:String, block:()->Unit):Int {
        val monitor=Instrumentation.ActivityMonitor(IntentFilter(action).apply { addCategory(Intent.CATEGORY_DEFAULT) },Instrumentation.ActivityResult(0,null),true)
        ins.addMonitor(monitor)
        try { block();await(10000) { monitor.hits>0 } } finally { ins.removeMonitor(monitor) }
        return monitor.hits
    }

    private fun taskNow(id:String)=runBlocking { app.repository.task(id) }

    @Test fun taskMenuMovesSharesAndDeletes()=runBlocking {
        val task=PlannerTask(title="QA menu task",dueDate=today,notes="Bring forms");app.repository.saveTask(task)
        start();await { find("Actions for QA menu task")!=null }
        click("Actions for QA menu task");await { find("Due tomorrow")!=null && find("Share task")!=null && find("Delete task")!=null }
        screenshot("task-menu")
        click("Due tomorrow")
        await { taskNow(task.id)?.dueDate==today.plusDays(1) && find("Moved QA menu task to tomorrow")!=null }
        click("Undo")
        await { taskNow(task.id)?.dueDate==today }
        // Share goes to the share sheet (caught here, nothing opens).
        click("Actions for QA menu task")
        assertEquals(1,catching(Intent.ACTION_CHOOSER) { click("Share task") })
        click("Actions for QA menu task");click("Delete task")
        await { taskNow(task.id)==null && find("Task deleted")!=null }
        click("Undo")
        await { taskNow(task.id)!=null }
    }

    @Test fun undoingATaskMoveLeavesALaterEditAlone()=runBlocking {
        val repo=app.repository
        val task=PlannerTask(title="QA edited after move",dueDate=today);repo.saveTask(task)
        repo.moveTaskToTomorrow(task.id,today)
        val move=repo.pendingMoves.value.last { it.task?.first?.id==task.id }
        repo.saveTask(repo.task(task.id)!!.copy(title="QA edited since"),create=false)
        assertFalse(repo.undoMove(move.token))
        assertEquals(listOf("QA edited since",today.plusDays(1)),repo.task(task.id)!!.let { listOf(it.title,it.dueDate) })
    }

    @Test fun searchResultsHaveTheEventMenu()=runBlocking {
        app.repository.saveItemId(ItineraryItem(tripId=0,date=today,startTime=LocalTime.of(10,0),title="QA search menu"))
        start();click("Search");await { find("Search events and tasks")!=null };setText("","QA search menu")
        await { find("Actions for QA search menu")!=null }
        click("Actions for QA search menu");await { find("Move to tomorrow")!=null && find("Share event")!=null }
        screenshot("search-menu")
    }

    @Test fun eventsFromOtherCalendarsCanBeCopiedFromTheirMenu()=runBlocking {
        val dao=app.database.outsideDao()
        val work=dao.insertSource(CalendarSource(account="https://cloud.test/|qa",href="/remote.php/dav/calendars/qa/menu/",name="QA Menu cal",
            color=0xFF2E7D32.toInt(),enabled=true))
        dao.insertEvents(listOf(OutsideEvent(sourceId=work,date=today,startTime=LocalTime.of(14,0),durationMinutes=45,title="QA outside menu")))
        start();await { find("Actions for QA outside menu")!=null }
        click("Actions for QA outside menu");await { find("Copy to Planner")!=null && find("Share event")!=null }
        assertNull(find("Move to tomorrow"))
        screenshot("outside-menu")
        click("Copy to Planner")
        await { find("New event")!=null && pickEditable(nodes(),"QA outside menu")!=null }
        // Nothing is saved until the editor saves.
        assertTrue(data().items.none { it.title=="QA outside menu" })
    }
}
