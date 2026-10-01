package com.example.itinerary

import android.content.Intent
import android.graphics.Bitmap
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

/** The floating "Checklist n/m" button in the task and event editors: shown while the checklist is out of sight, one tap
 *  scrolls to it and it disappears, back again once scrolled away; never there without checklist items. */
@Suppress("DEPRECATION")
class ChecklistJumpUiTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = ins.targetContext
    private val app get() = context.applicationContext as ItineraryApp
    private fun nodes(): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(n: AccessibilityNodeInfo) { result += n; for (i in 0 until n.childCount) n.getChild(i)?.let(::visit) }
        ins.uiAutomation.freshRoot?.let(::visit); return result
    }
    private fun find(text: String) = nodes().firstOrNull { it.isVisibleToUser && (it.text?.toString() == text || it.contentDescription?.toString() == text) }
    // The floating button, whose label carries the progress ("Go to checklist, 1 of 3 done").
    private fun jump() = nodes().firstOrNull { it.isVisibleToUser && it.contentDescription?.toString()?.startsWith("Go to checklist") == true }
    // Activated as a screen reader does (an accessibility click on the labelled node).
    private fun clickJump() { await { jump()?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true }; Thread.sleep(800) }
    private fun screenshot(name: String) {
        val dir = File(context.cacheDir, "qa-checklist-jump").apply { mkdirs() }
        ins.uiAutomation.takeScreenshot()?.let { b -> File(dir, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }; b.recycle() }
    }
    private fun await(timeout: Long = 20000, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < end) { if (condition()) return; Thread.sleep(150) }
        screenshot("failure"); fail("Timed out: " + nodes().filter { it.isVisibleToUser }.mapNotNull { it.text ?: it.contentDescription }.joinToString(" | "))
    }
    private fun click(text: String) {
        await {
            val node = nodes().firstOrNull { it.isVisibleToUser && (it.text?.toString() == text || it.contentDescription?.toString() == text) }
            var n = node; while (n != null && !n.isClickable) n = n.parent
            n?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        }
        Thread.sleep(400)
    }
    // A real touch swipe, as a finger would.
    private fun swipe(down: Boolean) {
        val m = context.resources.displayMetrics
        val (from, to) = if (down) m.heightPixels * 3 / 10 to m.heightPixels * 8 / 10 else m.heightPixels * 7 / 10 to m.heightPixels * 3 / 10
        android.os.ParcelFileDescriptor.AutoCloseInputStream(ins.uiAutomation.executeShellCommand("input swipe ${m.widthPixels / 3} $from ${m.widthPixels / 3} $to 300")).use { it.readBytes() }
        Thread.sleep(800)
    }
    private val longNotes = (1..25).joinToString("\n") { "Note line $it" }
    private val list = listOf(ChecklistEntry(text = "Passport", done = true), ChecklistEntry(text = "Charger"), ChecklistEntry(text = "Tickets"))

    private fun checkJump(editorTitle: String) {
        await { find(editorTitle) != null }
        hideQuickTestKeyboard(ins)
        // The checklist is below the fold: the button offers it.
        await { jump() != null && find("Charger") == null }
        assertEquals("Go to checklist, 1 of 3 done", jump()!!.contentDescription.toString())
        // One node a screen reader can read and activate.
        assertTrue(jump()!!.isClickable)
        screenshot("$editorTitle-button")
        clickJump()
        // Scrolled there: the checklist is on screen and the button is gone.
        await { find("Charger") != null && jump() == null }
        screenshot("$editorTitle-checklist")
        // Scrolled back up: it's offered again.
        repeat(4) { swipe(down = true) }
        await { jump() != null }
        // Ticking an item there updates the count.
        clickJump()
        await { find("Complete task 2") != null }
        click("Complete task 2")
        repeat(4) { swipe(down = true) }
        await { jump()?.contentDescription?.toString() == "Go to checklist, 2 of 3 done" }
        screenshot("$editorTitle-ticked")
    }

    @Test fun taskEditor() {
        val task = PlannerTask(title = "QA Pack bag", dueDate = LocalDate.now(), notes = longNotes, checklist = list)
        runBlocking { app.repository.saveTask(task) }
        ins.startActivitySync(Intent(context, MainActivity::class.java).setAction(com.example.itinerary.widget.TodayWidget.OPEN_TASK)
            .putExtra("task_id", task.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        checkJump("Edit task")
    }

    @Test fun eventEditor() {
        runBlocking { app.repository.saveItem(ItineraryItem(tripId = 0, date = LocalDate.now(), startTime = LocalTime.of(23, 0), title = "QA Trip day",
            notes = longNotes, checklist = list)) }
        app.settings.lastViewCalendar = false
        ins.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        await { find("AGENDA") != null }
        // A tap in the agenda first shows that day in the calendar; a tap on it there opens the editor.
        click("QA Trip day")
        Thread.sleep(800)
        if (find("Edit event") == null) click("QA Trip day")
        checkJump("Edit event")
    }

    @Test fun noButtonWithoutChecklistItems() {
        val task = PlannerTask(title = "QA No list", dueDate = LocalDate.now(), notes = longNotes)
        runBlocking { app.repository.saveTask(task) }
        ins.startActivitySync(Intent(context, MainActivity::class.java).setAction(com.example.itinerary.widget.TodayWidget.OPEN_TASK)
            .putExtra("task_id", task.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        await { find("Edit task") != null }
        Thread.sleep(1500)
        assertNull(jump())
    }
}
