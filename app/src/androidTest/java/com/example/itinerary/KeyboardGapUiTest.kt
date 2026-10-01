package com.example.itinerary

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.example.itinerary.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.LocalTime

/** With the keyboard up, an editor's pinned buttons sit just above it — as close for a low field (a checklist item) as
 *  for the title. Before adjustResize, Android also panned the window for a low field, leaving a wide empty band.
 *  The measurements go to the test app's cache, qa-keyboard-gap/results.txt. */
@Suppress("DEPRECATION")
class KeyboardGapUiTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = ins.targetContext
    private val app get() = context.applicationContext as ItineraryApp
    private val out get() = File(context.cacheDir, "qa-keyboard-gap").apply { mkdirs() }
    private fun nodes(): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(n: AccessibilityNodeInfo) { result += n; for (i in 0 until n.childCount) n.getChild(i)?.let(::visit) }
        ins.uiAutomation.freshRoot?.let(::visit); return result
    }
    private fun label(n: AccessibilityNodeInfo) = (n.text ?: n.contentDescription)?.toString()
    private fun find(text: String) = nodes().firstOrNull { it.isVisibleToUser && label(it) == text }
    private fun shot(name: String) {
        ins.uiAutomation.takeScreenshot()?.let { b -> File(out, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }; b.recycle() }
    }
    private fun await(timeout: Long = 20000, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < end) { if (condition()) return; Thread.sleep(150) }
        shot("failure"); fail("Timed out: " + nodes().filter { it.isVisibleToUser }.mapNotNull(::label).joinToString(" | "))
    }
    private fun click(text: String) {
        await { var n = find(text); while (n != null && !n.isClickable) n = n.parent; n?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true }
        Thread.sleep(500)
    }
    private fun shell(cmd: String) = android.os.ParcelFileDescriptor.AutoCloseInputStream(ins.uiAutomation.executeShellCommand(cmd)).use { it.readBytes() }
    // A real tap in the middle of the field, as a finger would, so the keyboard opens as it does by hand.
    private fun tapField(pick: (List<AccessibilityNodeInfo>) -> AccessibilityNodeInfo?) {
        await { pick(nodes().filter { it.isVisibleToUser && it.isEditable }) != null }
        val r = Rect(); pick(nodes().filter { it.isVisibleToUser && it.isEditable })!!.getBoundsInScreen(r)
        shell("input tap ${r.centerX()} ${r.centerY()}"); Thread.sleep(1500)
    }
    // The focused window's own view of the keyboard: its height from the bottom of that window.
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

    // Lowest clickable thing above the keyboard (the pinned row) and the gap to the keyboard, in px and dp.
    private fun measure(name: String): Pair<Int, Int> {
        await(8000) { imeTop() != null }
        Thread.sleep(800)
        val top = imeTop()!!
        val density = context.resources.displayMetrics.density
        val pinned = nodes().filter { it.isVisibleToUser && it.isClickable && !it.isEditable }
            .map { n -> Rect().also(n::getBoundsInScreen) to (label(n) ?: firstText(n)) }
            .filter { it.first.bottom <= top + 2 }
            .maxByOrNull { it.first.bottom }
        val focused = nodes().firstOrNull { it.isFocused && it.isEditable }?.let { Rect().also(it::getBoundsInScreen) }
        val line = "$name: keyboardTop=$top pinned='${pinned?.second}' pinnedBottom=${pinned?.first?.bottom} " +
            "gap=${pinned?.let { top - it.first.bottom }}px (${pinned?.let { ((top - it.first.bottom) / density).toInt() }}dp) " +
            "focusedField=$focused screenH=${context.resources.displayMetrics.heightPixels} density=$density"
        File(out, "results.txt").appendText(line + "\n")
        shot(name)
        hideQuickTestKeyboard(ins); Thread.sleep(400)
        assertNotNull("$name: no pinned button above the keyboard", pinned)
        // The field being typed in is on screen, above the keyboard.
        assertTrue("$name: focused field $focused under the keyboard at $top", focused != null && focused.bottom <= top)
        return top to top - pinned!!.first.bottom
    }
    // Low field and title alike: the keyboard starts at the same place in the window (a panned window moved it: 869 or
    // 904 px against 972 on the 720 x 1560 emulator), the same gap above it (a few px of rounding allowed), never under it.
    private fun sameGap(title: Pair<Int, Int>, checklist: Pair<Int, Int>) {
        assertTrue("title gap ${title.second} px", title.second >= 0)
        assertEquals("keyboard top, checklist vs title", title.first, checklist.first)
        assertTrue("checklist gap ${checklist.second} px, title gap ${title.second} px", kotlin.math.abs(checklist.second - title.second) <= 3)
    }
    private fun firstText(n: AccessibilityNodeInfo): String? {
        for (i in 0 until n.childCount) n.getChild(i)?.let { c -> label(c)?.let { return it }; firstText(c)?.let { return it } }
        return null
    }
    private fun openMain(action: String? = null, extra: (Intent) -> Unit = {}) {
        app.settings.lastViewCalendar = false
        ins.startActivitySync(Intent(context, MainActivity::class.java).setAction(action).also(extra)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
    }
    private val list = listOf(ChecklistEntry(text = "Passport"), ChecklistEntry(text = "Charger"))
    private fun checklistField(f: List<AccessibilityNodeInfo>) = f.firstOrNull { it.text?.toString() == "Charger" }
    private fun firstField(f: List<AccessibilityNodeInfo>) = f.firstOrNull()
    // The checklist is below the fold: its own "Go to checklist" button brings it up.
    private fun toChecklist() {
        hideQuickTestKeyboard(ins); Thread.sleep(500)
        await { nodes().firstOrNull { it.isVisibleToUser && it.contentDescription?.toString()?.startsWith("Go to checklist") == true }
            ?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true }
        Thread.sleep(1000)
    }

    @Test fun taskEditorFromAgenda() {
        runBlocking { app.repository.saveTask(PlannerTask(title = "QA gap task", dueDate = LocalDate.now(), checklist = list)) }
        openMain(); await { find("AGENDA") != null }
        click("QA gap task"); await { find("Edit task") != null }
        tapField(::firstField); val title = measure("task-agenda-title")
        toChecklist(); tapField(::checklistField); sameGap(title, measure("task-agenda-checklist"))
    }

    @Test fun taskEditorFromWidget() {
        val task = PlannerTask(title = "QA gap widget", dueDate = LocalDate.now(), checklist = list)
        runBlocking { app.repository.saveTask(task) }
        openMain(com.example.itinerary.widget.TodayWidget.OPEN_TASK) { it.putExtra("task_id", task.id) }
        await { find("Edit task") != null }
        tapField(::firstField); val title = measure("task-widget-title")
        toChecklist(); tapField(::checklistField); sameGap(title, measure("task-widget-checklist"))
    }

    @Test fun eventEditor() {
        runBlocking { app.repository.saveItem(ItineraryItem(tripId = 0, date = LocalDate.now(), startTime = LocalTime.of(23, 0), title = "QA gap event", checklist = list)) }
        openMain(); await { find("AGENDA") != null }
        click("QA gap event"); Thread.sleep(800); if (find("Edit event") == null) click("QA gap event")
        await { find("Edit event") != null }
        tapField(::firstField); val title = measure("event-title")
        toChecklist(); tapField(::checklistField); sameGap(title, measure("event-checklist"))
    }

    @Test fun newEvent() {
        openMain(); await { find("AGENDA") != null }
        click("Add menu"); click("Add event"); await { find("New event") != null }
        tapField(::firstField); assertTrue(measure("event-new-title").second in 0..60)
    }
}
