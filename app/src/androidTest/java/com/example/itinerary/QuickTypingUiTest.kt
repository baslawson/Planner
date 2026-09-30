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
class QuickTypingUiTest {
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
        val dir=File(context.cacheDir,"qa-quick-typing-evidence").apply { mkdirs() }
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
    @Test fun fortnightlyWordsCreateEventsFourteenDaysApart()=runBlocking {
        start();setText("","QA Green Bin every two week")
        await { find("Add 12 events")!=null }
        screenshot("fortnightly-recognised")
        click("More options");setText("12","2");click("Add 2 events")
        if(find("Add anyway")!=null)click("Add anyway")
        await { data().items.count { it.title=="QA Green Bin" }==2 }
        val items=data().items.filter { it.title=="QA Green Bin" }.sortedBy { it.date }
        assertEquals(items.first().date.plusDays(14),items.last().date)
        assertTrue(items.all { it.repeatRule=="FORTNIGHTLY" })
    }
    @Test fun suggestionsInsertOnlyOnTapAndKeepTheRestOfTheSentence()=runBlocking {
        start();setText("","QA suggested tom")
        await { find("Tomorrow")!=null };screenshot("typing-suggestions")
        assertTrue(data().items.none { it.title=="QA suggested" })
        click("Tomorrow")
        await { nodes().any { it.isEditable && it.text?.toString()=="QA suggested tomorrow " } }
        setText("QA suggested tomorrow ","QA suggested tomorrow 3pm remind me")
        click("30 minutes before")
        await { find("Reminder, 30 minutes before")!=null };screenshot("compact-preview")
        assertNull(find("Occurrences"));assertNull(find("Remove reminder"))
        click("Add event");if(find("Add anyway")!=null)click("Add anyway")
        await { data().items.any { it.title=="QA suggested" } }
        val item=data().items.single { it.title=="QA suggested" }
        assertEquals(30L,data().reminders.single { it.itemId==item.id }.offsetMinutes)
    }
    @Test fun titleEditsKeepDateTimeRepeatAndReminderCorrectionsAcrossRecreation()=withDateFormat(app,ins,com.example.itinerary.data.DateFormatChoice.ISO) { titleEditsKeepCorrections() }
    private fun titleEditsKeepCorrections()=runBlocking {
        start()
        val raw="QA retained 03/04/2030 at 3 every week remind me 30 minutes before for 3 occurrences"
        setText("",raw);click(LocalDate.of(2030,4,3).fullLabel());click("3 PM")
        click("More options");setText("3","2");click("Remove reminder")
        setText(raw,raw.replace("QA retained","QA retained check-up"))
        ins.runOnMainSync {
            androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).first().recreate()
        }
        await { find("Add 2 events")!=null };screenshot("preserved-corrections")
        click("Add 2 events");if(find("Add anyway")!=null)click("Add anyway")
        await { data().items.count { it.title=="QA retained check-up" }==2 }
        val items=data().items.filter { it.title=="QA retained check-up" }.sortedBy { it.date }
        assertEquals(LocalDate.of(2030,4,3),items.first().date)
        items.forEach { assertEquals(java.time.LocalTime.of(15,0),it.startTime);assertTrue(data().reminders.none { r -> r.itemId==it.id }) }
    }
    @Test fun durationChipUsesPersistedCorrectionAndSurvivesTitleEdit()=runBlocking {
        start();val raw="QA duration chip tomorrow 3pm for 1h";setText("",raw)
        click("Duration, for 60 min");await { find("Minutes (1–1440)")!=null };click("45 min");click("Set duration")
        setText(raw,raw.replace("QA duration chip","QA adjusted duration"))
        click("Close");click("Quick entry")
        await { find("Duration, for 45 min")!=null };screenshot("duration-correction")
        click("Add event");if(find("Add anyway")!=null)click("Add anyway")
        await { data().items.any { it.title=="QA adjusted duration" } }
        assertEquals(45,data().items.single { it.title=="QA adjusted duration" }.durationMinutes)
    }
    @Test fun addAnotherKeepsEntryOpenAndStartsWithCleanTaskFields()=runBlocking {
        start();click("Task");setText("","QA fast first tomorrow")
        click("Add another")
        await { data().tasks.any { it.title=="QA fast first" } && nodes().any { it.isEditable && it.text.isNullOrEmpty() } }
        await { find("No due date")==null };screenshot("ready-for-next")
        setText("","QA fast second");await { find("No due date")!=null }
        click("Add task");await { data().tasks.any { it.title=="QA fast second" } }
        assertEquals(1,data().tasks.count { it.title=="QA fast first" })
        assertNull(data().tasks.single { it.title=="QA fast second" }.dueDate)
        assertNull(QuickDraftStore(context).read())
    }
    @Test fun incompleteInputGetsGentleFeedbackThenErrorWithoutSaving()=runBlocking {
        start();setText("","QA typing tomorrow at")
        // The pause check uses real UI time; the draft remains unsaved throughout.
        await { nodes().any { it.text?.toString()?.contains("Finish") == true || it.text?.toString()?.contains("Add a place") == true } }
        assertTrue(data().items.none { it.title.startsWith("QA typing") })
        screenshot("unfinished-feedback")
        setText("QA typing tomorrow at","QA typing tomorrow at 3pm")
        click("Add event");if(find("Add anyway")!=null)click("Add anyway")
        await { data().items.any { it.title=="QA typing" } }
    }
    @Test fun shorthandTimeRangePreviewsAndSavesOffline() = runBlocking {
        val old = app.settings.aiFeaturesEnabled.value
        try {
            app.settings.setAiFeaturesEnabled(false)
            start();setText("","QA range dentist tomorrow 3-4pm")
            await { find("Duration, for 60 min")!=null };screenshot("shorthand-range")
            assertNull(find("Understand with AI"))
            click("Add event");if(find("Add anyway")!=null)click("Add anyway")
            await { data().items.any { it.title=="QA range dentist" } }
            val saved=data().items.single { it.title=="QA range dentist" }
            assertEquals(java.time.LocalTime.of(15,0),saved.startTime);assertEquals(60,saved.durationMinutes)
        } finally { app.settings.setAiFeaturesEnabled(old) }
    }

    @Test fun everydayDateAndReminderSaveWithAiOff() = runBlocking {
        val old=app.settings.aiFeaturesEnabled.value
        try {
            app.settings.setAiFeaturesEnabled(false);start()
            setText("","QA natural dentist day after tomorrow 3pm notify me half an hour before")
            await { QuickDraftStore(context).read()?.single?.suggestion()?.reminderMinutes==30 }
            screenshot("everyday-single");click("Add event")
            if(find("Add anyway")!=null)click("Add anyway")
            await { data().items.any { it.title=="QA natural dentist" } }
            val saved=data().items.single { it.title=="QA natural dentist" }
            assertEquals(LocalDate.now().plusDays(2),saved.date)
            assertEquals(java.time.LocalTime.of(15,0),saved.startTime)
        } finally { app.settings.setAiFeaturesEnabled(old) }
    }
    @Test fun flexibleClockDurationAndTypoTapSaveOffline() = runBlocking {
        val old=app.settings.aiFeaturesEnabled.value
        try {
            app.settings.setAiFeaturesEnabled(false);start()
            // "tommorow" is now read as tomorrow outright; "tomorrw" is still only offered as a suggestion.
            setText("","QA flexible tomorrw")
            await { find("Tomorrow")!=null };screenshot("typo-suggestion")
            assertTrue(nodes().any { it.isEditable && it.text?.toString()=="QA flexible tomorrw" })
            click("Tomorrow")
            await { nodes().any { it.isEditable && it.text?.toString()=="QA flexible tomorrow " } }
            setText("QA flexible tomorrow ","QA flexible tomorrow 3.30pm—actually 4 p.m. for an hour and a half")
            await { find("Duration, for 90 min")!=null };screenshot("flexible-preview")
            assertNull(find("Understand with AI"));click("Add event")
            if(find("Add anyway")!=null)click("Add anyway")
            await { data().items.any { it.title=="QA flexible" } }
            val saved=data().items.single { it.title=="QA flexible" }
            assertEquals(LocalDate.now().plusDays(1),saved.date)
            assertEquals(java.time.LocalTime.of(16,0),saved.startTime);assertEquals(90,saved.durationMinutes)
        } finally { app.settings.setAiFeaturesEnabled(old) }
    }
}
