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
class QuickReviewUiTest {
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
        val dir=File(context.cacheDir,"qa-quick-review-evidence").apply { mkdirs() }
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
        // The original calendar can legitimately overlap a test fixture; conflict behavior has its own tests.
        if (text.startsWith("Add ") && find("Check before adding") != null) click("Add anyway")
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
                null -> find("Add menu")!=null || find("Discard")!=null
                else -> find("Discard")!=null
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
    @Test fun dateAndTimeClarificationButtonsPreserveTheTitle()=withDateFormat(app,ins,com.example.itinerary.data.DateFormatChoice.ISO) { clarificationButtons() }
    private fun clarificationButtons()=runBlocking {
        start();setText("","QA clarified 03/04/2027 at 3")
        await { find("Which date did you mean?")!=null };screenshot("date-choices")
        val date=LocalDate.of(2027,4,3)
        click(date.fullLabel())
        await { find("3 PM")!=null };screenshot("time-choices");click("3 PM")
        click("Add event");await { data().items.any { it.title=="QA clarified" } }
        val saved=data().items.single { it.title=="QA clarified" }
        assertEquals(date,saved.date);assertEquals(java.time.LocalTime.of(15,0),saved.startTime)
    }
    @Test fun keepInTitleSurvivesTypingAndCanBeUndone()=runBlocking {
        start();click("Task");setText("","QA discuss Friday tomorrow");click("More options")
        click("Adjust recognised text");click("Date: Friday");click("Keep in title")
        await { find("QA discuss Friday")!=null };screenshot("kept-title")
        setText("QA discuss Friday tomorrow","QA discuss Friday tomorrow please")
        await { find("QA discuss Friday please")!=null }
        ins.runOnMainSync {
            androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).first().recreate()
        }
        await { find("QA discuss Friday please")!=null }
        click("Undo keep in title")
        await { find("Use one date. Remove the extra date or keep literal words in the title.")!=null }
        click("Date: Friday");click("Keep in title")
        click("Add task");await { data().tasks.any { it.title=="QA discuss Friday please" } }
        assertEquals(LocalDate.now().plusDays(1),data().tasks.single { it.title=="QA discuss Friday please" }.dueDate)
    }
    @Test fun directRepeatCopiesReminderToEveryOccurrence()=runBlocking {
        val date=LocalDate.now().plusDays(2)
        val before=data().items.size
        start();setText("","QA repeat direct $date 3pm every week remind me 30 minutes before for 3 occurrences")
        reveal { find("Reminder, 30 minutes before")!=null };screenshot("repeat-reminder-preview")
        assertEquals(before,data().items.size)
        click("Add 3 events");await { data().items.count { it.title=="QA repeat direct" }==3 }
        val saved=data().items.filter { it.title=="QA repeat direct" }.sortedBy { it.date }
        assertEquals(listOf(date,date.plusWeeks(1),date.plusWeeks(2)),saved.map { it.date })
        assertEquals(1,saved.map { it.seriesId }.distinct().size);assertNotNull(saved.first().seriesId)
        saved.forEach { item ->
            assertEquals("WEEKLY",item.repeatRule)
            assertEquals(30L,data().reminders.single { it.itemId==item.id }.offsetMinutes)
        }
    }
    @Test fun moreDetailsRetainsRepeatCountAndSeededReminder()=runBlocking {
        val date=LocalDate.now().plusDays(3)
        start();setText("","QA repeat editor $date noon every month remind me 1 hour before for 2 occurrences")
        click("More options");click("Open in full editor");reveal { find("Discard")!=null }
        val draft=EditorDraftStore(context).read()!!
        assertEquals("2",draft.getJSONObject("state").getString("count"))
        assertEquals("MONTHLY",draft.getJSONObject("state").getString("repeat"))
        assertEquals(60L,DraftCodec.reminders(draft.optJSONArray("addedReminders")).single().offsetMinutes)
        open();reveal { find("Discard")!=null }
        click("Save");await { data().items.count { it.title=="QA repeat editor" }==2 }
        data().items.filter { it.title=="QA repeat editor" }.forEach { item -> assertEquals(60L,data().reminders.single { it.itemId==item.id }.offsetMinutes) }
    }
    @Test fun taskRepeatAndReminderContinueAfterCompletion()=runBlocking {
        val due=LocalDate.now().plusDays(3)
        start();click("Task");setText("","QA repeat task in 3 days every week remind me 1 day before")
        click("Add task");await { data().tasks.any { it.title=="QA repeat task" } }
        val task=data().tasks.single { it.title=="QA repeat task" }
        assertEquals(due,task.dueDate);assertEquals("WEEKLY",task.repeat)
        val reminder=java.time.Instant.ofEpochMilli(task.reminderAt!!).atZone(java.time.ZoneId.systemDefault())
        assertEquals(due.minusDays(1),reminder.toLocalDate());assertEquals(java.time.LocalTime.of(9,0),reminder.toLocalTime())
        app.repository.setTaskDone(task.id,true)
        val next=data().tasks.single { it.title=="QA repeat task" && !it.done }
        assertEquals(due.plusWeeks(1),next.dueDate)
        assertEquals(due.plusWeeks(1).minusDays(1),java.time.Instant.ofEpochMilli(next.reminderAt!!).atZone(java.time.ZoneId.systemDefault()).toLocalDate())
    }
    @Test fun pastWarningsAndReminderRemovalDoNotSaveUntilConfirmed()=runBlocking {
        val before=data().items.size
        val past=LocalDate.now().minusDays(1)
        start();setText("","QA past warning $past 3pm remind me 30 minutes before")
        reveal { find("This reminder time has passed. Change the date or time, or remove the reminder.")!=null }
        assertEquals(before,data().items.size)
        click("More options");click("Remove reminder")
        reveal { find("This date or time is in the past.")!=null };screenshot("past-warning")
        click("Move to tomorrow");click("Add event")
        await { data().items.any { it.title=="QA past warning" } }
        val saved=data().items.single { it.title=="QA past warning" }
        assertEquals(LocalDate.now().plusDays(1),saved.date);assertTrue(data().reminders.none { it.itemId==saved.id })
    }
    @Test fun keepPlaceInTitlePreservesTheChosenTime()=runBlocking {
        start();setText("","QA keep place in 3 days at 3 at Cafe every week remind me 30 minutes before for 3 occurrences")
        click("3 PM");click("More options");setText("3","2");click("Remove reminder")
        click("Adjust recognised text");click("Place: at Cafe");click("Keep in title")
        assertNull(find("3 PM"))
        click("Add 2 events");await { data().items.count { it.title=="QA keep place at Cafe" }==2 }
        data().items.filter { it.title=="QA keep place at Cafe" }.forEach { saved ->
            assertEquals(java.time.LocalTime.of(15,0),saved.startTime);assertEquals("",saved.location)
            assertTrue(data().reminders.none { it.itemId==saved.id })
        }
    }
}
