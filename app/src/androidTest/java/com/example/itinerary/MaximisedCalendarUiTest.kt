package com.example.itinerary

import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.example.itinerary.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * The Calendar's month can be maximised: it fills the screen, the day's list goes, and each day shows its events, bills
 * and tasks. A tap on a day goes back to the full month with that day's list.
 */
@Suppress("DEPRECATION")
class MaximisedCalendarUiTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = ins.targetContext.applicationContext as ItineraryApp
    private val start = LocalDate.of(2036, 4, 12)
    private val busy = LocalDate.of(2036, 4, 15)

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
    // Text on screen (a day cell's description is the date too, so headers are looked for by text only).
    private fun shows(text: String) = nodes().any { it.text?.toString() == text }
    private fun described(prefix: String) = nodes().firstOrNull { it.contentDescription?.toString()?.startsWith(prefix) == true }
    private fun await(what: String, check: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 20_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (check()) return
            Thread.sleep(50)
        }
        fail("Timed out: $what; visible: " + nodes().mapNotNull { it.text ?: it.contentDescription }.joinToString(" | "))
    }
    private fun clickNode(node: AccessibilityNodeInfo?, what: String) {
        var n = node
        while (n != null && !n.isClickable) n = n.parent
        assertTrue("Click $what", n?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
    }
    private fun click(label: String) {
        await(label) { find(label) != null }
        clickNode(find(label), label)
    }
    private fun shot(name: String) {
        val dir = java.io.File(ins.targetContext.cacheDir, "qa-maximised").apply { mkdirs() }
        ins.uiAutomation.takeScreenshot()?.let { b -> java.io.File(dir, "$name.png").outputStream().use { b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; b.recycle() }
    }

    @Test fun maximisedMonthShowsEachDaysEntriesAndATapOpensTheDay() {
        runBlocking {
            app.repository.saveItem(ItineraryItem(tripId = 0, date = busy, startTime = LocalTime.of(9, 0), title = "Dentist"))
            app.repository.saveItem(ItineraryItem(tripId = 0, date = busy, startTime = null, title = "Rent", category = "Bills", billAmountMinor = 50000))
            app.repository.saveTask(PlannerTask(title = "Tax return", dueDate = busy, priority = TaskPriority.HIGH))
            app.repository.saveTask(PlannerTask(title = "Already done", dueDate = busy, done = true))
            for (h in 8..15) app.repository.saveItem(ItineraryItem(tripId = 0, date = busy.plusDays(1), startTime = LocalTime.of(h, 0), title = "Meeting $h"))
        }
        ins.targetContext.startActivity(Intent(ins.targetContext, MainActivity::class.java).apply {
            action = "com.example.itinerary.widget.OPEN_DATE"
            putExtra("widget_date", start.toString())
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        })
        await("Calendar") { find("CALENDAR") != null && find("AGENDA") == null }
        // A selected tab isn't clickable (tapping it would do nothing), so only when it isn't the size already.
        if (find("Calendar: month")?.isSelected != true) click("Calendar: month")
        await("month chosen") { find("Calendar: month")?.isSelected == true }
        // Not maximised yet: the day's list is there and the busy day is a plain day with a dot.
        assertTrue(shows("Saturday 12 April 2036"))
        assertEquals("Tuesday 15 April 2036, has entries", described("Tuesday 15 April 2036")?.contentDescription?.toString())

        click("Calendar: full screen")
        await("maximised") { described("Tuesday 15 April 2036, 3 entries") != null }
        shot("maximised")
        // Events in the Calendar's order (all-day first), then the task; the done task is left out.
        assertEquals("Tuesday 15 April 2036, 3 entries: Rent, Dentist, Tax return",
            described("Tuesday 15 April 2036")?.contentDescription?.toString())
        assertTrue(described("Wednesday 16 April 2036")!!.contentDescription.toString().startsWith("Wednesday 16 April 2036, 8 entries: Meeting 8"))
        assertFalse("the day's list is hidden", shows("Saturday 12 April 2036"))
        assertTrue(find("Calendar: full screen")!!.isSelected)
        assertTrue(app.settings.calendarMaximised.value)

        // A tap on a day opens it: back to the month, that day selected, its list under the grid.
        clickNode(described("Tuesday 15 April 2036"), "15 April")
        await("the day's list") { shows("Tuesday 15 April 2036") && find("Calendar: month")?.isSelected == true }
        await("its entries listed") { shows("Dentist") }
        assertFalse(app.settings.calendarMaximised.value)
        shot("opened-day")

        // A swipe on the bar: down is one size bigger, up one size smaller.
        fun swipeBar(down: Boolean) {
            val r = android.graphics.Rect().also { find("Calendar: month")!!.getBoundsInScreen(it) }
            val y2 = if (down) r.centerY() + 300 else r.centerY() - 300
            ins.uiAutomation.executeShellCommand("input swipe ${r.left - 40} ${r.centerY()} ${r.left - 40} $y2 300").close()
        }
        swipeBar(down = true)
        await("swiped to full") { app.settings.calendarMaximised.value && find("Calendar: full screen")?.isSelected == true }
        swipeBar(down = false)
        await("swiped back to month") { !app.settings.calendarMaximised.value && find("Calendar: month")?.isSelected == true }
        swipeBar(down = false)
        await("swiped to week") { app.settings.calendarCollapsed.value && find("Calendar: week")?.isSelected == true }
        // Straight from the week to the full screen in one tap.
        click("Calendar: full screen")
        await("full again") { app.settings.calendarMaximised.value && !app.settings.calendarCollapsed.value }
        // A selected tab isn't clickable (tapping it would do nothing), so only when it isn't the size already.
        if (find("Calendar: month")?.isSelected != true) click("Calendar: month")
        await("month again") { find("Calendar: month")?.isSelected == true }
        assertFalse(app.settings.calendarCollapsed.value)
        assertFalse(app.settings.calendarMaximised.value)

        // The arrows at the left end do what the swipe does, and are greyed out at the smallest and biggest size.
        // The button itself carries the label (IconButton merges its icon), so its own enabled state is the one read.
        fun arrow(label: String) = nodes().firstOrNull { it.contentDescription?.toString() == label }
        click("Smaller calendar")
        await("arrow to week") { app.settings.calendarCollapsed.value && find("Calendar: week")?.isSelected == true }
        await("up greyed at week") { arrow("Smaller calendar")?.isEnabled == false && arrow("Bigger calendar")?.isEnabled == true }
        click("Bigger calendar")
        await("arrow to month") { !app.settings.calendarCollapsed.value && find("Calendar: month")?.isSelected == true }
        click("Bigger calendar")
        await("arrow to full") { app.settings.calendarMaximised.value && find("Calendar: full screen")?.isSelected == true }
        await("down greyed at full") { arrow("Bigger calendar")?.isEnabled == false && arrow("Smaller calendar")?.isEnabled == true }
        shot("arrows-full")
        click("Smaller calendar")
        await("arrow back to month") { !app.settings.calendarMaximised.value && find("Calendar: month")?.isSelected == true }
        shot("arrows-month")
    }

    // An event on several days is one bar per week row (drawn over the cells, hidden from TalkBack): each day it covers
    // still names it, and the full month still opens. The bars themselves are checked by eye in the "bars" screenshot.
    @Test fun eventsOnSeveralDaysAreBarsAndEachDayStillNamesThem() {
        runBlocking {
            app.repository.saveItem(ItineraryItem(tripId = 0, date = busy.minusDays(1), endDate = busy.plusDays(8), startTime = null, title = "Holiday"))
            app.repository.saveItem(ItineraryItem(tripId = 0, date = busy, startTime = LocalTime.of(22, 0), durationMinutes = 180, title = "Party"))
            app.repository.saveItem(ItineraryItem(tripId = 0, date = busy.plusDays(1), endDate = busy.plusDays(2), startTime = null, title = "Course"))
            app.repository.saveItem(ItineraryItem(tripId = 0, date = busy.plusDays(1), startTime = LocalTime.of(9, 0), title = "Dentist"))
            app.repository.saveTask(PlannerTask(title = "Tax return", dueDate = busy.plusDays(2)))
        }
        ins.targetContext.startActivity(Intent(ins.targetContext, MainActivity::class.java).apply {
            action = "com.example.itinerary.widget.OPEN_DATE"
            putExtra("widget_date", start.toString())
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        })
        await("Calendar") { find("CALENDAR") != null && find("AGENDA") == null }
        click("Calendar: full screen")
        await("maximised") { described("Tuesday 15 April 2036, 2 entries") != null }
        // The holiday on every day it covers, the party on the morning after too, in the Calendar's order.
        assertEquals("Monday 14 April 2036, 1 entry: Holiday", described("Monday 14 April 2036")?.contentDescription?.toString())
        assertEquals("Tuesday 15 April 2036, 2 entries: Holiday, Party", described("Tuesday 15 April 2036")?.contentDescription?.toString())
        assertEquals("Wednesday 16 April 2036, 4 entries: Holiday, Course, Party, Dentist", described("Wednesday 16 April 2036")?.contentDescription?.toString())
        assertEquals("Wednesday 23 April 2036, 1 entry: Holiday", described("Wednesday 23 April 2036")?.contentDescription?.toString())
        assertEquals("Thursday 24 April 2036", described("Thursday 24 April 2036")?.contentDescription?.toString())
        // The bars are drawn, not separate nodes: no "Holiday" text for TalkBack to read again on each day.
        assertFalse(shows("Holiday"))
        shot("bars")
        // A tap on a bar opens the day under it.
        val r = android.graphics.Rect().also { described("Friday 18 April 2036")!!.getBoundsInScreen(it) }
        ins.uiAutomation.executeShellCommand("input tap ${r.centerX()} ${r.top + r.height() * 2 / 3}").close()
        await("the day's list") { shows("Friday 18 April 2036") && find("Calendar: month")?.isSelected == true }
        await("its entries listed") { shows("Holiday") }
    }

    // Hold an entry and drag it to another day: it moves there (a multi-day event keeps its length), with Undo; the full
    // month stays open, as the drag isn't a tap on the day.
    @Test fun holdingAnEntryAndDraggingItMovesItToAnotherDay() {
        val courseId = runBlocking {
            app.repository.saveItem(ItineraryItem(tripId = 0, date = busy, startTime = LocalTime.of(9, 0), title = "Dentist"))
            app.repository.saveTask(PlannerTask(id = "tax", title = "Tax return", dueDate = busy.plusDays(1)))
            app.repository.saveItem(ItineraryItem(tripId = 0, date = busy.plusDays(7), endDate = busy.plusDays(8), startTime = null, title = "Course"))
            app.repository.snapshot().items.single { it.title == "Course" }.id
        }
        ins.targetContext.startActivity(Intent(ins.targetContext, MainActivity::class.java).apply {
            action = "com.example.itinerary.widget.OPEN_DATE"
            putExtra("widget_date", start.toString())
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        })
        await("Calendar") { find("CALENDAR") != null && find("AGENDA") == null }
        click("Calendar: full screen")
        await("maximised") { described("Tuesday 15 April 2036, 1 entry") != null }
        val density = ins.targetContext.resources.displayMetrics.density
        // The first entry line of a day: under the 26 dp number, half way down the 14 dp line.
        fun entry(day: String) = android.graphics.Rect().also { described(day)!!.getBoundsInScreen(it) }.let { it.centerX() to it.top + (37 * density).toInt() }
        fun cell(day: String) = android.graphics.Rect().also { described(day)!!.getBoundsInScreen(it) }.let { it.centerX() to it.centerY() }
        fun drag(from: Pair<Int, Int>, to: Pair<Int, Int>) =
            ins.uiAutomation.executeShellCommand("input draganddrop ${from.first} ${from.second} ${to.first} ${to.second} 2000").close()

        drag(entry("Tuesday 15 April 2036"), cell("Friday 18 April 2036"))
        await("dentist moved") { described("Friday 18 April 2036, 1 entry: Dentist") != null }
        assertEquals(busy.plusDays(3), runBlocking { app.repository.snapshot() }.items.single { it.title == "Dentist" }.date)
        assertTrue("still full screen", app.settings.calendarMaximised.value)
        await("Undo offered") { shows("Moved Dentist to Friday 18 April") }
        shot("dragged")
        click("Undo")
        await("dentist back") { runBlocking { app.repository.snapshot() }.items.single { it.title == "Dentist" }.date == busy }

        // A task, a day back.
        drag(entry("Wednesday 16 April 2036"), cell("Monday 14 April 2036"))
        await("task moved") { runBlocking { app.repository.snapshot() }.tasks.single { it.id == "tax" }.dueDate == busy.minusDays(1) }

        // The course held on its second day and dropped a day on: both its days move one day.
        drag(entry("Wednesday 23 April 2036"), cell("Thursday 24 April 2036"))
        await("course moved") { runBlocking { app.repository.snapshot() }.items.single { it.id == courseId }.date == busy.plusDays(8) }
        assertEquals(busy.plusDays(9), runBlocking { app.repository.snapshot() }.items.single { it.id == courseId }.endDate)
    }

    // The day's list has the tasks due that day under its events: ticking one leaves it there crossed out, and a tap
    // opens the task editor.
    @Test fun tasksDueThatDayAreInTheDaysList() {
        runBlocking {
            app.repository.saveItem(ItineraryItem(tripId = 0, date = busy, startTime = LocalTime.of(9, 0), title = "Dentist"))
            app.repository.saveTask(PlannerTask(id = "tax", title = "Tax return", dueDate = busy, priority = TaskPriority.HIGH))
            app.repository.saveTask(PlannerTask(title = "Already done", dueDate = busy, done = true))
            app.repository.saveTask(PlannerTask(title = "Next day's task", dueDate = busy.plusDays(1)))
        }
        ins.targetContext.startActivity(Intent(ins.targetContext, MainActivity::class.java).apply {
            action = "com.example.itinerary.widget.OPEN_DATE"
            putExtra("widget_date", busy.toString())
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        })
        await("Calendar") { find("CALENDAR") != null && find("AGENDA") == null }
        await("the day's tasks") { shows("Tuesday 15 April 2036") && shows("Tax return") && shows("Already done") }
        assertFalse("another day's task isn't listed", shows("Next day's task"))
        assertFalse(shows("Nothing planned for this day. Tap + to add something."))
        fun top(text: String) = android.graphics.Rect().also { r -> nodes().first { it.text?.toString() == text }.getBoundsInScreen(r) }.top
        assertTrue("event, then the task to do, then the done one", top("Dentist") < top("Tax return") && top("Tax return") < top("Already done"))
        // The next day has a dot for its task.
        assertEquals("Wednesday 16 April 2036, has entries", described("Wednesday 16 April 2036")?.contentDescription?.toString())

        click("Mark Tax return done")
        await("ticked") { find("Mark Tax return incomplete") != null }
        assertTrue("still listed once done", shows("Tax return"))
        assertTrue(runBlocking { app.repository.snapshot() }.tasks.single { it.id == "tax" }.done)
        shot("day-tasks")

        click("Tax return")
        await("task editor") { find("Edit task") != null }
    }

    // A sideways swipe on the grid changes the month (finger moving left: next, right: previous), and on the week strip
    // the week. Full screen, the size bar sits at the top, clear of the + button.
    @Test fun sidewaysSwipesChangeTheMonthAndTheWeek() {
        ins.targetContext.startActivity(Intent(ins.targetContext, MainActivity::class.java).apply {
            action = "com.example.itinerary.widget.OPEN_DATE"
            putExtra("widget_date", start.toString())
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        })
        await("Calendar") { find("CALENDAR") != null && find("AGENDA") == null }
        if (find("Calendar: month")?.isSelected != true) click("Calendar: month")
        await("April") { shows("April 2036") }
        // A finger moving left is the next month (and week), as in other Android calendars.
        fun swipe(rightwards: Boolean) {
            val r = android.graphics.Rect().also { nodes().first { n -> n.text?.toString() == "12" }.getBoundsInScreen(it) }
            val (from, to) = if (rightwards) 150 to 900 else 900 to 150
            ins.uiAutomation.executeShellCommand("input swipe $from ${r.centerY()} $to ${r.centerY()} 250").close()
        }
        swipe(rightwards = false)
        await("next month after a swipe left") { shows("May 2036") }
        swipe(rightwards = true)
        await("back to April after a swipe right") { shows("April 2036") }
        swipe(rightwards = true)
        await("previous month after a swipe right") { shows("March 2036") }

        // Full screen: the bar is above the month title.
        click("Calendar: full screen")
        await("full") { find("Calendar: full screen")?.isSelected == true }
        val bar = android.graphics.Rect().also { find("Calendar: full screen")!!.getBoundsInScreen(it) }
        val title = android.graphics.Rect().also { nodes().first { n -> n.text?.toString() == "March 2036" }.getBoundsInScreen(it) }
        assertTrue("bar above the title ($bar, $title)", bar.bottom <= title.top)
        shot("full-bar-top")

        // The week strip: a swipe left is the next week.
        click("Calendar: week")
        // The week of 12 April starts on the phone's first day of the week (Sunday 6 or Monday 7 April); the next week ends
        // 13 days after that start.
        val weekStart = LocalDate.of(2036, 4, 12).with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.temporal.WeekFields.of(java.util.Locale.getDefault()).firstDayOfWeek))
        val first = weekStart.dayOfMonth.toString(); val nextLast = weekStart.plusDays(13).dayOfMonth.toString()
        await("week of 12 April") { app.settings.calendarCollapsed.value && shows(first) && !shows(nextLast) }
        swipe(rightwards = false)
        await("a week on") { shows(nextLast) && !shows(first) }
        click("Calendar: month")
    }
}
