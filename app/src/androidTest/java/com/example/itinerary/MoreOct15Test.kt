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
import com.example.itinerary.reminders.UpNext
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/** The second 15 Oct round: drag to create, free time on the grid, Up next, Habits, week titles and landscape. */
@Suppress("DEPRECATION")
class MoreOct15Test {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = ins.targetContext.applicationContext as ItineraryApp
    private val manager get() = app.getSystemService(NotificationManager::class.java)
    private val density get() = ins.targetContext.resources.displayMetrics.density
    private val day = LocalDate.of(2036, 4, 15)

    private fun nodes(): List<AccessibilityNodeInfo> {
        val out = mutableListOf<AccessibilityNodeInfo>()
        fun walk(n: AccessibilityNodeInfo?) { if (n == null) return; out += n; repeat(n.childCount) { walk(n.getChild(it)) } }
        walk(ins.uiAutomation.freshRoot)
        return out
    }
    private fun find(label: String) = nodes().firstOrNull { (!it.isEditable && it.text?.toString() == label) || it.contentDescription?.toString() == label }
    private fun described(part: String) = nodes().firstOrNull { it.contentDescription?.toString()?.contains(part) == true }
    private fun showsStarting(text: String) = nodes().any { it.text?.toString()?.startsWith(text) == true }
    private fun await(what: String, seconds: Int = 20, check: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + seconds * 1000L
        while (SystemClock.uptimeMillis() < deadline) { if (check()) return; Thread.sleep(100) }
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
        val dir = java.io.File(ins.targetContext.cacheDir, "qa-more").apply { mkdirs() }
        ins.uiAutomation.takeScreenshot()?.let { b -> java.io.File(dir, "$name.png").outputStream().use { b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; b.recycle() }
    }
    private fun shell(command: String) = ins.uiAutomation.executeShellCommand(command).use { pfd ->
        android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().readText() }
    private fun open(date: LocalDate) {
        ins.targetContext.startActivity(Intent(ins.targetContext, MainActivity::class.java).apply {
            action = "com.example.itinerary.widget.OPEN_DATE"
            putExtra("widget_date", date.toString())
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        })
        await("Calendar") { find("CALENDAR") != null && find("AGENDA") == null }
    }
    private fun size(label: String) { if (find(label)?.isSelected != true) click(label) }
    private var downAt = 0L
    private fun send(action: Int, x: Int, y: Int) {
        val now = SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_DOWN) downAt = now
        val e = MotionEvent.obtain(downAt, now, action, x.toFloat(), y.toFloat(), 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        ins.uiAutomation.injectInputEvent(e, true); e.recycle()
    }
    private fun hold(x: Int, y: Int, toX: Int, toY: Int) {
        send(MotionEvent.ACTION_DOWN, x, y); Thread.sleep(900)
        for (i in 1..20) { send(MotionEvent.ACTION_MOVE, x + (toX - x) * i / 20, y + (toY - y) * i / 20); Thread.sleep(16) }
        Thread.sleep(200); send(MotionEvent.ACTION_UP, toX, toY)
    }
    private var rotation: Pair<String, String>? = null

    @After fun tearDown() {
        app.settings.setDayHours(false)
        app.settings.setDayHoursFree(false)
        app.settings.setCalendarCollapsed(false)
        app.settings.setUpNext(false)
        manager.cancelAll()
        rotation?.let { (auto, user) ->
            shell("settings put system user_rotation $user"); shell("settings put system accelerometer_rotation $auto")
        }
    }

    // Hold an empty spot on the grid and drag down: the new event opens with that length.
    @Test fun dragToCreateAnEvent() {
        runBlocking { app.repository.saveItem(ItineraryItem(tripId = 0, date = day, startTime = LocalTime.of(9, 0), durationMinutes = 60, title = "Dentist")) }
        open(day)
        size("Calendar: week")
        click("Hours")
        await("the grid") { described(", Dentist") != null }
        val dentist = bounds(described(", Dentist")!!)
        val hour = (56 * density).toInt()
        // Just under the dentist (10:00), down an hour and a half.
        val x = dentist.centerX(); val y = dentist.bottom + hour / 8
        hold(x, y, x, y + hour * 3 / 2)
        await("the new event") { find("New event") != null || showsStarting("New event") }
        shot("created")
        val title = nodes().first { it.isEditable }
        title.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "Drawn") })
        click("Save")
        await("saved") { runBlocking { app.repository.snapshot().items.any { it.title == "Drawn" } } }
        val drawn = runBlocking { app.repository.snapshot().items.single { it.title == "Drawn" } }
        assertEquals(day, drawn.date)
        assertEquals(LocalTime.of(10, 0), drawn.startTime)
        assertEquals(90, drawn.durationMinutes)
    }

    private fun saveNewAs(name: String): ItineraryItem {
        await("the new event") { showsStarting("New event") || nodes().any { it.isEditable } }
        val title = nodes().first { it.isEditable }
        title.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, name) })
        click("Save")
        await("saved") { runBlocking { app.repository.snapshot().items.any { it.title == name } } }
        // Save keeps the editor open; Close (once the save has shown) leaves it.
        await("Saved") { find("Saved") != null }
        click("Close")
        await("editor closed") { find("Edit event") == null }
        return runBlocking { app.repository.snapshot().items.single { it.title == name } }
    }

    // Bug hunt 30 A1: a hold let go where it started opens a 1-hour event at that hour (as a tap does);
    // A5: drawn upwards, the new event runs from the finger up to the end of the 15 minutes first held.
    @Test fun holdAndDrawUpwards() {
        runBlocking { app.repository.saveItem(ItineraryItem(tripId = 0, date = day, startTime = LocalTime.of(9, 0), durationMinutes = 60, title = "Dentist")) }
        open(day)
        size("Calendar: week")
        click("Hours")
        await("the grid") { described(", Dentist") != null }
        val nine = bounds(described(", Dentist")!!).top
        val hour = (56 * density).toInt()
        val x = bounds(described(", Dentist")!!).centerX()
        // 11:20, held and let go in place.
        // Bug hunt 31: while held in place, the preview is the hour that letting go makes.
        send(MotionEvent.ACTION_DOWN, x, nine + hour * 2 + hour / 3); Thread.sleep(900)
        Thread.sleep(300); shot("held-preview")
        send(MotionEvent.ACTION_UP, x, nine + hour * 2 + hour / 3)
        val held = saveNewAs("Held")
        assertEquals(LocalTime.of(11, 0), held.startTime)
        assertEquals(60, held.durationMinutes)
        await("the grid again") { described(", Held") != null }
        // From 12:07 (the 12:00 quarter) up to 10:32: 10:30 to 12:15.
        hold(x, nine + hour * 3 + hour / 8, x, nine + hour + hour / 2 + hour / 24)
        val up = saveNewAs("Up")
        assertEquals(LocalTime.of(10, 30), up.startTime)
        assertEquals(105, up.durationMinutes)
    }

    // The Free chip shades the free windows between 8 and 8, travel buffers counted.
    @Test fun freeTimeOnTheGrid() {
        runBlocking {
            app.repository.saveItem(ItineraryItem(tripId = 0, date = day, startTime = LocalTime.of(10, 0), durationMinutes = 60, title = "Call", bufferBeforeMinutes = 30))
            app.repository.saveItem(ItineraryItem(tripId = 0, date = day, startTime = LocalTime.of(13, 0), durationMinutes = 120, title = "Workshop"))
            // Bug hunt 30 A4: a timed bill is drawn as a block (30 minutes), so it isn't free under it.
            app.repository.saveItem(ItineraryItem(tripId = 0, date = day, startTime = LocalTime.of(16, 0), title = "Rent", category = "Bills"))
        }
        open(day)
        size("Calendar: month")
        click("Hours")
        await("the grid") { described(", Call") != null }
        assertNull("off: no bands", described("Free from"))
        click("Show free time")
        // 8:00–9:30 (the buffer), 11:00–13:00, 15:00–16:00, 16:30–20:00; read from the top of the grid down (only what is on screen is read).
        await("free bands") { described("Free from") != null }
        val seen = sortedSetOf<String>()
        val grid = bounds(described("Free from")!!)
        repeat(3) { shell("input swipe ${grid.centerX()} ${grid.top + 50} ${grid.centerX()} ${grid.top + 900} 300"); Thread.sleep(400) }
        repeat(6) { nodes().mapNotNull { it.contentDescription?.toString() }.filter { it.startsWith("Free from") }.forEach { seen += it }
            shell("input swipe ${grid.centerX()} ${grid.top + 600} ${grid.centerX()} ${grid.top + 200} 300"); Thread.sleep(400) }
        val bands = seen.toList()
        assertEquals(listOf("Free from 08:00 to 09:30", "Free from 11:00 to 13:00", "Free from 15:00 to 16:00", "Free from 16:30 to 20:00"), bands.map { it.replace(Regex("""(\d)(\d):"""), "$1$2:") }.sorted())
        assertTrue(app.settings.dayHoursFree.value)
        shot("free")
        click("Show free time")
        await("bands gone") { described("Free from") == null }
    }

    // Up next: the next event, a countdown to when to leave, and off again with the switch.
    @Test fun upNext(): Unit = runBlocking {
        shell("pm grant ${app.packageName} android.permission.POST_NOTIFICATIONS")
        val start = java.time.LocalDateTime.now().plusHours(2).withSecond(0).withNano(0)
        app.repository.saveItem(ItineraryItem(tripId = 0, date = start.toLocalDate(), startTime = start.toLocalTime(), title = "QA up next",
            location = "Main St", bufferBeforeMinutes = 20))
        app.settings.setUpNext(true)
        UpNext.refresh(app)
        var shown: android.app.Notification? = null
        await("Up next") { manager.activeNotifications.firstOrNull { it.tag == "up-next" }?.notification?.also { shown = it } != null }
        // Bug hunt 30 B5: leaving is 1 h 40 min off, so not "soon" yet.
        val title = shown!!.extras.getString("android.title")!!
        assertTrue(title, title.startsWith("Leave at ") && title.endsWith(" for QA up next"))
        assertTrue(shown!!.extras.getString("android.text")!!.contains("Main St"))
        assertEquals(start.minusMinutes(20).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(), shown!!.`when`)
        assertTrue("ongoing", shown!!.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals("up_next", shown!!.channelId)
        // B4: no event words on the lock screen.
        assertEquals(android.app.Notification.VISIBILITY_PRIVATE, shown!!.visibility)
        assertEquals("Up next", shown!!.publicVersion!!.extras.getString("android.title"))
        // B1: one with a long journey whose leaving time comes first goes ahead of a nearer one.
        val soon = java.time.LocalDateTime.now().plusMinutes(150).withSecond(0).withNano(0)
        app.repository.saveItem(ItineraryItem(tripId = 0, date = soon.toLocalDate(), startTime = soon.toLocalTime(), title = "QA airport", bufferBeforeMinutes = 120))
        UpNext.refresh(app)
        await("the airport") { manager.activeNotifications.firstOrNull { it.tag == "up-next" }?.notification?.also { shown = it }
            ?.extras?.getString("android.title") == "Leave soon for QA airport" }
        assertTrue(shown!!.extras.getString("android.text")!!.contains("leave at"))
        // B6: swiped away, it stays away for that event (Android 14 lets an ongoing one be swiped).
        shown!!.deleteIntent.send()
        await("dismissed noted") { ins.targetContext.getSharedPreferences("up_next", 0).getString("dismissed", null)?.contains("QA airport") == true }
        manager.cancelAll()
        UpNext.refresh(app)
        Thread.sleep(1500)
        assertTrue("stays away", manager.activeNotifications.none { it.tag == "up-next" })
        // Bug hunt 31: turned off and on again, it shows again.
        app.settings.setUpNext(false)
        app.settings.setUpNext(true)
        await("back after on again") { ins.targetContext.getSharedPreferences("up_next", 0).getString("dismissed", null) == null }
        UpNext.refresh(app)
        await("shown again") { manager.activeNotifications.any { it.tag == "up-next" } }
        UpNext.setDismissed(app, null)
        app.settings.setUpNext(false)
        UpNext.refresh(app)
        await("gone") { manager.activeNotifications.none { it.tag == "up-next" } }
    }

    // ⋮ › Habits: a repeating task with its streak and marks.
    @Test fun habitsPage() {
        runBlocking {
            val mon = LocalDate.of(2026, 9, 7)
            val ids = (0..4).map { "habit-$it" }
            ids.forEachIndexed { i, id ->
                app.repository.saveTask(PlannerTask(id = id, title = "QA gym", dueDate = mon.plusWeeks(i.toLong()), repeat = "WEEKLY",
                    done = i < 4, doneOn = if (i < 4) mon.plusWeeks(i.toLong()).plusDays(if (i == 1) 2 else 0) else null, nextTaskId = ids.getOrNull(i + 1)?.takeIf { i < 4 }))
            }
            // Bug hunt 30 B3: ticked off then unticked, with its next one still there: listed once.
            app.repository.saveTask(PlannerTask(id = "swim-1", title = "QA swim", dueDate = mon, repeat = "WEEKLY", nextTaskId = "swim-2"))
            app.repository.saveTask(PlannerTask(id = "swim-2", title = "QA swim", dueDate = mon.plusWeeks(1), repeat = "WEEKLY"))
        }
        open(day)
        click("More options")
        click("Habits")
        await("the habit") { find("QA gym") != null }
        assertTrue(showsStarting("Weekly · 🔥 2 in a row · 3 of the last 4 on time"))
        assertNotNull(described("Last 5: 3 on time, 1 late, 1 to do"))
        assertEquals("swim once", 1, nodes().count { it.text?.toString() == "QA swim" })
        shot("habits")
        click("Close")
    }

    // Week titles in narrow columns break with a hyphen; landscape gives the week grid two thirds of the screen.
    @Test fun weekTitlesAndLandscape() {
        runBlocking {
            app.repository.saveItem(ItineraryItem(tripId = 0, date = day, startTime = LocalTime.of(9, 0), durationMinutes = 90, title = "Physiotherapy appointment"))
        }
        open(day)
        size("Calendar: week")
        click("Hours")
        await("the week grid") { described("Physiotherapy appointment") != null }
        shot("week-titles")
        rotation = shell("settings get system accelerometer_rotation").trim() to shell("settings get system user_rotation").trim()
        shell("settings put system accelerometer_rotation 0"); shell("settings put system user_rotation 1")
        await("landscape") { ins.targetContext.resources.displayMetrics.widthPixels > ins.targetContext.resources.displayMetrics.heightPixels ||
            nodes().any { n -> bounds(n).width() > bounds(n).height() * 2 && n.contentDescription?.toString()?.contains("Physiotherapy") == true } }
        Thread.sleep(1500)
        await("the week grid in landscape") { described("Physiotherapy appointment") != null }
        val screen = Rect().also { nodes().first().getBoundsInScreen(it) }
        val block = bounds(described("Physiotherapy appointment")!!)
        shot("landscape")
        // Its column: the grid has 2/3 of the width, so a column is about 2/3 * width / 7 (less the hours' gutter).
        assertTrue("column ${block.width()} of ${screen.width()}", block.width() > screen.width() / 14)
    }
}
