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
class ColourBlindThemeUiTest {
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
        ins.waitForIdleSync();Thread.sleep(700)
        val dir=File(context.cacheDir,"qa-colour-blind-evidence").apply { mkdirs() }
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
        reveal { nodes().any { it.isVisibleToUser && it.isEditable && it.text?.toString()==old } }
        val node=nodes().first { it.isVisibleToUser && it.isEditable && it.text?.toString()==old }
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



    @Test fun presetSelectionAndBothAppearancesWorkAcrossMainScreens() {
        val oldTheme=app.settings.appTheme.value
        val oldMode=app.settings.themeMode.value
        val oldView=app.settings.lastViewCalendar
        val oldHeading=app.settings.headingColor.value
        try {
            app.settings.lastViewCalendar=false
            app.settings.setAppTheme(AppTheme.MATRIX);app.settings.setThemeMode(ThemeMode.DARK)
            open();click("More options");click("Themes")
            await { find("More below")!=null };screenshot("theme-list-top")
            click("Colour-blind friendly")
            await { app.settings.appTheme.value==AppTheme.COLOUR_BLIND && find("✓ Colour-blind friendly")!=null }
            screenshot("selected-dark-preview")
            assertEquals(AppTheme.COLOUR_BLIND,SettingsRepository(context).appTheme.value)
            for (mode in listOf(ThemeMode.LIGHT,ThemeMode.DARK)) {
                click(mode.label);await { app.settings.themeMode.value==mode }
                screenshot("appearance-${mode.name.lowercase()}");click("Done")
                screenshot("agenda-${mode.name.lowercase()}")
                click("Switch to Calendar view");await { find("Switch to Agenda view")!=null }
                screenshot("calendar-${mode.name.lowercase()}")
                click("Switch to Agenda view");await { find("Switch to Calendar view")!=null }
                click("Quick entry");await { find("Title")!=null };screenshot("quick-entry-${mode.name.lowercase()}");click("Close")
                click("More options");click("Themes")
            }
            click("Use system setting");assertEquals(ThemeMode.SYSTEM,app.settings.themeMode.value)
            click("Matrix Green");assertEquals(oldHeading,app.settings.headingColor.value)
            click("Done")
        } finally {
            app.settings.setAppTheme(oldTheme);app.settings.setThemeMode(oldMode);app.settings.lastViewCalendar=oldView
        }
    }
}
