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

/** The new controls must be visible, save their own choice, and still be checked after reopening. */
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
    private fun reveal(text: String) {
        var forward = true
        await("Missing $text") {
            if (find(text) != null) true else {
                val page = nodes().filter { it.isScrollable && it.isVisibleToUser && !it.isEditable }
                    .maxByOrNull { android.graphics.Rect().also(it::getBoundsInScreen).height() }
                if (page?.performAction(if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) == false) forward = !forward
                Thread.sleep(200); false
            }
        }
    }
    private fun click(text: String) {
        reveal(text)
        var node = find(text)
        while (node != null && !node.isClickable) node = node.parent
        assertTrue("Cannot click $text", node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
        Thread.sleep(300)
    }
    private fun ringSwitch(): AccessibilityNodeInfo? {
        var parent = find("Ring until I stop it")
        repeat(4) {
            nodes(parent).firstOrNull { it.isCheckable && it.isVisibleToUser }?.let { return it }
            parent = parent?.parent
        }
        return null
    }
    private fun enableRing() {
        reveal("Ring until I stop it")
        assertFalse(ringSwitch()!!.isChecked)
        assertTrue(ringSwitch()!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        await("Ring switch wasn't enabled") { ringSwitch()?.isChecked == true }
    }
    private fun screenshot(name: String) {
        val dir = File(context.cacheDir, "qa-ringing").apply { mkdirs() }
        ins.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
    }
    @Test fun taskRingChoiceSavesAndReopens() {
        val task = TaskCodec.decode(org.json.JSONArray().put(org.json.JSONObject()
            .put("id", "qa-ui-ring-task").put("title", "QA task ring choice").put("dueDate", org.json.JSONObject.NULL)
            .put("priority", "NORMAL").put("notes", "").put("done", false).put("reminderAt", System.currentTimeMillis() + 3_600_000))).single()
        runBlocking { app.repository.saveTask(task) }
        ins.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        click(task.title)
        enableRing(); screenshot("task-ring-on")
        click("Save"); click("Close")
        assertTrue(runBlocking { app.repository.snapshot().tasks.single().ringUntilDismissed })
        click(task.title); reveal("Ring until I stop it")
        assertTrue(ringSwitch()!!.isChecked)
        screenshot("task-reopened")
        click("Close")
    }
    // U14-3: the label and the switch are one control: TalkBack reads "Ring until I stop it", and the words toggle it.
    private fun labelledRing(): AccessibilityNodeInfo? {
        var node = find("Ring until I stop it")
        while (node != null && !node.isCheckable) node = node.parent
        return node
    }
    // U14-2: Remove reminder also ends its ringing choice: Close asks nothing, and a new reminder starts quiet.
    @Test fun taskRingSwitchIsLabelledAndEndsWithItsReminder() {
        val task = PlannerTask(id = "qa-ui-ring-remove", title = "QA ring remove")
        runBlocking { app.repository.saveTask(task) }
        ins.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        click(task.title)
        var chip: AccessibilityNodeInfo? = null
        await("No Tomorrow reminder chip") { chip = nodes().firstOrNull { it.isVisibleToUser && it.text?.toString()?.startsWith("Tomorrow") == true }; chip != null }
        while (chip != null && !chip!!.isClickable) chip = chip!!.parent
        assertTrue(chip!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        reveal("Ring until I stop it")
        val row = labelledRing()
        assertNotNull("The ring switch isn't one control with its label", row)
        assertFalse(row!!.isChecked)
        assertTrue(row.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        await("Tapping the labelled row didn't turn ringing on") { labelledRing()?.isChecked == true }
        screenshot("task-ring-labelled")
        val remove = nodes().first { it.isVisibleToUser && it.contentDescription?.toString()?.startsWith("Remove reminder") == true }
        var target: AccessibilityNodeInfo? = remove
        while (target != null && !target.isClickable) target = target.parent
        assertTrue(target!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        await("Reminder wasn't removed") { find("Ring until I stop it") == null }
        click("Close")
        Thread.sleep(800)
        assertNull("Close asked to save an unchanged task", find("Keep editing"))
        val saved = runBlocking { app.repository.snapshot().tasks.single() }
        assertNull(saved.reminderAt); assertFalse(saved.ringUntilDismissed)
    }
    // U15-1: a task an earlier build stored with ringing on but no reminder: a new reminder starts quiet, and Remove then
    // Close asks nothing. Inserted as stored (the start-up clean-up would otherwise have run already).
    @Test fun legacyRingWithoutReminderStartsQuiet() {
        runBlocking(kotlinx.coroutines.Dispatchers.IO) { app.database.taskDao().insert(PlannerTask(id = "qa-ui-legacy", title = "QA legacy ring", ringUntilDismissed = true)) }
        ins.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        click("QA legacy ring")
        var chip: AccessibilityNodeInfo? = null
        await("No Tomorrow reminder chip") { chip = nodes().firstOrNull { it.isVisibleToUser && it.text?.toString()?.startsWith("Tomorrow") == true }; chip != null }
        while (chip != null && !chip!!.isClickable) chip = chip!!.parent
        assertTrue(chip!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        reveal("Ring until I stop it")
        assertFalse("A new reminder on a legacy task started with ringing on", labelledRing()!!.isChecked)
        var target: AccessibilityNodeInfo? = nodes().first { it.isVisibleToUser && it.contentDescription?.toString()?.startsWith("Remove reminder") == true }
        while (target != null && !target.isClickable) target = target.parent
        assertTrue(target!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        await("Reminder wasn't removed") { find("Ring until I stop it") == null }
        click("Close"); Thread.sleep(800)
        assertNull("Close asked to save an unchanged legacy task", find("Keep editing"))
    }
    @Test fun noteRingChoiceSavesAndReopens() {
        val initial = NoteCodec.decode(org.json.JSONArray().put(org.json.JSONObject().put("id", "qa-ui-ring-note")
            .put("title", "QA note ring choice").put("content", "").put("reminderAt", System.currentTimeMillis() + 3_600_000))).single()
        val note = runBlocking { app.repository.saveNote(initial, true) }
        ins.startActivitySync(NoteReminderReceiver.openIntent(context, note.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        enableRing(); screenshot("note-ring-on")
        click("Save"); click("Close")
        assertTrue(runBlocking { app.repository.note(note.id)!!.ringUntilDismissed })
        context.startActivity(NoteReminderReceiver.openIntent(context, note.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        reveal("Ring until I stop it"); assertTrue(ringSwitch()!!.isChecked)
        screenshot("note-reopened")
        click("Close")
    }
}
