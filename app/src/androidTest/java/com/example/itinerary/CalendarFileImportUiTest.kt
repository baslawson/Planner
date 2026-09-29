package com.example.itinerary

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.example.itinerary.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** Import calendar file in the real app: opening a .ics file, the checkbox list, Add, and Undo import. */
@Suppress("DEPRECATION")
class CalendarFileImportUiTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = ins.targetContext
    private val app get() = context.applicationContext as ItineraryApp
    private val today get() = LocalDate.now()
    private fun data() = runBlocking { app.repository.snapshot() }
    private fun nodes(): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(n: AccessibilityNodeInfo) { result += n; for (i in 0 until n.childCount) n.getChild(i)?.let(::visit) }
        ins.uiAutomation.freshRoot?.let(::visit); return result
    }
    private fun find(text: String) = nodes().firstOrNull { it.isVisibleToUser && (it.text?.toString() == text || it.contentDescription?.toString() == text) }
    private fun screenshot(name: String) {
        val dir = File(context.cacheDir, "qa-calendar-import-evidence").apply { mkdirs() }
        ins.uiAutomation.takeScreenshot()?.let { b -> File(dir, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }; b.recycle() }
    }
    private fun await(timeout: Long = 30000, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < end) { if (condition()) return; Thread.sleep(150) }
        screenshot("failure"); fail("Timed out: " + nodes().mapNotNull { it.text }.joinToString(" | "))
    }
    // Scrolls the import list (both ways) until [test] holds.
    private fun reveal(test: () -> Boolean) {
        var tries = 0; var forward = true
        await { if (test()) true else { if (++tries % 3 == 0 && !scrollStep(nodes(), forward)) forward = !forward; false } }
    }
    private fun click(text: String) {
        reveal {
            nodes().filter { it.isVisibleToUser && (it.text?.toString() == text || it.contentDescription?.toString() == text) }
                .firstNotNullOfOrNull { var n: AccessibilityNodeInfo? = it; while (n != null && !n.isClickable) n = n.parent; n?.takeIf { c -> c.isEnabled } }
                ?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        }
        Thread.sleep(400)
    }
    // The tick box state of a row, found by its title.
    private fun ticked(title: String): Boolean? {
        var n: AccessibilityNodeInfo? = nodes().firstOrNull { it.isVisibleToUser && it.text?.toString() == title } ?: return null
        while (n != null && !n.isCheckable) n = n.parent
        return n?.isChecked
    }

    @Test fun importsTickedEventsAsSeriesAndUndoes() = runBlocking {
        val stamp = DateTimeFormatter.BASIC_ISO_DATE
        fun at(date: LocalDate, time: String) = "${date.format(stamp)}T$time"
        app.repository.saveItem(ItineraryItem(tripId = 0, date = today.plusDays(1), startTime = LocalTime.of(12, 0), title = "QA Import duplicate"))
        val file = File(context.cacheDir, "qa-calendar-import.ics")
        file.writeText("BEGIN:VCALENDAR\r\nVERSION:2.0\r\n" + listOf(
            "UID:past\r\nDTSTART:${at(today.minusDays(400), "090000")}\r\nDURATION:PT1H\r\nSUMMARY:QA Import past",
            "UID:soon\r\nDTSTART:${at(today.plusDays(1), "100000")}\r\nDURATION:PT30M\r\nSUMMARY:QA Import soon\r\nLOCATION:Room 1",
            "UID:dup\r\nDTSTART:${at(today.plusDays(1), "120000")}\r\nSUMMARY:QA Import duplicate",
            "UID:untick\r\nDTSTART:${at(today.plusDays(1), "150000")}\r\nSUMMARY:QA Import untick",
            "UID:weekly\r\nDTSTART:${at(today.plusDays(2), "180000")}\r\nDURATION:PT1H\r\nRRULE:FREQ=WEEKLY;COUNT=6\r\nSUMMARY:QA Import weekly",
        ).joinToString("") { "BEGIN:VEVENT\r\n$it\r\nEND:VEVENT\r\n" } + "END:VCALENDAR\r\n")
        try {
            app.settings.lastViewCalendar = false
            ins.startActivitySync(Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW)
                .setDataAndType(Uri.fromFile(file), "text/calendar")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            await { find("Import calendar file") != null && find("5 events in this file (1 repeating)") != null }
            screenshot("list")
            // Upcoming ticked; past and already-in-Planner not.
            await { find("Add 3 events") != null }
            reveal { ticked("QA Import soon") == true }
            reveal { ticked("QA Import weekly") == true }
            assertNotNull(find("Weekly · 6 dates"))
            reveal { ticked("QA Import duplicate") == false && find("Already in Planner") != null }
            reveal { ticked("QA Import past") == false && find("Past event") != null }
            click("Include past events (1)")
            await { find("Add 4 events") != null }
            click("Include past events (1)")
            await { find("Add 3 events") != null }
            click("QA Import untick")
            await { find("Add 2 events") != null }
            screenshot("chosen")
            click("Add 2 events")
            await { find("Calendar imported") != null && find("Added 7 events.") != null }
            screenshot("added")
            val items = data().items
            val weekly = items.filter { it.title == "QA Import weekly" }
            assertEquals(6, weekly.size)
            assertEquals(1, weekly.map { it.seriesId }.distinct().size)
            assertNotNull(weekly.first().seriesId)
            assertTrue(weekly.all { it.repeatRule == "WEEKLY" && it.startTime == LocalTime.of(18, 0) && it.durationMinutes == 60 })
            assertEquals((0L until 6).map { today.plusDays(2).plusWeeks(it) }, weekly.map { it.date }.sorted())
            assertEquals("Room 1", items.single { it.title == "QA Import soon" }.location)
            assertTrue(items.none { it.title == "QA Import untick" || it.title == "QA Import past" })
            assertEquals(1, items.count { it.title == "QA Import duplicate" })

            click("Undo import")
            await { data().items.none { it.title.startsWith("QA Import") && it.title != "QA Import duplicate" } }
            assertEquals(1, data().items.count { it.title == "QA Import duplicate" })
            assertEquals(1, data().deleted.size) // all 7 in one Recently deleted entry
            await { find("AGENDA") != null }
            screenshot("undone")
        } finally {
            file.delete()
        }
    }
}
