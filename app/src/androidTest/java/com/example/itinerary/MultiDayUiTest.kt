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

/** Multi-day all-day events in the real app: the editor, the agenda, the calendar and Quick entry. */
@Suppress("DEPRECATION")
class MultiDayUiTest {
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
        val dir=File(context.cacheDir,"qa-multi-day-evidence").apply { mkdirs() }
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


    // Save keeps the editor open on the saved event; Close then leaves (nothing is unsaved).
    private fun saveAndClose() { click("Save");click("Close") }
    private fun back() {
        ins.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        Thread.sleep(350)
    }
    private fun has(prefix:String)=nodes().any { it.isVisibleToUser && it.text?.toString()?.startsWith(prefix)==true }
    private val today get()=LocalDate.now()

    @Test fun editorTurnsAnAllDayEventIntoTwoDaysAndBack()=runBlocking {
        app.repository.saveItem(ItineraryItem(tripId=0,date=today,startTime=null,title="QA span editor"))
        open();click("QA span editor");click("QA span editor") // the calendar, then the editor
        await { find("Multiple days")!=null };screenshot("editor-single")
        click("Multiple days");await { find("Set dates")!=null };screenshot("range-picker")
        click("Set dates") // the picker opens on today – tomorrow
        reveal { nodes().any { it.text?.toString()?.contains("· 2 days")==true } };screenshot("editor-two-days")
        saveAndClose()
        await { data().items.single { it.title=="QA span editor" }.endDate==today.plusDays(1) }
        // Switching Multiple days off makes it one day again.
        click("QA span editor");await { find("Multiple days")!=null }
        click("Multiple days");saveAndClose()
        await { data().items.single { it.title=="QA span editor" }.endDate==null }
    }

    @Test fun aTripUnderWayShowsUnderTodayAndInTheCalendar()=runBlocking {
        app.repository.saveItem(ItineraryItem(tripId=0,date=today.minusDays(2),startTime=null,title="QA span trip",endDate=today.plusDays(2)))
        open() // Upcoming: only under Today, as day 3 of 5
        reveal { find("QA span trip")!=null && has("Day 3 of 5") };screenshot("agenda-under-way")
        click("All") // All: under its first day and under Today – two cards, no crash
        val firstDay=today.minusDays(2).format(java.time.format.DateTimeFormatter.ofPattern("d MMM"))
        reveal { has("$firstDay – ") } // its full label under its first day
        screenshot("agenda-all");assertTrue(find("AGENDA")!=null)
        click("Upcoming")
        click("QA span trip");await { find("CALENDAR")!=null }
        reveal { find("QA span trip")!=null && has("Day 3 of 5") };screenshot("calendar-day")
    }

    @Test fun quickEntryAddsATrip()=runBlocking {
        open();click("Add menu");click("Quick entry");setText("","QA span quick 3–7 Oct")
        await { has("3 Oct – 7 Oct · 5 days") };screenshot("quick-span")
        click("Add event")
        await { data().items.any { it.title=="QA span quick" } }
        data().items.single { it.title=="QA span quick" }.let { assertEquals(5, it.dayCount); assertNull(it.startTime) }
    }
}
