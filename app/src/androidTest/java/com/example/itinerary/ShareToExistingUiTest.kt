package com.example.itinerary

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.FileProvider
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
 * A share added to an item that already exists ("Add to existing…" in Add to Planner): the photo goes on the task, note or
 * event picked, the text that came with it after its notes, the share's staging goes and the files stay; the item opens.
 */
class ShareToExistingUiTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = ins.targetContext
    private val app get() = context.applicationContext as ItineraryApp
    private var activity: MainActivity? = null

    private fun nodes(): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(n: AccessibilityNodeInfo) { result += n; for (i in 0 until n.childCount) n.getChild(i)?.let(::visit) }
        ins.uiAutomation.freshRoot?.let(::visit); return result
    }
    private fun find(text: String) = nodes().firstOrNull { it.isVisibleToUser && (it.text?.toString() == text || it.contentDescription?.toString() == text) }
    private fun screenshot(name: String) {
        val dir = File(context.cacheDir, "qa-share-existing").apply { mkdirs() }
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
            while (n != null && !n.isClickable) n = n.parent
            n?.takeIf { it.isEnabled }?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        }; Thread.sleep(400)
    }
    private fun data() = runBlocking { app.repository.snapshot() }
    private val staging get() = File(context.filesDir, "shared-files").list().orEmpty().toSet()
    private fun stored(fileName: String) = File(context.filesDir, "attachments/$fileName").exists()

    private fun photo(): Uri {
        val file = File(context.cacheDir, "manual-scans/qa-share/QA photo.png").apply { parentFile!!.mkdirs() }
        val bitmap = Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.GREEN) }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }
    private fun share(caption: String? = null) {
        val intent = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, photo())
            .setClass(context, MainActivity::class.java).setType("image/png")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        caption?.let { intent.putExtra(Intent.EXTRA_TEXT, it) }
        activity = ins.startActivitySync(intent) as MainActivity
        await { find("Add to Planner") != null }
    }

    @Before fun fresh() {
        TaskDraftStore(context).clear("new"); EditorDraftStore(context).clear(); NoteDraftStore(context).clearAll()
        File(context.filesDir, "shared-files").deleteRecursively()
    }
    @After fun done() {
        activity?.let { a -> ins.runOnMainSync { a.finish() } }
        TaskDraftStore(context).clear("new"); EditorDraftStore(context).clear(); NoteDraftStore(context).clearAll()
        File(context.cacheDir, "manual-scans/qa-share").deleteRecursively()
    }

    @Test fun aPhotoWithTextGoesOnAnExistingTaskWhichOpens() {
        runBlocking { app.repository.saveTask(PlannerTask(title = "QA receipts", notes = "Keep for tax")) }
        share(caption = "QA from the hardware shop")
        click("Add to existing…")
        await { find("Add to existing") != null && find("QA receipts") != null }
        screenshot("picker")
        click("QA receipts")
        await { data().tasks.single { it.title == "QA receipts" }.attachments.isNotEmpty() }
        val task = data().tasks.single { it.title == "QA receipts" }
        assertEquals(listOf("QA photo.png"), task.attachments.map { it.name })
        assertEquals("Keep for tax\n\nQA from the hardware shop", task.notes)
        // The task's editor opens on it (as a widget task does), and the share's staging is gone while the file stays.
        await { find("Task title") != null || find("Edit task") != null }
        await { staging.isEmpty() }
        assertTrue(stored(task.attachments.single().fileName))
        screenshot("task-open")
    }

    @Test fun searchFindsANoteAndThePhotoGoesOnIt() {
        val note = runBlocking { app.repository.saveNote(PlannerNote(title = "QA groceries", content = "milk"), create = true) }
        runBlocking { app.repository.saveTask(PlannerTask(title = "QA other")) }
        share()
        click("Add to existing…")
        await { find("Search") != null }
        val search = nodes().first { it.isEditable }
        search.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "groceries") })
        await { find("QA groceries") != null && find("QA other") == null }
        click("QA groceries")
        await { runBlocking { app.repository.note(note.id) }!!.attachments.isNotEmpty() }
        val saved = runBlocking { app.repository.note(note.id) }!!
        assertEquals(listOf("QA photo.png"), saved.attachments.map { it.name })
        assertEquals("No text came with it: the note's own stays", "milk", saved.content)
        await { staging.isEmpty() }
        assertTrue(stored(saved.attachments.single().fileName))
    }

    @Test fun aPhotoGoesOnAnExistingEventAndBackLeavesTheShareOpen() {
        runBlocking { app.repository.saveItem(ItineraryItem(tripId = 0, date = LocalDate.now().plusDays(2), startTime = null, title = "QA dentist")) }
        val event = data().items.single { it.title == "QA dentist" }
        share()
        click("Add to existing…")
        await { find("QA dentist") != null }
        // Back returns to Add to Planner, with nothing added.
        click("Back")
        await { find("Add to Planner") != null }
        assertTrue(data().attachments.none { it.itemId == event.id })
        click("Add to existing…")
        click("QA dentist")
        await { data().attachments.any { it.itemId == event.id } }
        assertEquals(listOf("QA photo.png"), data().attachments.filter { it.itemId == event.id }.map { it.name })
        await { find("Add to Planner") == null && staging.isEmpty() }
        assertTrue(data().attachments.filter { it.itemId == event.id }.all { stored(it.fileName) })
    }
}
