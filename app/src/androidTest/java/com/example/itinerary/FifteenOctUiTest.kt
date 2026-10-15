package com.example.itinerary

import android.app.NotificationManager
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.example.itinerary.data.*
import com.example.itinerary.reminders.MorningSummary
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * The 15 Oct features on a phone: dragging on the hour grid (moving and stretching), the week on the grid, turning the
 * month while dragging in the full-screen month, Move to… / Due on…, "When to leave" following the travel buffer, and the
 * summaries' buttons.
 */
@Suppress("DEPRECATION")
class FifteenOctUiTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = ins.targetContext.applicationContext as ItineraryApp
    private val day = LocalDate.of(2036, 4, 15)
    private val density get() = ins.targetContext.resources.displayMetrics.density

    private fun nodes(): List<AccessibilityNodeInfo> {
        val out = mutableListOf<AccessibilityNodeInfo>()
        fun walk(n: AccessibilityNodeInfo?) { if (n == null) return; out += n; repeat(n.childCount) { walk(n.getChild(it)) } }
        walk(ins.uiAutomation.freshRoot)
        return out
    }
    private fun find(label: String) = nodes().firstOrNull { (!it.isEditable && it.text?.toString() == label) || it.contentDescription?.toString() == label }
    private fun described(part: String) = nodes().firstOrNull { it.contentDescription?.toString()?.contains(part) == true }
    private fun shows(text: String) = nodes().any { it.text?.toString() == text }
    private fun showsStarting(text: String) = nodes().any { it.text?.toString()?.startsWith(text) == true }
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
    private fun bounds(node: AccessibilityNodeInfo) = Rect().also { node.getBoundsInScreen(it) }
    private fun shot(name: String) {
        val dir = java.io.File(ins.targetContext.cacheDir, "qa-oct15").apply { mkdirs() }
        ins.uiAutomation.takeScreenshot()?.let { b -> java.io.File(dir, "$name.png").outputStream().use { b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; b.recycle() }
    }
    private fun event(title: String) = runBlocking { app.repository.snapshot().items.single { it.title == title } }
    private fun open(date: LocalDate) {
        ins.targetContext.startActivity(Intent(ins.targetContext, MainActivity::class.java).apply {
            action = "com.example.itinerary.widget.OPEN_DATE"
            putExtra("widget_date", date.toString())
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        })
        await("Calendar") { find("CALENDAR") != null && find("AGENDA") == null }
    }
    private fun size(label: String) { if (find(label)?.isSelected != true) click(label) }
    // Touches sent one by one, so a hold is clearly longer than the long-press time before the finger moves (input
    // draganddrop starts moving right at that time, a race with Compose's own timer).
    private var downAt = 0L
    private fun send(action: Int, x: Int, y: Int) {
        val now = SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_DOWN) downAt = now
        val e = MotionEvent.obtain(downAt, now, action, x.toFloat(), y.toFloat(), 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        ins.uiAutomation.injectInputEvent(e, true); e.recycle()
    }
    private fun moveTo(fromX: Int, fromY: Int, x: Int, y: Int, steps: Int = 20) {
        for (i in 1..steps) { send(MotionEvent.ACTION_MOVE, fromX + (x - fromX) * i / steps, fromY + (y - fromY) * i / steps); Thread.sleep(16) }
    }
    private fun hold(x: Int, y: Int, toX: Int, toY: Int) {
        send(MotionEvent.ACTION_DOWN, x, y)
        Thread.sleep(900)
        moveTo(x, y, toX, toY)
        Thread.sleep(200)
        send(MotionEvent.ACTION_UP, toX, toY)
    }

    @After fun tearDown() {
        app.settings.setDayHours(false)
        app.settings.setCalendarCollapsed(false)
        app.settings.setCalendarMaximised(false)
        app.settings.setMorningSummary(false, LocalTime.of(7, 30))
        app.settings.setEveningSummary(false, LocalTime.of(20, 0))
        app.settings.morningSummaryShownOn = null
        app.settings.eveningSummaryShownOn = null
        app.getSystemService(NotificationManager::class.java).cancelAll()
    }

    @Test fun anEventOnTheHourGridIsDraggedToAnotherTimeAndStretched() {
        runBlocking { app.repository.saveItem(ItineraryItem(tripId = 0, date = day, startTime = LocalTime.of(9, 0), durationMinutes = 60, title = "Dentist")) }
        open(day)
        size("Calendar: month")
        click("Hours")
        await("the block") { described(", Dentist") != null }
        val hour = (56 * density).toInt()
        // Held and moved an hour down: 10:00, with the Undo bar.
        val block = bounds(described(", Dentist")!!)
        hold(block.centerX(), block.top + hour / 4, block.centerX(), block.top + hour / 4 + hour)
        await("moved to 10:00") { event("Dentist").startTime == LocalTime.of(10, 0) }
        assertEquals(60, event("Dentist").durationMinutes)
        await("Undo bar") { showsStarting("Moved Dentist to 10") }
        shot("grid-moved")
        // The bar at its bottom dragged half an hour down: 10:00 to 11:30.
        await("the block at 10") { described("Dentist")?.let { bounds(it).top > block.top + hour / 2 } == true }
        val moved = bounds(described(", Dentist")!!)
        val handle = moved.bottom - (6 * density).toInt()
        // The bar is held first, then dragged (bug hunt 29: so a scroll starting on a short event still scrolls).
        hold(moved.centerX(), handle, moved.centerX(), handle + hour / 2)
        await("stretched to 90 minutes") { event("Dentist").durationMinutes == 90 }
        assertEquals(LocalTime.of(10, 0), event("Dentist").startTime)
        await("Undo bar") { showsStarting("Dentist now ends at 11") }
        // Undo puts the length back.
        click("Undo")
        await("undone") { event("Dentist").durationMinutes == 60 }
    }

    @Test fun theWeekOnTheHourGridAndADragToAnotherDay() {
        runBlocking { app.repository.saveItem(ItineraryItem(tripId = 0, date = day, startTime = LocalTime.of(9, 0), durationMinutes = 60, title = "Dentist")) }
        open(day)
        size("Calendar: week")
        click("Hours")
        await("the week's grid") { described(", Dentist") != null }
        // The week's columns: the days of the selected one's week, each headed by its date.
        val weekStart = day.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.temporal.WeekFields.of(java.util.Locale.getDefault()).firstDayOfWeek))
        // Bug hunt 29 S8: the grid's own headings ("Tuesday 15 April", no year; the week strip above says the year).
        (0L until 7L).forEach { d -> val name = weekStart.plusDays(d).format(java.time.format.DateTimeFormatter.ofPattern("EEEE d MMMM"))
            assertTrue("heading $name", nodes().any { it.contentDescription?.toString() == name }) }
        shot("week-grid")
        val block = bounds(described(", Dentist")!!)
        val column = block.width() + (2 * density).toInt()
        val rightwards = if (ins.targetContext.resources.configuration.layoutDirection == android.view.View.LAYOUT_DIRECTION_RTL) -1 else 1
        hold(block.centerX(), block.top + 10, block.centerX() + rightwards * column, block.top + 10)
        await("moved to the next day") { event("Dentist").date == day.plusDays(1) }
        assertEquals(LocalTime.of(9, 0), event("Dentist").startTime)
        // A tap on a day's heading picks that day.
        val next = day.plusDays(1).format(java.time.format.DateTimeFormatter.ofPattern("EEEE d MMMM"))
        clickNode(nodes().first { it.contentDescription?.toString() == next }, "heading")
        // The day's heading above the grid now names it.
        await("picked") { shows("Wednesday 16 April 2036") }
    }

    // Held against the right edge, the full-screen month turns to the next; let go on a day there, the entry goes there.
    @Test fun aDragHeldAtTheEdgeTurnsTheMonth() {
        runBlocking { app.repository.saveItem(ItineraryItem(tripId = 0, date = LocalDate.of(2036, 4, 28), startTime = LocalTime.of(9, 0), title = "Trip prep")) }
        open(LocalDate.of(2036, 4, 28))
        size("Calendar: full screen")
        await("April") { shows("April 2036") && described("Monday 28 April 2036") != null }
        val cell = bounds(described("Monday 28 April 2036")!!)
        val start = cell.centerX() to cell.top + (37 * density).toInt()
        val width = ins.targetContext.resources.displayMetrics.widthPixels
        send(MotionEvent.ACTION_DOWN, start.first, start.second)
        Thread.sleep(900) // the hold
        val edgeX = width - (8 * density).toInt()
        moveTo(start.first, start.second, edgeX, start.second)
        // Held there: the month turns; then straight off the edge (held on, it would turn again), into the middle.
        await("May") { shows("May 2036") }
        moveTo(edgeX, start.second, width / 2, start.second, steps = 5)
        await("May settled") { described("Friday 2 May 2036") != null }
        Thread.sleep(300)
        assertTrue("still May", shows("May 2036"))
        val target = bounds(described("Friday 2 May 2036")!!)
        moveTo(width / 2, start.second, target.centerX(), target.centerY())
        Thread.sleep(300)
        send(MotionEvent.ACTION_UP, target.centerX(), target.centerY())
        await("moved into May") { event("Trip prep").date == LocalDate.of(2036, 5, 2) }
        assertEquals(LocalTime.of(9, 0), event("Trip prep").startTime)
        shot("month-turned")
    }

    // Bug hunt 29 S9: the ⋮ menu's Move to… itself, through the date picker, with the Undo bar.
    @Test fun moveToFromTheMenuPicksADay() {
        runBlocking { app.repository.saveItem(ItineraryItem(tripId = 0, date = day, startTime = LocalTime.of(9, 0), durationMinutes = 60, title = "Dentist")) }
        open(day)
        size("Calendar: month")
        if (find("List")?.let { n -> generateSequence(n) { it.parent }.any { it.isSelected || it.isChecked } } != true) click("List")
        click("Actions for Dentist")
        click("Move to…")
        await("the date picker") { find("Set date") != null }
        // Friday 18 April 2036 in the picker (its day cells say the full date).
        fun label(n: AccessibilityNodeInfo) = "${n.text ?: ""} ${n.contentDescription ?: ""}"
        await("18 April in the picker") { nodes().any { label(it).contains("18") && label(it).contains("April") } || nodes().any { it.text?.toString() == "18" } }
        val cell = nodes().firstOrNull { n -> label(n).contains("18") && label(n).contains("April") }
            ?: nodes().first { it.text?.toString() == "18" }
        clickNode(cell, "18 April")
        click("Set date")
        await("moved to the 18th") { event("Dentist").date == LocalDate.of(2036, 4, 18) }
        assertEquals(LocalTime.of(9, 0), event("Dentist").startTime)
        await("Undo bar") { showsStarting("Moved Dentist to Friday 18 April") }
    }

    // Streaks (bug hunt 29, "a"): the day a task is ticked is kept (bug hunt 35: also through an untick), not passed on to the next
    // occurrence, and survives an editor save.
    @Test fun theDayATaskIsDoneIsKept() = runBlocking {
        val task = PlannerTask(title = "QA gym", dueDate = day, repeat = "WEEKLY")
        app.repository.saveTask(task)
        app.repository.setTaskDone(task.id, true)
        val done = app.repository.snapshot().tasks.single { it.id == task.id }
        assertEquals(LocalDate.now(), done.doneOn)
        val next = app.repository.snapshot().tasks.single { it.id == done.nextTaskId }
        assertNull("the next one isn't done", next.doneOn)
        app.repository.saveTask(done.copy(title = "QA gym (edited)", doneOn = null), create = false)
        assertEquals("an editor save keeps it", LocalDate.now(), app.repository.snapshot().tasks.single { it.id == task.id }.doneOn)
        // Bug hunt 35: kept while unticked, and used again on a re-tick (unticked by mistake, ticked days later: still on time).
        app.repository.setTaskDone(task.id, false)
        app.repository.snapshot().tasks.single { it.id == task.id }.let { assertFalse(it.done); assertEquals(LocalDate.now(), it.doneOn) }
        app.repository.setTaskDone(task.id, true)
        assertEquals(LocalDate.now(), app.repository.snapshot().tasks.single { it.id == task.id }.doneOn)
    }

    @Test fun theDoneDayGoesThroughABackupAndOlderFilesHaveNone() {
        val done = PlannerTask(id = "a", title = "Gym", dueDate = day, done = true, doneOn = day.minusDays(1))
        val open = PlannerTask(id = "b", title = "Gym", dueDate = day.plusDays(7))
        assertEquals(listOf(done, open), TaskCodec.decode(TaskCodec.encode(listOf(done, open))))
        // A file from before has no "doneOn"; one that is wrong, or on a task not done, is dropped rather than refused.
        val old = TaskCodec.encode(listOf(done)).also { it.getJSONObject(0).remove("doneOn") }
        assertNull(TaskCodec.decode(old).single().doneOn)
        val odd = TaskCodec.encode(listOf(done)).also { it.getJSONObject(0).put("doneOn", "yesterday") }
        assertNull(TaskCodec.decode(odd).single().doneOn)
        val notDone = TaskCodec.encode(listOf(open)).also { it.getJSONObject(0).put("doneOn", day.toString()) }
        assertNull(TaskCodec.decode(notDone).single().doneOn)
    }

    // Version 36 (before the done day) upgrades to 37 with every task kept and none with a done day.
    @Test fun version36UpgradesWithNoDoneDays() = runBlocking {
        val base = ins.targetContext
        val name = "done-day-upgrade.db"
        base.deleteDatabase(name)
        try {
            var db = androidx.room.Room.databaseBuilder(base, AppDatabase::class.java, name).build()
            val task = PlannerTask(title = "Kept", dueDate = day, done = true)
            db.taskDao().insert(task); db.close()
            android.database.sqlite.SQLiteDatabase.openDatabase(base.getDatabasePath(name).path, null, 0).use { raw ->
                raw.execSQL("ALTER TABLE tasks DROP COLUMN doneOn")
                raw.execSQL("DELETE FROM room_master_table"); raw.version = 36
            }
            db = androidx.room.Room.databaseBuilder(base, AppDatabase::class.java, name).addMigrations(*ALL_MIGRATIONS).build()
            assertEquals(37, db.openHelper.readableDatabase.version)
            assertEquals(task, db.taskDao().all().single())
            db.taskDao().update(task.copy(doneOn = day))
            db.close()
            db = androidx.room.Room.databaseBuilder(base, AppDatabase::class.java, name).build()
            assertEquals(day, db.taskDao().all().single().doneOn); db.close()
        } finally { base.deleteDatabase(name) }
    }

    @Test fun moveToAndDueOnTakeAnyDayWithUndo() = runBlocking {
        val id = app.repository.saveItemId(ItineraryItem(tripId = 0, date = day, endDate = day.plusDays(2), startTime = null, title = "Trip"))
        app.repository.moveEventTo(id, day.plusDays(30))
        val moved = event("Trip")
        assertEquals(day.plusDays(30), moved.date)
        assertEquals("a multi-day event keeps its length", day.plusDays(32), moved.endDate)
        val undo = app.repository.pendingMoves.value.last()
        assertTrue(app.repository.undoMove(undo.token))
        assertEquals(day, event("Trip").date)
        // A task without a due date gets one.
        val task = PlannerTask(title = "Someday")
        app.repository.saveTask(task)
        app.repository.moveTaskTo(task.id, day)
        assertEquals(day, app.repository.snapshot().tasks.single { it.id == task.id }.dueDate)
    }

    @Test fun aTimeMoveIsUndoneOnlyWhileTheEventStillHasThatTime() = runBlocking {
        val id = app.repository.saveItemId(ItineraryItem(tripId = 0, date = day, startTime = LocalTime.of(9, 0), durationMinutes = 60, title = "Call"))
        app.repository.moveEventTo(id, day.plusDays(1), LocalTime.of(14, 15), 45)
        assertEquals(Triple(day.plusDays(1), LocalTime.of(14, 15), 45), event("Call").let { Triple(it.date, it.startTime, it.durationMinutes) })
        assertTrue(app.repository.undoMove(app.repository.pendingMoves.value.last().token))
        assertEquals(Triple(day, LocalTime.of(9, 0), 60), event("Call").let { Triple(it.date, it.startTime, it.durationMinutes) })
        // Changed again since (here: in the editor): Undo leaves it.
        app.repository.moveEventTo(id, day, LocalTime.of(11, 0), 60)
        val token = app.repository.pendingMoves.value.last().token
        app.repository.saveItem(event("Call").copy(startTime = LocalTime.of(12, 0)))
        assertFalse(app.repository.undoMove(token))
        assertEquals(LocalTime.of(12, 0), event("Call").startTime)
        // Other calendars' events can't be moved.
        assertTrue(runCatching { app.repository.moveEventTo(-5L, day, LocalTime.NOON, 60) }.isFailure)
    }

    // A reminder at the end of the travel buffer is "When to leave", and follows the buffer when it is changed.
    @Test fun whenToLeaveFollowsTheTravelBuffer() {
        runBlocking {
            app.repository.saveItem(ItineraryItem(tripId = 0, date = day, startTime = LocalTime.of(9, 0), durationMinutes = 60, title = "Dentist",
                bufferBeforeMinutes = 20), addedReminders = listOf(Reminder(itemId = 0, amount = 20, unit = ReminderUnit.MINUTES)))
        }
        open(day)
        size("Calendar: month")
        click("Dentist")
        await("editor") { find("Edit event") != null }
        await("Time to leave") { described("Remove reminder: Time to leave") != null }
        // The travel buffers are further down the form: scrolled to.
        fun beforeBox() = nodes().firstOrNull { it.isEditable && it.text?.toString() == "20" }
        repeat(8) {
            if (beforeBox() == null) nodes().firstOrNull { it.isScrollable && it.actionList.any { a -> a.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD } }
                ?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            Thread.sleep(300)
        }
        await("the Before box") { beforeBox() != null }
        val before = beforeBox()!!
        before.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "35") })
        // Bug hunt 29 N1: it follows at Save, not while typing; till then it is still the one that will.
        Thread.sleep(500)
        assertTrue(described("Remove reminder: Time to leave")?.contentDescription?.contains("20") == true)
        shot("leave")
        click("Save")
        await("saved") { runBlocking { app.repository.snapshot().reminders.map { it.offsetMinutes } } == listOf(35L) }
        assertEquals(35, event("Dentist").bufferBeforeMinutes)
    }

    @Test fun theSummariesHaveButtonsThatMarkDoneAndPaid() = runBlocking {
        ins.uiAutomation.executeShellCommand("pm grant ${app.packageName} android.permission.POST_NOTIFICATIONS").close()
        val manager = app.getSystemService(NotificationManager::class.java)
        val today = LocalDate.now()
        val tomorrow = today.plusDays(1)
        val task = PlannerTask(title = "QA water plants", dueDate = today)
        app.repository.saveTask(task)
        val bill = app.repository.saveItemId(ItineraryItem(tripId = 0, date = today, startTime = null, title = "QA rent", category = "Bills"))
        app.settings.setMorningSummary(true, LocalTime.of(7, 30))
        app.settings.morningSummaryShownOn = null
        MorningSummary.show(app, MorningSummary.Kind.MORNING)
        // Notifications are posted asynchronously: waited for.
        fun posted(tag: String, check: (android.app.Notification) -> Boolean = { true }): android.app.Notification {
            val end = SystemClock.uptimeMillis() + 5_000
            while (SystemClock.uptimeMillis() < end) {
                manager.activeNotifications.firstOrNull { it.tag == tag && check(it.notification) }?.let { return it.notification }
                Thread.sleep(100)
            }
            fail("No $tag notification: " + manager.activeNotifications.map { "${it.tag} ${it.notification.extras.getString("android.title")}" }); error("")
        }
        fun gone(tag: String) = (0 until 50).any { manager.activeNotifications.none { n -> n.tag == tag }.also { if (!it) Thread.sleep(100) } }
        val shown = posted("morning-summary")
        assertEquals(listOf("Open", "Mark done", "Mark paid"), shown.actions.map { it.title.toString() })
        // Mark done, as its button sends it.
        MorningSummary.act(app, MorningSummary.Kind.MORNING, Intent(MorningSummary.ACTION_DONE).putExtra(MorningSummary.EXTRA_TASK, task.id))
        assertTrue(app.repository.snapshot().tasks.single { it.id == task.id }.done)
        val after = posted("morning-summary") { it.extras.getString("android.title") == "Today: 1 bill due" }
        assertEquals("Today: 1 bill due", after.extras.getString("android.title"))
        MorningSummary.act(app, MorningSummary.Kind.MORNING, Intent(MorningSummary.ACTION_PAID).putExtra(MorningSummary.EXTRA_BILL, bill))
        assertTrue(event("QA rent").paid)
        assertTrue("nothing left: gone", gone("morning-summary"))

        // Tomorrow's, in the evening.
        app.repository.saveItem(ItineraryItem(tripId = 0, date = tomorrow, startTime = LocalTime.of(8, 0), title = "QA flight"))
        app.settings.eveningSummaryShownOn = null
        MorningSummary.show(app, MorningSummary.Kind.EVENING)
        assertTrue("off: nothing", manager.activeNotifications.none { it.tag == "evening-summary" })
        app.settings.setEveningSummary(true, LocalTime.of(20, 0))
        MorningSummary.show(app, MorningSummary.Kind.EVENING)
        val evening = posted("evening-summary")
        assertEquals("Tomorrow: 1 event", evening.extras.getString("android.title"))
        assertEquals("evening_summary", evening.channelId)
    }
}
