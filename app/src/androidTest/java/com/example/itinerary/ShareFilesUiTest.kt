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

/**
 * Files and photos shared to Planner: the "Add to Planner" choices name them, and the event, task or note made from them
 * has them attached. A share closed without saving leaves no files behind.
 */
class ShareFilesUiTest {
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
        val dir = File(context.cacheDir, "qa-share-files").apply { mkdirs() }
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
    private val stored get() = File(context.filesDir, "attachments").list().orEmpty().toSet()
    private val staging get() = File(context.filesDir, "shared-files").list().orEmpty().toSet()

    // A file another app would share: here one of Planner's own, served through its FileProvider as a content: URI.
    private fun source(name: String, write: (File) -> Unit): Uri {
        val file = File(context.cacheDir, "manual-scans/qa-share/$name").apply { parentFile!!.mkdirs(); write(this) }
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }
    private fun photo() = source("QA photo.png") { f ->
        val bitmap = Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLUE) }
        f.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }
    private fun document() = source("QA receipt.txt") { it.writeText("Total 12.50") }

    private fun share(uris: List<Uri>, type: String, caption: String? = null) {
        val intent = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.single())
            else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        intent.setClass(context, MainActivity::class.java).setType(type)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        caption?.let { intent.putExtra(Intent.EXTRA_TEXT, it) }
        activity = ins.startActivitySync(intent) as MainActivity
        await { find("Add to Planner") != null }
    }

    @Before fun fresh() {
        TaskDraftStore(context).clear("new"); EditorDraftStore(context).clear(); NoteDraftStore(context).clearAll()
        // The runner's clean start empties the database, not files: a share an earlier failed run left must not count here.
        File(context.filesDir, "shared-files").deleteRecursively()
    }
    @After fun done() {
        activity?.let { a -> ins.runOnMainSync { a.finish() } }
        TaskDraftStore(context).clear("new"); EditorDraftStore(context).clear(); NoteDraftStore(context).clearAll()
        File(context.cacheDir, "manual-scans/qa-share").deleteRecursively()
    }

    @Test fun photoAndDocumentBecomeAnEventWithBoth() {
        share(listOf(photo(), document()), "*/*")
        assertNotNull(find("2 files: QA photo.png, QA receipt.txt"))
        assertNotNull(find("Add bill")); assertNotNull(find("Add note"))
        screenshot("files-popup")
        click("Add event")
        await { find("New event") != null }
        screenshot("files-event")
        click("Save")
        await { data().items.any { it.title == "QA photo" } }
        val event = data().items.single { it.title == "QA photo" }
        val files = data().attachments.filter { it.itemId == event.id }
        assertEquals(setOf("QA photo.png", "QA receipt.txt"), files.map { it.name }.toSet())
        assertTrue(files.all { File(context.filesDir, "attachments/${it.fileName}").exists() })
        click("Close")
        await { find("New event") == null && find("Edit event") == null }
        // The staging file goes once the share is closed; the saved event's files stay.
        await { staging.isEmpty() }
        assertTrue(files.all { File(context.filesDir, "attachments/${it.fileName}").exists() })
    }

    @Test fun photoWithACaptionBecomesATask() {
        share(listOf(photo()), "image/png", caption = "QA fix the shelf")
        assertNotNull(find("1 file: QA photo.png"))
        click("Add task")
        await { find("Task title") != null }
        click("Save")
        await { data().tasks.any { it.title == "QA fix the shelf" } }
        val task = data().tasks.single { it.title == "QA fix the shelf" }
        assertEquals(listOf("QA photo.png"), task.attachments.map { it.name })
        assertTrue(File(context.filesDir, "attachments/${task.attachments.single().fileName}").exists())
        // Saved, the editor stays open on the task; the share ends when it closes.
        click("Close")
        await { find("Task title") == null }
        await { staging.isEmpty() }
        assertTrue(File(context.filesDir, "attachments/${task.attachments.single().fileName}").exists())
    }

    @Test fun documentBecomesANote() {
        share(listOf(document()), "text/plain")
        click("Add note")
        await { find("Save") != null }
        screenshot("files-note")
        click("Save")
        await { data().notes.any { it.title == "QA receipt" } }
        val note = data().notes.single { it.title == "QA receipt" }
        assertEquals(listOf("QA receipt.txt"), note.attachments.map { it.name })
        assertTrue(File(context.filesDir, "attachments/${note.attachments.single().fileName}").exists())
        // H17-A6: handed to a note, the staging file waits for the week-old sweep, which leaves a saved note's files alone.
        // R18-D2: and its marker that a window took it, so it isn't offered again after a restart.
        assertEquals(staging.toString(), 1, staging.count { it.endsWith(".json") })
        assertEquals(staging.toString(), setOf(".json", ".taken"), staging.map { "." + it.substringAfterLast(".") }.toSet())
    }

    // A second share of files while one is open waits its turn instead of replacing it (whose files would be left behind).
    @Test fun aSecondShareWaitsForTheFirst() {
        val before = stored
        share(listOf(photo()), "image/png")
        assertNotNull(find("1 file: QA photo.png"))
        context.startActivity(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, document()).setType("text/plain")
            .setClass(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_GRANT_READ_URI_PERMISSION))
        await { stored.size == before.size + 2 }
        Thread.sleep(1500)
        assertNotNull(find("1 file: QA photo.png")) // still the first
        click("Cancel")
        await { find("1 file: QA receipt.txt") != null }
        click("Cancel")
        await { find("Add to Planner") == null }
        await { stored == before && staging.isEmpty() }
    }

    @Test fun cancelOrDiscardLeavesNoFilesBehind() {
        val before = stored
        share(listOf(photo(), document()), "*/*")
        await { stored.size == before.size + 2 }
        click("Cancel")
        await { find("Add to Planner") == null }
        await { stored == before && staging.isEmpty() }
        // Discarded from the event editor too.
        share(listOf(photo()), "image/png")
        click("Add event")
        await { find("New event") != null }
        click("Close")
        await { find("Save changes?") != null }
        click("Discard")
        await { find("New event") == null }
        await { stored == before && staging.isEmpty() }
        assertFalse(data().items.any { it.title == "QA photo" })
    }
}
