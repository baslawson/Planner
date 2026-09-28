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
class QuickEntryUiTest {
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
    private fun find(text:String)=nodes().firstOrNull { it.isVisibleToUser && (it.text?.toString()==text || it.contentDescription?.toString()==text) }
    private fun screenshot(name:String) {
        val dir=File(context.cacheDir,"qa-quick-entry-evidence").apply { mkdirs() }
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
    @Test fun eventDirectSaveIncludesDurationAndDoesNotOpenEditor()=runBlocking {
        val before=data().items.size
        start()
        await { nodes().any { it.isEditable && it.isFocused } }
        screenshot("empty-ready-to-type")
        setText("","QA quick dentist Fri 3pm for 45 minutes")
        await { find("QA quick dentist")!=null };screenshot("event-preview")
        assertEquals(before,data().items.size)
        click("Add event")
        await { data().items.any { it.title=="QA quick dentist" } }
        val saved=data().items.single { it.title=="QA quick dentist" }
        assertEquals(java.time.LocalTime.of(15,0),saved.startTime);assertEquals(45,saved.durationMinutes)
        assertEquals(LocalDate.now().with(java.time.temporal.TemporalAdjusters.nextOrSame(java.time.DayOfWeek.FRIDAY)),saved.date)
        assertEquals(before+1,data().items.size);assertNull(find("Discard"))
    }
    @Test fun tasksCanBeUndatedOrDueTomorrowAndNeverLoseTimedInput()=runBlocking {
        val before=data().tasks.size
        start();click("Task");setText("","QA quick groceries")
        await { find("No due date")!=null };screenshot("undated-task")
        click("Add task");await { data().tasks.any { it.title=="QA quick groceries" } }
        assertNull(data().tasks.single { it.title=="QA quick groceries" }.dueDate)
        click("Quick entry");click("Task");setText("","QA quick plumber tmr")
        click("Add task");await { data().tasks.any { it.title=="QA quick plumber" } }
        assertEquals(LocalDate.now().plusDays(1),data().tasks.single { it.title=="QA quick plumber" }.dueDate)
        click("Quick entry");click("Task");setText("","QA timed task tmr 3pm")
        await { find("Tasks use due dates. Choose Event for a time or duration.")!=null }
        assertEquals(before+2,data().tasks.size);click("Close")
    }
    @Test fun ambiguousTimeUsesExistingClockAndDateCorrectionPersists()=runBlocking {
        start();setText("","QA quick clock tomorrow at 3")
        await { find("Morning or afternoon? Choose a time below, or type am or pm.")!=null }
        click("Choose time");await { find("Choose a time")!=null };screenshot("analog-clock")
        click("Set time")
        // Default picker time is 09:00; committing it resolves the ambiguous input.
        click("More options");click("Open in full editor")
        reveal { find("Discard")!=null };screenshot("event-more-details")
        click("Save");await { data().items.any { it.title=="QA quick clock" } }
        assertEquals(java.time.LocalTime.of(9,0),data().items.single { it.title=="QA quick clock" }.startTime)
        click("Quick entry");click("Task");setText("","QA quick date")
        click("No due date");await { find("Select date")!=null };click("Set date")
        click("Add task");await { data().tasks.any { it.title=="QA quick date" } }
        assertEquals(LocalDate.now(),data().tasks.single { it.title=="QA quick date" }.dueDate)
    }
    @Test fun taskMoreDetailsAndExampleReuseExistingFlows()=runBlocking {
        start();click("Task");click("Call plumber tmr")
        // The example fills the Title box and the Due box separately.
        await { nodes().any { it.isEditable && it.text?.toString()=="Call plumber" } && nodes().any { it.isEditable && it.text?.toString()=="tmr" } }
        setText("Call plumber","QA detailed task");click("More options");click("Open in full editor")
        reveal { find("Save task")!=null || find("Save")!=null };screenshot("task-more-details")
        click(if(find("Save task")!=null) "Save task" else "Save")
        await { data().tasks.any { it.title=="QA detailed task" } }
        assertEquals(LocalDate.now().plusDays(1),data().tasks.single { it.title=="QA detailed task" }.dueDate)
    }
    @Test fun invalidInputCannotSaveAndClearingDateMakesTaskUndated()=runBlocking {
        val before=data()
        start();setText("","QA invalid 2026-02-30")
        await { find("That date isn't valid.")!=null }
        fun enabled(label:String):Boolean {
            var n=find(label)
            while(n!=null && !n.isClickable)n=n.parent
            return n?.isEnabled==true
        }
        assertFalse(enabled("Add event"));assertEquals(before,data())
        setText("QA invalid 2026-02-30","QA duration for 30min")
        await { find("Choose a start time for this duration.")!=null }
        assertFalse(enabled("Add event"))
        click("Task");setText("QA duration for 30min","QA ambiguous task at 3")
        await { find("Tasks use due dates. Choose Event for a time or duration.")!=null }
        assertFalse(enabled("Add task"));screenshot("task-time-guidance")
        setText("QA ambiguous task at 3","QA cleared date tmr")
        click("More options");click("Clear date");await { find("No due date")!=null }
        click("Add task");await { data().tasks.any { it.title=="QA cleared date" } }
        assertNull(data().tasks.single { it.title=="QA cleared date" }.dueDate)
        assertEquals(before.items,data().items)
        click("Quick entry");setText("","QA cancelled today 3pm");click("Close")
        assertEquals(before.items,data().items)
    }
    @Test fun naturalRangeAndLocationSaveThroughDirectAndFullEditorPaths()=runBlocking {
        start();setText("","QA natural lunch in 3 days 2pm–3:30pm at Riverside Cafe")
        click("More options");reveal { find("Location: Riverside Cafe")!=null };screenshot("natural-range-location")
        click("Add event");await { data().items.any { it.title=="QA natural lunch" } }
        val direct=data().items.single { it.title=="QA natural lunch" }
        assertEquals(LocalDate.now().plusDays(3),direct.date)
        assertEquals(java.time.LocalTime.of(14,0),direct.startTime)
        assertEquals(90,direct.durationMinutes);assertEquals("Riverside Cafe",direct.location)
        click("Quick entry");setText("","QA natural study tomorrow 3pm for 1h 30m at Library")
        click("More options");click("Open in full editor");reveal { find("Discard")!=null };click("Save")
        await { data().items.any { it.title=="QA natural study" } }
        val detailed=data().items.single { it.title=="QA natural study" }
        assertEquals(90,detailed.durationMinutes);assertEquals("Library",detailed.location)
    }
    @Test fun quotedTaskTitleAndLocationAreKeptWithRelativeDueDate()=runBlocking {
        start();click("Task");setText("","\"QA pay May tomorrow\" in 3 days at Office")
        await { find("QA pay May tomorrow at Office")!=null };screenshot("literal-task-title")
        click("Add task");await { data().tasks.any { it.title=="QA pay May tomorrow at Office" } }
        assertEquals(LocalDate.now().plusDays(3),data().tasks.single { it.title=="QA pay May tomorrow at Office" }.dueDate)
    }
    @Test fun unclearNumericDateRequiresCorrectionBeforeSave()=runBlocking {
        val before=data().items.size
        withDateFormat(app,ins,DateFormatChoice.ISO) { unclearNumericDate(before) }
    }
    private fun unclearNumericDate(before:Int)=runBlocking {
        start();setText("","QA clear date 03/04")
        await { find("Which date did you mean?")!=null }
        assertEquals(before,data().items.size);screenshot("ambiguous-date")
        setText("QA clear date 03/04","QA clear date September 30th")
        click("Add event");await { data().items.any { it.title=="QA clear date" } }
        val expected=if(LocalDate.now()>LocalDate.of(LocalDate.now().year,9,30)) LocalDate.of(LocalDate.now().year+1,9,30) else LocalDate.of(LocalDate.now().year,9,30)
        assertEquals(expected,data().items.single { it.title=="QA clear date" }.date)
    }
}
