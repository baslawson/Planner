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
import java.time.*
import java.time.temporal.TemporalAdjusters

/** Real application UI; run only with an external backup/restore harness. */
@Suppress("DEPRECATION")
class QuickSuggestionsUiTest {
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
        val dir=File(context.cacheDir,"qa-quick-suggestions-evidence").apply { mkdirs() }
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
    private fun enabled(label:String):Boolean {
        var n=find(label)
        while(n!=null && !n.isClickable)n=n.parent
        return n?.isEnabled==true
    }
    private fun selected(label:String)=nodes().any { it.isVisibleToUser && it.text?.toString()==label && (it.isChecked || it.isSelected || it.parent?.isSelected==true || it.parent?.isChecked==true) }

    @Test fun remindMeToPicksTaskOrEventAndSavesReminder()=runBlocking {
        start()
        setText("","Remind me to QA call mum tomorrow")
        await { find("QA call mum")!=null && selected("Task") };screenshot("remind-task")
        click("Add task");await { data().tasks.any { it.title=="QA call mum" } }
        val task=data().tasks.single { it.title=="QA call mum" }
        val tomorrow=LocalDate.now().plusDays(1)
        assertEquals(tomorrow,task.dueDate)
        assertEquals(tomorrow.atTime(9,0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),task.reminderAt)
        click("Quick entry");setText("","Remind me to QA call dad tomorrow at 3pm")
        await { find("QA call dad")!=null && selected("Event") };screenshot("remind-event")
        click("Add event");await { data().items.any { it.title=="QA call dad" } }
        val event=data().items.single { it.title=="QA call dad" }
        assertEquals(LocalTime.of(15,0),event.startTime);assertEquals(tomorrow,event.date)
        val reminder=data().reminders.single { it.itemId==event.id }
        assertEquals(0,reminder.amount)
        // A type chosen by hand is not overridden by later typing.
        click("Quick entry");click("Task");click("Event");setText("","Remind me to QA stay event tomorrow") // Event chosen by hand
        await { find("QA stay event")!=null };assertTrue(selected("Event"));assertFalse(selected("Task"))
        click("Close")
    }

    @Test fun unpaddedTimeOffersMinutesChoicesAndSaves()=runBlocking {
        start();setText("","QA movie tomorrow 7:30")
        await { find("7:30 PM")!=null && find("7:30 AM")!=null };screenshot("ampm-minutes")
        assertFalse(enabled("Add event"))
        click("7:30 PM");await { enabled("Add event") }
        click("Add event");await { data().items.any { it.title=="QA movie" } }
        assertEquals(LocalTime.of(19,30),data().items.single { it.title=="QA movie" }.startTime)
    }

    @Test fun everyWeekdayCreatesMondayToFridaySeriesAndEditorOffersWeekdays()=runBlocking {
        start();setText("","QA standup every weekday 9am for 4 occurrences")
        await { find("Weekdays · 4 events")!=null };screenshot("weekday-preview")
        click("Add 4 events");await { data().items.count { it.title=="QA standup" }==4 }
        val saved=data().items.filter { it.title=="QA standup" }.sortedBy { it.date }
        val first=generateSequence(LocalDate.now()) { it.plusDays(1) }.first { it.dayOfWeek.value<=5 }
        assertEquals(generateSequence(first) { it.plusDays(1) }.filter { it.dayOfWeek.value<=5 }.take(4).toList(),saved.map { it.date })
        assertTrue(saved.all { it.repeatRule=="WEEKDAYS" && it.seriesId!=null && it.startTime==LocalTime.of(9,0) })
        click("Quick entry");setText("","QA editor repeat tomorrow 10am");click("More options");click("Open in full editor")
        reveal { find("Does not repeat")!=null };click("Does not repeat")
        click("Weekdays");reveal { find("Monday to Friday. A weekend start moves to the following Monday.")!=null }
        screenshot("editor-weekdays");click("Discard")
        if (find("Discard changes?")!=null || find("Discard")!=null) click("Discard")
        assertTrue(data().items.none { it.title=="QA editor repeat" })
    }

    @Test fun titleWordsWeekdayCrossCheckAndRelativeTime()=runBlocking {
        start();setText("","QA buy sun cream")
        await { find("QA buy sun cream")!=null };screenshot("sun-cream")
        click("Add event");await { data().items.any { it.title=="QA buy sun cream" } }
        assertEquals(LocalDate.now(),data().items.single { it.title=="QA buy sun cream" }.date)
        val friday=LocalDate.now().plusDays(1).with(TemporalAdjusters.nextOrSame(DayOfWeek.FRIDAY))
        val month=friday.month.getDisplayName(java.time.format.TextStyle.FULL,java.util.Locale.ENGLISH)
        click("Quick entry");setText("","QA cross Thursday ${friday.dayOfMonth} $month 3pm")
        await { nodes().any { it.text?.toString()?.contains("is a Friday, not a Thursday")==true } };screenshot("weekday-mismatch")
        assertFalse(enabled("Add event"))
        setText("QA cross Thursday ${friday.dayOfMonth} $month 3pm","QA cross Friday ${friday.dayOfMonth} $month 3pm")
        await { enabled("Add event") };click("Add event")
        await { data().items.any { it.title=="QA cross" } }
        assertEquals(friday,data().items.single { it.title=="QA cross" }.date)
        val before=LocalDateTime.now()
        click("Quick entry");setText("","QA soon in 30 minutes")
        await { find("QA soon")!=null && enabled("Add event") };screenshot("relative-time")
        click("Add event");await { data().items.any { it.title=="QA soon" } }
        val soon=data().items.single { it.title=="QA soon" }
        val at=soon.date.atTime(soon.startTime!!)
        assertTrue("$at after $before",!at.isBefore(before.plusMinutes(30)) && !at.isAfter(LocalDateTime.now().plusMinutes(36)))
        assertEquals(0,at.minute%5)
    }

    @Test fun numericDateFollowsDateFormatSetting()=runBlocking {
        val original=app.settings.dateFormat.value
        try {
            ins.runOnMainSync { app.settings.setDateFormat(DateFormatChoice.NUMERIC_MDY) }
            start();setText("","QA numeric 3/4")
            await { find("QA numeric")!=null };Thread.sleep(1000);screenshot("numeric-mdy")
            assertNull(find("Which date did you mean?"))
            click("Add event");await { data().items.any { it.title=="QA numeric" } }
            val d=data().items.single { it.title=="QA numeric" }.date
            assertEquals(3,d.monthValue);assertEquals(4,d.dayOfMonth)
            ins.runOnMainSync { app.settings.setDateFormat(DateFormatChoice.ISO) }
            click("Quick entry");setText("","QA numeric ask 3/4")
            await { find("Which date did you mean?")!=null };screenshot("numeric-ask");click("Close")
        } finally { ins.runOnMainSync { app.settings.setDateFormat(original) } }
    }

    @Test fun fourDigit24HourTimesSaveAndPlainNumbersStayInTitles()=runBlocking {
        start();setText("","QA shift tomorrow 0900-1700")
        await { find("QA shift")!=null && enabled("Add event") };screenshot("four-digit-range")
        click("Add event");await { data().items.any { it.title=="QA shift" } }
        val shift=data().items.single { it.title=="QA shift" }
        assertEquals(LocalDate.now().plusDays(1),shift.date);assertEquals(LocalTime.of(9,0),shift.startTime);assertEquals(480,shift.durationMinutes)
        click("Quick entry");setText("","QA gym tomorrow 0600")
        await { find("QA gym")!=null && enabled("Add event") };screenshot("four-digit-single")
        click("Add event");await { data().items.any { it.title=="QA gym" } }
        assertEquals(LocalTime.of(6,0),data().items.single { it.title=="QA gym" }.startTime)
        click("Quick entry");setText("","QA buy 1500 screws tomorrow")
        await { find("QA buy 1500 screws")!=null && enabled("Add event") };screenshot("four-digit-title")
        click("Add event");await { data().items.any { it.title=="QA buy 1500 screws" } }
        assertNull(data().items.single { it.title=="QA buy 1500 screws" }.startTime)
    }
}
