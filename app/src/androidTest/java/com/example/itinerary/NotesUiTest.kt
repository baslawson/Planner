package com.example.itinerary

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.example.itinerary.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Notes from the ⋮ menu: write one with the Markdown toolbar, tick its checklist in Preview, file it in a notebook and a
 *  colour, then pin, archive, delete (Undo) it from its card, and Close with changes asks first. */
@Suppress("DEPRECATION")
class NotesUiTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = ins.targetContext
    private val app get() = context.applicationContext as ItineraryApp
    private fun notes() = runBlocking { app.repository.snapshot().notes }
    private fun nodes(): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(n: AccessibilityNodeInfo) { result += n; for (i in 0 until n.childCount) n.getChild(i)?.let(::visit) }
        ins.uiAutomation.freshRoot?.let(::visit); return result
    }
    private fun find(text: String) = nodes().firstOrNull { it.isVisibleToUser && (it.text?.toString() == text || it.contentDescription?.toString() == text) }
    private fun screenshot(name: String) {
        val dir = File(context.cacheDir, "qa-notes").apply { mkdirs() }
        ins.uiAutomation.takeScreenshot()?.let { b -> File(dir, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }; b.recycle() }
    }
    private fun await(timeout: Long = 20000, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < end) { if (condition()) return; Thread.sleep(150) }
        screenshot("failure"); fail("Timed out: " + nodes().filter { it.isVisibleToUser }.mapNotNull { it.text ?: it.contentDescription }.joinToString(" | "))
    }
    // The page itself (the tallest scrolling thing), not a text box that scrolls inside it.
    private fun page() = nodes().filter { it.isScrollable && it.isVisibleToUser && !it.isEditable }
        .maxByOrNull { android.graphics.Rect().also(it::getBoundsInScreen).height() }
    // Scrolls down the page until [text] shows, then back up from the top if it ran out.
    private fun reveal(text: String) {
        var forward = true
        await {
            find(text) != null || run {
                val p = page()
                if (p != null && !p.performAction(if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) forward = !forward
                Thread.sleep(250); false
            }
        }
    }
    private fun click(text: String) {
        reveal(text)
        await { var n = find(text); while (n != null && !n.isClickable) n = n.parent; n?.takeIf { it.isEnabled }?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true }
        Thread.sleep(400)
    }
    // The [index]th text field on screen (editor: 0 Title, 1 Note in Edit, then Notebook).
    private fun type(index: Int, value: String) {
        await { nodes().filter { it.isVisibleToUser && it.isEditable }.size > index }
        val field = nodes().filter { it.isVisibleToUser && it.isEditable }[index]
        assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }))
        Thread.sleep(300)
    }
    private fun openNotes() {
        app.settings.lastViewCalendar = false
        ins.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        await { find("AGENDA") != null }
        click("More options"); click("Notes")
        await { find("Search notes") != null }
    }

    @Test fun writeTickFileAndManageANote() {
        openNotes()
        await { find("No notes yet. Tap New note to write one.") != null }
        click("New note"); await { find("New note") != null && find("Title") != null }
        type(0, "QA groceries")
        type(1, "milk")
        // The cursor is after "milk": the checklist button makes that line a box.
        click("Checklist")
        type(1, "- [ ] milk\n- [ ] **bread**")
        type(2, "Home")
        click("Teal")
        screenshot("editor-edit")
        click("Save")
        await { find("Saved") != null && notes().singleOrNull()?.title == "QA groceries" }
        val note = notes().single()
        assertEquals("Home", note.notebook); assertEquals(Notes.colors[1], note.color)
        assertEquals("- [ ] milk\n- [ ] **bread**", note.content)

        // Preview: bold shows as text, ticking a line is a change to save.
        click("Preview"); await { find("bread") != null }
        screenshot("editor-preview")
        click("milk")
        await { find("Save") != null }
        click("Save"); await { notes().single().content == "- [x] milk\n- [ ] **bread**" }
        click("Close")

        // The card: title, checklist progress, notebook; the notebook gets its own chip.
        await { find("QA groceries") != null && find("☑ 1 of 2 done") != null && nodes().count { it.text?.toString() == "Home" } >= 2 }
        screenshot("notes-grid")

        // ⋮ Pin, Archive (out of All, into Archive), Unarchive.
        click("Actions for QA groceries"); click("Pin to the top")
        await { notes().single().pinned && find("Pinned") != null }
        click("Actions for QA groceries"); click("Archive")
        await { notes().single().archived && find("QA groceries") == null }
        click("Archive"); await { find("QA groceries") != null }
        click("Actions for QA groceries"); click("Unarchive")
        await { !notes().single().archived && find("No archived notes.") != null }
        click("All notes"); await { find("QA groceries") != null }

        // Close with a change asks; Discard leaves the note as it was.
        click("QA groceries"); await { find("Edit note") != null }
        type(0, "QA changed title")
        click("Close"); await { find("Save changes?") != null }
        click("Discard")
        await { find("QA groceries") != null }
        assertEquals("QA groceries", notes().single().title)

        // Delete from the card, with Undo.
        click("Actions for QA groceries"); click("Delete note")
        await { notes().isEmpty() && find("Note deleted") != null }
        click("Undo")
        await { notes().singleOrNull()?.title == "QA groceries" && find("QA groceries") != null }
    }

    @Test fun tagsFilterAndAttachmentsList() {
        val store = app.attachmentStore
        store.fileFor("qa-note-receipt.txt").apply { parentFile?.mkdirs(); writeText("receipt") }
        runBlocking {
            app.repository.saveNote(PlannerNote(title = "QA with file", attachments = listOf(
                Attachment(itemId = 0, name = "Receipt.txt", fileName = "qa-note-receipt.txt", mimeType = "text/plain"))), create = true)
            app.repository.saveNote(PlannerNote(title = "QA other"), create = true)
        }
        openNotes()
        await { find("QA with file") != null && find("1 attachment") != null }
        // A tag typed in the editor: Title 0, Note 1, Notebook 2, Add a tag 3.
        click("New note"); await { find("Add a tag") != null }
        type(0, "QA errands")
        type(3, "#errands")
        click("Add tag")
        await { find("#errands") != null && find("Remove tag errands") != null }
        click("Save"); await { notes().any { it.title == "QA errands" && it.tags == listOf("errands") } }
        screenshot("editor-tags")
        click("Close")
        // Its chip filters the page to it.
        await { nodes().count { it.text?.toString() == "#errands" } >= 2 }
        click("#errands")
        await { find("QA errands") != null && find("QA other") == null && find("QA with file") == null }
        screenshot("tag-filter")
        click("All notes")
        // The attachment is listed in the note and can be taken off.
        click("QA with file"); reveal("Receipt.txt")
        screenshot("editor-attachment")
        click("Remove"); await { find("Receipt.txt") == null }
        click("Save"); await { notes().single { it.title == "QA with file" }.attachments.isEmpty() }
        click("Close"); await { find("QA with file") != null && find("1 attachment") == null }
        // Nothing uses the file now, so it has gone.
        await { !store.fileFor("qa-note-receipt.txt").exists() }
    }

    @Test fun gridLooksInTheDarkTheme() {
        runBlocking {
            listOf(
                PlannerNote(title = "Wifi", content = "Network: **home-5G**", pinned = true, color = Notes.colors[0]),
                PlannerNote(title = "Packing", content = "- [x] passport\n- [ ] charger\n- [ ] adaptor", notebook = "Travel"),
                PlannerNote(content = "# Ideas\nPaint the fence\nNew shelves for the garage", color = Notes.colors[3]),
                PlannerNote(title = "Recipe", content = "1. Boil water\n2. Add pasta\n> Salt generously", notebook = "Home", color = Notes.colors[6]),
            ).forEach { app.repository.saveNote(it, create = true) }
        }
        app.settings.setThemeMode(ThemeMode.DARK)
        try {
            openNotes()
            await { find("Wifi") != null && find("Packing") != null && find("Ideas") != null && find("Recipe") != null }
            screenshot("grid-dark")
            click("Packing"); await { find("Edit note") != null && find("charger") != null }
            screenshot("preview-dark")
        } finally { app.settings.setThemeMode(ThemeMode.SYSTEM) }
    }
}
