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
class BugFixUiTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val repo get() = (instrumentation.targetContext.applicationContext as ItineraryApp).repository
    private fun snapshot() = runBlocking { repo.snapshot() }

    @Test fun carriedOverEventShowsYesterdayAndTodaysEnd() = runBlocking {
        val context = instrumentation.targetContext
        val day = LocalDate.now()
        repo.saveItem(ItineraryItem(tripId = 0, date = day.minusDays(1), startTime = LocalTime.of(23,30), title = "QA overnight label", durationMinutes = 120))
        instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .setAction(com.example.itinerary.widget.TodayWidget.OPEN_TODAY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        await { find("QA overnight label") != null }
        await { find("Started yesterday · Until 01:30 · 2 h") != null }
        assertTrue(nodes().none { it.text?.contains("(next day)") == true })
        screenshot("overnight-calendar")
        click("QA overnight label")
        await { find("Edit event") != null }
        await {
            if (find("Until 01:30 (next day) · 2 h") != null) true
            else { nodes().firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD); false }
        }
        screenshot("overnight-editor")
        click("Discard")
        repo.deleteItem(snapshot().items.single { it.title == "QA overnight label" })
    }

    private fun nodes(): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo) {
            result.add(node)
            for (i in 0 until node.childCount) node.getChild(i)?.let(::visit)
        }
        instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
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
        val folder = File(instrumentation.targetContext.cacheDir, "qa-bug-fix-evidence").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
