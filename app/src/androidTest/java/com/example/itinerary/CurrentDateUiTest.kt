package com.example.itinerary

import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.test.platform.app.InstrumentationRegistry
import com.example.itinerary.ui.rememberCurrentDate
import com.example.itinerary.ui.theme.ItineraryTheme
import org.junit.Assert.*
import org.junit.Test
import java.time.ZonedDateTime

class CurrentDateUiTest {
    @Test fun visibleScreenAdvancesAtMidnightWithoutResume() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val initial = ZonedDateTime.parse("2000-01-01T23:59:57+08:00[Australia/Perth]")
        val started = SystemClock.elapsedRealtime()
        try {
            instrumentation.runOnMainSync {
                activity.setContent {
                    ItineraryTheme {
                        val today = rememberCurrentDate { initial.plusNanos((SystemClock.elapsedRealtime() - started) * 1_000_000) }
                        Text("Observed date: $today")
                    }
                }
            }
            fun contains(node: AccessibilityNodeInfo?, text: String): Boolean {
                if (node == null) return false
                if (node.text?.toString() == text) return true
                return (0 until node.childCount).any { contains(node.getChild(it), text) }
            }
            fun awaitDate(text: String) {
                val deadline = SystemClock.elapsedRealtime() + 7000
                while (SystemClock.elapsedRealtime() < deadline) {
                    if (contains(instrumentation.uiAutomation.rootInActiveWindow, text)) return
                    Thread.sleep(50)
                }
                fail("Never displayed $text")
            }
            awaitDate("Observed date: 2000-01-01")
            awaitDate("Observed date: 2000-01-02")
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
}
