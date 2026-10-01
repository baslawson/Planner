package com.example.itinerary

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.provider.CalendarContract
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.example.itinerary.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/** Calendar sync step 3 in the real app with the emulator's real calendar storage: a temporary local "QA test calendar"
 *  is created, shown read-only in Planner, changed while Planner is open, and deleted again at the end. */
@Suppress("DEPRECATION")
class CalendarPhoneUiTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = ins.targetContext
    private val app get() = context.applicationContext as ItineraryApp
    private val tomorrow get() = LocalDate.now().plusDays(1)
    private fun nodes(): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(n: AccessibilityNodeInfo) { result += n; for (i in 0 until n.childCount) n.getChild(i)?.let(::visit) }
        ins.uiAutomation.freshRoot?.let(::visit); return result
    }
    private fun find(text: String) = nodes().firstOrNull { it.isVisibleToUser && (it.text?.toString() == text || it.contentDescription?.toString() == text) }
    private fun has(part: String) = nodes().any { it.isVisibleToUser && it.text?.toString()?.contains(part) == true }
    private fun screenshot(name: String) {
        val dir = File(context.cacheDir, "qa-calendar-phone-evidence").apply { mkdirs() }
        ins.uiAutomation.takeScreenshot()?.let { b -> File(dir, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }; b.recycle() }
    }
    private fun await(timeout: Long = 30000, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < end) { if (condition()) return; Thread.sleep(150) }
        screenshot("failure"); fail("Timed out: " + nodes().mapNotNull { it.text }.joinToString(" | "))
    }
    private fun click(text: String) {
        if (text == "Settings" && find(text) == null && find("More options") != null) click("More options")
        var tries = 0
        await {
            val target = nodes().filter { it.isVisibleToUser && (it.text?.toString() == text || it.contentDescription?.toString() == text) }
                .firstNotNullOfOrNull { var n: AccessibilityNodeInfo? = it; while (n != null && !n.isClickable) n = n.parent; n?.takeIf { c -> c.isEnabled } }
            if (target != null) target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            else { if (++tries % 4 == 0) scrollStep(nodes(), true); false }
        }
        Thread.sleep(400)
    }
    private fun asSyncAdapter(uri: Uri) = uri.buildUpon().appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, ACCOUNT)
        .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL).build()

    @Test fun phoneCalendarIsShownReadOnlyAndFollowsChanges() {
        val resolver = context.contentResolver
        // A leftover from an interrupted run is removed first.
        resolver.delete(asSyncAdapter(CalendarContract.Calendars.CONTENT_URI), "${CalendarContract.Calendars.ACCOUNT_NAME} = ?", arrayOf(ACCOUNT))
        val calendarId = resolver.insert(asSyncAdapter(CalendarContract.Calendars.CONTENT_URI), ContentValues().apply {
            put(CalendarContract.Calendars.ACCOUNT_NAME, ACCOUNT)
            put(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            put(CalendarContract.Calendars.NAME, "qa-test-calendar")
            put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, "QA test calendar")
            put(CalendarContract.Calendars.CALENDAR_COLOR, 0xFFAA3377.toInt())
            put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_OWNER)
            put(CalendarContract.Calendars.OWNER_ACCOUNT, ACCOUNT)
            put(CalendarContract.Calendars.VISIBLE, 1)
            put(CalendarContract.Calendars.SYNC_EVENTS, 1)
        })!!.lastPathSegment!!.toLong()
        try {
            val zone = ZoneId.systemDefault()
            val eventUri = resolver.insert(CalendarContract.Events.CONTENT_URI, ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calendarId)
                put(CalendarContract.Events.TITLE, "QA Phone dentist")
                put(CalendarContract.Events.EVENT_LOCATION, "Clinic")
                put(CalendarContract.Events.DTSTART, tomorrow.atTime(10, 0).atZone(zone).toInstant().toEpochMilli())
                put(CalendarContract.Events.DTEND, tomorrow.atTime(11, 0).atZone(zone).toInstant().toEpochMilli())
                put(CalendarContract.Events.EVENT_TIMEZONE, zone.id)
            })!!
            app.settings.lastViewCalendar = false
            ins.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            await { find("AGENDA") != null }
            click("Settings")
            click("Calendars")
            await { find("QA test calendar") != null } // the test app was installed with its permissions granted
            screenshot("phone-list")
            assertTrue(runBlocking { app.database.outsideDao().sources() }.single { it.name == "QA test calendar" }.let { !it.enabled && it.kind == OutsideCalendars.KIND_PHONE })
            click("QA test calendar")
            await { has("Synced ") }
            click("Close")
            click("Save")
            await { find("QA Phone dentist") != null && has("From QA test calendar") }
            // Its ⋮ has only Copy to Planner and Share (see CardMenusUiTest).
            assertNotNull(find("Actions for QA Phone dentist"))
            screenshot("agenda")

            // Changed on the phone while Planner is open: Planner follows without a tap.
            resolver.update(eventUri, ContentValues().apply { put(CalendarContract.Events.TITLE, "QA Phone dentist moved") }, null, null)
            await { find("QA Phone dentist moved") != null }
            screenshot("agenda-changed")

            click("QA Phone dentist moved") // the calendar on that day
            await { find("CALENDAR") != null && find("QA Phone dentist moved") != null }
            click("QA Phone dentist moved") // the read-only view
            await { has("belongs to a calendar on your phone") }
            screenshot("read-only")
            click("Close")
            // Planner never wrote to the phone's calendar.
            resolver.query(eventUri, arrayOf(CalendarContract.Events.TITLE), null, null, null)!!.use { it.moveToFirst(); assertEquals("QA Phone dentist moved", it.getString(0)) }
        } finally {
            resolver.delete(asSyncAdapter(CalendarContract.Calendars.CONTENT_URI), "${CalendarContract.Calendars._ID} = ?", arrayOf(calendarId.toString()))
        }
        resolver.query(CalendarContract.Calendars.CONTENT_URI, arrayOf(CalendarContract.Calendars._ID), "${CalendarContract.Calendars.ACCOUNT_NAME} = ?", arrayOf(ACCOUNT), null)!!
            .use { assertEquals("The test calendar must be gone", 0, it.count) }
    }

    companion object { private const val ACCOUNT = "Planner QA test" }
}
