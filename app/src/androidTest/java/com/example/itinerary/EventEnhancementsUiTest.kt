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
class EventEnhancementsUiTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val repo get() = (instrumentation.targetContext.applicationContext as ItineraryApp).repository
    private fun snapshot() = runBlocking { repo.snapshot() }

    @Test fun duplicateConflictUndoAndSeriesEditingInNormalApp() = runBlocking {
        (instrumentation.targetContext.applicationContext as ItineraryApp).settings.setAgendaRange(AgendaRange.ALL)
        repo.saveItem(ItineraryItem(tripId = 0, date = LocalDate.now().plusDays(2),
            startTime = LocalTime.of(11, 15), title = "QA original event"),
            added = listOf(Attachment(itemId = 0, name = "QA link", fileName = "", mimeType = Links.MIME_TYPE, url = "https://example.com")))
        instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        click("QA original event") // Agenda → Calendar.
        click("QA original event") // Calendar → Editor.
        await { find("Edit event") != null }
        click("Duplicate event")
        await { find("Duplicate event") != null && nodes().any { it.isEditable } }
        setText(nodes().first { it.isEditable }, "QA copied event")
        click("All day") // All-day copies must not show a time collision.
        await { find("11:15") == null &&
            nodes().none { it.text?.contains("QA original event.") == true } } // no clash note
        screenshot("all-day-no-conflict")
        click("All day")
        await { find("11:15") != null }
        await {
            if (nodes().any { it.text?.let { t -> t.startsWith("Conflicts with") && t.contains("QA original event") } == true }) true
            else { nodes().firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD); false }
        }
        screenshot("duplicate-conflict")
        saveAndClose()
        await { snapshot().items.any { it.title == "QA copied event" } }
        val original = snapshot().items.single { it.title == "QA original event" }
        val copy = snapshot().items.single { it.title == "QA copied event" }
        assertNotEquals(original.id, copy.id)
        assertEquals(1, snapshot().attachments.count { it.itemId == copy.id })
        click("QA copied event")
        click("Delete")
        await { snapshot().items.none { it.id == copy.id } }
        screenshot("undo-offered")
        click("Undo")
        await { snapshot().items.any { it.id == copy.id } }
        assertEquals(1, snapshot().attachments.count { it.itemId == copy.id })
        click("Switch to Agenda view") // the calendar has no Back button
        click("Add menu");click("Add event")
        await { find("New event") != null }
        setText(nodes().first { it.isEditable }, "QA recurring event")
        click("Does not repeat")
        click("Daily")
        await { nodes().any { it.isEditable && it.text?.toString() == "12" } }
        setText(nodes().first { it.isEditable && it.text?.toString() == "12" }, "3")
        screenshot("repeat-editor")
        saveAndClose()
        await { snapshot().items.count { it.title == "QA recurring event" } == 3 }
        val seriesId = snapshot().items.first { it.title == "QA recurring event" }.seriesId!!
        click("QA recurring event")
        click("QA recurring event")
        click("Entire series")
        setText(nodes().first { it.isEditable }, "QA renamed series")
        saveAndClose()
        await { snapshot().items.count { it.title == "QA renamed series" } == 3 }
        click("QA renamed series")
        click("Delete")
        await { find("Delete repeating event?") != null }
        screenshot("delete-series")
        click("Entire series")
        await { snapshot().items.none { it.seriesId == seriesId } }
        click("Undo")
        await { snapshot().items.count { it.seriesId == seriesId } == 3 }
        click("Switch to Agenda view") // the calendar has no Back button
        screenshot("final-agenda")
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
    // Save keeps the editor open on the saved event; Close then leaves (nothing is unsaved).
    private fun saveAndClose() { click("Save"); click("Close") }
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
        val folder = File(instrumentation.targetContext.cacheDir, "qa-enhancements-evidence").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
