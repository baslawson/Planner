package com.example.itinerary

import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.example.itinerary.data.*
import com.example.itinerary.reminders.NoteReminderReceiver
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * A reminder's sound (bugnotes 7 Oct: "Sound: Default (…) ▾" in place of the "Ring until I stop it" switch) must be
 * visible, save its own choice, and still show after reopening. CleanStart sets Settings' default to the notification
 * sound only, so Default reads "Default (notification sound only)" here.
 */
class TaskNoteRingingUiTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = ins.targetContext
    private val app get() = context.applicationContext as ItineraryApp
    private fun nodes(root: AccessibilityNodeInfo? = ins.uiAutomation.freshRoot): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun walk(n: AccessibilityNodeInfo) { result += n; for (i in 0 until n.childCount) n.getChild(i)?.let(::walk) }
        root?.let(::walk); return result
    }
    private fun find(text: String) = nodes().firstOrNull { it.isVisibleToUser && (it.text?.toString() == text || it.contentDescription?.toString() == text) }
    private fun await(message: String, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 20_000
        while (SystemClock.uptimeMillis() < end) { if (condition()) return; Thread.sleep(150) }
        fail(message + ": " + nodes().filter { it.isVisibleToUser }.mapNotNull { it.text ?: it.contentDescription }.joinToString(" | "))
    }
    private fun reveal(text: String) = revealWhere("Missing $text") { find(text) != null }
    private fun revealWhere(message: String, shown: () -> Boolean) {
        var forward = true
        await(message) {
            if (shown()) true else {
                val page = nodes().filter { it.isScrollable && it.isVisibleToUser && !it.isEditable }
                    .maxByOrNull { android.graphics.Rect().also(it::getBoundsInScreen).height() }
                if (page?.performAction(if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) == false) forward = !forward
                Thread.sleep(200); false
            }
        }
    }
    private fun clickNode(start: AccessibilityNodeInfo?, what: String) {
        var node = start
        while (node != null && !node.isClickable) node = node.parent
        assertTrue("Cannot click $what", node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
        Thread.sleep(300)
    }
    private fun click(text: String) { reveal(text); clickNode(find(text), text) }
    // The reminder's "Sound: …" button (a dropdown), or null with no reminder.
    private fun soundButton() = nodes().firstOrNull { it.isVisibleToUser && it.text?.toString()?.startsWith("Sound: ") == true }
    private fun soundShown() = soundButton()?.text?.toString()?.removePrefix("Sound: ")
    private fun chooseSound(label: String) {
        revealWhere("No Sound choice") { soundButton() != null }
        clickNode(soundButton(), "Sound")
        await("The Sound list didn't open") { find(label) != null }
        clickNode(find(label), label)
        await("Sound didn't become $label") { soundShown() == label }
    }
    private fun screenshot(name: String) {
        val dir = File(context.cacheDir, "qa-ringing").apply { mkdirs() }
        ins.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
    }
    private fun addTomorrowReminder() {
        if (find("Add reminder") != null || nodes().none { it.isVisibleToUser && it.text?.toString()?.startsWith("Tomorrow") == true }) {
            reveal("Add reminder"); click("Add reminder")
        }
        var chip: AccessibilityNodeInfo? = null
        await("No Tomorrow reminder choice") { chip = nodes().firstOrNull { it.isVisibleToUser && it.text?.toString()?.startsWith("Tomorrow") == true }; chip != null }
        clickNode(chip, "Tomorrow")
    }
    private fun removeReminder() {
        clickNode(nodes().first { it.isVisibleToUser && it.contentDescription?.toString()?.startsWith("Remove reminder") == true }, "Remove reminder")
        await("Reminder wasn't removed") { soundButton() == null }
    }

    @Test fun taskSoundChoiceSavesAndReopens() {
        val task = TaskCodec.decode(org.json.JSONArray().put(org.json.JSONObject()
            .put("id", "qa-ui-ring-task").put("title", "QA task ring choice").put("dueDate", org.json.JSONObject.NULL)
            .put("priority", "NORMAL").put("notes", "").put("done", false).put("reminderAt", System.currentTimeMillis() + 3_600_000))).single()
        runBlocking { app.repository.saveTask(task) }
        ins.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        click(task.title)
        revealWhere("No Sound choice") { soundButton() != null }
        assertEquals("a task saved before reads as Default", "Default (notification sound only)", soundShown())
        chooseSound("Until I stop it"); screenshot("task-ring-on")
        click("Save"); click("Close")
        runBlocking { app.repository.snapshot().tasks.single() }.let { assertTrue(it.ringUntilDismissed); assertEquals(0, it.ringSeconds) }
        click(task.title)
        revealWhere("No Sound choice") { soundButton() != null }
        assertEquals("Until I stop it", soundShown())
        chooseSound("30 seconds")
        click("Save"); click("Close")
        runBlocking { app.repository.snapshot().tasks.single() }.let { assertFalse(it.ringUntilDismissed); assertEquals(30, it.ringSeconds) }
        click(task.title)
        revealWhere("No Sound choice") { soundButton() != null }
        assertEquals("30 seconds", soundShown())
        screenshot("task-reopened")
        click("Close")
    }

    // U14-2: Remove reminder also ends its sound choice: Close asks nothing, and a new reminder starts at Default.
    @Test fun taskSoundEndsWithItsReminder() {
        val task = PlannerTask(id = "qa-ui-ring-remove", title = "QA ring remove")
        runBlocking { app.repository.saveTask(task) }
        ins.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        click(task.title)
        addTomorrowReminder()
        revealWhere("No Sound choice") { soundButton() != null }
        assertEquals("Default (notification sound only)", soundShown())
        chooseSound("Until I stop it")
        screenshot("task-ring-chosen")
        removeReminder()
        click("Close")
        Thread.sleep(800)
        assertNull("Close asked to save an unchanged task", find("Keep editing"))
        val saved = runBlocking { app.repository.snapshot().tasks.single() }
        assertNull(saved.reminderAt); assertFalse(saved.ringUntilDismissed); assertEquals(0, saved.ringSeconds)
    }

    // U15-1: a task an earlier build stored with ringing on but no reminder: a new reminder starts at Default, and Remove
    // then Close asks nothing. Inserted as stored (the start-up clean-up would otherwise have run already).
    @Test fun legacyRingWithoutReminderStartsAtDefault() {
        runBlocking(kotlinx.coroutines.Dispatchers.IO) { app.database.taskDao().insert(PlannerTask(id = "qa-ui-legacy", title = "QA legacy ring", ringUntilDismissed = true, ringSeconds = 30)) }
        ins.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        click("QA legacy ring")
        addTomorrowReminder()
        revealWhere("No Sound choice") { soundButton() != null }
        assertEquals("A new reminder on a legacy task didn't start at Default", "Default (notification sound only)", soundShown())
        removeReminder()
        click("Close"); Thread.sleep(800)
        assertNull("Close asked to save an unchanged legacy task", find("Keep editing"))
    }

    @Test fun noteSoundChoiceSavesAndReopens() {
        val initial = NoteCodec.decode(org.json.JSONArray().put(org.json.JSONObject().put("id", "qa-ui-ring-note")
            .put("title", "QA note ring choice").put("content", "").put("reminderAt", System.currentTimeMillis() + 3_600_000))).single()
        val note = runBlocking { app.repository.saveNote(initial, true) }
        ins.startActivitySync(NoteReminderReceiver.openIntent(context, note.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        // The reminder is in the note's details panel.
        await("No Note details button") { find("Note details") != null }
        click("Note details")
        chooseSound("1 minute"); screenshot("note-ring-on")
        click("Close note details")
        click("Close") // Close saves a note without asking (auto save)
        await("The note's sound wasn't saved") { runBlocking { app.repository.note(note.id)!! }.let { !it.ringUntilDismissed && it.ringSeconds == 60 } }
        context.startActivity(NoteReminderReceiver.openIntent(context, note.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        await("No Note details button") { find("Note details") != null }
        click("Note details")
        revealWhere("No Sound choice") { soundButton() != null }
        assertEquals("1 minute", soundShown())
        chooseSound("Until I stop it")
        click("Close note details")
        click("Close") // Close saves a note without asking (auto save)
        await("The note's sound wasn't saved") { runBlocking { app.repository.note(note.id)!! }.let { it.ringUntilDismissed && it.ringSeconds == 0 } }
        screenshot("note-reopened")
    }
}
