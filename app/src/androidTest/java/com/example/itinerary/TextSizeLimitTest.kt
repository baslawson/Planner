package com.example.itinerary

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import com.example.itinerary.data.SettingsRepository
import com.example.itinerary.data.TextSize
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import org.junit.After
import org.junit.Assert.fail
import org.junit.Assert.assertEquals
import org.junit.Test

// Text size tops out at 125 %: a larger saved value (from an older version) is read as 125, and 150 is stored as 125.
class TextSizeLimitTest {
    private val base get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val context: Context get() = object : ContextWrapper(base) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = base.getSharedPreferences("text_size_limit_$name", mode)
    }
    @After fun cleanup() { base.deleteSharedPreferences("text_size_limit_settings") }

    @Test fun textSizeStopsAt125() {
        assertEquals(125, TextSize.MAX_PERCENT)
        context.getSharedPreferences("settings", 0).edit().clear().putInt("text_size_percent", 150).commit()
        val settings = SettingsRepository(context)
        assertEquals(125, settings.textSizePercent.value)
        settings.setTextSizePercent(150)
        assertEquals(125, settings.textSizePercent.value)
        assertEquals(125, context.getSharedPreferences("settings", 0).getInt("text_size_percent", 0))
        settings.setTextSizePercent(110)
        assertEquals(110, SettingsRepository(context).textSizePercent.value)
    }

    // In the real Settings page the slider ends at 125.
    @Test fun settingsSliderEndsAt125() {
        val ins = InstrumentationRegistry.getInstrumentation()
        fun nodes(): List<AccessibilityNodeInfo> {
            val result = mutableListOf<AccessibilityNodeInfo>()
            fun visit(n: AccessibilityNodeInfo) { result += n; for (i in 0 until n.childCount) n.getChild(i)?.let(::visit) }
            ins.uiAutomation.freshRoot?.let(::visit); return result
        }
        fun await(condition: () -> Boolean) {
            val end = SystemClock.uptimeMillis() + 30000
            while (SystemClock.uptimeMillis() < end) { if (condition()) return; Thread.sleep(150) }
            fail("Timed out: " + nodes().mapNotNull { it.text ?: it.contentDescription }.joinToString(" | "))
        }
        fun click(label: String) = await {
            var n = nodes().firstOrNull { it.isVisibleToUser && (it.text?.toString() == label || it.contentDescription?.toString() == label) }
            while (n != null && !n.isClickable) n = n.parent
            n?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        }
        ins.startActivitySync(Intent(base, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        await { nodes().any { it.text?.toString() == "AGENDA" } }
        click("More options"); Thread.sleep(400); click("Settings")
        fun slider() = nodes().firstOrNull { it.contentDescription?.toString() == "Text size" && it.rangeInfo != null }
        await {
            if (slider()?.isVisibleToUser == true) true
            else { nodes().firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD); Thread.sleep(300); false }
        }
        val range = slider()!!.rangeInfo
        assertEquals(80f, range.min, 0.01f)
        assertEquals(125f, range.max, 0.01f)
        val dir = java.io.File(base.cacheDir, "qa-text-size-evidence").apply { mkdirs() }
        ins.uiAutomation.takeScreenshot()?.let { b -> java.io.File(dir, "settings-slider.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }; b.recycle() }
    }
}
