package com.example.itinerary

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.example.itinerary.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters

/**
 * The README's screenshots, in the test app ("Planner test", its own empty data) with made-up entries, Matrix Green, light.
 * A harness step: not in normal runs. Run it on its own (TESTING.md, without notAnnotation), then pull
 * cache/readme/<name>.png with run-as io.github.baslawson.planner.uitest into docs/images.
 */
@HarnessStage
@Suppress("DEPRECATION")
class ReadmeScreenshots {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = ins.targetContext.applicationContext as ItineraryApp

    private fun nodes(): List<AccessibilityNodeInfo> {
        val out = mutableListOf<AccessibilityNodeInfo>()
        fun walk(n: AccessibilityNodeInfo?) { if (n == null) return; out += n; repeat(n.childCount) { walk(n.getChild(it)) } }
        walk(ins.uiAutomation.freshRoot)
        return out
    }
    private fun find(label: String) = nodes().firstOrNull { (!it.isEditable && it.text?.toString() == label) || it.contentDescription?.toString() == label }
    private fun startsWith(prefix: String) = nodes().firstOrNull { it.contentDescription?.toString()?.startsWith(prefix) == true }
    private fun shows(text: String) = nodes().any { it.text?.toString()?.contains(text) == true }
    private fun await(what: String, check: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 20_000
        while (SystemClock.uptimeMillis() < deadline) { if (check()) return; Thread.sleep(50) }
        fail("Timed out: $what; visible: " + nodes().mapNotNull { it.text ?: it.contentDescription }.joinToString(" | "))
    }
    private fun clickNode(node: AccessibilityNodeInfo?, what: String) {
        var n = node
        while (n != null && !n.isClickable) n = n.parent
        assertTrue("Click $what", n?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
    }
    private fun click(label: String) { await(label) { find(label) != null }; clickNode(find(label), label) }
    // A selected tab can't be clicked (it would do nothing): only when it isn't chosen already.
    private fun choose(label: String) { await(label) { find(label) != null }; if (find(label)?.isSelected != true) click(label) }
    private fun type(text: String, box: Int = 0) {
        await("a text box") { nodes().count { it.isEditable } > box }
        nodes().filter { it.isEditable }[box].performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) })
    }
    private fun shot(name: String) {
        Thread.sleep(1200) // animations settle, the keyboard goes
        val dir = java.io.File(ins.targetContext.cacheDir, "readme").apply { mkdirs() }
        ins.uiAutomation.takeScreenshot()?.let { b -> java.io.File(dir, "$name.png").outputStream().use { b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; b.recycle() }
    }
    private fun open(view: String? = null) {
        ins.targetContext.startActivity(Intent(ins.targetContext, MainActivity::class.java).apply {
            if (view != null) { action = "com.example.itinerary.widget.OPEN_DATE"; putExtra("widget_date", view) }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        })
        await("Planner") { find("Add menu") != null }
    }

    @Test fun takeTheReadmeScreenshots() {
        val today = LocalDate.now()
        val tomorrow = today.plusDays(1)
        val weekend = today.with(TemporalAdjusters.next(DayOfWeek.FRIDAY))
        app.settings.setAppTheme(AppTheme.MATRIX)
        app.settings.setThemeMode(ThemeMode.LIGHT)
        app.settings.lastViewCalendar = false
        app.settings.setShowBillsSummary(false)
        runBlocking {
            val repo = app.repository
            repo.replaceAll(DataSnapshot(emptyList(), emptyList(), emptyList(), emptyList())) // the test app's own data only
            fun at(h: Int, m: Int = 0) = LocalTime.of(h, m)
            repo.saveItem(ItineraryItem(tripId = 0, date = today, startTime = at(7, 30), durationMinutes = 45, title = "Morning run", colorIndex = 2))
            repo.saveItem(ItineraryItem(tripId = 0, date = today, startTime = at(12, 30), durationMinutes = 60, title = "Lunch with Alex", location = "Cafe Verde", colorIndex = 4))
            repo.saveItem(ItineraryItem(tripId = 0, date = today, startTime = at(18, 0), durationMinutes = 90, title = "Book club", colorIndex = 5))
            // Tomorrow: a full day for the hour grid, two of them at the same time.
            repo.saveItem(ItineraryItem(tripId = 0, date = tomorrow, startTime = at(9, 0), durationMinutes = 30, title = "Team stand-up", colorIndex = 1))
            repo.saveItem(ItineraryItem(tripId = 0, date = tomorrow, startTime = at(10, 0), durationMinutes = 90, title = "Design review", colorIndex = 3))
            repo.saveItem(ItineraryItem(tripId = 0, date = tomorrow, startTime = at(10, 30), durationMinutes = 45, title = "Call the bank", colorIndex = 0))
            repo.saveItem(ItineraryItem(tripId = 0, date = tomorrow, startTime = at(13, 0), durationMinutes = 60, title = "Dentist", location = "Smile Clinic", colorIndex = 6))
            repo.saveItem(ItineraryItem(tripId = 0, date = tomorrow, startTime = at(16, 0), durationMinutes = 60, title = "Piano lesson", colorIndex = 2))
            repo.saveItem(ItineraryItem(tripId = 0, date = tomorrow, startTime = null, title = "Pay rent", category = "Bills", billAmountMinor = 185000))
            // Days on end: one bar across them in the full month.
            repo.saveItem(ItineraryItem(tripId = 0, date = weekend, endDate = weekend.plusDays(2), startTime = null, title = "Beach weekend", colorIndex = 4))
            repo.saveItem(ItineraryItem(tripId = 0, date = weekend.plusDays(4), endDate = weekend.plusDays(6), startTime = null, title = "Conference", colorIndex = 3))
            repo.saveItem(ItineraryItem(tripId = 0, date = today.plusDays(3), startTime = at(8, 30), durationMinutes = 60, title = "Car service", colorIndex = 1))
            repo.saveItem(ItineraryItem(tripId = 0, date = today.plusDays(9), startTime = at(19, 0), durationMinutes = 120, title = "Concert", colorIndex = 5))
            repo.saveTask(PlannerTask(title = "Buy a birthday card", dueDate = today))
            repo.saveTask(PlannerTask(title = "Renew passport", dueDate = today.plusDays(2), priority = TaskPriority.HIGH))
            repo.saveTask(PlannerTask(title = "Call the plumber", dueDate = tomorrow))
            repo.saveTask(PlannerTask(title = "Plan the holiday"))
        }

        // Agenda.
        open()
        find("Remind me in a week")?.let { clickNode(it, "Remind me in a week") }
        nodes().firstOrNull { it.text?.toString()?.let { t -> t.startsWith("Anytime tasks") && t.endsWith("Collapse") } == true }?.let { clickNode(it, "Collapse") }
        await("agenda") { shows("Book club") || shows("Team stand-up") }
        shot("agenda")

        // The full month: the days' entries, a bar across the weekend.
        click("Switch to Calendar view")
        choose("Calendar: full screen")
        await("full month") { nodes().any { it.contentDescription?.toString()?.contains("Beach weekend") == true } }
        shot("calendar")

        // Tomorrow on the hour grid.
        choose("Calendar: month")
        val label = tomorrow.format(java.time.format.DateTimeFormatter.ofPattern("EEEE d MMMM yyyy"))
        await("tomorrow's cell") { startsWith(label) != null }
        clickNode(startsWith(label), "tomorrow")
        choose("Hours")
        await("hours") { nodes().any { it.contentDescription?.toString()?.endsWith(", Design review") == true } }
        shot("day-hours")
        choose("List")
        click("Switch to Agenda view")

        // Quick entry with its preview.
        click("Add menu")
        click("Quick entry")
        type("Lunch with Sam at Cafe Verde")
        type("Friday 12:30pm for 1 hour", box = 1)
        Thread.sleep(800)
        hideQuickTestKeyboard(ins)
        await("preview") { nodes().count { it.text?.toString()?.contains("Lunch with Sam") == true } >= 2 }
        shot("quick-entry")
        ins.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        Thread.sleep(500)
        find("Close")?.let { clickNode(it, "Close") }
        find("Discard")?.let { clickNode(it, "Discard") }

        // Find free time.
        open()
        click("More options")
        click("Find free time")
        click("Find gaps")
        await("gaps") { nodes().any { it.text?.toString()?.contains(" · ") == true && it.text?.toString()?.contains("–") == true } }
        shot("find-free-time")
    }
}
