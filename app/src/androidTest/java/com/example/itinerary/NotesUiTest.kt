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
        // The page, or a recovered draft's editor opened straight over it.
        await { find("Search notes") != null || find("Recovered unsaved changes. Save them, or Close and Discard.") != null }
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
        // A tag typed in the editor; the tag box is below the note box, so scroll to it and find it by its own text.
        click("New note"); await { find("New note") != null && find("Title") != null }
        type(0, "QA errands")
        reveal("Add a tag")
        val tagBox = nodes().first { n -> n.isEditable && (0 until n.childCount).any { n.getChild(it)?.text?.toString() == "Add a tag" } }
        assertTrue(tagBox.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "#errands") }))
        Thread.sleep(300)
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

    @Test fun reminderChipSetsAndShowsOnTheCard() {
        openNotes()
        click("New note"); await { find("Title") != null }
        type(0, "QA remind me")
        click("Tomorrow 09:00")
        await { nodes().any { it.contentDescription?.toString()?.startsWith("Remove reminder: ") == true } }
        screenshot("editor-reminder")
        click("Save")
        val tomorrowNine = java.time.LocalDate.now().plusDays(1).atTime(9, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        await { notes().singleOrNull()?.reminderAt == tomorrowNine }
        click("Close")
        await { nodes().any { it.contentDescription?.toString()?.startsWith("Reminder, ") == true } }
        screenshot("card-reminder")
    }

    @Test fun syncSwitchAndCloud() {
        val store = com.example.itinerary.data.NextcloudAccountStore(context)
        // Without a Nextcloud login: the dialog says where to sign in, and the switch waits.
        store.clear()
        openNotes()
        click("Notes sync: off")
        await { find("Sync notes with Nextcloud") != null && find("Sign in to Nextcloud first, in Settings → Nextcloud.") != null }
        screenshot("sync-signed-out")
        click("Close")

        val certificate = okhttp3.tls.HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val server = okhttp3.mockwebserver.MockWebServer()
        val fake = FakeNotes("qa", "qa-test-password")
        fake.add("From Nextcloud", "From Nextcloud\nWritten on the web", category = "Web")
        server.dispatcher = fake
        server.useHttps(okhttp3.tls.HandshakeCertificates.Builder().heldCertificate(certificate).build().sslSocketFactory(), false)
        server.start()
        val trusted = okhttp3.tls.HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val original = app.noteSync.api
        try {
            app.noteSync.api = com.example.itinerary.data.NotesApi(okhttp3.OkHttpClient.Builder()
                .sslSocketFactory(trusted.sslSocketFactory(), trusted.trustManager).build())
            store.save(com.example.itinerary.data.NextcloudAccount.create(server.url("/").toString(), "qa", "qa-test-password"))
            runBlocking { app.repository.saveNote(PlannerNote(title = "From the phone", content = "Typed here"), create = true) }
            openNotes()
            click("Notes sync: off"); await { find("Sync notes") != null && find("Sign in to Nextcloud first, in Settings → Nextcloud.") == null }
            click("Sync notes")
            await { nodes().any { it.text?.toString()?.startsWith("Synced ") == true } }
            screenshot("sync-on")
            click("Close")
            // The page's cloud (under the dialog until now) says so too.
            await { find("Notes sync: up to date") != null }
            // Both ways: Nextcloud's note here (in its notebook), the phone's note there.
            await { find("From Nextcloud") != null && find("Web") != null }
            assertTrue(fake.notes.values.any { it.title == "From the phone" && it.content == "Typed here" })
            screenshot("synced-grid")
        } finally {
            runBlocking { app.noteSync.setEnabled(false) }
            app.noteSync.api = original
            store.clear(); server.shutdown()
        }
    }

    // Bug hunt 2 Oct: U-1 (Save overwrote a change made elsewhere), U-2 (the editor vanished with its note), Q-1/N-9
    // (no draft, not counted as an open editor).
    @Test fun changedElsewhereMergesOrAsks() {
        val note = runBlocking { app.repository.saveNote(PlannerNote(title = "QA shared", content = "first"), create = true) }
        openNotes()
        click("QA shared"); await { find("Edit note") != null }
        assertEquals(1, com.example.itinerary.data.NoteDraftStore.openEditors.value)
        click("Edit"); type(1, "mine")
        // Elsewhere, something else changes: Save keeps both.
        runBlocking { app.repository.updateNote(note.id) { it.copy(notebook = "Synced") } }
        click("Save")
        await { find("Merged with a change made elsewhere.") != null }
        await { notes().single().let { it.content == "mine" && it.notebook == "Synced" } }
        // Elsewhere, the same text changes: Save asks.
        type(1, "mine again")
        runBlocking { app.repository.updateNote(note.id) { it.copy(content = "theirs") } }
        click("Save")
        await { find("Changed elsewhere") != null }
        screenshot("changed-elsewhere")
        click("Keep my version")
        await { notes().single().content == "mine again" }
        click("Close")
        await { com.example.itinerary.data.NoteDraftStore.openEditors.value == 0 }
    }

    @Test fun deletedElsewhereKeepsTheEditor() {
        val note = runBlocking { app.repository.saveNote(PlannerNote(title = "QA vanishing", content = "keep me"), create = true) }
        openNotes()
        click("QA vanishing"); await { find("Edit note") != null }
        click("Edit"); type(1, "keep me, edited")
        runBlocking { app.repository.deleteNote(note.id) }
        await { find("This note was deleted elsewhere. Save keeps your version as a new note.") != null }
        screenshot("deleted-elsewhere")
        click("Save")
        await { notes().singleOrNull()?.content == "keep me, edited" }
    }

    @Test fun aDraftReopensTheEditor() {
        val draftNote = PlannerNote(id = "qa-draft-note", title = "QA recovered", content = "typed before Android closed Planner")
        com.example.itinerary.data.NoteDraftStore(context).write(com.example.itinerary.data.NoteDraftStore.Draft(draftNote, creating = true, base = null, pendingPhoto = null))
        try {
            openNotes()
            await { find("New note") != null && find("Recovered unsaved changes. Save them, or Close and Discard.") != null }
            screenshot("draft-recovered")
            click("Save")
            await { notes().singleOrNull()?.content == "typed before Android closed Planner" }
            await { com.example.itinerary.data.NoteDraftStore(context).read() == null }
        } finally { com.example.itinerary.data.NoteDraftStore(context).clear() }
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

    private fun shell(command: String) = ins.uiAutomation.executeShellCommand(command).use { pfd ->
        android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().readText() }
    private fun noteText() = nodes().filter { it.isVisibleToUser && it.isEditable }.getOrNull(1)?.text?.toString()
    private fun awaitNote(expected: String) {
        val end = SystemClock.uptimeMillis() + 10000
        while (SystemClock.uptimeMillis() < end) { if (noteText() == expected) return; Thread.sleep(150) }
        screenshot("failure"); fail("Note text " + noteText()?.replace("\n", "\\n") + ", expected " + expected.replace("\n", "\\n"))
    }

    // Real key presses, not SET_TEXT: Enter on a checklist line starts the next box, Enter on an empty box ends the list.
    @Test fun enterCarriesAChecklistOn() {
        openNotes()
        click("New note"); await { find("New note") != null && find("Title") != null }
        type(0, "QA list")
        type(1, "- [ ] test")
        val field = nodes().filter { it.isVisibleToUser && it.isEditable }[1]
        field.performAction(AccessibilityNodeInfo.ACTION_CLICK); Thread.sleep(400)
        field.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 10); putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, 10) })
        Thread.sleep(300)
        shell("input keyevent KEYCODE_ENTER"); awaitNote("- [ ] test\n- [ ] ")
        shell("input text eggs"); awaitNote("- [ ] test\n- [ ] eggs")
        screenshot("checklist-enter")
        shell("input keyevent KEYCODE_ENTER"); awaitNote("- [ ] test\n- [ ] eggs\n- [ ] ")
        shell("input keyevent KEYCODE_ENTER"); awaitNote("- [ ] test\n- [ ] eggs\n") // the empty box ends the list
        shell("input text done")
        click("Save")
        await { notes().singleOrNull()?.content == "- [ ] test\n- [ ] eggs\ndone" }
    }

    // "Continue lists on Enter" off: Enter is a plain new line again; the choice is remembered.
    @Test fun continueListsSwitchTurnsItOff() {
        try {
            openNotes()
            click("New note"); await { find("New note") != null && find("Title") != null }
            screenshot("toolbar") // the Checklist button is a ticked box
            type(0, "QA plain")
            type(1, "- [ ] a")
            assertTrue(app.settings.continueLists.value)
            click("Continue lists on Enter"); await { !app.settings.continueLists.value }
            screenshot("switch-off")
            val field = nodes().filter { it.isVisibleToUser && it.isEditable }[1]
            field.performAction(AccessibilityNodeInfo.ACTION_CLICK); Thread.sleep(400)
            field.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 7); putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, 7) })
            Thread.sleep(300)
            shell("input keyevent KEYCODE_ENTER"); awaitNote("- [ ] a\n")
            click("Continue lists on Enter"); await { app.settings.continueLists.value }
        } finally { app.settings.setContinueLists(true) }
    }
}
