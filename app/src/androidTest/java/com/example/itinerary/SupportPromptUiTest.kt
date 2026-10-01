package com.example.itinerary

import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.edit
import androidx.test.platform.app.InstrumentationRegistry
import com.example.itinerary.data.*
import com.example.itinerary.ui.KOFI_URL
import com.example.itinerary.ui.SupportPrompt
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

/** The "free and open source" pop-up: once per installed version; Ko-fi, Remind me later (a task), No thanks.
 *  Real application UI; run only with an external backup/restore harness. */
@Suppress("DEPRECATION")
class SupportPromptUiTest {
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
        val dir=File(context.cacheDir,"qa-support-prompt").apply { mkdirs() }
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
    private val title = "Planner is free and open source"
    private val prefs get() = context.getSharedPreferences(SupportPrompt.PREFS, Context.MODE_PRIVATE)
    private val installed get() = SupportPrompt.installedVersion(context)
    // The pop-up is its own window, so either it or the agenda's + button shows the app is up.
    private fun launch() {
        app.settings.lastViewCalendar=false
        ins.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        await { find(title)!=null || find("Add menu")!=null }
    }
    private fun relaunchAndWait():Boolean {
        launch();Thread.sleep(1500);return find(title)!=null
    }

    @Before fun showIt() { SupportPrompt.showInTestApp = true; prefs.edit { clear() } }
    @After fun hideIt() { SupportPrompt.showInTestApp = false }

    @Test fun shownOnceAfterInstallAndRemindMeLaterAddsATask()=runBlocking {
        launch();await { find(title)!=null }
        screenshot("support-prompt")
        click("Remind me later")
        await { find(title)==null && data().tasks.any { it.title==SupportPrompt.TASK_TITLE && it.notes==KOFI_URL } }
        assertEquals(installed,prefs.getLong(SupportPrompt.KEY_SHOWN_FOR,0))
        // The same version again: no pop-up.
        assertFalse(relaunchAndWait())
        // The next update's "Remind me later" doesn't add a second task while the first is still to do.
        prefs.edit { putLong(SupportPrompt.KEY_SHOWN_FOR, installed - 1) }
        launch();await { find(title)!=null }
        click("Remind me later")
        await { find(title)==null && prefs.getLong(SupportPrompt.KEY_SHOWN_FOR,0)==installed }
        Thread.sleep(1500)
        assertEquals(1,data().tasks.count { it.title==SupportPrompt.TASK_TITLE })
    }

    @Test fun shownAgainAfterAnUpdateAndNoThanksCloses() {
        prefs.edit { putLong(SupportPrompt.KEY_SHOWN_FOR, installed - 1) }
        launch();await { find(title)!=null }
        click("No thanks")
        await { find(title)==null }
        assertEquals(installed,prefs.getLong(SupportPrompt.KEY_SHOWN_FOR,0))
        assertFalse(relaunchAndWait())
    }

    @Test fun supportOnKofiOpensThePage() {
        launch();await { find(title)!=null }
        val filter=IntentFilter(Intent.ACTION_VIEW).apply { addCategory(Intent.CATEGORY_DEFAULT);addDataScheme("https");addDataAuthority("ko-fi.com",null) }
        val monitor=Instrumentation.ActivityMonitor(filter,Instrumentation.ActivityResult(0,null),true)
        ins.addMonitor(monitor)
        try { click("Support on Ko-fi");await(10000) { monitor.hits==1 } } finally { ins.removeMonitor(monitor) }
        await { find(title)==null }
    }
}
