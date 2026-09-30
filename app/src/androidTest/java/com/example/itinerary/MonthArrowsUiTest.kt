package com.example.itinerary

import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.example.itinerary.ui.label
import org.junit.Assert.*
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

/**
 * Fourth bug hunt D5: two quick taps on "Next month" move two months, and the month grid ends snapped on that month
 * (a second tap late in the first page animation used to be undone, leaving the pager between two months).
 * Needs window animations on (the pager animation is what is being raced). Requires external backup/restore of the
 * shared emulator's app data (opening the Calendar from the widget intent remembers the Calendar view).
 */
@Suppress("DEPRECATION")
class MonthArrowsUiTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private val start = LocalDate.of(2036, 4, 12)

    private fun nodes(): List<AccessibilityNodeInfo> {
        val out = mutableListOf<AccessibilityNodeInfo>()
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null) return
            out += n
            repeat(n.childCount) { walk(n.getChild(it)) }
        }
        walk(ins.uiAutomation.freshRoot)
        return out
    }
    private fun find(label: String) = nodes().firstOrNull {
        (!it.isEditable && it.text?.toString() == label) || it.contentDescription?.toString() == label
    }
    private fun await(what: String, check: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 20_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (check()) return
            Thread.sleep(20)
        }
        fail("Timed out: $what; visible text: " + nodes().mapNotNull { it.text }.joinToString(" | "))
    }
    private fun click(label: String) {
        await(label) { find(label) != null }
        var node = find(label)
        while (node != null && !node.isClickable) node = node.parent
        assertTrue("Click $label", node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
    }
    private fun openCalendar() {
        ins.targetContext.startActivity(Intent(ins.targetContext, MainActivity::class.java).apply {
            action = "com.example.itinerary.widget.OPEN_DATE"
            putExtra("widget_date", start.toString())
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        })
        await("Calendar") { find("CALENDAR") != null && find("AGENDA") == null }
        Thread.sleep(900)
        if (find("Full month") != null) click("Full month")
        await("full month") { find("Next month") != null }
        Thread.sleep(900)
    }
    private fun visible(text: String) = nodes().filter { it.isVisibleToUser && !it.isEditable && it.text?.toString() == text }

    // Snapped: the month's day 1 is the only visible "1", and it sits in the grid's first row, right under the weekday names.
    private fun assertSnapped(month: YearMonth) {
        val names = DayOfWeek.values().map { it.getDisplayName(TextStyle.SHORT, Locale.getDefault()) }.toSet()
        val weekdays = nodes().filter { it.isVisibleToUser && it.text?.toString() in names }
        assertTrue("weekday names", weekdays.isNotEmpty())
        val headerBottom = weekdays.maxOf { Rect().also(it::getBoundsInScreen).bottom }
        val ones = visible("1")
        assertEquals("one visible day 1 in $month", 1, ones.size)
        val top = Rect().also(ones.single()::getBoundsInScreen).top
        val halfRow = (24 * ins.targetContext.resources.displayMetrics.density).toInt()
        assertTrue("day 1 of $month in the first row (top $top, weekdays end $headerBottom)", top in headerBottom..headerBottom + halfRow)
    }

    @Test fun twoQuickNextTapsMoveTwoMonthsAndSnap() {
        openCalendar()
        var month = YearMonth.from(start)
        await("start month") { find(month.label()) != null }
        for (gap in listOf(150L, 200L, 250L, 350L)) {
            click("Next month")
            Thread.sleep(gap)
            click("Next month")
            month = month.plusMonths(2)
            val expected = month
            await("header $expected after taps $gap ms apart") { find(expected.label()) != null }
            // Long enough for both page animations; the month must not revert meanwhile.
            Thread.sleep(1500)
            assertNotNull("header still $expected after taps $gap ms apart", find(expected.label()))
            assertSnapped(expected)
        }
    }
}
