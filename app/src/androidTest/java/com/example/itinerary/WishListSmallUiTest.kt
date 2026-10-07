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

/**
 * Wish list (4 Oct 2026): the NOTES heading as Agenda's, a note reopening in the view it was left in (Settings → Notes
 * open in), and a long notebook list in the Notes page's Show menu scrolling to its end.
 * Run only with an external backup/restore harness for the shared emulator.
 */
class WishListSmallUiTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = ins.targetContext
    private val app get() = context.applicationContext as ItineraryApp

    private fun nodes(): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(n: AccessibilityNodeInfo) { result += n; for (i in 0 until n.childCount) n.getChild(i)?.let(::visit) }
        ins.uiAutomation.windows.mapNotNull { it.root }.ifEmpty { listOfNotNull(ins.uiAutomation.rootInActiveWindow) }.forEach(::visit)
        return result
    }
    private fun find(text: String) = nodes().firstOrNull { it.isVisibleToUser && (it.text?.toString() == text || it.contentDescription?.toString() == text) }
    private fun has(prefix: String) = nodes().any { it.isVisibleToUser && it.text?.toString()?.startsWith(prefix) == true }
    private fun screenshot(name: String) {
        val dir = File(context.cacheDir, "qa-wish-small").apply { mkdirs() }
        ins.uiAutomation.takeScreenshot()?.let { b -> File(dir, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }; b.recycle() }
    }
    private fun await(timeout: Long = 30000, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < end) { if (condition()) return; Thread.sleep(150) }
        screenshot("failure"); fail("Timed out: " + nodes().mapNotNull { it.text }.joinToString(" | "))
    }
    private fun click(text: String) {
        await {
            var n = find(text)
            while (n != null && !n.isClickable) n = n.parent
            n?.takeIf { it.isEnabled }?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        }; Thread.sleep(400)
    }
    private fun openNotes() {
        app.settings.noteFilter = "all"; app.settings.setNoteSort(NoteSort.MY_ORDER); app.settings.setNotesAsList(false)
        app.settings.lastViewCalendar = false
        ins.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        await { find("AGENDA") != null }
        click("More options"); click("Notes")
        await { find("Search notes") != null }
    }
    // In Edit the editor shows its Markdown hint; Preview doesn't.
    private fun inEdit() = find("Preview") != null // in Edit the eye offers Preview
    private val note = PlannerNote(title = "QA view memory", content = "Some **words**")

    @Before fun fresh() { NoteDraftStore(context).clearAll(); app.settings.setNoteOpenView(NoteOpenView.LAST) }
    @After fun done() {
        app.settings.setNoteOpenView(NoteOpenView.LAST)
        runBlocking { app.repository.allNotes().filter { it.title.startsWith("QA ") }.map { it.id }.let { if (it.isNotEmpty()) app.repository.deleteNotes(it) } }
        NoteDraftStore(context).clearAll()
    }

    @Test fun notesHeadingAndANoteReopensTheWayItWasLeft() = runBlocking {
        app.repository.saveNote(note, create = true)
        openNotes()
        assertNotNull(find("NOTES"))
        // Never left either way: it opens in Preview, as before.
        click("QA view memory"); await { find("Edit") != null } // in Preview the eye offers Edit
        assertFalse(inEdit())
        click("Edit"); await { inEdit() }
        click("Close"); await { find("Search notes") != null }
        // Left in Edit, it reopens in Edit.
        click("QA view memory"); await { inEdit() }
        screenshot("reopened-in-edit")
        click("Preview"); await { !inEdit() }
        click("Close"); await { find("Search notes") != null }
        click("QA view memory"); await { find("Edit") != null }; Thread.sleep(500)
        assertFalse(inEdit())
        click("Close"); await { find("Search notes") != null }
        // Settings → Notes open in Edit: every saved note opens in Edit.
        app.settings.setNoteOpenView(NoteOpenView.EDIT)
        click("QA view memory"); await { inEdit() }
        click("Close"); await { find("Search notes") != null }
    }

    @Test fun aLongNotebookListScrollsToItsEnd() = runBlocking {
        (1..30).forEach { app.repository.saveNote(PlannerNote(title = "QA nb $it", content = "x", notebook = "QA Book %02d".format(it)), create = true) }
        openNotes()
        click("Show"); click("Choose what to show")
        await { find("QA Book 01") != null }
        assertNull(find("QA Book 30"))
        screenshot("show-list-top")
        // The list scrolls, by hand or by accessibility, to its last notebook.
        fun inList(n: AccessibilityNodeInfo): Boolean = n.text?.toString()?.startsWith("QA Book") == true ||
            (0 until n.childCount).any { i -> n.getChild(i)?.let(::inList) == true }
        await {
            find("QA Book 30") != null ||
                nodes().firstOrNull { it.isScrollable && it.isVisibleToUser && inList(it) }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD).let { false }
        }
        screenshot("show-list-end")
        click("QA Book 30")
        await { find("QA nb 30") != null && find("QA nb 29") == null }
    }
}
