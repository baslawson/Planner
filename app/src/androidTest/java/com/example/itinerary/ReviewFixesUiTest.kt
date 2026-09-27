package com.example.itinerary

import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.test.platform.app.InstrumentationRegistry
import com.example.itinerary.data.ItineraryItem
import com.example.itinerary.data.Search
import com.example.itinerary.data.Trip
import com.example.itinerary.ui.DateRangeDialog
import com.example.itinerary.ui.SingleDateDialog
import com.example.itinerary.ui.rememberCurrentDate
import com.example.itinerary.ui.rememberSearchOutcome
import com.example.itinerary.ui.theme.ItineraryTheme
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZonedDateTime

/** Uses isolated UI content; run with the normal external data backup/restore harness. */
class ReviewFixesUiTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private fun launch() = ins.startActivitySync(Intent(ins.targetContext, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
    private fun find(node: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString() == text) return node
        for (i in 0 until node.childCount) find(node.getChild(i), text)?.let { return it }
        return null
    }
    private fun awaitText(text: String): AccessibilityNodeInfo {
        val deadline = SystemClock.elapsedRealtime() + 10000
        while (SystemClock.elapsedRealtime() < deadline) {
            find(ins.uiAutomation.rootInActiveWindow, text)?.let { return it }
            Thread.sleep(50)
        }
        error("Never displayed $text")
    }
    private fun click(text: String) {
        var node: AccessibilityNodeInfo? = awaitText(text)
        while (node != null && !node.isClickable) node = node.parent
        assertTrue(node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
        ins.waitForIdleSync()
    }

    @Test fun singlePickerPreservesDatesOutsideDefaultRange() {
        for (date in listOf(LocalDate.of(1899, 12, 31), LocalDate.of(2101, 1, 1))) {
            val activity = launch()
            val confirmed = java.util.concurrent.atomic.AtomicReference<LocalDate>()
            try {
                ins.runOnMainSync { activity.setContent {
                    ItineraryTheme { SingleDateDialog(date, {}, { confirmed.set(it) }) }
                } }
                click("Set date")
                assertEquals(date, confirmed.get())
            } finally { ins.runOnMainSync { activity.finish() } }
        }
    }

    @Test fun rangePickerPreservesBothOutOfRangeEndpoints() {
        val activity = launch()
        val start = LocalDate.of(1899, 12, 31)
        val end = LocalDate.of(2101, 1, 1)
        val confirmed = java.util.concurrent.atomic.AtomicReference<Pair<LocalDate, LocalDate>>()
        try {
            ins.runOnMainSync { activity.setContent {
                ItineraryTheme { DateRangeDialog(start, end, {}, { a, b -> confirmed.set(a to b) }) }
            } }
            click("Set dates")
            assertEquals(start to end, confirmed.get())
        } finally { ins.runOnMainSync { activity.finish() } }
    }

    @Test fun relativeSearchRefreshesAtMidnightWithUnchangedQueryAndIndex() {
        val activity = launch()
        val initial = ZonedDateTime.parse("2000-01-01T23:59:56+08:00[Australia/Perth]")
        val date = initial.toLocalDate()
        val index = Search.prepare(
            listOf(Trip(id = 1, name = "Test", destination = "", startDate = date, endDate = date.plusDays(2))),
            (0L..2L).map { ItineraryItem(id = it + 1, tripId = 1, date = date.plusDays(it), startTime = null, title = "Day $it") },
            emptyList(),
        )
        val started = SystemClock.elapsedRealtime()
        try {
            ins.runOnMainSync { activity.setContent {
                ItineraryTheme {
                    val today = rememberCurrentDate { initial.plusNanos((SystemClock.elapsedRealtime() - started) * 1_000_000) }
                    val current = rememberSearchOutcome("today", emptySet(), index, today)
                    val next = rememberSearchOutcome("tomorrow", emptySet(), index, today)
                    Text("Today: ${current.hits.singleOrNull()?.item?.title}; Tomorrow: ${next.hits.singleOrNull()?.item?.title}")
                }
            } }
            awaitText("Today: Day 0; Tomorrow: Day 1")
            awaitText("Today: Day 1; Tomorrow: Day 2")
        } finally { ins.runOnMainSync { activity.finish() } }
    }
}
