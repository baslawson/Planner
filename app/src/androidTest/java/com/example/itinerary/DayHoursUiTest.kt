package com.example.itinerary

import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.example.itinerary.data.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/** The Calendar's day on an hour grid: List | Hours, events as blocks, a tap on an empty hour for a new event. */
@Suppress("DEPRECATION")
class DayHoursUiTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = ins.targetContext.applicationContext as ItineraryApp
    private val day = LocalDate.of(2036, 4, 15)

    private fun nodes(): List<AccessibilityNodeInfo> {
        val out = mutableListOf<AccessibilityNodeInfo>()
        fun walk(n: AccessibilityNodeInfo?) { if (n == null) return; out += n; repeat(n.childCount) { walk(n.getChild(it)) } }
        walk(ins.uiAutomation.freshRoot)
        return out
    }
    private fun find(label: String) = nodes().firstOrNull { (!it.isEditable && it.text?.toString() == label) || it.contentDescription?.toString() == label }
    private fun described(suffix: String) = nodes().firstOrNull { it.contentDescription?.toString()?.endsWith(suffix) == true }
    private fun shows(text: String) = nodes().any { it.text?.toString() == text }
    // A chip's on state is on the clickable node around its text, as selected or checked.
    private fun on(label: String): Boolean { var n = find(label); while (n != null && !n.isClickable) n = n.parent; return n?.isSelected == true || n?.isChecked == true }
    private fun await(what: String, check: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 20_000
        while (SystemClock.uptimeMillis() < deadline) { if (check()) return; Thread.sleep(50) }
        fail("Timed out: $what; visible: " + nodes().mapNotNull { it.text ?: it.contentDescription }.joinToString(" | "))
    }
    private fun click(label: String) {
        await(label) { find(label) != null }
        var n = find(label)
        while (n != null && !n.isClickable) n = n.parent
        assertTrue("Click $label", n?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
    }
    private fun shot(name: String) {
        val dir = java.io.File(ins.targetContext.cacheDir, "qa-hours").apply { mkdirs() }
        ins.uiAutomation.takeScreenshot()?.let { b -> java.io.File(dir, "$name.png").outputStream().use { b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; b.recycle() }
    }

    @After fun tearDown() { app.settings.setDayHours(false) }

    @Test fun theDayOnAnHourGrid() {
        runBlocking {
            app.repository.saveItem(ItineraryItem(tripId = 0, date = day, startTime = LocalTime.of(9, 0), durationMinutes = 60, title = "Dentist"))
            app.repository.saveItem(ItineraryItem(tripId = 0, date = day, startTime = LocalTime.of(9, 30), durationMinutes = 90, title = "Call"))
            app.repository.saveItem(ItineraryItem(tripId = 0, date = day, startTime = null, title = "Market"))
            app.repository.saveTask(PlannerTask(title = "Tax return", dueDate = day))
        }
        ins.targetContext.startActivity(Intent(ins.targetContext, MainActivity::class.java).apply {
            action = "com.example.itinerary.widget.OPEN_DATE"
            putExtra("widget_date", day.toString())
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        })
        await("Calendar") { find("CALENDAR") != null && find("AGENDA") == null }
        if (find("Calendar: month")?.isSelected != true) click("Calendar: month")
        await("the day's list") { shows("Dentist") && on("List") && !on("Hours") }
        click("Hours")
        await("hours") { described(", Dentist") != null }
        assertTrue(app.settings.dayHours.value)
        // Overlapping, so side by side; the all-day event and the task above the grid.
        val dentist = android.graphics.Rect().also { described(", Dentist")!!.getBoundsInScreen(it) }
        val call = android.graphics.Rect().also { described(", Call")!!.getBoundsInScreen(it) }
        assertTrue("side by side ($dentist, $call)", dentist.right <= call.left + 4 && call.top > dentist.top && call.bottom > dentist.bottom)
        assertTrue(shows("Market") && shows("☐ Tax return"))
        shot("hours")

        // A tap on an event opens it.
        click(described(", Dentist")!!.contentDescription.toString())
        await("editor") { find("Edit event") != null }
        ins.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        await("back on the grid") { described(", Dentist") != null && find("Edit event") == null }

        // A tap on an empty hour: a new event at that hour (an hour below the call's end is free).
        val r = android.graphics.Rect().also { described(", Call")!!.getBoundsInScreen(it) }
        val hour = (r.bottom - r.top) * 2 / 3 // the call is 1.5 hours tall
        ins.uiAutomation.executeShellCommand("input tap ${r.centerX()} ${r.bottom + hour}").close()
        await("new event") { find("New event") != null }
        ins.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)

        click("List")
        await("list again") { shows("Dentist") && described(", Dentist") == null }
        assertFalse(app.settings.dayHours.value)
    }
}
