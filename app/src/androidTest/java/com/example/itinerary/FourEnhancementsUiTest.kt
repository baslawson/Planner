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
import java.time.LocalTime

/** Normal application flow. Run only after backing up user data; the external harness restores it. */
@Suppress("DEPRECATION")
class FourEnhancementsUiTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val repo get() = (instrumentation.targetContext.applicationContext as ItineraryApp).repository
    private fun snapshot() = runBlocking { repo.snapshot() }

    @Test fun durationOverlapMoveAndFilterInNormalApp() = runBlocking {
        val app = instrumentation.targetContext.applicationContext as ItineraryApp
        app.settings.setAgendaRange(AgendaRange.ALL)
        val day = LocalDate.now()
        repo.saveItem(ItineraryItem(tripId = 0, date = day, startTime = LocalTime.of(9, 0), title = "QA duration", durationMinutes = 60))
        repo.saveItem(ItineraryItem(tripId = 0, date = day, startTime = LocalTime.of(9, 30), title = "QA overlap"))
        instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        click("Today")
        await { app.settings.agendaRange.value == AgendaRange.TODAY }
        click("QA duration")
        click("QA duration")
        await { find("Edit event") != null }
        click("2 hours")
        await {
            if (nodes().any { it.text?.let { t -> t.startsWith("Overlaps with") && t.contains("QA overlap") } == true }) true
            else { nodes().firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD); false }
        }
        screenshot("duration-overlap")
        click("Save")
        await { snapshot().items.single { it.title == "QA duration" }.durationMinutes == 120 }
        click("Switch to Agenda view") // saving from the calendar returns there
        await { find("AGENDA") != null }
        instrumentation.waitForIdleSync()
        click("Actions for QA duration")
        screenshot("move-menu")
        click("Move to tomorrow")
        await { snapshot().items.single { it.title == "QA duration" }.date == day.plusDays(1) }
        await { find("QA duration") == null }
        click("Upcoming")
        await { find("QA duration") != null }
        click("Actions for QA duration")
        await { find("Already tomorrow") != null }
        screenshot("moved-event")
        instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        click("All")
        await { app.settings.agendaRange.value == AgendaRange.ALL }
        screenshot("all-selected")
    }

    private fun nodes(): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo) {
            result.add(node)
            for (i in 0 until node.childCount) node.getChild(i)?.let(::visit)
        }
        instrumentation.uiAutomation.freshRoot?.let(::visit)
        return result
    }
    private fun find(text: String) = nodes().firstOrNull { it.isVisibleToUser &&
        (it.text?.toString() == text || it.contentDescription?.toString() == text) }
    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(150)
        }
        screenshot("failure")
        throw AssertionError("Timed out. Visible text: " + nodes().mapNotNull { it.text?.toString() }.joinToString(" | "))
    }
    private fun click(text: String) {
        var attempts = 0
        var forward = true
        await {
            var target = find(text)
            while (target != null && !target.isClickable) target = target.parent
            if (target != null) target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            else {
                // Give navigation/recomposition time before moving the new screen. Search both
                // directions so a transient missing node cannot strand the test below the target.
                if (++attempts > 5) {
                    val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                    val scrolled = nodes().firstOrNull { it.isScrollable }?.performAction(action) == true
                    if (!scrolled || attempts % 12 == 0) forward = !forward
                }
                false
            }
        }
        Thread.sleep(350)
    }
    private fun setText(node: AccessibilityNodeInfo, value: String) {
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }))
        Thread.sleep(250)
    }
    private fun screenshot(name: String) {
        val folder = File(instrumentation.targetContext.cacheDir, "qa-four-evidence").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
