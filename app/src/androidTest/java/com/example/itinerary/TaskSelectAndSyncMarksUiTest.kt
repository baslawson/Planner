package com.example.itinerary

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.example.itinerary.data.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.LocalTime

/**
 * Task cards join the long-press selection (deleted with events, one Undo); a Planner event on Nextcloud says so, and
 * an event from another calendar has its own tinted card. Real application UI; run only with an external
 * backup/restore harness.
 */
@Suppress("DEPRECATION")
class TaskSelectAndSyncMarksUiTest {
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
        val dir=File(context.cacheDir,"qa-task-select-sync-marks").apply { mkdirs() }
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
    @After fun lightTheme() { app.settings.setThemeMode(ThemeMode.SYSTEM) }

    @Test fun eventsAndTasksAreDeletedTogetherWithOneUndo()=runBlocking {
        val repo=app.repository
        val id=repo.saveItemId(ItineraryItem(tripId=0,date=today,startTime=LocalTime.of(10,0),title="QA data event"))
        val task=PlannerTask(title="QA data task",dueDate=today);repo.saveTask(task)
        repo.deleteEventsWithUndo(setOf(id),setOf(task.id))
        assertTrue(data().items.none { it.id==id } && data().tasks.none { it.id==task.id })
        val pending=repo.pendingDeletions.value.last()
        assertEquals(listOf(1,1),listOf(pending.items.size,pending.tasks.size))
        repo.undoDeletion(pending.token)
        assertTrue(data().items.any { it.title=="QA data event" } && data().tasks.any { it.title=="QA data task" })
    }

    @Test fun longPressSelectsTaskCardsWithEvents()=runBlocking {
        app.repository.saveItemId(ItineraryItem(tripId=0,date=today,startTime=LocalTime.of(10,0),title="QA mix event"))
        app.repository.saveTask(PlannerTask(title="QA mix task",dueDate=today))
        start();await { find("QA mix task")!=null && find("QA mix event")!=null }
        longPress("QA mix task")
        await { find("1 selected")!=null }
        // While selecting, a tap selects instead of opening.
        click("QA mix event")
        await { find("2 selected")!=null }
        assertNull(find("Edit task"));assertNull(find("Edit event"))
        screenshot("task-and-event-selected")
        click("Delete")
        await { find("Delete 2 items?")!=null }
        click("Delete 2 items")
        await { data().items.none { it.title=="QA mix event" } && data().tasks.none { it.title=="QA mix task" } && find("2 items deleted")!=null }
        click("Undo")
        await { data().items.any { it.title=="QA mix event" } && data().tasks.any { it.title=="QA mix task" } }
        // A tap on a task with nothing selected still opens it.
        await { find("QA mix task")!=null };click("QA mix task");await { find("Edit task")!=null }
    }

    @Test fun syncedAndOutsideCardsAreMarked()=runBlocking {
        val synced=app.repository.saveItemId(ItineraryItem(tripId=0,date=today,startTime=LocalTime.of(9,0),title="QA on Nextcloud"))
        val conflict=app.repository.saveItemId(ItineraryItem(tripId=0,date=today,startTime=LocalTime.of(11,0),title="QA sync conflict"))
        app.repository.saveItemId(ItineraryItem(tripId=0,date=today,startTime=LocalTime.of(12,0),title="QA only here"))
        val sent=app.database.sentDao()
        sent.put(SentEvent(itemId=synced,account="https://cloud.test/|qa",calendar="/cal/",uid="qa-1",fingerprint="x"))
        sent.put(SentEvent(itemId=conflict,account="https://cloud.test/|qa",calendar="/cal/",uid="qa-2",fingerprint="x",problem=SentEvent.CONFLICT))
        val dao=app.database.outsideDao()
        // Marked only while that calendar is the one kept in sync.
        val planner=dao.insertSource(CalendarSource(account="https://cloud.test/|qa",href="/cal/",name="QA Planner",enabled=true,sendHere=true))
        val work=dao.insertSource(CalendarSource(account="https://cloud.test/|qa",href="/remote.php/dav/calendars/qa/work/",name="QA Work",
            color=0xFF1565C0.toInt(),enabled=true))
        dao.insertEvents(listOf(OutsideEvent(sourceId=work,date=today,startTime=LocalTime.of(13,0),durationMinutes=30,title="QA from work")))
        start()
        await { find("QA on Nextcloud")!=null && find("QA from work")!=null }
        await { find("Nextcloud")!=null && find("Nextcloud: check Calendars")!=null && find("From QA Work")!=null }
        assertEquals(1,nodes().count { it.isVisibleToUser && it.text?.toString()=="Nextcloud" })
        screenshot("marks-light")
        app.settings.setThemeMode(ThemeMode.DARK)
        Thread.sleep(1200)
        await { find("From QA Work")!=null }
        screenshot("marks-dark")
        // Two-way sync turned off: the rows stay, the marks go.
        dao.updateSource(dao.source(planner)!!.copy(sendHere=false,enabled=false))
        await { find("Nextcloud")==null && find("Nextcloud: check Calendars")==null && find("QA on Nextcloud")!=null }
    }
}
