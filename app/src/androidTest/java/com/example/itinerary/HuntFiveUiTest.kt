package com.example.itinerary

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.example.itinerary.data.PlannerTask
import com.example.itinerary.ui.label
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.YearMonth

/**
 * Fifth bug hunt.
 * E1: the Calendar opens on any month from the year 1 to 9999 (1895 crashed it, and every start after), the arrows stop
 * at the ends, and a saved month outside that range is replaced by this month.
 * E4: a second task tapped on the widget while one is open closes the first like Close ("Save changes?" when it has
 * unsaved changes; Keep editing stays on it) and then opens the second, never mixing the two.
 * Run only with an external backup/restore harness for the shared emulator (it adds tasks and changes the saved view).
 */
@Suppress("DEPRECATION")
class HuntFiveUiTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = ins.targetContext
    private val app get() = context.applicationContext as ItineraryApp
    private fun data() = runBlocking { app.repository.snapshot() }
    private fun nodes(): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(n: AccessibilityNodeInfo) { result += n; for (i in 0 until n.childCount) n.getChild(i)?.let(::visit) }
        ins.uiAutomation.freshRoot?.let(::visit); return result
    }
    private fun find(text: String) = nodes().firstOrNull { it.isVisibleToUser && !it.isEditable && (it.text?.toString() == text || it.contentDescription?.toString() == text) }
    private fun screenshot(name: String) {
        val dir = File(context.cacheDir, "qa-hunt-five").apply { mkdirs() }
        ins.uiAutomation.takeScreenshot()?.let { b -> File(dir, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }; b.recycle() }
    }
    private fun await(timeout: Long = 30000, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < end) { if (condition()) return; Thread.sleep(150) }
        screenshot("failure"); fail("Timed out: " + nodes().mapNotNull { it.text }.joinToString(" | "))
    }
    private fun button(text: String): AccessibilityNodeInfo? {
        var node = find(text)
        while (node != null && !node.isClickable) node = node.parent
        return node
    }
    private fun click(text: String) {
        await { button(text)?.takeIf { it.isEnabled }?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true }; Thread.sleep(350)
    }
    private fun setText(old: String, value: String) {
        await { pickEditable(nodes(), old) != null }
        assertTrue(pickEditable(nodes(), old)!!.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        })); Thread.sleep(350)
    }

    // ---- E1 ----

    private fun openDate(date: String) {
        context.startActivity(Intent(context, MainActivity::class.java).apply {
            action = "com.example.itinerary.widget.OPEN_DATE"
            putExtra("widget_date", date)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        })
        await { find("CALENDAR") != null && find("AGENDA") == null }
    }
    private fun relaunch() {
        context.startActivity(Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        })
        await { find("CALENDAR") != null && find("AGENDA") == null }
    }
    private fun fullMonth() {
        Thread.sleep(700)
        if (find("Full month") != null) click("Full month")
        await { find("Next month") != null }
    }

    @Test fun calendarOpensOnVeryOldAndFarFutureMonthsAndStartsAgain() {
        for (date in listOf(LocalDate.of(1895, 6, 1), LocalDate.of(2400, 1, 1))) {
            val header = YearMonth.from(date).label()
            openDate(date.toString())
            fullMonth()
            await { find(header) != null }
            screenshot("opened-$date")
            // The saved month is this one now: a normal start must show it again, not crash.
            relaunch()
            await { find(header) != null }
            screenshot("relaunched-$date")
        }
    }

    @Test fun arrowsStopAtTheEndsOfTheRange() {
        openDate("0001-01-15")
        fullMonth()
        await { find(YearMonth.of(1, 1).label()) != null }
        assertFalse(button("Previous month")!!.isEnabled)
        assertTrue(button("Next month")!!.isEnabled)
        openDate("9999-12-15")
        fullMonth()
        await { find(YearMonth.of(9999, 12).label()) != null }
        assertFalse(button("Next month")!!.isEnabled)
        assertTrue(button("Previous month")!!.isEnabled)
        click("Previous month")
        await { find(YearMonth.of(9999, 11).label()) != null }
    }

    @Test fun savedMonthOutsideTheRangeOpensThisMonth() {
        app.settings.lastViewCalendar = true
        app.settings.lastCalendarDate = LocalDate.now()
        app.settings.lastCalendarMonth = YearMonth.of(0, 6)
        relaunch()
        fullMonth()
        await { find(YearMonth.now().label()) != null }
        assertEquals(YearMonth.now(), app.settings.lastCalendarMonth)
    }

    // ---- E4 ----

    private fun seedTask(title: String) = runBlocking {
        PlannerTask(title = title).also { app.repository.saveTask(it) }.id
    }
    private fun openWidgetTask(id: String, fresh: Boolean = false) {
        context.startActivity(Intent(context, MainActivity::class.java).apply {
            action = "com.example.itinerary.widget.OPEN_TASK"
            putExtra("task_id", id)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                if (fresh) Intent.FLAG_ACTIVITY_CLEAR_TASK else Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
        Thread.sleep(500)
    }
    private fun editing(title: String) = find("Edit task") != null && pickEditable(nodes(), title) != null

    @Test fun secondWidgetTaskClosesTheFirstLikeClose() {
        val a = seedTask("QA widget task A")
        val b = seedTask("QA widget task B")
        openWidgetTask(a, fresh = true)
        await { editing("QA widget task A") }
        // Nothing unsaved: the second task simply replaces the first.
        openWidgetTask(b)
        await { editing("QA widget task B") }
        assertNull(find("Save changes?"))
        assertNull(pickEditable(nodes(), "QA widget task A"))
        // Unsaved changes in B: asked first; Keep editing stays on B and forgets A.
        setText("QA widget task B", "QA widget task B edited")
        openWidgetTask(a)
        await { find("Save changes?") != null }
        screenshot("widget-task-save-changes")
        click("Keep editing")
        await { find("Save changes?") == null && editing("QA widget task B edited") }
        Thread.sleep(700)
        assertTrue(editing("QA widget task B edited"))
        // Discard: B is left as it was saved and A opens.
        openWidgetTask(a)
        await { find("Save changes?") != null }
        click("Discard")
        await { editing("QA widget task A") }
        assertEquals("QA widget task B", data().tasks.single { it.id == b }.title)
        // Save: A's edit goes into A only, then B opens with its own title.
        setText("QA widget task A", "QA widget task A saved")
        openWidgetTask(b)
        await { find("Save changes?") != null }
        click("Save")
        await { editing("QA widget task B") }
        assertEquals("QA widget task A saved", data().tasks.single { it.id == a }.title)
        assertEquals("QA widget task B", data().tasks.single { it.id == b }.title)
        screenshot("widget-task-b-after-save")
    }
}
