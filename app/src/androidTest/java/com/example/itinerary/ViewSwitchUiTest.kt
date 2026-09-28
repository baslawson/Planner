package com.example.itinerary

import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.example.itinerary.data.ItineraryItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/** Requires external backup/restore of the shared emulator's app data. */
@Suppress("DEPRECATION")
class ViewSwitchUiTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = ins.targetContext.applicationContext as ItineraryApp
    private val date = LocalDate.of(2036, 4, 12)
    private val prefix = "QA switch "

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
    private fun await(check: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 20_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (check()) return
            Thread.sleep(20)
        }
        fail("Timed out; visible text: " + nodes().mapNotNull { it.text }.joinToString(" | "))
    }
    private fun click(label: String) {
        await { find(label) != null }
        var node = find(label)
        while (node != null && !node.isClickable) node = node.parent
        assertTrue("Click $label", node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
    }
    private fun settled(title: String, other: String): Boolean {
        val labels = nodes().mapNotNull { it.text?.toString() }
        return title in labels && other !in labels
    }
    private fun switch(toCalendar: Boolean): Double {
        val target = if (toCalendar) "CALENDAR" else "AGENDA"
        val old = if (toCalendar) "AGENDA" else "CALENDAR"
        val start = SystemClock.elapsedRealtimeNanos()
        click(if (toCalendar) "Switch to Calendar view" else "Switch to Agenda view")
        await { settled(target, old) }
        return (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
    }
    private fun openCalendar(day: LocalDate = date) {
        ins.targetContext.startActivity(Intent(ins.targetContext, MainActivity::class.java).apply {
            action = "com.example.itinerary.widget.OPEN_DATE"
            putExtra("widget_date", day.toString())
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        })
        await { settled("CALENDAR", "AGENDA") }
        Thread.sleep(900)
    }
    private fun seed() = runBlocking {
        app.repository.snapshot().items.firstOrNull { it.title == prefix + "updated" }?.let {
            app.repository.saveItem(it.copy(title = prefix + "23"))
        }
        if (app.repository.snapshot().items.none { it.title.startsWith(prefix) }) {
            repeat(24) { i ->
                app.repository.saveItem(ItineraryItem(
                    tripId = 0, date = date, startTime = LocalTime.of(i, 0), title = prefix + i,
                ))
            }
        }
    }

    @Test fun switchTiming() {
        seed()
        openCalendar()
        switch(false); Thread.sleep(900)
        switch(true); Thread.sleep(900)
        val samples = mutableListOf<Double>()
        repeat(8) { i ->
            samples += switch(i % 2 == 1)
            Thread.sleep(900)
        }
        ins.sendStatus(0, Bundle().apply {
            putString("stream", "\nVIEW_SWITCH_MS=" + samples.joinToString(",") +
                " MEDIAN=" + samples.sorted().let { (it[3] + it[4]) / 2 } + "\n")
        })
    }

    @Test fun retainsScrollAndRefreshesDataWithExplicitDateOverride() {
        seed()
        openCalendar()
        if (find("Collapse") != null) click("Collapse")
        Thread.sleep(900)
        await { find(prefix + "0") != null }
        var attempts = 0
        while (find(prefix + "23") == null && attempts++ < 35) {
            val list = nodes().filter { it.isScrollable && it.isVisibleToUser }.maxByOrNull {
                val r = Rect(); it.getBoundsInScreen(r); r.height()
            }
            assertNotNull(list)
            val bounds = Rect(); list!!.getBoundsInScreen(bounds)
            ins.uiAutomation.executeShellCommand(
                "input swipe ${bounds.left + 20} ${bounds.bottom - 40} ${bounds.left + 20} ${bounds.top + 40} 350",
            ).use { fd -> java.io.FileInputStream(fd.fileDescriptor).use { it.readBytes() } }
            Thread.sleep(350)
        }
        assertNotNull(find(prefix + "23"))
        switch(false); Thread.sleep(900)
        switch(true); Thread.sleep(900)
        assertNotNull("Calendar scroll should survive toggling", find(prefix + "23"))
        switch(false)
        Thread.sleep(5500) // Exercise the stopped WhileSubscribed flow, not only its warm grace period.
        runBlocking {
            val item = app.repository.snapshot().items.single { it.title == prefix + "23" }
            app.repository.saveItem(item.copy(title = prefix + "updated"))
        }
        switch(true)
        await { find(prefix + "updated") != null }
        click("More options"); click("Settings")
        await { find("Settings") != null }
        click("Back")
        await { settled("CALENDAR", "AGENDA") }
        switch(false); Thread.sleep(900)
        // Warm widget intent must discard the old saved Calendar instead of restoring its date.
        ins.targetContext.startActivity(Intent(ins.targetContext, MainActivity::class.java).apply {
            action = "com.example.itinerary.widget.OPEN_DATE"
            putExtra("widget_date", "2036-06-21")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
        await { find("Saturday 21 June 2036") != null }
        switch(false); Thread.sleep(900)
        switch(true)
        await { find("Saturday 21 June 2036") != null }
        switch(false); Thread.sleep(900)
        click("Search")
        await { nodes().any { it.isEditable && it.isVisibleToUser } }
        val query = nodes().first { it.isEditable && it.isVisibleToUser }
        assertTrue(query.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, prefix + "0")
        }))
        await { find(prefix + "0") != null }
        click(prefix + "0")
        await { settled("CALENDAR", "Search") }
        await { find("Saturday 12 April 2036") != null }
        Thread.sleep(900)
        switch(false); Thread.sleep(900)
        switch(true)
        await { find("Saturday 12 April 2036") != null }
    }
}
