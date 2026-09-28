package com.example.itinerary

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import com.example.itinerary.data.*
import com.example.itinerary.widget.TodayWidget
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.LocalTime

class TodayWidgetTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    @Test fun renderedWidgetHandlesEmptyOverflowOvernightAndClick() {
        val context = instrumentation.targetContext
        val day = LocalDate.now()
        val events = (1L..8L).map { ItineraryItem(id = it, tripId = 1, date = day, startTime = LocalTime.of(9, 0), title = "Widget event $it", durationMinutes = 60) }
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            instrumentation.runOnMainSync {
                val empty = TodayWidget.render(context, emptyList(), day, TimeFormat.HOUR_24, 220).apply(activity, null)
                assertEquals(View.VISIBLE, empty.findViewById<View>(R.id.widget_empty).visibility)
                val filled = TodayWidget.render(context, events, day, TimeFormat.HOUR_24, 300).apply(activity, null)
                val rows = filled.findViewById<LinearLayout>(R.id.widget_rows)
                assertTrue(rows.childCount in 1..6)
                assertTrue(rows.childCount < events.size)
                assertEquals("View all 8 entries ›", filled.findViewById<TextView>(R.id.widget_open).text.toString())
                assertTrue(rows.getChildAt(0).findViewById<TextView>(R.id.widget_event_time).text.contains("09:00–10:00"))
                val overnight = TodayWidget.render(context, listOf(events[0].copy(date = day.minusDays(1), startTime = LocalTime.of(23, 30), durationMinutes = 120)), day, TimeFormat.HOUR_24, 300).apply(activity, null)
                assertTrue(overnight.findViewById<TextView>(R.id.widget_event_time).text.contains("From yesterday"))
                activity.setContentView(filled)
            }
            screenshot("widget-rendered")
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    @Test fun actualWidgetHostReceivesProviderUpdatesAndOpensToday() {
        val context = instrumentation.targetContext
        val manager = AppWidgetManager.getInstance(context)
        val host = AppWidgetHost(context, 4081)
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        var id = -1
        var hostedView: View? = null
        try {
            instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.BIND_APPWIDGET")
            id = host.allocateAppWidgetId()
            val options = Bundle().apply {
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 300)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 280)
            }
            assertTrue("Widget binding denied", manager.bindAppWidgetIdIfAllowed(id, ComponentName(context, TodayWidget::class.java), options))
            instrumentation.uiAutomation.dropShellPermissionIdentity()
            instrumentation.runOnMainSync {
                host.startListening()
                hostedView = host.createView(activity, id, manager.getAppWidgetInfo(id))
                activity.addContentView(hostedView, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
            TodayWidget.requestUpdate(context)
            await { find("Open today’s calendar") != null || nodes().any { it.text?.startsWith("View all") == true } }
            val repo = (context.applicationContext as ItineraryApp).repository
            runBlocking { repo.saveItem(ItineraryItem(tripId = 0, date = LocalDate.now(), startTime = null, title = "QA widget refresh")) }
            await { find("QA widget refresh") != null }
            val saved = runBlocking { repo.snapshot().items.single { it.title == "QA widget refresh" } }
            runBlocking { repo.saveItem(saved.copy(title = "QA widget updated")) }
            await { find("QA widget updated") != null }
            screenshot("widget-real-host")
            val text = nodes().first { it.text?.toString() == "Open today’s calendar" || it.text?.startsWith("View all") == true }
            var click: AccessibilityNodeInfo? = text
            while (click != null && !click.isClickable) click = click.parent
            assertTrue(click?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
            instrumentation.runOnMainSync { (hostedView?.parent as? ViewGroup)?.removeView(hostedView) }
            await { find("CALENDAR") != null }
            screenshot("widget-opened-calendar")
            runBlocking { repo.deleteItem(saved) }
        } finally {
            instrumentation.uiAutomation.dropShellPermissionIdentity()
            instrumentation.runOnMainSync { host.stopListening(); activity.finish() }
            if (id >= 0) host.deleteAppWidgetId(id)
            host.deleteHost()
        }
    }
    private fun nodes(): List<AccessibilityNodeInfo> {
        val all = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo) { all.add(node); for (i in 0 until node.childCount) node.getChild(i)?.let(::visit) }
        instrumentation.uiAutomation.freshRoot?.let(::visit)
        return all
    }
    private fun find(text: String) = nodes().firstOrNull { it.text?.toString() == text }
    private fun await(test: () -> Boolean) {
        val limit = SystemClock.uptimeMillis() + 15000
        while (SystemClock.uptimeMillis() < limit) { if (test()) return; Thread.sleep(100) }
        screenshot("widget-failure")
        fail("Widget did not reach expected state: " + nodes().mapNotNull { it.text }.joinToString(" | "))
    }
    private fun screenshot(name: String) {
        Thread.sleep(500)
        val dir = File(instrumentation.targetContext.cacheDir, "qa-four-evidence").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot()?.let { image ->
            File(dir, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
        }
    }
}
