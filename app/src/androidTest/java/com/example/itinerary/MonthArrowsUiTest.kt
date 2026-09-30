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
    private fun assertSnapped(month: YearMonth) {
        shot("month-$month")
        val drift = dayOneDrift(month)
        assertTrue("day 1 of $month centred under its weekday (drift $drift px, resting ${restingDrift} px)", kotlin.math.abs(drift - restingDrift) <= 3)
    }
    @Test fun twoQuickNextTapsMoveTwoMonthsAndSnap() {
        openCalendar()
        var month = YearMonth.from(start)
        await("start month") { find(month.label()) != null }
        shot("start-$month"); restingDrift = dayOneDrift(month)
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
        restingDrift = dayOneDrift(month)
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
            val drift = dayOneDrift(expected)
            assertTrue("day 1 of $expected centred after touches $gap ms apart (drift $drift px)", kotlin.math.abs(drift - restingDrift) <= 3)
        }
    }
}
