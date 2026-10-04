package com.example.itinerary

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
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
 * Wish list (5 Oct 2026): Settings → Open Planner on (#10), photo and PDF previews in the task and note editors (#4), and
 * Undo / Redo of typing (#2). Run only with an external backup/restore harness for the shared emulator.
 */
class WishListMediumUiTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = ins.targetContext
    private val app get() = context.applicationContext as ItineraryApp
    private val repo get() = app.repository

    private fun nodes(): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(n: AccessibilityNodeInfo) { result += n; for (i in 0 until n.childCount) n.getChild(i)?.let(::visit) }
        ins.uiAutomation.windows.mapNotNull { it.root }.ifEmpty { listOfNotNull(ins.uiAutomation.rootInActiveWindow) }.forEach(::visit)
        return result
    }
    private fun find(text: String) = nodes().firstOrNull { it.isVisibleToUser && (it.text?.toString() == text || it.contentDescription?.toString() == text) }
    private fun screenshot(name: String) {
        val dir = File(context.cacheDir, "qa-wish-medium").apply { mkdirs() }
        ins.uiAutomation.takeScreenshot()?.let { b -> File(dir, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }; b.recycle() }
    }
    private fun await(timeout: Long = 30000, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < end) { if (condition()) return; Thread.sleep(150) }
        screenshot("failure"); fail("Timed out: " + nodes().mapNotNull { it.text ?: it.contentDescription }.joinToString(" | "))
    }
    private fun click(text: String) {
        await {
            var n = find(text)
            if (n == null) nodes().filter { it.isScrollable && it.isVisibleToUser }.maxByOrNull { r -> android.graphics.Rect().also(r::getBoundsInScreen).height() }
                ?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            while (n != null && !n.isClickable) n = n.parent
            n?.takeIf { it.isEnabled }?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        }; Thread.sleep(500)
    }
    private fun enabled(label: String): Boolean {
        var n = find(label); while (n != null && !n.isClickable) n = n.parent
        return n?.isEnabled == true
    }
    private fun setText(old: String, value: String) {
        await { pickEditable(nodes(), old) != null }
        assertTrue(pickEditable(nodes(), old)!!.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }))
        Thread.sleep(1_300) // past the pause that ends a step
    }
    private fun open() {
        app.settings.setAgendaRange(AgendaRange.ALL); app.settings.setAgendaTypes(AgendaType.entries.toSet()); app.settings.lastViewCalendar = false
        ins.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
    }
    private fun data() = runBlocking { repo.snapshot() }

    @Before fun fresh() { TaskDraftStore(context).clear("new"); EditorDraftStore(context).clear() }
    @After fun done() {
        app.settings.setStartScreen(StartScreen.LAST)
        runBlocking { data().tasks.filter { it.title.startsWith("QA wish") }.forEach { repo.deleteTask(it.id) } }
        TaskDraftStore(context).clear("new")
    }

    @Test fun plannerOpensOnTheChosenPage() {
        app.settings.setStartScreen(StartScreen.NOTES)
        open(); await { find("NOTES") != null && find("Search notes") != null }
        // Back from Notes reaches Agenda underneath.
        ins.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        await { find("AGENDA") != null }
        app.settings.setStartScreen(StartScreen.CALENDAR)
        open(); await { find("CALENDAR") != null }
        app.settings.setStartScreen(StartScreen.AGENDA)
        app.settings.lastViewCalendar = true
        ins.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        await { find("AGENDA") != null }
    }

    @Test fun aTaskShowsPreviewsAndUndoesTyping() = runBlocking {
        // A photo and a one-page PDF in the attachment store.
        val store = app.attachmentStore
        val photo = store.newPhotoFile()
        Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(200, 60, 40)) }
            .also { b -> photo.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 90, it) }; b.recycle() }
        val pdf = store.writableFileFor("qa-wish.pdf")
        android.graphics.pdf.PdfDocument().apply {
            val page = startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(300, 400, 1).create())
            page.canvas.drawColor(Color.WHITE)
            page.canvas.drawText("QA page one", 40f, 60f, Paint().apply { textSize = 28f; color = Color.BLACK })
            finishPage(page)
            pdf.outputStream().use { writeTo(it) }; close()
        }
        repo.saveTask(PlannerTask(title = "QA wish task", dueDate = LocalDate.now().plusDays(1), attachments = listOf(
            Attachment(itemId = 0, name = "Photo.jpg", fileName = photo.name, mimeType = "image/jpeg"),
            Attachment(itemId = 0, name = "Letter.pdf", fileName = pdf.name, mimeType = "application/pdf"))))
        assertNotNull(store.thumbnail(pdf.name, 160))
        open(); await { find("AGENDA") != null }
        click("QA wish task"); await { find("Edit task") != null }
        // Undo and Redo of typing.
        assertFalse(enabled("Undo"))
        setText("QA wish task", "QA wish task renamed")
        setText("QA wish task renamed", "QA wish task renamed again")
        click("Undo"); await { pickEditable(nodes(), "QA wish task renamed") != null }
        click("Undo"); await { pickEditable(nodes(), "QA wish task") != null }
        assertFalse(enabled("Undo"))
        click("Redo"); await { pickEditable(nodes(), "QA wish task renamed") != null }
        screenshot("task-undo")
        // Both rows, each with its preview (checked on the screenshot) and its remove button.
        await {
            (find("Remove Photo.jpg") != null && find("Remove Letter.pdf") != null) || nodes().filter { it.isScrollable && it.isVisibleToUser }
                .maxByOrNull { r -> android.graphics.Rect().also(r::getBoundsInScreen).height() }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD).let { false }
        }
        screenshot("task-previews")
        click("Close"); await { find("Save changes?") != null }; click("Discard")
    }
}
