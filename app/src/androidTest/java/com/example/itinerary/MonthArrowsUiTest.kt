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
 * Also: moving between a 5-week and a 6-week month used to leave the grid ~10 dp too high or too low for good — the
 * pager's height changes during the page animation — so every check also measures the first row's height.
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

    private fun bounds(node: AccessibilityNodeInfo) = Rect().also(node::getBoundsInScreen)
    // How far day 1 is, downwards, from the centre of the month title (which never moves): the same in every month when
    // the pager has snapped. Checked first: a grid left too high covers the weekday names, which then drop out of the tree.
    private fun dayOneHeight(month: YearMonth): Int {
        val title = nodes().filter { it.isVisibleToUser && it.text?.toString() == month.label() }
        assertEquals("title $month", 1, title.size)
        val ones = visible("1")
        assertEquals("one visible day 1 in $month", 1, ones.size)
        return bounds(ones.single()).centerY() - bounds(title.single()).centerY()
    }
    private var restingHeight = 0
    // How far day 1 is, sideways, from the centre of its own weekday name: 0 when the pager has snapped to that month.
    private fun dayOneDrift(month: YearMonth): Int {
        val weekday = month.atDay(1).dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
        val name = nodes().filter { it.isVisibleToUser && it.text?.toString() == weekday }
        assertEquals("weekday name $weekday", 1, name.size)
        val ones = visible("1")
        assertEquals("one visible day 1 in $month", 1, ones.size)
        return Rect().also(ones.single()::getBoundsInScreen).centerX() - Rect().also(name.single()::getBoundsInScreen).centerX()
    }
    private var restingDrift = 0
    private fun shot(name: String) {
        val dir = java.io.File(ins.targetContext.cacheDir, "qa-month-arrows").apply { mkdirs() }
        ins.uiAutomation.takeScreenshot()?.let { b -> java.io.File(dir, "$name.png").outputStream().use { b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; b.recycle() }
    }
    private fun rest(month: YearMonth) { restingHeight = dayOneHeight(month); restingDrift = dayOneDrift(month) }
    private fun assertSnapped(month: YearMonth, what: String = "") {
        shot("month-$month")
        val height = dayOneHeight(month)
        assertTrue("first row of $month in place$what (day 1 is $height px below the title, resting $restingHeight px)", kotlin.math.abs(height - restingHeight) <= 3)
        val drift = dayOneDrift(month)
        assertTrue("day 1 of $month centred under its weekday (drift $drift px, resting ${restingDrift} px)", kotlin.math.abs(drift - restingDrift) <= 3)
    }
    @Test fun twoQuickNextTapsMoveTwoMonthsAndSnap() {
        openCalendar()
        var month = YearMonth.from(start)
        await("start month") { find(month.label()) != null }
        shot("start-$month"); rest(month)
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

    private fun tapAt(x: Float, y: Float) {
        val down = SystemClock.uptimeMillis()
        fun event(action: Int, t: Long) = android.view.MotionEvent.obtain(down, t, action, x, y, 0).apply { source = android.view.InputDevice.SOURCE_TOUCHSCREEN }
        ins.uiAutomation.injectInputEvent(event(android.view.MotionEvent.ACTION_DOWN, down), true)
        ins.uiAutomation.injectInputEvent(event(android.view.MotionEvent.ACTION_UP, down + 30), true)
    }
    // Real touches, closer together than accessibility clicks allow, so the second lands during the first page animation.
    @Test fun twoFastTouchesOnNextMoveTwoMonthsAndSnap() {
        openCalendar()
        var month = YearMonth.from(start)
        await("start month") { find(month.label()) != null }
        rest(month)
        for (gap in listOf(60L, 90L, 120L, 150L, 180L)) {
            await("Next month") { find("Next month") != null }
            var node = find("Next month")
            while (node != null && !node.isClickable) node = node.parent
            val r = Rect().also(node!!::getBoundsInScreen)
            tapAt(r.exactCenterX(), r.exactCenterY())
            Thread.sleep(gap)
            tapAt(r.exactCenterX(), r.exactCenterY())
            month = month.plusMonths(2)
            val expected = month
            Thread.sleep(1500)
            shot("touch-$gap-$expected")
            assertNotNull("header $expected after touches $gap ms apart (shows: ${nodes().mapNotNull { it.text }.firstOrNull { t -> t.contains(" 20") }})", find(expected.label()))
            assertSnapped(expected, " after touches $gap ms apart")
        }
    }

    // A real upward swipe across the grid (the next month), quick enough to be a fling.
    private fun swipeUp(x: Float, fromY: Float, toY: Float) {
        val down = SystemClock.uptimeMillis()
        fun event(action: Int, t: Long, y: Float) = android.view.MotionEvent.obtain(down, t, action, x, y, 0).apply { source = android.view.InputDevice.SOURCE_TOUCHSCREEN }
        ins.uiAutomation.injectInputEvent(event(android.view.MotionEvent.ACTION_DOWN, down, fromY), true)
        for (step in 1..8) ins.uiAutomation.injectInputEvent(event(android.view.MotionEvent.ACTION_MOVE, down + step * 12L, fromY + (toY - fromY) * step / 8), true)
        ins.uiAutomation.injectInputEvent(event(android.view.MotionEvent.ACTION_UP, down + 110, toY), true)
    }
    // AG-1: a swipe right after an arrow's move has landed, while the grid is still changing height, moves on one more
    // month and the header follows it (it used to stay on the arrow's month for good, or the grid was pulled back).
    @Test fun aSwipeRightAfterAnArrowTapMovesOnAndTheHeaderFollows() {
        openCalendar()
        var month = YearMonth.from(start)
        await("start month") { find(month.label()) != null }
        rest(month)
        repeat(5) {
            click("Next month")
            val arrow = month.plusMonths(1)
            await("header $arrow") { find(arrow.label()) != null }
            val day = Rect().also(visible("15").single()::getBoundsInScreen)
            swipeUp(day.exactCenterX(), day.exactCenterY() + day.height(), day.exactCenterY() - 2 * day.height())
            month = month.plusMonths(2)
            val expected = month
            await("header $expected after a swipe right after the arrow") { find(expected.label()) != null }
            Thread.sleep(1500)
            assertNotNull("header still $expected", find(expected.label()))
            assertSnapped(expected, " after an arrow then a swipe")
        }
    }

    // One tap at a time from April 2036 (5 weeks) through August and November (6 weeks) and back: each month in place.
    @Test fun singleTapsBetweenFiveAndSixWeekMonthsKeepTheGridInPlace() {
        openCalendar()
        var month = YearMonth.from(start)
        await("start month") { find(month.label()) != null }
        rest(month)
        repeat(9) {
            click("Next month")
            month = month.plusMonths(1)
            val expected = month
            await("header $expected") { find(expected.label()) != null }
            Thread.sleep(1500)
            assertSnapped(expected, " after one tap")
        }
    }
}
