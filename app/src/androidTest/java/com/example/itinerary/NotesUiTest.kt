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
    // The nodes of the popup windows (the Show list, the suggestion lists), for [block]. Seeing every window is switched
    // on only while [block] runs: left on, it changes which window the other helpers read.
    private fun <T> inPopups(block: (List<AccessibilityNodeInfo>) -> T): T {
        val info = ins.uiAutomation.serviceInfo
        val before = info.flags
        info.flags = before or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        ins.uiAutomation.serviceInfo = info
        try {
            if (android.os.Build.VERSION.SDK_INT >= 34) ins.uiAutomation.clearCache()
            val result = mutableListOf<AccessibilityNodeInfo>()
            fun visit(n: AccessibilityNodeInfo) { result += n; for (i in 0 until n.childCount) n.getChild(i)?.let(::visit) }
            // The popups: every app window but the largest, which is the screen itself.
            fun area(w: android.view.accessibility.AccessibilityWindowInfo) = android.graphics.Rect().also(w::getBoundsInScreen).let { it.width() * it.height() }
            val apps = ins.uiAutomation.windows.filter { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION }
            apps.sortedByDescending(::area).drop(1).forEach { w -> w.root?.let(::visit) }
            return block(result)
        } finally { info.flags = before; ins.uiAutomation.serviceInfo = info }
    }
    private fun listShows(text: String) = inPopups { all -> all.any { it.isVisibleToUser && it.text?.toString() == text } }
    // Taps [text] in an open popup list, once (the list closes as it is tapped, so the tap may report failure), then
    // waits for the list to close.
    private fun pick(text: String) {
        await { listShows(text) }
        inPopups { all ->
            var n = all.first { it.isVisibleToUser && it.text?.toString() == text }
            while (!n.isClickable) n = n.parent
            n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        await { !listShows(text) }
        Thread.sleep(300)
    }
    // Chooses [choice] in the Notes page's Show list.
    private fun show(choice: String) { click("Show"); pick(choice) }
    // The editor's box labelled [label], focused, with [value] typed into it.
    private fun typeInto(label: String, value: String): AccessibilityNodeInfo {
        reveal(label)
        // Found once: when its list opens, the list becomes the active window and the box is no longer in nodes().
        val box = nodes().first { n -> n.isEditable && (0 until n.childCount).any { n.getChild(it)?.text?.toString() == label } }
        box.performAction(AccessibilityNodeInfo.ACTION_FOCUS); box.performAction(AccessibilityNodeInfo.ACTION_CLICK); Thread.sleep(300)
        // In long runs the box is sometimes still settling (its list opening) and refuses the first try: try again.
        val typed = (1..5).any { attempt ->
            if (attempt > 1) { Thread.sleep(400); box.refresh() }
            box.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) })
        }
        assertTrue("Couldn't type into $label", typed)
        Thread.sleep(400)
        return box
    }

    // The [index]th text field on screen (editor: 0 Title, 1 Note in Edit, then Notebook).
    private fun type(index: Int, value: String) {
        await { nodes().filter { it.isVisibleToUser && it.isEditable }.size > index }
        val field = nodes().filter { it.isVisibleToUser && it.isEditable }[index]
        assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }))
        Thread.sleep(300)
    }
    // [fresh]: the page's remembered choices (Show, Sort, grid or list) back to how a new install has them.
    private fun openNotes(fresh: Boolean = true) {
        if (fresh) { app.settings.noteFilter = "all"; app.settings.setNoteSort(NoteSort.MY_ORDER); app.settings.setNotesAsList(false) }
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
        await { find("QA groceries") != null && find("☑ 1 of 2 done") != null && find("Home") != null }
        screenshot("notes-grid")

        // ⋮ Pin, Archive (out of All, into Archive), Unarchive.
        click("Actions for QA groceries"); click("Pin to the top")
        await { notes().single().pinned && find("Pinned") != null }
        click("Actions for QA groceries"); click("Archive")
        await { notes().single().archived && find("QA groceries") == null }
        show("Archive"); await { find("QA groceries") != null }
        click("Actions for QA groceries"); click("Unarchive")
        await { !notes().single().archived && find("No archived notes.") != null }
        show("All notes"); await { find("QA groceries") != null }

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
        // The Show list filters the page to it.
        await { find("#errands") != null }
        show("#errands")
        await { find("QA errands") != null && find("QA other") == null && find("QA with file") == null }
        screenshot("tag-filter")
        show("All notes")
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
            await { com.example.itinerary.data.NoteDraftStore(context).read("qa-draft-note") == null }
        } finally { com.example.itinerary.data.NoteDraftStore(context).clearAll() }
    }

    // Bug hunt 4 Oct (b) N6-1 / A6-1: a second Planner window (in a task of its own, as a share from the mail app opens)
    // with its own Notes page. It doesn't reopen the note still open in the first window as "Recovered", and its own
    // note's Discard leaves the first window's draft.
    @Test fun aSecondWindowLeavesTheFirstWindowsNoteAlone() {
        openNotes()
        click("New note"); await { find("New note") != null && find("Title") != null }
        type(0, "QA first window"); type(1, "typed in the first window")
        await { NoteDraftStore(context).readAll().singleOrNull()?.note?.content == "typed in the first window" }
        val firstId = NoteDraftStore(context).readAll().single().note.id
        val second = ins.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK))
        try {
            await { find("AGENDA") != null }
            click("More options"); click("Notes")
            await { find("Search notes") != null && find("New note") != null }
            Thread.sleep(1000) // a recovered draft would open by now
            assertNull(find("Recovered unsaved changes. Save them, or Close and Discard."))
            assertNull(find("QA first window"))
            screenshot("second-window-notes")
            click("New note"); await { find("Title") != null }
            type(0, "QA second window"); type(1, "second")
            await { NoteDraftStore(context).readAll().size == 2 }
            click("Close"); click("Discard")
            await { find("Search notes") != null && NoteDraftStore(context).readAll().size == 1 }
            assertEquals("typed in the first window", NoteDraftStore(context).read(firstId)?.note?.content)
            assertTrue("the first window's editor is still open", NoteDraftStore.isOpen(firstId))
        } finally { ins.runOnMainSync { second.finish() } }
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

    // The focused window's own view of the keyboard: where its top is on screen, or null while it's down.
    private fun imeTop(): Int? {
        var top: Int? = null
        ins.runOnMainSync {
            android.view.inspector.WindowInspector.getGlobalWindowViews().firstOrNull { it.hasWindowFocus() }?.let { v ->
                val ime = v.rootWindowInsets?.getInsets(android.view.WindowInsets.Type.ime())?.bottom ?: 0
                val loc = IntArray(2); v.getLocationOnScreen(loc)
                if (ime > 0) top = loc[1] + v.height - ime
            }
        }
        return top
    }
    private fun bounds(text: String) = android.graphics.Rect().also { find(text)!!.getBoundsInScreen(it) }
    private fun realTap(field: AccessibilityNodeInfo) {
        val r = android.graphics.Rect().also(field::getBoundsInScreen); shell("input tap ${r.centerX()} ${r.centerY()}"); Thread.sleep(1500)
    }

    // Typing in the note box: B, I, ☑… sit right on top of the keyboard (below Save), and still act on the selection.
    // In the title box, or with the keyboard down, they go back above the note box.
    @Test fun formattingButtonsSitOnTopOfTheKeyboard() {
        openNotes()
        click("New note"); await { find("New note") != null && find("Title") != null }
        type(0, "QA pinned")
        type(1, "hello world")
        val density = context.resources.displayMetrics.density
        realTap(nodes().filter { it.isVisibleToUser && it.isEditable }[1])
        await(8000) { imeTop() != null }
        Thread.sleep(800)
        val top = imeTop()!!
        val bold = bounds("Bold")
        screenshot("pinned-tools")
        assertTrue("Bold ${bold.bottom} should touch the keyboard at $top", bold.bottom <= top + 2 && top - bold.bottom <= 16 * density)
        assertTrue("Bold ${bold.top} should be below Save ${bounds("Save").bottom}", bold.top >= bounds("Save").bottom)
        listOf("Italic", "Strikethrough", "Heading", "Bulleted list", "Checklist", "Code").forEach { assertNotNull(it, find(it)) }
        // A selected word, then Bold: the note box keeps the keyboard and the buttons stay put.
        nodes().filter { it.isVisibleToUser && it.isEditable }[1].performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0); putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, 5) })
        Thread.sleep(300)
        click("Bold"); awaitNote("**hello** world")
        await(5000) { imeTop() != null && find("Bold") != null && bounds("Bold").bottom <= imeTop()!! + 2 }
        // The title box: the buttons go back above the note box.
        realTap(nodes().filter { it.isVisibleToUser && it.isEditable }[0])
        await(8000) { find("Bold") != null && nodes().filter { it.isVisibleToUser && it.isEditable }.size > 1 &&
            bounds("Bold").bottom <= android.graphics.Rect().also(nodes().filter { it.isVisibleToUser && it.isEditable }[1]::getBoundsInScreen).top }
        screenshot("tools-in-place")
        click("Save")
        await { notes().singleOrNull()?.content == "**hello** world" }
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

    // Notebooks and tags: the page's Show list (with counts), and suggestions under the editor's boxes as you type.
    @Test fun showListAndSuggestionsAsYouType() {
        runBlocking {
            app.repository.saveNote(PlannerNote(title = "QA one", notebook = "Home", tags = listOf("errands")), create = true)
            app.repository.saveNote(PlannerNote(title = "QA two", notebook = "Work", tags = listOf("ideas")), create = true)
            app.repository.saveNote(PlannerNote(title = "QA old", archived = true), create = true)
        }
        openNotes()
        await { find("QA one") != null && find("QA two") != null }
        click("Show")
        await { listOf("All notes", "Home", "Work", "#errands", "#ideas", "Archive").all(::listShows) }
        screenshot("show-list")
        pick("Work"); await { find("QA two") != null && find("QA one") == null }
        show("#errands"); await { find("QA one") != null && find("QA two") == null }
        show("Archive"); await { find("QA old") != null && find("QA one") == null }
        show("All notes"); await { find("QA one") != null && find("QA two") != null && find("QA old") == null }

        click("New note"); await { find("New note") != null && find("Title") != null }
        type(0, "QA three")
        // Typing part of a notebook offers it; tapping fills the box.
        typeInto("Notebook (optional)", "wo")
        await { listShows("Work") }; Thread.sleep(800); screenshot("notebook-suggestions")
        pick("Work"); await { nodes().any { it.isEditable && it.text?.toString() == "Work" } }
        // A tag: part of it in other capitals offers it; a whole one typed in other capitals is the existing one.
        val tagBox = typeInto("Add a tag", "ER")
        // A real key press while the list is open still reaches the box: the list doesn't take the keyboard.
        shell("input text R")
        await { tagBox.refresh() && tagBox.text?.toString() == "ERR" && listShows("#errands") }
        screenshot("tag-suggestions")
        pick("#errands"); await { find("Remove tag errands") != null }
        typeInto("Add a tag", "Ideas"); click("Add tag"); await { find("Remove tag ideas") != null }
        // A notebook typed in other capitals saves into the existing one.
        typeInto("Notebook (optional)", "home")
        click("Save")
        await { notes().any { it.title == "QA three" && it.notebook == "Home" && it.tags == listOf("errands", "ideas") } }
        assertEquals(listOf("Home", "Work"), Notes.notebooks(notes()))
    }

    // A finger on the screen: down at [from], held [holdMs], moved to [to] in small steps, then lifted.
    private fun touch(from: android.graphics.Point, holdMs: Long, to: android.graphics.Point = from) {
        val start = SystemClock.uptimeMillis()
        fun send(action: Int, x: Int, y: Int) {
            val e = android.view.MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, x.toFloat(), y.toFloat(), 0)
            e.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            ins.uiAutomation.injectInputEvent(e, true); e.recycle()
        }
        send(android.view.MotionEvent.ACTION_DOWN, from.x, from.y)
        Thread.sleep(holdMs)
        val steps = 30
        for (i in 1..steps) { send(android.view.MotionEvent.ACTION_MOVE, from.x + (to.x - from.x) * i / steps, from.y + (to.y - from.y) * i / steps); Thread.sleep(20) }
        Thread.sleep(300)
        send(android.view.MotionEvent.ACTION_UP, to.x, to.y)
        Thread.sleep(500)
    }
    private fun centre(text: String): android.graphics.Point {
        await { find(text) != null }
        val r = android.graphics.Rect(); find(text)!!.getBoundsInScreen(r); return android.graphics.Point(r.centerX(), r.centerY())
    }
    private fun hold(text: String) = touch(centre(text), 900)
    private fun pageOrder() = notes().filter { !it.archived }.sortedWith(Notes.order).map { it.title }

    // Long press held still selects; the bar selects all or none, and pins, archives, moves and deletes them together.
    @Test fun longPressSelectsAndActsOnSeveralNotes() {
        runBlocking { listOf("QA a", "QA b", "QA c").forEach { app.repository.saveNote(PlannerNote(title = it), create = true) } }
        openNotes()
        await { find("QA a") != null && find("QA c") != null }
        hold("QA a"); await { find("1 selected") != null }
        assertNull(find("New note")) // no new note while selecting
        click("QA b"); await { find("2 selected") != null }
        click("QA b"); await { find("1 selected") != null } // a tap while selecting unticks
        click("Select all"); await { find("3 selected") != null }
        screenshot("notes-selected")
        click("Select all"); await { find("Cancel") == null && find("New note") != null } // all off: selection over
        // Pin them all.
        hold("QA a"); click("Select all"); await { find("3 selected") != null }
        click("Pin"); await { notes().all { it.pinned } && find("Cancel") == null }
        // Archive two.
        hold("QA a"); click("QA b"); await { find("2 selected") != null }
        click("Archive"); await { notes().count { it.archived } == 2 && find("Cancel") == null }
        show("Archive"); await { find("QA a") != null && find("QA b") != null && find("QA c") == null }
        show("All notes"); await { find("QA c") != null }
        // Move to a notebook.
        hold("QA c"); await { find("1 selected") != null }
        click("Move to notebook"); await { find("Move 1 note to a notebook") != null }
        typeInto("Notebook", "Work"); click("Move")
        await { notes().single { it.title == "QA c" }.notebook == "Work" && find("Cancel") == null }
        // Back leaves the selection.
        hold("QA c"); await { find("1 selected") != null }
        ins.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        await { find("Cancel") == null && find("Search notes") != null }
        // Delete asks first, listing the notes; Keep notes changes nothing.
        hold("QA c"); click("Delete 1 note"); await { find("Delete 1 note?") != null }
        screenshot("notes-delete-warning")
        click("Keep notes"); await { find("Delete 1 note?") == null }
        assertEquals(3, notes().size)
        click("Delete 1 note"); await { find("Delete 1 note?") != null }; click("Delete 1 note")
        await { notes().none { it.title == "QA c" } && find("Cancel") == null }
        assertEquals(2, notes().size)
    }

    // Long press held and moved drags a card to a new place, kept after a fresh start; not while searching.
    @Test fun dragPutsANoteWhereItIsDropped() {
        runBlocking { listOf("QA one", "QA two", "QA three").forEach { app.repository.saveNote(PlannerNote(title = it), create = true); Thread.sleep(5) } }
        openNotes()
        await { find("QA one") != null && find("QA three") != null }
        assertEquals(listOf("QA three", "QA two", "QA one"), pageOrder()) // newest at the top
        screenshot("drag-before")
        // "QA one" (last) dropped on "QA three" (first).
        touch(centre("QA one"), 900, centre("QA three"))
        await { pageOrder() == listOf("QA one", "QA three", "QA two") }
        await { find("Cancel") == null } // a drag doesn't select
        screenshot("drag-after")
        // While searching, the cards stay where they are.
        reveal("Search notes")
        val search = nodes().first { n -> n.isEditable && (0 until n.childCount).any { n.getChild(it)?.text?.toString() == "Search notes" } }
        search.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "QA") })
        Thread.sleep(500)
        touch(centre("QA two"), 900, centre("QA one"))
        Thread.sleep(800)
        assertEquals(listOf("QA one", "QA three", "QA two"), pageOrder())
    }

    // Grid or list, the Sort list (a drag in another sort makes it My order), a note's importance and a custom colour.
    @Test fun listViewSortImportanceAndCustomColour() {
        try {
            runBlocking { listOf("QA b", "QA c", "QA a").forEach { app.repository.saveNote(PlannerNote(title = it), create = true); Thread.sleep(5) } }
            openNotes()
            await { find("QA a") != null && find("QA c") != null }
            fun left(t: String) = android.graphics.Rect().also { find(t)!!.getBoundsInScreen(it) }.left
            fun top(t: String) = android.graphics.Rect().also { find(t)!!.getBoundsInScreen(it) }.top
            assertNotEquals(left("QA a"), left("QA c")) // a grid: two columns
            click("Show as list"); await { app.settings.notesAsList.value && find("Show as grid") != null }
            await { left("QA a") == left("QA c") && left("QA c") == left("QA b") } // one column
            screenshot("notes-list")
            // Sort by title.
            click("Sort"); pick("Title A–Z")
            await { app.settings.noteSort.value == NoteSort.TITLE && top("QA a") < top("QA b") && top("QA b") < top("QA c") }
            // Importance and a custom colour, from the editor.
            click("QA c"); await { find("Edit note") != null }
            reveal("High"); click("High")
            reveal("Custom colour"); click("Custom colour"); await { find("Use this colour") != null }
            val hex = nodes().first { n -> n.isEditable && (0 until n.childCount).any { n.getChild(it)?.text?.toString() == "Hex colour" } }
            hex.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "123456") })
            Thread.sleep(400)
            click("Use this colour"); await { find("Custom colour (chosen)") != null }
            screenshot("editor-importance-colour")
            click("Save"); await { notes().single { it.title == "QA c" }.let { it.priority == TaskPriority.HIGH && it.color == 0xFF123456.toInt() } }
            click("Close")
            await { find("High importance") != null }
            // Sort by importance: the High note first.
            click("Sort"); pick("Importance")
            await { top("QA c") < top("QA a") && top("QA c") < top("QA b") }
            screenshot("notes-importance")
            // A drag in another sort keeps the order it shows, as My order.
            touch(centre("QA b"), 900, centre("QA c"))
            await { app.settings.noteSort.value == NoteSort.MY_ORDER }
            await { pageOrder().first() == "QA b" }
            // Back to a grid; the choice is kept.
            click("Show as grid"); await { !app.settings.notesAsList.value }
        } finally { app.settings.setNotesAsList(false); app.settings.setNoteSort(NoteSort.MY_ORDER) }
    }

    private fun recreate() {
        ins.runOnMainSync {
            androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).first().recreate()
        }
        Thread.sleep(500)
    }
    // What Android closing Planner leaves: the saved state, the draft on disk, and nothing in memory.
    private fun recreateAsAfterProcessDeath() {
        ins.runOnMainSync { com.example.itinerary.ui.NoteEditorMemory.forgetAll() }
        recreate()
    }

    // Bug hunt 3 Oct NA-1 / NA-8: a new note saved once, still open when the page is rebuilt (rotation, or Android
    // closing Planner), was taken for a new one again and every Save failed. Its body is no longer in the saved state.
    @Test fun aSavedNewNoteStillSavesAfterThePageIsRebuilt() {
        openNotes()
        click("New note"); await { find("New note") != null && find("Title") != null }
        type(0, "QA restored"); type(1, "A")
        click("Save"); await { find("Saved") != null && notes().singleOrNull()?.content == "A" }
        type(1, "AB"); Thread.sleep(800) // the draft is written 400 ms after a change
        // Rotation: the body comes back from memory.
        recreate()
        await { find("Edit note") != null && noteText() == "AB" }
        // Android closed Planner: the draft has the body.
        recreateAsAfterProcessDeath()
        await { find("Edit note") != null && find("Recovered unsaved changes. Save them, or Close and Discard.") != null && noteText() == "AB" }
        screenshot("restored-after-process-death")
        click("Save"); await { notes().singleOrNull()?.content == "AB" && find("Saved") != null }
        assertNull(find("Couldn't save this note. Please try again."))
        // No draft either: the editor still opens the saved note (not a new one), and Save updates it.
        type(1, "ABC"); Thread.sleep(800)
        com.example.itinerary.data.NoteDraftStore(context).clearAll()
        recreateAsAfterProcessDeath()
        await { find("Edit note") != null && find("Delete") != null }
        type(1, "ABCD"); click("Save")
        await { notes().singleOrNull()?.content == "ABCD" && find("Saved") != null }
        assertNull(find("Couldn't save this note. Please try again."))
        click("Close"); await { find("Search notes") != null }
        assertEquals(1, notes().size)
    }

    // NA-3: a drag let go where it began left the page in that order, so Sort and Pin seemed to do nothing.
    @Test fun aDropThatMovesNothingLeavesSortAndPinWorking() {
        try {
            runBlocking { listOf("QA b", "QA c", "QA a").forEach { app.repository.saveNote(PlannerNote(title = it), create = true); Thread.sleep(5) } }
            openNotes()
            click("Show as list"); await { app.settings.notesAsList.value }
            fun top(t: String) = android.graphics.Rect().also { find(t)!!.getBoundsInScreen(it) }.top
            await { find("QA a") != null && top("QA a") < top("QA c") && top("QA c") < top("QA b") } // newest first
            // Held, moved sideways past the touch slop over its own card, and let go: no place changes.
            val from = centre("QA c")
            touch(from, 900, android.graphics.Point(from.x + 80, from.y))
            await { find("Cancel") == null }
            click("Sort"); pick("Title A–Z")
            await { top("QA a") < top("QA b") && top("QA b") < top("QA c") }
            click("Actions for QA c"); click("Pin to the top")
            await { notes().single { it.title == "QA c" }.pinned && top("QA c") < top("QA a") }
            screenshot("drop-in-place-then-sort")
        } finally { app.settings.setNotesAsList(false); app.settings.setNoteSort(NoteSort.MY_ORDER) }
    }

    // NA-10: in My order a card offers Move earlier / Move later to screen readers, which can't drag.
    @Test fun screenReaderActionsMoveACard() {
        runBlocking { listOf("QA one", "QA two", "QA three").forEach { app.repository.saveNote(PlannerNote(title = it), create = true); Thread.sleep(5) } }
        openNotes()
        await { find("QA one") != null && find("QA three") != null }
        fun card(text: String): AccessibilityNodeInfo { var n = find(text); while (n != null && !n.isClickable) n = n.parent; return n!! }
        fun actions(text: String) = card(text).actionList.mapNotNull { it.label?.toString() }
        assertEquals(listOf("QA three", "QA two", "QA one"), pageOrder())
        assertTrue("Move later" in actions("QA three")); assertFalse("Move earlier" in actions("QA three"))
        assertFalse("Move later" in actions("QA one"))
        val later = card("QA three").actionList.first { it.label?.toString() == "Move later" }
        assertTrue(card("QA three").performAction(later.id))
        await { pageOrder() == listOf("QA two", "QA three", "QA one") }
        await { "Move earlier" in actions("QA three") }
        val earlier = card("QA one").actionList.first { it.label?.toString() == "Move earlier" }
        assertTrue(card("QA one").performAction(earlier.id))
        await { pageOrder() == listOf("QA two", "QA one", "QA three") }
        // In another sort there is no order of the user's own to move in.
        try {
            click("Sort"); pick("Title A–Z")
            await { app.settings.noteSort.value == NoteSort.TITLE && "Move later" !in actions("QA one") && "Move earlier" !in actions("QA two") }
        } finally { app.settings.setNoteSort(NoteSort.MY_ORDER) }
    }

    // Notes opens on the last Show choice, list view and sort; one whose notebook has gone falls back to all notes.
    @Test fun notesOpensAsLastLeft() {
        runBlocking {
            app.repository.saveNote(PlannerNote(title = "QA work note", notebook = "Work"), create = true)
            app.repository.saveNote(PlannerNote(title = "QA home note", notebook = "Home"), create = true)
        }
        openNotes()
        show("Work"); await { find("QA work note") != null && find("QA home note") == null }
        click("Show as list"); await { app.settings.notesAsList.value }
        click("Sort"); pick("Title A–Z")
        openNotes(fresh = false) // a fresh start of the app's screen
        await { find("QA work note") != null && find("QA home note") == null && find("Show as grid") != null }
        assertEquals(NoteSort.TITLE, app.settings.noteSort.value)
        // The Work notebook empties: back to all notes.
        runBlocking { app.repository.deleteNotes(notes().filter { it.notebook == "Work" }.map { it.id }) }
        await { find("QA home note") != null }
        openNotes(fresh = false)
        await { find("QA home note") != null && app.settings.noteFilter == "all" }
        openNotes() // defaults back for the other tests
    }

    // Duplicate (4 Oct): from a card's ⋮ (next to the original, "Note duplicated" with Open), from the selection bar (each
    // one next to its original), and from the editor (an unsaved copy of what is typed; the original stays as saved).
    @Test fun duplicateFromTheMenuTheBarAndTheEditor() {
        runBlocking {
            app.repository.saveNote(PlannerNote(title = "QA first", content = "one", tags = listOf("t"), priority = TaskPriority.HIGH,
                pinned = false, reminderAt = System.currentTimeMillis() + 86_400_000), create = true)
            app.repository.saveNote(PlannerNote(title = "QA second", content = "two"), create = true)
        }
        openNotes()
        await { find("QA first") != null && find("QA second") != null }
        // ⋮ → Duplicate: right after the original, the reminder left behind; Open opens the copy.
        click("Actions for QA first"); click("Duplicate")
        await { notes().any { it.title == "QA first (copy)" } }
        val copy = notes().single { it.title == "QA first (copy)" }
        assertEquals("one", copy.content); assertEquals(listOf("t"), copy.tags); assertEquals(TaskPriority.HIGH, copy.priority)
        assertNull(copy.reminderAt)
        assertEquals(listOf("QA second", "QA first", "QA first (copy)"), pageOrder())
        await { find("Note duplicated") != null }; screenshot("duplicated-bar")
        click("Open"); await { find("Edit note") != null && nodes().any { it.isEditable && it.text?.toString() == "QA first (copy)" } }
        click("Close"); await { find("Search notes") != null }
        // The selection bar: both picked, each copied next to its own.
        hold("QA second"); await { find("1 selected") != null }
        click("QA first"); await { find("2 selected") != null }
        click("Duplicate"); await { notes().size == 5 }
        assertEquals(listOf("QA second", "QA second (copy)", "QA first", "QA first (copy)", "QA first (copy)"), pageOrder())
        await { find("2 notes duplicated") != null }
        // The editor: what is typed goes to the copy; the original keeps what was saved.
        click("QA second"); await { find("Edit note") != null }
        click("Edit") // a note with words opens in Preview
        type(1, "two, edited")
        click("Duplicate note"); await { find("New note") != null && nodes().any { it.isEditable && it.text?.toString() == "QA second (copy)" } }
        click("Save"); await { notes().count { it.title == "QA second (copy)" } == 2 }
        assertTrue(notes().any { it.title == "QA second (copy)" && it.content == "two, edited" })
        assertEquals("two", notes().single { it.title == "QA second" }.content)
        // ...and it went next to its original, not to the top (D6-7).
        assertEquals("QA second", pageOrder().first())
        assertEquals("two, edited", notes().filter { !it.archived }.sortedWith(Notes.order)[1].content)
    }
}
