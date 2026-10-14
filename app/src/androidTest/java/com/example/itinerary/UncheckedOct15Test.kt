package com.example.itinerary

import android.app.NotificationManager
import android.content.ContentProviderOperation
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.provider.ContactsContract
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.example.itinerary.data.*
import com.example.itinerary.reminders.BirthdayReminders
import com.example.itinerary.reminders.MorningSummary
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * What was left unchecked after the 15 Oct work (user: "do what you haven't checked"): the evening summary coming after
 * midnight, a real contact's birthday (reminder, Message, Call, the pop-up), the grid's TalkBack actions, and right to left
 * (the test app's own language set to Hebrew).
 */
@Suppress("DEPRECATION")
class UncheckedOct15Test {
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
    private fun shows(text: String) = nodes().any { it.text?.toString() == text }
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
        val dir = java.io.File(ins.targetContext.cacheDir, "qa-unchecked").apply { mkdirs() }
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
    private fun shell(command: String) = ins.uiAutomation.executeShellCommand(command).use { pfd ->
        android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().readText() }
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
        send(MotionEvent.ACTION_DOWN, x, y); Thread.sleep(900); moveTo(x, y, toX, toY); Thread.sleep(200); send(MotionEvent.ACTION_UP, toX, toY)
    }

    private var contactId: Long? = null

    @After fun tearDown() {
        app.settings.setDayHours(false)
        app.settings.setCalendarCollapsed(false)
        app.settings.setCalendarMaximised(false)
        app.settings.setEveningSummary(false, LocalTime.of(20, 0))
        app.settings.eveningSummaryShownOn = null
        app.settings.setBirthdayReminderDays(ContactBirthdays.REMIND_OFF)
        app.settings.birthdayRemindersShownOn = null
        manager.cancelAll()
        shell("cmd locale set-app-locales ${app.packageName} --locales \"\"")
        // The made-up contact goes (as a sync adapter, so it is removed for good, not just marked deleted).
        contactId?.let { id ->
            ins.uiAutomation.adoptShellPermissionIdentity()
            try {
                ins.targetContext.contentResolver.delete(ContactsContract.RawContacts.CONTENT_URI.buildUpon()
                    .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true").build(),
                    "${ContactsContract.RawContacts.CONTACT_ID} = ?", arrayOf(id.toString()))
            } finally { ins.uiAutomation.dropShellPermissionIdentity() }
        }
    }

    // An evening summary whose alarm (set for yesterday evening) comes just after midnight: today's summary, called today.
    @Test fun anEveningSummaryAfterMidnightIsAboutToday(): Unit = runBlocking {
        shell("pm grant ${app.packageName} android.permission.POST_NOTIFICATIONS")
        val today = LocalDate.now()
        app.repository.saveItem(ItineraryItem(tripId = 0, date = today, startTime = LocalTime.of(9, 0), title = "QA today"))
        app.repository.saveItem(ItineraryItem(tripId = 0, date = today.plusDays(1), startTime = LocalTime.of(9, 0), title = "QA tomorrow"))
        app.settings.setEveningSummary(true, LocalTime.of(23, 55))
        app.settings.eveningSummaryShownOn = null
        MorningSummary.show(app, MorningSummary.Kind.EVENING, alarmDay = today.minusDays(1))
        var shown: android.app.Notification? = null
        await("the summary") { manager.activeNotifications.firstOrNull { it.tag == "evening-summary" }?.notification?.also { shown = it } != null }
        assertEquals("Today: 1 event", shown!!.extras.getString("android.title"))
        val lines = shown!!.extras.getCharSequenceArray("android.textLines")!!.map { it.toString() }
        assertTrue(lines.toString(), lines.single().endsWith("QA today"))
        assertEquals("yesterday evening's is done", today.minusDays(1).toString(), app.settings.eveningSummaryShownOn)
        // This evening's still comes: the next alarm is today at 23:55, not tomorrow.
        assertEquals(today.atTime(23, 55), MorningSummary.nextAt(today.atTime(0, 5), LocalTime.of(23, 55),
            shownToday = app.settings.eveningSummaryShownOn == today.toString()))
    }

    // A contact with a birthday tomorrow and a phone number: the reminder a day before, with Message and Call that open the
    // messaging and phone apps with the number; the birthday's pop-up in the Calendar has them too.
    @Test fun aRealContactsBirthday(): Unit = runBlocking {
        shell("pm grant ${app.packageName} android.permission.POST_NOTIFICATIONS")
        shell("pm grant ${app.packageName} android.permission.READ_CONTACTS")
        val tomorrow = LocalDate.now().plusDays(1)
        val number = "+61 400 111 222"
        ins.uiAutomation.adoptShellPermissionIdentity()
        try {
            val ops = arrayListOf(
                ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null).withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null).build(),
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI).withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, "QA Birthday").build(),
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI).withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.Event.TYPE, ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY)
                    .withValue(ContactsContract.CommonDataKinds.Event.START_DATE, "1990-%02d-%02d".format(tomorrow.monthValue, tomorrow.dayOfMonth)).build(),
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI).withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, number)
                    .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE).build(),
            )
            val raw = ins.targetContext.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops).first().uri!!
            contactId = ins.targetContext.contentResolver.query(raw, arrayOf(ContactsContract.RawContacts.CONTACT_ID), null, null, null)!!
                .use { it.moveToFirst(); it.getLong(0) }
        } finally { ins.uiAutomation.dropShellPermissionIdentity() }
        val age = tomorrow.year - 1990

        // The reminder, a day before.
        app.settings.setBirthdayReminderDays(1)
        app.settings.birthdayRemindersShownOn = null
        BirthdayReminders.show(app)
        var shown: android.app.Notification? = null
        await("the birthday reminder") { manager.activeNotifications.firstOrNull { it.tag == "birthday-$contactId" }?.notification?.also { shown = it } != null }
        assertEquals("QA Birthday's birthday tomorrow (turns $age)", shown!!.extras.getString("android.title"))
        assertEquals(listOf("Message", "Call"), shown!!.actions.map { it.title.toString() })
        // Message: the messaging app, to that number. Call: the phone app with it filled in (it doesn't call).
        fun top() = shell("dumpsys activity activities").lines().firstOrNull { "topResumedActivity" in it }.orEmpty()
        fun recentIntent(pkg: String) = shell("dumpsys activity activities").lines().filter { pkg in it && ("dat=" in it || "data=" in it) }.joinToString("\n")
        shown!!.actions[0].actionIntent.send()
        await("Messages") { "com.google.android.apps.messaging" in top() }
        assertTrue(recentIntent("messaging"), recentIntent("messaging").contains("smsto"))
        shot("message")
        shell("input keyevent KEYCODE_HOME")
        Thread.sleep(1500) // the launcher back in front first
        shown!!.actions[1].actionIntent.send()
        await("Phone") { "com.google.android.dialer" in top() }
        shot("call")
        shell("input keyevent KEYCODE_HOME")

        // The birthday in the Calendar (the phone's birthdays read in), its pop-up with Message and Call.
        app.calendarSync.refreshPhone()
        open(tomorrow)
        size("Calendar: month")
        val title = "QA Birthday's birthday ($age)"
        await("the birthday on the day") { find(title) != null }
        click(title)
        await("its pop-up") { find("Copy to Planner") != null }
        assertTrue("Message", find("Message") != null)
        assertTrue("Call", find("Call") != null)
        shot("popup")
        click("Message")
        await("Messages from the pop-up") { "com.google.android.apps.messaging" in top() }
        shell("input keyevent KEYCODE_HOME")
    }

    // The week widget on the launcher: pinned through Android's own dialog, showing the next seven days with today's
    // entries; a tap on a day opens it in Planner; then taken off the home screen again.
    @Test fun theWeekWidgetOnTheHomeScreen() {
        val today = LocalDate.now()
        runBlocking {
            app.repository.saveItem(ItineraryItem(tripId = 0, date = today, startTime = LocalTime.of(9, 0), title = "QA widget dentist"))
            app.repository.saveTask(PlannerTask(title = "QA widget task", dueDate = today.plusDays(2)))
        }
        val widgets = android.appwidget.AppWidgetManager.getInstance(app)
        val provider = android.content.ComponentName(app, com.example.itinerary.widget.WeekWidget::class.java)
        assertTrue(widgets.getAppWidgetIds(provider).isEmpty())
        shell("input keyevent KEYCODE_HOME")
        Thread.sleep(1000)
        assertTrue("pinning offered", widgets.requestPinAppWidget(provider, null, null))
        // The launcher's dialog: "Add to home screen" (or "Add automatically").
        await("the launcher's add dialog") { nodes().any { it.text?.toString()?.lowercase()?.startsWith("add") == true } }
        shot("widget-dialog")
        clickNode(nodes().first { it.text?.toString()?.lowercase()?.startsWith("add") == true }, "Add")
        await("placed") { widgets.getAppWidgetIds(provider).isNotEmpty() }
        val heading = "Week · ${today.format(java.time.format.DateTimeFormatter.ofPattern("d MMM"))}"
        await("the widget's week") { nodes().any { it.text?.toString()?.startsWith(heading) == true } && nodes().any { it.text?.toString()?.contains("QA widget dentist") == true } }
        assertTrue("the task two days on", nodes().any { it.text?.toString()?.contains("☐ QA widget task") == true })
        // On the page the launcher put it on.
        fun toWidget() {
            val w = ins.targetContext.resources.displayMetrics.widthPixels
            repeat(3) {
                val r = bounds(nodes().first { it.text?.toString()?.startsWith(heading) == true })
                if (r.left in 0 until w && r.width() > 0) return
                shell("input swipe ${w * 4 / 5} ${w} ${w / 5} ${w} 300"); Thread.sleep(800)
            }
        }
        toWidget()
        shot("widget")
        // A tap on the day with the task opens Planner on that day.
        val day = today.plusDays(2)
        val description = day.format(java.time.format.DateTimeFormatter.ofPattern("EEEE d MMMM"))
        clickNode(nodes().first { it.contentDescription?.toString()?.startsWith(description) == true }, "the day")
        val heading2 = day.format(java.time.format.DateTimeFormatter.ofPattern("EEEE d MMMM yyyy"))
        await("Planner on that day") { find("CALENDAR") != null && nodes().any { it.text?.toString() == heading2 } }
        shot("widget-opened")
        // Off the home screen again: held, then Remove.
        shell("input keyevent KEYCODE_HOME")
        Thread.sleep(1000)
        toWidget()
        val placed = bounds(nodes().first { it.text?.toString()?.startsWith(heading) == true })
        send(MotionEvent.ACTION_DOWN, placed.centerX(), placed.centerY()); Thread.sleep(1200); send(MotionEvent.ACTION_UP, placed.centerX(), placed.centerY())
        await("the widget's menu") { find("Remove") != null }
        click("Remove")
        await("removed") { widgets.getAppWidgetIds(provider).isEmpty() }
    }

    // What TalkBack offers on an event on the grid: 15 minutes later/earlier/longer/shorter, each doing that.
    @Test fun theGridsTalkBackActions() {
        runBlocking { app.repository.saveItem(ItineraryItem(tripId = 0, date = day, startTime = LocalTime.of(9, 0), durationMinutes = 60, title = "Dentist")) }
        open(day)
        size("Calendar: month")
        click("Hours")
        await("the block") { described(", Dentist") != null }
        fun act(label: String) {
            val node = described(", Dentist")!!
            val action = node.actionList.firstOrNull { it.label?.toString() == label }
            assertNotNull("$label among ${node.actionList.map { "${it.id}:${it.label}" }} on ${node.className} ${node.contentDescription}; all: " + nodes().filter { n -> n.actionList.any { a -> a.label != null } }.map { n -> "${n.contentDescription ?: n.text} ${n.actionList.mapNotNull { a -> a.label }}" }, action)
            assertTrue(label, node.performAction(action!!.id))
        }
        // One item for TalkBack: what it says, the tap and the actions on the same node.
        val read = described(", Dentist")!!
        fun info(n: AccessibilityNodeInfo?): String = if (n == null) "-" else "[${n.className} d=${n.contentDescription} t=${n.text} click=${n.isClickable} acts=${n.actionList.map { "${it.id}:${it.label}" }} kids=${n.childCount}]"
        assertTrue("tappable where it is read: ${info(read)} parent ${info(read.parent)} grand ${info(read.parent?.parent)} kids ${(0 until read.childCount).map { info(read.getChild(it)) }}", read.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK })
        act("15 minutes later")
        await("9:15") { event("Dentist").startTime == LocalTime.of(9, 15) }
        act("15 minutes longer")
        await("75 minutes") { event("Dentist").durationMinutes == 75 }
        act("15 minutes earlier")
        await("back to 9:00") { event("Dentist").startTime == LocalTime.of(9, 0) }
        act("15 minutes shorter")
        await("60 minutes") { event("Dentist").durationMinutes == 60 }
    }

    // Right to left (Hebrew): the week runs from the right, so a drag leftwards is the next day; in the full month a hold
    // at the left edge turns to the next month.
    @Test fun rightToLeft() {
        runBlocking {
            app.repository.saveItem(ItineraryItem(tripId = 0, date = day, startTime = LocalTime.of(9, 0), durationMinutes = 60, title = "Dentist"))
            app.repository.saveItem(ItineraryItem(tripId = 0, date = LocalDate.of(2036, 4, 28), startTime = LocalTime.of(9, 0), title = "Trip prep"))
        }
        shell("cmd locale set-app-locales ${app.packageName} --locales he")
        open(day)
        await("right to left") { ins.targetContext.resources.configuration.layoutDirection == android.view.View.LAYOUT_DIRECTION_RTL ||
            app.resources.configuration.layoutDirection == android.view.View.LAYOUT_DIRECTION_RTL }
        size("Calendar: week")
        click("Hours")
        await("the block") { described(", Dentist") != null }
        shot("rtl-week")
        val block = bounds(described(", Dentist")!!)
        val column = block.width() + (2 * density).toInt()
        // The next day is to the left.
        hold(block.centerX(), block.top + 10, block.centerX() - column, block.top + 10)
        await("moved to the next day") { event("Dentist").date == day.plusDays(1) }
        assertEquals(LocalTime.of(9, 0), event("Dentist").startTime)

        // Full month: held at the left edge, on to May.
        size("Calendar: full screen")
        val monthNames = { m: Int -> java.time.Month.of(m).getDisplayName(java.time.format.TextStyle.FULL_STANDALONE, java.util.Locale.getDefault()) }
        await("the month") { nodes().any { n -> n.contentDescription?.toString()?.contains("28") == true && n.contentDescription.toString().contains("2036") } }
        val cell = bounds(nodes().first { n -> n.contentDescription?.toString()?.let { "28" in it && "2036" in it && ("4" in it || monthNames(4) in it) } == true })
        val start = cell.centerX() to cell.top + (37 * density).toInt()
        send(MotionEvent.ACTION_DOWN, start.first, start.second)
        Thread.sleep(900)
        val edgeX = (6 * density).toInt()
        moveTo(start.first, start.second, edgeX, start.second)
        val width = ins.targetContext.resources.displayMetrics.widthPixels
        await("May") { nodes().any { n -> n.contentDescription?.toString()?.let { " 2 " in " $it " && "2036" in it && ("5" in it || monthNames(5) in it) } == true } }
        moveTo(edgeX, start.second, width / 2, start.second, steps = 5)
        Thread.sleep(500)
        shot("rtl-may")
        val target = bounds(nodes().first { n -> n.contentDescription?.toString()?.let { Regex("""(^|\D)2(\D|$)""").containsMatchIn(it) && "2036" in it && ("5" in it || monthNames(5) in it) } == true })
        moveTo(width / 2, start.second, target.centerX(), target.centerY())
        Thread.sleep(300)
        send(MotionEvent.ACTION_UP, target.centerX(), target.centerY())
        await("moved into May") { event("Trip prep").date == LocalDate.of(2036, 5, 2) }
    }
}
