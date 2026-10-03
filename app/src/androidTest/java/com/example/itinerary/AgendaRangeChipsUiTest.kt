package com.example.itinerary

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.example.itinerary.data.AgendaRange
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** The agenda's range chips (Today / This week / Upcoming / All) at normal and large text. */
class AgendaRangeChipsUiTest {
    private val ins get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=ins.targetContext
    private val app get()=context.applicationContext as ItineraryApp
    private fun nodes():List<AccessibilityNodeInfo> {
        val result=mutableListOf<AccessibilityNodeInfo>()
        fun visit(n:AccessibilityNodeInfo) { result+=n;for(i in 0 until n.childCount)n.getChild(i)?.let(::visit) }
        ins.uiAutomation.freshRoot?.let(::visit);return result
    }
    // The chip is the clickable node around the label (the label may be its own child node).
    private fun chip(label:String):AccessibilityNodeInfo? {
        var node=nodes().firstOrNull { it.isVisibleToUser && it.text?.toString()==label }
        while(node!=null && !node.isClickable)node=node.parent
        return node
    }
    private fun bounds(label:String)=Rect().also { chip(label)!!.getBoundsInScreen(it) }
    private fun screenshot(name:String) {
        val dir=File(context.cacheDir,"qa-range-chips-evidence").apply { mkdirs() }
        ins.uiAutomation.takeScreenshot()?.let { b -> File(dir,"$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG,100,it) };b.recycle() }
    }
    private fun await(timeout:Long=30000,condition:()->Boolean) {
        val end=SystemClock.uptimeMillis()+timeout
        while(SystemClock.uptimeMillis()<end) { if(runCatching(condition).getOrDefault(false))return;Thread.sleep(150) }
        screenshot("failure");fail("Timed out: "+nodes().mapNotNull { it.text }.joinToString(" | "))
    }
    private fun shell(command:String)=ins.uiAutomation.executeShellCommand(command).use { pfd ->
        android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().readText().trim()
    }
    private fun open() {
        app.settings.lastViewCalendar=false
        ins.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        await { AgendaRange.entries.all { chip(it.label)!=null } }
        Thread.sleep(500) // let the chips settle after the first frame
    }
    // Every label on one line: all four chips are as tall as "Today", which can't wrap.
    private fun assertNoLabelSplits() {
        val today=bounds("Today").height()
        AgendaRange.entries.forEach { assertEquals("${it.label} chip height",today.toFloat(),bounds(it.label).height().toFloat(),2f) }
    }
    private fun assertEachChipSelects() {
        AgendaRange.entries.forEach { range ->
            chip(range.label)!!.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            await { app.settings.agendaRange.value==range }
        }
    }

    @Test fun largeTextPutsTwoWholeChipsOnEachLine() {
        val before=shell("settings get system font_scale")
        try {
            shell("settings put system font_scale 1.3")
            open();screenshot("font-1.3")
            assertNoLabelSplits()
            // Two per line: Today and This week side by side, Upcoming below Today.
            assertEquals(bounds("Today").top,bounds("This week").top)
            assertTrue(bounds("Upcoming").top>=bounds("Today").bottom)
            assertEquals(bounds("Upcoming").top,bounds("All").top)
            assertEachChipSelects()
        } finally { shell(if(before=="null")"settings delete system font_scale" else "settings put system font_scale $before") }
    }

    @Test fun normalTextKeepsAllFourChipsOnOneLine() {
        assertEquals("1.0",shell("settings get system font_scale"))
        open();screenshot("font-1.0")
        assertNoLabelSplits()
        val top=bounds("Today").top
        AgendaRange.entries.forEach { assertEquals("${it.label} top",top,bounds(it.label).top) }
        assertEachChipSelects()
    }
}
