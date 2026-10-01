package com.example.itinerary

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
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
import java.util.Locale

/**
 * T3: Quick entry's Add on the Agenda scrolls to the new event when it lands on a day already listed far down (the
 * list used to wait only for a new day, then said it in the bar instead).
 * Run only with an external backup/restore harness for the shared emulator (it adds events).
 */
@Suppress("DEPRECATION")
class AgendaFollowUiTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = ins.targetContext
    private val app get() = context.applicationContext as ItineraryApp
    private fun data() = runBlocking { app.repository.snapshot() }
    private fun nodes(): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(n: AccessibilityNodeInfo) { result += n; for (i in 0 until n.childCount) n.getChild(i)?.let(::visit) }
        ins.uiAutomation.freshRoot?.let(::visit); return result
    }
    private fun find(text: String) = nodes().firstOrNull { it.isVisibleToUser && !it.isEditable && (it.text?.toString() == text || it.contentDescription?.toString() == text) }
    private fun screenshot(name: String) {
        val dir = File(context.cacheDir, "qa-agenda-follow").apply { mkdirs() }
        ins.uiAutomation.takeScreenshot()?.let { b -> File(dir, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }; b.recycle() }
    }
    private fun await(timeout: Long = 30000, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < end) { if (condition()) return; Thread.sleep(150) }
        screenshot("failure"); fail("Timed out: " + nodes().mapNotNull { it.text }.joinToString(" | "))
    }
    private fun click(text: String) {
        if (text == "Quick entry" && find(text) == null && find("Add menu") != null) click("Add menu")
        await {
            var node = find(text)
            while (node != null && !node.isClickable) node = node.parent
            node?.takeIf { it.isEnabled }?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        }; Thread.sleep(350)
    }

    @Test fun quickAddToADayAlreadyListedScrollsThere() = runBlocking {
        app.settings.setAgendaRange(AgendaRange.ALL)
        val far = LocalDate.now().plusDays(40)
        // One event a day, so the far day is listed already and well below the first screen.
        for (d in 1L..40L) app.repository.saveItem(ItineraryItem(tripId = 0, date = LocalDate.now().plusDays(d),
            startTime = LocalTime.of(9, 0), title = "QA follow day $d"))
        app.settings.lastViewCalendar = false
        ins.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        await { find("AGENDA") != null }
        assertNull(find("QA follow day 40"))
        click("Quick entry")
        val words = far.format(DateTimeFormatter.ofPattern("d MMMM", Locale.ENGLISH)).lowercase()
        await { pickEditable(nodes(), "") != null }
        assertTrue(pickEditable(nodes(), "")!!.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "QA follow target $words 3 pm")
        })); Thread.sleep(350)
        click("Add event")
        await { data().items.any { it.title == "QA follow target" && it.date == far } }
        // The list goes to it, and the bar doesn't say it instead.
        await(5000) { find("QA follow target") != null }
        Thread.sleep(2000)
        assertNotNull(find("QA follow target"))
        assertTrue(nodes().none { it.text?.toString()?.startsWith("Added: QA follow target") == true })
        screenshot("followed")
    }
}
