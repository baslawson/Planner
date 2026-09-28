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
class TasksUiTest {
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
        val dir=File(context.cacheDir,"qa-tasks-evidence").apply { mkdirs() }
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


    private fun back() {
        ins.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        Thread.sleep(350)
    }
    private fun cleanFixture(prefix:String)=runBlocking {
        data().tasks.filter { it.title.startsWith(prefix) }.forEach { app.repository.deleteTask(it.id) }
        data().deleted.filter { it.label.startsWith(prefix) }.forEach { app.repository.permanentlyDelete(it.id) }
    }
    @Test fun createCompleteReopenEditDeleteAndUndoTaskFromAgenda()=runBlocking {
        cleanFixture("QA task editor")
        app.settings.setAgendaRange(AgendaRange.ALL)
        open();click("Add menu")
        for(label in listOf("Add task","Add event","Quick entry")) assertNotNull(label,find(label))
        assertNull(find("Add bill"));assertNull(find("Scan bill"))
        screenshot("add-task-menu");click("Add task");click("To-do task")
        await { find("Task title")!=null }
        setText("","QA task editor");screenshot("new-task-editor");click("Save")
        await { data().tasks.any { it.title=="QA task editor" } }
        var task=data().tasks.single { it.title=="QA task editor" }
        assertNull(task.dueDate);assertEquals(TaskPriority.NORMAL,task.priority)
        reveal { find("QA task editor")!=null };screenshot("anytime-task")
        click("Mark QA task editor done");await { data().tasks.single { it.id==task.id }.done }
        await { find("QA task editor")==null };click("Show completed tasks");reveal { find("QA task editor")!=null }
        click("Mark QA task editor incomplete");await { !data().tasks.single { it.id==task.id }.done }
        click("QA task editor");click("High");click("Choose due date");click("Set date")
        setText("","Unique mechanic note")
        screenshot("task-editor-dated");click("Save")
        await { data().tasks.single { it.id==task.id }.dueDate==LocalDate.now() }
        task=data().tasks.single { it.id==task.id }
        assertEquals(TaskPriority.HIGH,task.priority);assertEquals("Unique mechanic note",task.notes)
        click("QA task editor");click("Delete");click("Keep task")
        assertEquals(task,data().tasks.single { it.id==task.id })
        click("Delete");await { find("Delete task?")!=null };click("Delete")
        await { data().tasks.none { it.id==task.id } };click("Undo")
        await { data().tasks.any { it.id==task.id } };assertEquals(task,data().tasks.single { it.id==task.id })
        click("QA task editor");click("Remove due date");click("Save")
        await { data().tasks.single { it.id==task.id }.dueDate==null }
        click("QA task editor");setText("QA task editor","Discarded edit");click("Discard")
        assertEquals("QA task editor",data().tasks.single { it.id==task.id }.title)
    }
    @Test fun datedPriorityOverdueAndAnytimeTasksStayInAgendaAndSearchCanEditThem()=runBlocking {
        cleanFixture("QA task view")
        val today=LocalDate.now()
        val low=PlannerTask(title="QA task view low",dueDate=today,priority=TaskPriority.LOW)
        val high=PlannerTask(title="QA task view high",dueDate=today,priority=TaskPriority.HIGH)
        val overdue=PlannerTask(title="QA task view overdue",dueDate=today.minusDays(2))
        val anytime=PlannerTask(title="QA task view anytime",notes="Unicorn wrench")
        val completed=PlannerTask(title="QA task view completed",done=true)
        listOf(low,high,overdue,anytime,completed).forEach { app.repository.saveTask(it) }
        app.settings.setAgendaRange(AgendaRange.TODAY)
        open() // the agenda has no text filter; reveal scrolls to each task
        reveal { find("QA task view overdue")!=null };screenshot("overdue-task-today")
        reveal { find("QA task view high")!=null && find("QA task view low")!=null }
        // Measure only once the list has stopped moving after the last swipe, with both cards fully in view.
        fun rects()=listOf("QA task view high","QA task view low").map { t -> android.graphics.Rect().also { r -> find(t)?.getBoundsInScreen(r) } }
        var settled=rects()
        await { val a=rects();Thread.sleep(400);settled=rects();a==settled && settled.all { it.height()>20 } }
        val (h,l)=settled
        screenshot("task-priority-order");assertTrue("high $h should be above low $l",h.top<l.top)
        click("Upcoming");reveal { find("QA task view overdue")!=null }
        assertEquals(today.minusDays(2),data().tasks.single { it.id==overdue.id }.dueDate)
        // Find the heading whatever its count, and check the fold by the cards themselves.
        fun anytimeHeading()=nodes().firstOrNull { it.isVisibleToUser && it.text?.toString()?.startsWith("Anytime tasks (")==true }?.text?.toString()
        reveal { find("QA task view anytime")!=null && anytimeHeading()!=null };click(anytimeHeading()!!)
        await { find("QA task view anytime")==null };screenshot("anytime-collapsed")
        click(anytimeHeading()!!);reveal { find("QA task view anytime")!=null }
        click("Search");setText("","Unicorn wrench")
        reveal { find("QA task view anytime")!=null };screenshot("task-search")
        click("QA task view anytime");setText("QA task view anytime","QA task view renamed");click("Save")
        await { data().tasks.single { it.id==anytime.id }.title=="QA task view renamed" }
        setText("Unicorn wrench","QA task view completed");await { find("Nothing found. Try fewer words or check the spelling.")!=null }
        click("Show completed tasks");reveal { find("QA task view completed")!=null }
        click("Mark QA task view completed incomplete");await { !data().tasks.single { it.id==completed.id }.done }
        // Tasks are separate records and never appear as calendar events.
        assertTrue(data().items.none { it.title.startsWith("QA task view") })
        back()
    }
    @HarnessStage @Test fun prepareColdRecoveryFixtures()=runBlocking {
        cleanFixture("QA task cold")
        val done=PlannerTask(title="QA task cold done",dueDate=LocalDate.now().minusDays(2),priority=TaskPriority.HIGH,notes="Retain completed notes",done=true)
        val anytime=PlannerTask(title="QA task cold anytime",priority=TaskPriority.LOW,notes="Retain undated notes")
        val deleted=PlannerTask(title="QA task cold deleted",dueDate=LocalDate.now().plusDays(2),notes="Recover these notes")
        listOf(done,anytime,deleted).forEach { app.repository.saveTask(it) }
        app.repository.deleteTask(deleted.id)
        app.repository.finishDeletion(app.repository.pendingDeletions.value.single { it.tasks.any { t -> t.id==deleted.id } }.token)
        assertTrue(data().tasks.any { it==done });assertTrue(data().tasks.any { it==anytime })
        assertTrue(data().deleted.any { DeletedCodec.decode(it.payload).tasks.contains(deleted) })
    }
    @Test fun deletedTaskAppearsInRecentlyDeletedAndCanBeRestored()=runBlocking {
        cleanFixture("QA task trash")
        val task=PlannerTask(title="QA task trash",priority=TaskPriority.HIGH,notes="Keep these notes")
        app.repository.saveTask(task);app.repository.deleteTask(task.id)
        app.repository.finishDeletion(app.repository.pendingDeletions.value.single { it.tasks.any { t -> t.id==task.id } }.token)
        open();click("Settings");click("Recently deleted")
        reveal { find("QA task trash")!=null };screenshot("task-recently-deleted")
        // Locate the Restore action belonging to this task, rather than another archived record.
        fun descendants(n:AccessibilityNodeInfo):List<AccessibilityNodeInfo> = listOf(n)+(0 until n.childCount).flatMap { i -> n.getChild(i)?.let(::descendants).orEmpty() }
        var group=find("QA task trash")!!.parent
        while(group!=null && descendants(group).none { it.text?.toString()=="Restore" }) group=group.parent
        val restore=descendants(checkNotNull(group)).first { it.text?.toString()=="Restore" }
        var button:AccessibilityNodeInfo?=restore
        while(button!=null && !button.isClickable)button=button.parent
        assertTrue(button!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        await { data().tasks.any { it.id==task.id } };assertEquals(task,data().tasks.single { it.id==task.id })
        click("Close")
    }
}
