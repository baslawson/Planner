package com.example.itinerary

import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.example.itinerary.data.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.LocalDate

/**
 * Wish list #1 on screen: a task's ⋮ Make it an event, an event's ⋮ Make it a task, a repeating event's editor making the
 * whole series one task, the notice of what won't carry over, Save replacing the original and Undo taking both back.
 * Run only with an external backup/restore harness for the shared emulator.
 */
class TaskEventConversionUiTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = ins.targetContext
    private val app get() = context.applicationContext as ItineraryApp
    private val repo get() = app.repository
    private val day = LocalDate.now().plusDays(3)

    private fun nodes(): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(n: AccessibilityNodeInfo) { result += n; for (i in 0 until n.childCount) n.getChild(i)?.let(::visit) }
        ins.uiAutomation.windows.mapNotNull { it.root }.ifEmpty { listOfNotNull(ins.uiAutomation.rootInActiveWindow) }.forEach(::visit)
        return result
    }
    private fun find(text: String) = nodes().firstOrNull { it.isVisibleToUser && (it.text?.toString() == text || it.contentDescription?.toString() == text) }
    private fun has(part: String) = nodes().any { it.isVisibleToUser && it.text?.toString()?.contains(part) == true }
    private fun screenshot(name: String) {
        val dir = File(context.cacheDir, "qa-convert").apply { mkdirs() }
        ins.uiAutomation.takeScreenshot()?.let { b -> File(dir, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }; b.recycle() }
    }
    private fun await(timeout: Long = 30000, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < end) { if (condition()) return; Thread.sleep(150) }
        screenshot("failure"); fail("Timed out: " + nodes().mapNotNull { it.text }.joinToString(" | "))
    }
    // Clicks [text], scrolling the biggest list forward to find it when it is further down.
    private fun click(text: String) {
        await {
            var n = find(text)
            if (n == null) nodes().filter { it.isScrollable && it.isVisibleToUser }.maxByOrNull { r -> android.graphics.Rect().also(r::getBoundsInScreen).height() }
                ?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            while (n != null && !n.isClickable) n = n.parent
            n?.takeIf { it.isEnabled }?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        }; Thread.sleep(500)
    }
    private fun data() = runBlocking { repo.snapshot() }
    private fun open() {
        app.settings.setAgendaRange(AgendaRange.ALL); app.settings.setAgendaTypes(AgendaType.entries.toSet()); app.settings.lastViewCalendar = false
        ins.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        await { find("AGENDA") != null }
    }

    @Before fun fresh() { TaskDraftStore(context).clear("new"); EditorDraftStore(context).clear() }
    @After fun done() {
        runBlocking {
            data().tasks.filter { it.title.startsWith("QA make") }.forEach { repo.deleteTask(it.id) }
            data().items.filter { it.title.startsWith("QA make") }.forEach { repo.deleteWithUndo(it.id) }
        }
        TaskDraftStore(context).clear("new"); EditorDraftStore(context).clear()
    }

    @Test fun aTaskMadeIntoAnEventAndUndone() = runBlocking {
        val task = PlannerTask(title = "QA make event", dueDate = day, priority = TaskPriority.HIGH, notes = "from a task").also { repo.saveTask(it) }
        open()
        click("Actions for QA make event"); click("Make it an event")
        await { find("New event") != null && has("Saving makes this event") }
        assertTrue(has("Won't carry over:"))
        screenshot("task-to-event-editor")
        click("Save")
        await { data().items.any { it.title == "QA make event" } && data().tasks.none { it.id == task.id } }
        val event = data().items.single { it.title == "QA make event" }
        assertEquals(day, event.date); assertNull(event.startTime); assertEquals("from a task", event.notes)
        click("Close")
        await { find("Made into an event") != null }
        screenshot("made-into-an-event")
        click("Undo")
        await { data().tasks.any { it.id == task.id } && data().items.none { it.title == "QA make event" } }
    }

    @Test fun anEventMadeIntoATask() = runBlocking {
        repo.saveItemId(ItineraryItem(tripId = 0, date = day, startTime = java.time.LocalTime.of(14, 0), title = "QA make task", location = "Office"))
        open()
        click("Actions for QA make task"); click("Make it a task")
        await { find("Add task") != null && has("Saving makes this task") }
        assertTrue(has("Won't carry over:"))
        screenshot("event-to-task-editor")
        click("Save")
        await { data().tasks.any { it.title == "QA make task" } && data().items.none { it.title == "QA make task" } }
        assertEquals(day, data().tasks.single { it.title == "QA make task" }.dueDate)
        click("Close")
        await { find("Made into a task") != null }
    }

    @Test fun aRepeatingEventMadeIntoOneRepeatingTaskFromItsEditor() = runBlocking {
        repo.saveItemId(ItineraryItem(tripId = 0, date = day, startTime = null, title = "QA make series", repeatRule = "WEEKLY"),
            options = EventSaveOptions(repeat = RepeatRule(RepeatRule.Kind.WEEKLY), count = 3))
        open()
        await { nodes().any { it.text?.toString() == "QA make series" } }
        // From the agenda a tap opens the calendar day and a second tap the editor.
        click("QA make series")
        if (find("Edit event") == null) { Thread.sleep(700); if (find("Edit event") == null) click("QA make series") }
        await { find("Edit event") != null }
        click("Make it a task")
        await { find("Whole series") != null }
        click("Whole series")
        await { find("Add task") != null && has("Saving makes this task") }
        click("Save")
        await { data().tasks.any { it.title == "QA make series" } && data().items.none { it.title == "QA make series" } }
        assertEquals("WEEKLY", data().tasks.single { it.title == "QA make series" }.repeat)
        click("Close")
    }
}
