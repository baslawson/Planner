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
class BillTaskWorkflowUiTest {
    private val ins get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=ins.targetContext
    private val app get()=context.applicationContext as ItineraryApp
    private fun data()=runBlocking { app.repository.snapshot() }
    private fun nodes():List<AccessibilityNodeInfo> {
        val result=mutableListOf<AccessibilityNodeInfo>()
        fun visit(n:AccessibilityNodeInfo) { result+=n;for(i in 0 until n.childCount)n.getChild(i)?.let(::visit) }
        ins.uiAutomation.rootInActiveWindow?.let(::visit);return result
    }
    private fun find(text:String)=nodes().firstOrNull { it.isVisibleToUser && !it.isEditable && (it.text?.toString()==text || it.contentDescription?.toString()==text) }
    private fun screenshot(name:String) {
        val dir=File(context.cacheDir,"qa-bill-task-flow-evidence").apply { mkdirs() }
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
                    nodes().firstOrNull { it.isScrollable && !it.isEditable }?.getBoundsInScreen(bounds)
                    if(!bounds.isEmpty) {
                        val delta=bounds.height()/6
                        val from=bounds.centerY()+if(forward)delta else -delta
                        val to=bounds.centerY()-if(forward)delta else -delta
                        val command = if (bounds.width() > bounds.height() * 2) {
                            val dx = bounds.width()/3
                            val x1 = bounds.centerX() + if (forward) dx else -dx
                            val x2 = bounds.centerX() - if (forward) dx else -dx
                            "input swipe $x1 ${bounds.centerY()} $x2 ${bounds.centerY()} 200"
                        } else "input swipe ${bounds.left+12} $from ${bounds.left+12} $to 200"
                        ins.uiAutomation.executeShellCommand(command).use {
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
        }));await { nodes().any { it.isEditable && it.text?.toString()==value } }
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
        data().items.filter { it.title.startsWith(prefix) }.forEach { app.repository.deleteWithUndo(it) }
        data().tasks.filter { it.title.startsWith(prefix) }.forEach { app.repository.deleteTask(it.id) }
        data().deleted.filter { it.label.startsWith(prefix) }.forEach { app.repository.permanentlyDelete(it.id) }
    }
    @Test fun completedPastDueBillsReappearWhenCompletedTasksAreShown() = runBlocking {
        val title = "QA bill task flow completed overdue"
        cleanFixture(title)
        app.repository.saveItem(ItineraryItem(tripId=0, date=LocalDate.now().minusDays(40),
            startTime=null, title=title, category="Bills", billAmountMinor=4200))
        val id = data().items.single { it.title == title }.id
        app.repository.setPaid(id, true)
        for (range in listOf(AgendaRange.TODAY, AgendaRange.THIS_WEEK, AgendaRange.UPCOMING)) {
            app.settings.setAgendaRange(range)
            open()
            setText("", title)
            await { find(title) == null }
            click("Show completed tasks")
            reveal { find(title) != null }
            screenshot("completed-overdue-${range.name.lowercase()}")
            click("Show completed tasks")
            await { find(title) == null }
        }
        assertTrue(data().items.single { it.id == id }.paid)
    }

    @Test fun createBillThroughTasksPayPartiallyCompleteAndUndo() = runBlocking {
        val title = "QA bill task flow create"
        cleanFixture(title)
        app.settings.setAgendaRange(AgendaRange.TODAY)
        open(); click("Add menu"); click("Add task"); click("Bill payment")
        await { find("New bill task") != null }
        assertNotNull(find("Bill title")); assertNull(find("What are you doing?"))
        setText(nodes().first { it.isEditable }.text.toString(), title); setText("", "125.50")
        screenshot("bill-task-editor"); click("Save")
        await { data().items.any { it.title == title } }
        val saved = data().items.single { it.title == title }
        assertEquals("Bills", saved.category); assertEquals(12550L, saved.billAmountMinor)
        assertTrue(data().tasks.none { it.title == title })
        assertTrue(data().reminders.any { it.itemId == saved.id })
        reveal { find(title) != null }; assertNotNull(find("Bill payment · Task"))
        screenshot("bill-task-card"); click(title)
        await { find("Edit bill task") != null }
        click("Record payment"); setText("", "25.50"); click("Add payment"); click("Save")
        await { data().items.single { it.id == saved.id }.payments.size == 1 }
        val partial = data().items.single { it.id == saved.id }
        assertEquals(2550L, Payments.total(partial.payments)); assertFalse(partial.paid)
        click("Mark $title paid")
        await { data().items.single { it.id == saved.id }.paid && find(title) == null }
        click("Undo")
        await { !data().items.single { it.id == saved.id }.paid }
        assertEquals(partial.payments, data().items.single { it.id == saved.id }.payments)
        click("Mark $title paid")
        await { data().items.single { it.id == saved.id }.paid && find(title) == null }
        click("Show completed tasks"); reveal { find(title) != null }
        screenshot("completed-bill-task")
        click("Actions for $title"); click("Mark unpaid")
        await { !data().items.single { it.id == saved.id }.paid }
        assertEquals(0L, Payments.total(data().items.single { it.id == saved.id }.payments))
        assertTrue(data().items.single { it.id == saved.id }.payments.all { it.reversed })
    }

    @Test fun overdueBillOpensDirectlyFromAgendaSearchAndCalendarAndKeepsItsData() = runBlocking {
        val title = "QA bill task flow overdue"
        cleanFixture(title)
        val day = LocalDate.now().minusDays(5)
        app.repository.saveItem(ItineraryItem(tripId=0,date=day,startTime=null,title=title,category="Bills",billAmountMinor=9000),
            addedReminders=listOf(Reminder(itemId=0,amount=1,unit=ReminderUnit.DAYS)),
            added=listOf(Attachment(itemId=0,name="Provider portal",fileName="",mimeType="text/uri-list",url="https://example.com/bill")))
        val before = data().items.single { it.title == title }
        val reminders = data().reminders.filter { it.itemId == before.id }
        val attachments = data().attachments.filter { it.itemId == before.id }
        app.settings.setAgendaRange(AgendaRange.TODAY)
        open(); reveal { find(title) != null }; assertNotNull(find("Overdue"))
        screenshot("overdue-bill-in-today")
        click(title); await { find("Edit bill task") != null }; click("Save")
        await { find("Edit bill task") == null }
        assertEquals(before, data().items.single { it.id == before.id }.copy(draftToken = before.draftToken))
        assertEquals(reminders, data().reminders.filter { it.itemId == before.id })
        assertEquals(attachments, data().attachments.filter { it.itemId == before.id })
        click("This week"); reveal { find(title) != null }; click("Upcoming"); reveal { find(title) != null }
        click("Search"); click("Tasks"); setText("",title)
        reveal { find(title) != null }; screenshot("bill-task-search")
        click(title); await { find("Edit bill task") != null }
        click("Delete"); await { find("Delete bill?") != null }; click("Cancel"); click("Discard"); click("Back")
        val calendarTitle = "QA bill task flow calendar"
        cleanFixture(calendarTitle)
        app.repository.saveItem(ItineraryItem(tripId=0,date=LocalDate.now(),startTime=null,title=calendarTitle,category="Bills",billAmountMinor=5000))
        click("Switch to Calendar view")
        await { find("CALENDAR") != null }
        if (find("Collapse") != null) click("Collapse")
        reveal { find(calendarTitle) != null }; click(calendarTitle)
        await { find("Edit bill task") != null }; click("Discard")
    }
}
