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

/**
 * Only one editor at a time on the same draft.
 * U2: a bill editor that Agenda reopens while AppNav's recovery editor already has that bill's draft closes again.
 * U3: a second Planner window (here a share) doesn't recover the draft of an editor still open in the first, and
 * sharing to an event says an event is open.
 * U4: the widget's task editor doesn't open on a task already open in the agenda's (they shared one draft).
 * U5: the widget's day waits for an open task editor, as for an event editor (D10), instead of dropping it.
 * Run only with an external backup/restore harness for the shared emulator (it adds events and tasks).
 */
@Suppress("DEPRECATION")
class EditorOwnershipUiTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = ins.targetContext
    private val app get() = context.applicationContext as ItineraryApp
    private fun data() = runBlocking { app.repository.snapshot() }
    private fun nodes(): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(n: AccessibilityNodeInfo) { result += n; for (i in 0 until n.childCount) n.getChild(i)?.let(::visit) }
        ins.uiAutomation.freshRoot?.let(::visit); return result
    }
    private fun find(text: String) = nodes().firstOrNull { it.isVisibleToUser && !it.isEditable && (it.text?.toString() == text || it.contentDescription?.toString() == text) }
    private fun screenshot(name: String) {
        val dir = File(context.cacheDir, "qa-editor-ownership").apply { mkdirs() }
        ins.uiAutomation.takeScreenshot()?.let { b -> File(dir, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }; b.recycle() }
    }
    private fun await(timeout: Long = 30000, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < end) { if (condition()) return; Thread.sleep(150) }
        screenshot("failure"); fail("Timed out: " + nodes().mapNotNull { it.text }.joinToString(" | "))
    }
    private fun click(text: String) {
        var tries = 0; var forward = true
        await {
            var node = find(text)
            while (node != null && !node.isClickable) node = node.parent
            if (node != null && node.isEnabled) node.performAction(AccessibilityNodeInfo.ACTION_CLICK) else {
                if (++tries % 4 == 0 && !scrollStep(nodes(), forward)) forward = !forward
                false
            }
        }; Thread.sleep(350)
    }
    private fun launch() {
        app.settings.lastViewCalendar = false
        ins.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        await { find("AGENDA") != null }
    }

    private fun setText(old: String, value: String) {
        await { pickEditable(nodes(), old) != null }
        assertTrue(pickEditable(nodes(), old)!!.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        })); Thread.sleep(350)
    }

    @Test fun aSecondWindowDoesNotRecoverTheOpenEditorsDraft() = runBlocking {
        assertNull(EditorDraftStore(context).read())
        app.settings.setAgendaRange(AgendaRange.ALL)
        app.repository.saveItem(ItineraryItem(tripId = 0, date = LocalDate.now().plusDays(3), startTime = null, title = "QA window bill", category = "Bills"))
        launch()
        click("QA window bill")
        await { find("Edit bill task") != null }
        setText("QA window bill", "QA window bill edited")
        await { EditorDraftStore(context).read() != null }
        // A share opens Planner again in its own task while the first window's editor is open.
        ins.startActivitySync(Intent(context, MainActivity::class.java).setAction(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "QA shared while editing")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_NEW_DOCUMENT))
        await { find("Add to Planner") != null }
        Thread.sleep(1500)
        // No recovery editor here on the first window's draft.
        assertNull(pickEditable(nodes(), "QA window bill edited"))
        click("Add event")
        await { find("An event is open in Planner. Close this share, then save or close that event before sharing again.") != null }
        screenshot("second-window-share")
        click("Cancel")
        // The first window's draft is untouched.
        assertEquals("QA window bill edited", DraftCodec.item(EditorDraftStore(context).read()!!.getJSONObject("item")).title)
        EditorDraftStore(context).clear() // test data only; the harness restores the rest
    }

    private fun openWidgetTask(id: String) {
        context.startActivity(Intent(context, MainActivity::class.java).apply {
            action = "com.example.itinerary.widget.OPEN_TASK"
            putExtra("task_id", id)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
        Thread.sleep(500)
    }

    @Test fun theWidgetDoesNotOpenATaskAlreadyBeingEdited() = runBlocking {
        app.settings.setAgendaRange(AgendaRange.ALL)
        val task = PlannerTask(title = "QA owned task", dueDate = LocalDate.now().plusDays(2)).also { app.repository.saveTask(it) }
        launch()
        click("QA owned task")
        await { find("Edit task") != null }
        setText("QA owned task", "QA owned task edited")
        await { TaskDraftStore(context).read(task.id) != null }
        openWidgetTask(task.id)
        await { find("Task already open") != null }
        // No "Unfinished task" question on the agenda editor's draft, so nothing to discard there.
        assertNull(find("Unfinished task"))
        screenshot("task-already-open")
        click("Close")
        await { find("Task already open") == null && pickEditable(nodes(), "QA owned task edited") != null }
        assertEquals("QA owned task edited", TaskDraftStore(context).read(task.id)!!.optString("title"))
        click("Close")
        await { find("Save changes?") != null }
        click("Discard")
        await { find("Edit task") == null }
        assertEquals("QA owned task", data().tasks.single { it.id == task.id }.title)
    }

    @Test fun theWidgetsDayWaitsForAnOpenTaskEditor() = runBlocking {
        app.settings.setAgendaRange(AgendaRange.ALL)
        val task = PlannerTask(title = "QA waiting task", dueDate = LocalDate.now().plusDays(2)).also { app.repository.saveTask(it) }
        launch()
        click("QA waiting task")
        await { find("Edit task") != null }
        setText("QA waiting task", "QA waiting task edited")
        context.startActivity(Intent(context, MainActivity::class.java).apply {
            action = "com.example.itinerary.widget.OPEN_DATE"
            putExtra("widget_date", LocalDate.now().toString())
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
        Thread.sleep(1500)
        // Still on the task, with its edit; the day waits.
        assertNotNull(pickEditable(nodes(), "QA waiting task edited"))
        assertNull(find("CALENDAR"))
        screenshot("widget-day-waits-for-task")
        click("Close")
        await { find("Save changes?") != null }
        click("Discard")
        await { find("CALENDAR") != null && find("Edit task") == null }
        assertEquals("QA waiting task", data().tasks.single { it.id == task.id }.title)
    }

    @Test fun aBillEditorLeavesARecoveredBillToTheRecoveryEditor() = runBlocking {
        assertNull(EditorDraftStore(context).read())
        app.settings.setAgendaRange(AgendaRange.ALL)
        app.repository.saveItem(ItineraryItem(tripId = 0, date = LocalDate.now().plusDays(3), startTime = null, title = "QA owned bill", category = "Bills"))
        val id = data().items.single { it.title == "QA owned bill" }.id
        launch()
        // As while AppNav's recovery editor is up on this bill after process death.
        EditorDraftStore.recoveryOpened(id)
        try {
            click("QA owned bill")
            Thread.sleep(1500)
            assertNull(find("Edit bill task"))
            assertEquals(0, EditorDraftStore.openEditors.value)
            screenshot("left-to-recovery")
        } finally { EditorDraftStore.recoveryClosed(id) }
        // Once recovery is closed, the bill opens as usual.
        click("QA owned bill")
        await { find("Edit bill task") != null }
        click("Close")
        await { find("Edit bill task") == null }
    }
}
