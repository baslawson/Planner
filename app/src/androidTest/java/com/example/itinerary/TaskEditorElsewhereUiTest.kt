package com.example.itinerary

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.example.itinerary.data.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.LocalDate

/** U-N1/U-N2: an open task editor whose task changes underneath it (a sync pull) says so, offers Reload, and asks before
 *  Save writes over it; one whose task is deleted meanwhile stays open with a banner, can't save, and closes cleanly
 *  (in the agenda too, where it used to vanish and reopen when the task was restored). */
@Suppress("DEPRECATION")
class TaskEditorElsewhereUiTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = ins.targetContext
    private val app get() = context.applicationContext as ItineraryApp
    private fun nodes(): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(n: AccessibilityNodeInfo) { result += n; for (i in 0 until n.childCount) n.getChild(i)?.let(::visit) }
        ins.uiAutomation.freshRoot?.let(::visit); return result
    }
    private fun find(text: String) = nodes().firstOrNull { it.isVisibleToUser && (it.text?.toString() == text || it.contentDescription?.toString() == text) }
    private fun has(part: String) = nodes().any { it.isVisibleToUser && it.text?.toString()?.contains(part) == true }
    private fun screenshot(name: String) {
        val dir = File(context.cacheDir, "qa-task-editor-elsewhere").apply { mkdirs() }
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
            n?.isEnabled == true && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        Thread.sleep(400)
    }
    private fun enabled(text: String): Boolean? {
        var n = find(text); while (n != null && !n.isClickable) n = n.parent
        return n?.isEnabled
    }
    // The task's title box (the first text box in the editor).
    private fun setTitle(value: String) {
        await { nodes().any { it.isEditable && it.isVisibleToUser } }
        assertTrue(nodes().first { it.isEditable && it.isVisibleToUser }.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        })); Thread.sleep(400)
    }
    private fun stored(id: String) = runBlocking { app.repository.task(id) }
    private fun openFromWidget(task: PlannerTask) {
        ins.startActivitySync(Intent(context, MainActivity::class.java).setAction(com.example.itinerary.widget.TodayWidget.OPEN_TASK)
            .putExtra("task_id", task.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        await { find("Edit task") != null }
    }

    @After fun cleanUp() = runBlocking {
        val repo = app.repository
        repo.snapshot().tasks.filter { it.title.startsWith("QA Elsewhere") }.forEach { repo.deleteTask(it.id) }
        repo.pendingDeletions.value.forEach { repo.finishDeletion(it.token) }
        repo.snapshot().deleted.filter { it.label.startsWith("QA Elsewhere") }.forEach { repo.permanentlyDelete(it.id) }
    }

    @Test fun changedUnderneathOffersReloadAndAsksBeforeSaving() {
        val task = PlannerTask(title = "QA Elsewhere task", dueDate = LocalDate.now())
        runBlocking { app.repository.saveTask(task) }
        openFromWidget(task)
        // A sync pull changes it: the banner, and Reload shows it as stored.
        runBlocking { app.repository.saveTask(stored(task.id)!!.copy(title = "QA Elsewhere task (web)"), create = false) }
        await { find("This task was changed elsewhere") != null }
        screenshot("changed")
        click("Reload")
        await { find("This task was changed elsewhere") == null && has("QA Elsewhere task (web)") }
        // Changed again while edited here: Save asks; Save anyway keeps this version.
        setTitle("QA Elsewhere task (mine)")
        runBlocking { app.repository.saveTask(stored(task.id)!!.copy(notes = "From Nextcloud"), create = false) }
        await { find("This task was changed elsewhere") != null }
        click("Save")
        await { find("Changed elsewhere") != null }
        screenshot("asking")
        assertEquals("QA Elsewhere task (web)", stored(task.id)!!.title) // nothing written yet
        click("Save anyway")
        await { stored(task.id)!!.title == "QA Elsewhere task (mine)" }
    }

    @Test fun deletedUnderneathStaysOpenAndClosesCleanly() {
        val task = PlannerTask(title = "QA Elsewhere deleted", dueDate = LocalDate.now())
        runBlocking { app.repository.saveTask(task) }
        openFromWidget(task)
        runBlocking { app.repository.archiveTask(task.id) { true } }
        await { has("This task was deleted elsewhere") }
        assertNotNull(find("Edit task")) // still open, not "Task unavailable"
        assertNull(find("Task unavailable"))
        assertNull(find("Delete"))
        screenshot("deleted")
        click("Close")
        await { find("Edit task") == null }
        Thread.sleep(800)
        assertNull(find("Task unavailable"))
    }

    @Test fun deletedUnderneathInTheAgendaDoesntReopenWhenRestored() {
        val task = PlannerTask(title = "QA Elsewhere agenda", dueDate = LocalDate.now())
        runBlocking { app.repository.saveTask(task) }
        app.settings.lastViewCalendar = false
        runBlocking { app.settings.setAgendaRange(AgendaRange.ALL) }
        ins.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        await { find("AGENDA") != null }
        click("QA Elsewhere agenda")
        await { find("Edit task") != null }
        runBlocking { app.repository.archiveTask(task.id) { true } }
        await { has("This task was deleted elsewhere") }
        assertEquals(false, enabled("Save"))
        click("Close")
        await { find("Edit task") == null }
        // Restored: the agenda shows it, and no editor opens by itself.
        runBlocking { app.repository.snapshot().deleted.filter { it.label == "QA Elsewhere agenda" }.forEach { app.repository.restoreDeleted(it.id) } }
        await { find("QA Elsewhere agenda") != null }
        Thread.sleep(1500)
        assertNull(find("Edit task"))
    }
}
