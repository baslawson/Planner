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
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale

/**
 * An email shared from Thunderbird: the subject without its label, only the sender of the headers, the day (and time)
 * the message names, Add bill and Add note, and Close asking before it drops what the share filled in.
 * Run only with an external backup/restore harness for the shared emulator.
 */
class ShareEmailUiTest {
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
    private fun findStarting(text: String) = nodes().firstOrNull { it.isVisibleToUser && it.text?.toString()?.startsWith(text) == true }
    private fun screenshot(name: String) {
        val dir = File(context.cacheDir, "qa-share-email").apply { mkdirs() }
        ins.uiAutomation.takeScreenshot()?.let { b -> File(dir, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }; b.recycle() }
    }
    private fun await(timeout: Long = 30000, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < end) { if (condition()) return; Thread.sleep(150) }
        screenshot("failure"); fail("Timed out: " + nodes().mapNotNull { it.text }.joinToString(" | "))
    }
    private fun click(node: () -> AccessibilityNodeInfo?) {
        await {
            var n = node()
            while (n != null && !n.isClickable) n = n.parent
            n?.takeIf { it.isEnabled }?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        }; Thread.sleep(400)
    }
    private fun click(text: String) = click { find(text) }

    // The text Thunderbird's ShareIntentBuilder sends: no EXTRA_SUBJECT, the headers on top.
    private fun email(subject: String, body: String) = "Subject: $subject\nDate: 1 Oct 2026 09:14\n" +
        "From: QA Sender <sender@example.com>\nTo: QA Me <me@example.org>\n\n$body"
    private fun share(text: String) {
        activity = ins.startActivitySync(Intent(context, MainActivity::class.java).setAction(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, text).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        await { find("Add to Planner") != null }
    }
    private val day = LocalDate.now().plusDays(10)
    private val written = "${day.dayOfMonth} ${day.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)} ${day.year}"
    private fun data() = runBlocking { app.repository.snapshot() }

    @Before fun fresh() {
        TaskDraftStore(context).clear("new"); EditorDraftStore(context).clear(); NoteDraftStore(context).clearAll()
        app.settings.setTimeFormat(TimeFormat.HOUR_24)
    }
    @After fun done() {
        activity?.let { a -> ins.runOnMainSync { a.finish() } }
        TaskDraftStore(context).clear("new"); EditorDraftStore(context).clear(); NoteDraftStore(context).clearAll()
    }

    @Test fun eventTakesTheSubjectTheDayAndThePickedTimeAndCloseAsks() = runBlocking {
        share(email("QA dentist", "This confirms your appointment on $written at 10:30. Please arrive early."))
        assertNotNull(find("QA dentist")) // no "Subject:" label
        assertNotNull(findStarting("Date in the text: "))
        // "10:30": morning or evening is picked here.
        assertNotNull(find("22:30"))
        click("10:30")
        screenshot("email-share-popup")
        click("Add event")
        await { find("New event") != null }
        // Close asks before dropping what the share filled in; Keep editing stays.
        click("Close")
        await { find("Save changes?") != null }
        click("Keep editing")
        await { find("Save changes?") == null && find("New event") != null }
        click("Save")
        await { data().items.any { it.title == "QA dentist" } }
        val event = data().items.single { it.title == "QA dentist" }
        assertEquals(day, event.date); assertEquals(LocalTime.of(10, 30), event.startTime)
        assertTrue(event.notes.startsWith("From: QA Sender <sender@example.com>"))
        assertFalse(event.notes.contains("me@example.org")); assertFalse(event.notes.contains("1 Oct 2026"))
        click("Close") // saved, so it leaves at once
        await { find("New event") == null && find("Edit event") == null }
        app.repository.deleteItem(event)
    }

    @Test fun taskGetsTheDueDateAndDiscardSavesNothing() = runBlocking {
        share(email("QA reply to school", "Please reply by $written."))
        click("Add task")
        await { find("Add task") == null || find("Task title") != null }
        click("Close")
        await { find("Save changes?") != null }
        click("Discard")
        await { find("Save changes?") == null && find("Task title") == null }
        assertFalse(data().tasks.any { it.title == "QA reply to school" })
        assertNull(TaskDraftStore(context).read("new"))
        // Saved this time: the due date is the day the email names.
        share(email("QA reply to school", "Please reply by $written."))
        click("Add task")
        await { find("Task title") != null }
        click("Save")
        await { data().tasks.any { it.title == "QA reply to school" } }
        val task = data().tasks.single { it.title == "QA reply to school" }
        assertEquals(day, task.dueDate)
        click("Close")
        app.repository.deleteTask(task.id)
    }

    @Test fun billTakesTheAmountAndDueDate() = runBlocking {
        share(email("QA power bill", "Your electricity bill of EUR 84.20 is due on $written."))
        assertNotNull(find("Amount for a bill: " + Bills.format(8420, "EUR")))
        click("Add bill")
        await { find("New event") != null || find("New bill task") != null || findStarting("New bill") != null }
        screenshot("email-share-bill")
        click("Save")
        await { data().items.any { it.title == "QA power bill" } }
        val bill = data().items.single { it.title == "QA power bill" }
        assertEquals("Bills", bill.category); assertEquals(8420L, bill.billAmountMinor); assertEquals("EUR", bill.billCurrency)
        assertEquals(day, bill.date)
        click("Close")
        app.repository.deleteItem(bill)
    }

    @Test fun noteOpensOnTheNotesPageAndIsSavedOnlyBySave() = runBlocking {
        share(email("QA recipe from Sam", "Two eggs, flour and milk."))
        click("Add note")
        await { find("Add to Planner") == null && nodes().any { it.isEditable && it.text?.toString() == "QA recipe from Sam" } }
        screenshot("email-share-note")
        assertFalse(app.repository.allNotes().any { it.title == "QA recipe from Sam" })
        click("Close")
        await { find("Save changes?") != null }
        click("Save")
        await { runBlocking { app.repository.allNotes() }.any { it.title == "QA recipe from Sam" } }
        val note = app.repository.allNotes().single { it.title == "QA recipe from Sam" }
        assertTrue(note.content.contains("Two eggs, flour and milk."))
        assertTrue(note.content.startsWith("From: QA Sender <sender@example.com>"))
    }
}
