package com.example.itinerary

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.example.itinerary.data.*
import com.example.itinerary.ui.NoteEditorMemory
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.*
import org.junit.Test

/** Regression checks for note input protection and transient attachment cleanup. */
class NoteEditorHuntRegressionTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = ins.targetContext
    private val app get() = context.applicationContext as ItineraryApp
    private fun nodes(): List<AccessibilityNodeInfo> {
        val out = mutableListOf<AccessibilityNodeInfo>()
        fun visit(n: AccessibilityNodeInfo) { out += n; for (i in 0 until n.childCount) n.getChild(i)?.let(::visit) }
        ins.uiAutomation.freshRoot?.let(::visit)
        return out
    }
    private fun find(text: String) = nodes().firstOrNull { it.isVisibleToUser && (it.text?.toString() == text || it.contentDescription?.toString() == text) }
    private fun await(block: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 20_000
        while (SystemClock.uptimeMillis() < until) { if (block()) return; Thread.sleep(100) }
        fail("Timed out: " + nodes().mapNotNull { it.text ?: it.contentDescription }.joinToString(" | "))
    }
    private fun click(text: String) {
        await {
            var n = find(text)
            while (n != null && !n.isClickable) n = n.parent
            n?.takeIf { it.isEnabled }?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        }
        Thread.sleep(250)
    }
    private fun field() = nodes().firstOrNull { n -> n.isVisibleToUser && n.isEditable &&
        (0 until n.childCount).any { n.getChild(it)?.text?.toString() == "Title" } }
    private fun title(value: String) {
        await { field() != null }
        val n = field()!!
        assertTrue("Title enabled", n.isEnabled)
        assertTrue("Title text action accepted", n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }))
        await { nodes().any { it.isEditable && it.text?.toString() == value } }
    }
    private fun openNew() {
        app.settings.setStartScreen(StartScreen.AGENDA)
        app.settings.lastViewCalendar = false
        ins.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        await { find("AGENDA") != null }
        click("More options"); click("Notes"); await { find("Search notes") != null }
        click("New note"); await { field() != null }
    }
    @Test fun saveAndCloseDisablesTypingUntilCommit() {
        openNew(); title("beforeSave")
        click("Close"); await { find("Save changes?") != null }
        val gate = app.repository.javaClass.getDeclaredField("changes").apply { isAccessible = true }.get(app.repository) as Mutex
        runBlocking { gate.lock() }
        try {
            click("Save"); await { find("Saving…") != null }
            val titleBox = nodes().first { n -> (0 until n.childCount).any { n.getChild(it)?.text?.toString() == "Title" } }
            assertFalse("Title disabled while saving", titleBox.isEnabled)
            assertFalse("Accessibility cannot change saved input", titleBox.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "typedDuringSave")
            }))
            Thread.sleep(600)
            assertTrue(NoteDraftStore(context).readAll().any { it.note.title == "beforeSave" })
        } finally { gate.unlock() }
        await { find("Search notes") != null }
        val saved = runBlocking { app.repository.snapshot().notes }
        assertEquals("beforeSave", saved.single().title)
        assertTrue("Successful save leaves no recovery draft", NoteDraftStore(context).readAll().isEmpty())
    }
    @Test fun removingImportedFileReleasesItWithoutAFirstSave() {
        openNew(); title("importRemove")
        val source = app.attachmentStore.writableFileFor("qa-probe-source.txt").apply { writeText("QA only") }
        var imported: Attachment? = null
        try {
            imported = runBlocking { app.attachmentStore.import(app.attachmentStore.uriFor(source.name)) }
            assertNotNull("Real import succeeded", imported)
            // The actual import callback's sole state update, injected to avoid opening the shared system picker.
            ins.runOnMainSync {
                val kept = NoteEditorMemory.javaClass.getDeclaredField("kept").apply { isAccessible = true }.get(NoteEditorMemory) as Map<*, *>
                val entry = kept.values.last()!!
                val body = entry.javaClass.getDeclaredField("body").apply { isAccessible = true }.get(entry) as NoteEditorMemory.Body
                body.attachments = body.attachments + imported!!
            }
            val remove = "Remove ${imported!!.name}"
            await {
                if (find(remove) != null) true else {
                    nodes().filter { it.isScrollable && it.isVisibleToUser && !it.isEditable }
                        .maxByOrNull { android.graphics.Rect().also(it::getBoundsInScreen).height() }
                        ?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    Thread.sleep(150); false
                }
            }
            click(remove); click("Close"); click("Discard")
            await { find("Search notes") != null && NoteDraftStore(context).readAll().isEmpty() }
            // Ensure the app-scope releaseFiles coroutine had time to run and serialized cleanup completes.
            runBlocking { app.repository.releaseTaskFiles(emptyList()) }
            await { !app.attachmentStore.fileFor(imported!!.fileName).exists() }
            assertTrue(runBlocking { app.repository.snapshot().notes }.isEmpty())
        } finally {
            source.delete()
            imported?.let { app.attachmentStore.delete(it.fileName) }
        }
    }
}
