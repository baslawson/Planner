package com.example.itinerary

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.example.itinerary.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.LocalTime

/** The task and event editors share one bottom bar: Delete on the left, Close and Save on the right, evenly spaced and
 *  never touching — in the dark and light themes, and wrapped tidily at the largest text size. */
@Suppress("DEPRECATION")
class EditorActionsUiTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = ins.targetContext
    private val app get() = context.applicationContext as ItineraryApp
    private fun nodes(): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(n: AccessibilityNodeInfo) { result += n; for (i in 0 until n.childCount) n.getChild(i)?.let(::visit) }
        ins.uiAutomation.freshRoot?.let(::visit); return result
    }
    private fun find(text: String) = nodes().firstOrNull { it.isVisibleToUser && (it.text?.toString() == text || it.contentDescription?.toString() == text) }
    private fun button(text: String): Rect? { var n = find(text); while (n != null && !n.isClickable) n = n.parent; return n?.let { Rect().also(it::getBoundsInScreen) } }
    private fun screenshot(name: String) {
        val dir = File(context.cacheDir, "qa-editor-actions").apply { mkdirs() }
        ins.uiAutomation.takeScreenshot()?.let { b -> File(dir, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }; b.recycle() }
    }
    private fun await(timeout: Long = 20000, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < end) { if (condition()) return; Thread.sleep(150) }
        screenshot("failure"); fail("Timed out: " + nodes().filter { it.isVisibleToUser }.mapNotNull { it.text ?: it.contentDescription }.joinToString(" | "))
    }
    private fun click(text: String) {
        await { var n = find(text); while (n != null && !n.isClickable) n = n.parent; n?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true }
        Thread.sleep(400)
    }
    private val dp get() = context.resources.displayMetrics.density

    // One line: Delete | gap | Close 8 dp Save, all on one baseline. Wrapped (large text): nothing overlaps, Save on the right.
    private fun checkBar(name: String, wrapped: Boolean) {
        await { button("Delete") != null && button("Close") != null && (button("Save") ?: button("Saved")) != null }
        Thread.sleep(600)
        val delete = button("Delete")!!; val close = button("Close")!!; val save = (button("Save") ?: button("Saved"))!!
        screenshot(name)
        listOf(delete to close, close to save, delete to save).forEach { (a, b) -> assertFalse("$name: $a overlaps $b", Rect.intersects(a, b)) }
        val width = context.resources.displayMetrics.widthPixels
        assertTrue("$name: Save ends at the right edge", width - save.right in (16 * dp).toInt()..(24 * dp).toInt())
        if (!wrapped) {
            assertEquals("$name: one line", delete.centerY().toFloat(), save.centerY().toFloat(), 2f)
            assertEquals("$name: one line", close.centerY().toFloat(), save.centerY().toFloat(), 2f)
            assertTrue("$name: Delete on the left", delete.left < (24 * dp))
            assertEquals("$name: Close 8 dp from Save", 8 * dp, (save.left - close.right).toFloat(), 2f)
            assertTrue("$name: Delete clear of Close", close.left - delete.right >= 8 * dp)
            assertEquals("$name: same height", delete.height().toFloat(), save.height().toFloat(), 2f)
            assertEquals("$name: same height", close.height().toFloat(), save.height().toFloat(), 2f)
            // A quarter smaller than ordinary buttons: Close's outline is 30 dp tall (40 dp elsewhere). Measured on screen, as
            // accessibility (and touch) still give a small button 48 dp.
            val drawn = drawnHeight(close)
            assertEquals("$name: Close drawn 30 dp tall (was $drawn px)", 30 * dp, drawn.toFloat(), 2 * dp)
        }
    }
    // The height of the outlined button inside [r], from a screenshot: a column a quarter in from its right end, scanned up
    // and down from the middle to the first pixel that differs clearly from the fill (the outline).
    private fun drawnHeight(r: Rect): Int {
        val shot = ins.uiAutomation.takeScreenshot()!!
        try {
            val x = r.right - r.width() / 4
            val fill = shot.getPixel(x, r.centerY())
            fun differs(c: Int) = listOf(16, 8, 0).sumOf { kotlin.math.abs((c shr it and 255) - (fill shr it and 255)) } > 90
            var top = r.centerY(); while (top > r.top && !differs(shot.getPixel(x, top))) top--
            var bottom = r.centerY(); while (bottom < r.bottom && !differs(shot.getPixel(x, bottom))) bottom++
            return bottom - top
        } finally { shot.recycle() }
    }
    private fun closeEditor() { click("Close"); await { find("Edit task") == null && find("Edit event") == null } }
    private fun openTask(task: PlannerTask) {
        ins.startActivitySync(Intent(context, MainActivity::class.java).setAction(com.example.itinerary.widget.TodayWidget.OPEN_TASK)
            .putExtra("task_id", task.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        await { find("Edit task") != null }
    }
    private fun openEvent(title: String) {
        app.settings.lastViewCalendar = false
        ins.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        await { find("AGENDA") != null }
        click(title); Thread.sleep(800); if (find("Edit event") == null) click(title)
        await { find("Edit event") != null }
    }
    private fun themed(mode: ThemeMode, textSize: Int = TextSize.DEFAULT_PERCENT, block: () -> Unit) {
        app.settings.setThemeMode(mode); app.settings.setTextSizePercent(textSize)
        try { block() } finally { app.settings.setThemeMode(ThemeMode.SYSTEM); app.settings.setTextSizePercent(TextSize.DEFAULT_PERCENT) }
    }

    @Test fun taskEditorBar() {
        val task = PlannerTask(title = "QA bar task", dueDate = LocalDate.now()); runBlocking { app.repository.saveTask(task) }
        // Closed before the next opening: a widget task reopened while its editor is still closing in the screen being
        // replaced is (rightly) refused as "already open".
        themed(ThemeMode.DARK) { openTask(task); checkBar("task-dark", wrapped = false); closeEditor() }
        themed(ThemeMode.LIGHT) { openTask(task); checkBar("task-light", wrapped = false); closeEditor() }
        themed(ThemeMode.DARK, TextSize.MAX_PERCENT) { openTask(task); checkBar("task-dark-large", wrapped = true); closeEditor() }
    }

    @Test fun noteEditorBar() {
        val note = runBlocking { app.repository.saveNote(PlannerNote(title = "QA bar note", content = "text"), create = true) }
        themed(ThemeMode.DARK) {
            app.settings.lastViewCalendar = false; app.settings.noteFilter = "all"
            ins.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            await { find("AGENDA") != null }
            click("More options"); click("Notes"); click(note.title)
            await { find("Edit note") != null }
            checkBar("note-dark", wrapped = false)
        }
    }

    @Test fun eventEditorBar() {
        runBlocking { app.repository.saveItem(ItineraryItem(tripId = 0, date = LocalDate.now(), startTime = LocalTime.of(23, 0), title = "QA bar event")) }
        themed(ThemeMode.DARK) { openEvent("QA bar event"); checkBar("event-dark", wrapped = false) }
        themed(ThemeMode.LIGHT) { openEvent("QA bar event"); checkBar("event-light", wrapped = false) }
    }
}
