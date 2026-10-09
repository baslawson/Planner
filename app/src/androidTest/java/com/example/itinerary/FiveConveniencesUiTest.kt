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
class FiveConveniencesUiTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val repo get() = (instrumentation.targetContext.applicationContext as ItineraryApp).repository
    private fun snapshot() = runBlocking { repo.snapshot() }

    @Test fun endTimeChecklistReminderUndoAndShareInNormalApp() = runBlocking {
        val app = instrumentation.targetContext.applicationContext as ItineraryApp
        app.settings.setAgendaRange(AgendaRange.ALL)
        app.settings.setTimeFormat(TimeFormat.HOUR_24)
        val day = LocalDate.now()
        repo.saveItem(ItineraryItem(tripId = 0, date = day, startTime = LocalTime.of(10, 0),
            title = "QA conveniences", location = "Cafe QA", checklist = listOf(ChecklistEntry(text = "Bring tickets"))),
            addedReminders = listOf(Reminder(itemId = 0, amount = 15, unit = ReminderUnit.MINUTES)))
        instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        click("QA conveniences")
        click("QA conveniences")
        await { find("Edit event") != null }
        click("Set end time")
        await { find("End time") != null }
        screenshot("end-time-open")
        // The end time is a clock dial: its numbers ("12 hours", "30 minutes") take a real tap, not typing.
        fun tapDial(label: String) {
            val bounds = android.graphics.Rect()
            await { find(label)?.also { it.getBoundsInScreen(bounds) } != null && !bounds.isEmpty }
            instrumentation.uiAutomation.executeShellCommand("input tap ${bounds.centerX()} ${bounds.centerY()}")
                .use { java.io.FileInputStream(it.fileDescriptor).use { stream -> stream.readBytes() } }
            Thread.sleep(500)
        }
        tapDial("12 hours")
        await { find("12 hours")?.text?.toString() == "12" } // the hour at the top of the dial
        tapDial("30 minutes")
        await { find("Ends same day · 2 h 30 min") != null }
        screenshot("end-time-picker")
        click("Set end time")
        await { find("End time: 12:30") != null } // the editor's end-time button
        click("Add task")
        screenshot("checklist-before-check")
        click("Complete task 1")
        await { find("Task 2") != null }
        fun hasLabel(node: AccessibilityNodeInfo, label: String): Boolean {
            if (node.text?.toString() == label || node.hintText?.toString() == label) return true
            return (0 until node.childCount).any { node.getChild(it)?.let { child -> hasLabel(child,label) } == true }
        }
        val field = nodes().first { it.isEditable && hasLabel(it,"Task 2") }
        setText(field,"Confirm booking")
        await {
            if (nodes().any { it.text?.contains("09:45") == true }) true
            else { nodes().firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD); false }
        }
        screenshot("checklist-reminder-preview")
        saveAndClose()
        await { snapshot().items.single { it.title == "QA conveniences" }.durationMinutes == 150 }
        val saved = snapshot().items.single { it.title == "QA conveniences" }
        assertEquals(2,saved.checklist.size)
        assertTrue(saved.checklist.first().done)
        assertEquals("Confirm booking",saved.checklist.last().text)
        click("QA conveniences")
        click("Event options");click("Duplicate event")
        await { find("Duplicate event") != null && nodes().any { it.isEditable } } // the ⋮ menu has closed and the copy is open
        setText(nodes().first { it.isEditable },"QA conveniences copy")
        saveAndClose()
        await { snapshot().items.any { it.title == "QA conveniences copy" } }
        assertTrue(snapshot().items.single { it.title == "QA conveniences copy" }.checklist.none { it.done })
        click("Switch to Agenda view") // saving from the calendar returns there
        await { find("AGENDA") != null }
        instrumentation.waitForIdleSync()
        click("Actions for QA conveniences")
        click("Move to tomorrow")
        await { snapshot().items.single { it.id == saved.id }.date == day.plusDays(1) }
        click("Undo")
        await { snapshot().items.single { it.id == saved.id }.date == day }
        assertEquals(saved,snapshot().items.single { it.id == saved.id })
        screenshot("move-undone")
        click("Actions for QA conveniences")
        click("Share event")
        await { instrumentation.uiAutomation.freshRoot?.packageName?.toString()?.let { it != instrumentation.targetContext.packageName && it != "com.google.android.inputmethod.latin" } == true }
        screenshot("share-sheet")
        instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        await { find("AGENDA") != null }
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
                    if (!scrolled) forward = !forward
                    Thread.sleep(600)
                    screenshot("scroll-$attempts")
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
        val folder = File(instrumentation.targetContext.cacheDir, "qa-five-evidence").apply { mkdirs() }
        File(folder, "$name.txt").writeText(nodes().joinToString("\n") { "${it.className} text=${it.text} desc=${it.contentDescription} editable=${it.isEditable} focus=${it.isFocused} actions=${it.actionList}" })
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
