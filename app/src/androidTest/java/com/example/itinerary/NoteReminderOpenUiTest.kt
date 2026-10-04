package com.example.itinerary

import android.app.NotificationManager
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.example.itinerary.data.PlannerNote
import com.example.itinerary.reminders.NoteReminderReceiver
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** U-13: tapping a note reminder's notification opens that note's editor; a note deleted since opens the Notes page. */
@Suppress("DEPRECATION")
class NoteReminderOpenUiTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = ins.targetContext
    private val app get() = context.applicationContext as ItineraryApp
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    private fun nodes(): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(n: AccessibilityNodeInfo) { result += n; for (i in 0 until n.childCount) n.getChild(i)?.let(::visit) }
        ins.uiAutomation.freshRoot?.let(::visit); return result
    }
    private fun find(text: String) = nodes().firstOrNull { it.isVisibleToUser && (it.text?.toString() == text || it.contentDescription?.toString() == text) }
    private fun has(part: String) = nodes().any { it.isVisibleToUser && it.text?.toString()?.contains(part) == true }
    private fun screenshot(name: String) {
        val dir = File(context.cacheDir, "qa-note-reminder-open").apply { mkdirs() }
        ins.uiAutomation.takeScreenshot()?.let { b -> File(dir, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }; b.recycle() }
    }
    private fun await(timeout: Long = 20000, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < end) { if (condition()) return; Thread.sleep(150) }
        screenshot("failure"); fail("Timed out: " + nodes().filter { it.isVisibleToUser }.mapNotNull { it.text ?: it.contentDescription }.joinToString(" | "))
    }
    // Back, as the user would: an unchanged note's editor just closes.
    private fun closeEditor() {
        if (find("Edit note") != null) ins.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        Thread.sleep(400)
    }

    private val created = mutableListOf<String>()

    @After fun cleanUp() = runBlocking {
        closeEditor()
        created.forEach { manager.cancel("note:$it", 0); if (app.repository.note(it) != null) app.repository.deleteNote(it) }
        app.repository.pendingDeletions.value.forEach { app.repository.finishDeletion(it.token) }
        app.repository.snapshot().deleted.filter { it.label.startsWith("QA open note") }.forEach { app.repository.permanentlyDelete(it.id) }
    }

    @Test fun theNotificationsTapOpensItsNote() {
        val note = runBlocking { app.repository.saveNote(PlannerNote(title = "QA open note", content = "Bring the folder"), create = true) }
        created += note.id
        // Start on the agenda, then post the reminder as it rings and tap it.
        ins.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        await { find("AGENDA") != null || find("NOTES") != null || has("Today") }
        com.example.itinerary.reminders.postNoteReminder(context, note.id, System.currentTimeMillis(), note)
        var posted: android.app.Notification? = null
        await { posted = manager.activeNotifications.firstOrNull { it.tag == "note:${note.id}" }?.notification; posted != null }
        posted!!.contentIntent.send()
        await { find("Edit note") != null && has("QA open note") }
        screenshot("opened")
    }

    @Test fun aDeletedNoteOpensTheNotesPage() {
        ins.startActivitySync(NoteReminderReceiver.openIntent(context, "qa-open-note-gone")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        await { find("NOTES") != null }
        Thread.sleep(800)
        assertNull(find("Edit note"))
    }

    @Test fun aSecondNoteOpensAfterTheFirstFromAnIntentWhilePlannerIsOpen() {
        val first = runBlocking { app.repository.saveNote(PlannerNote(title = "QA open note one"), create = true) }
        val second = runBlocking { app.repository.saveNote(PlannerNote(title = "QA open note two"), create = true) }
        created += listOf(first.id, second.id)
        ins.startActivitySync(NoteReminderReceiver.openIntent(context, first.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        await { find("Edit note") != null && has("QA open note one") }
        closeEditor()
        await { find("Edit note") == null }
        // Planner is open on Notes: the next tap arrives as a new intent (onNewIntent).
        context.startActivity(NoteReminderReceiver.openIntent(context, second.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        await { find("Edit note") != null && has("QA open note two") }
    }
}
