package com.example.itinerary

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.example.itinerary.data.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.URLDecoder

/**
 * Wish list #6: ⋮ → Report a bug shows exactly what will be sent, the last crash only when ticked, and opens a new GitHub
 * issue with it. The browser is never opened: the test catches the link. It puts back the last crash and start screen it
 * changes (SR-11); opening GitHub clears the crash it sent, which is put back too.
 */
class BugReportUiTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = ins.targetContext
    private val app get() = context.applicationContext as ItineraryApp

    private fun nodes(): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(n: AccessibilityNodeInfo) { result += n; for (i in 0 until n.childCount) n.getChild(i)?.let(::visit) }
        ins.uiAutomation.windows.mapNotNull { it.root }.ifEmpty { listOfNotNull(ins.uiAutomation.rootInActiveWindow) }.forEach(::visit)
        return result
    }
    private fun find(text: String) = nodes().firstOrNull { it.isVisibleToUser && (it.text?.toString() == text || it.contentDescription?.toString() == text) }
    private fun has(part: String) = nodes().any { it.isVisibleToUser && it.text?.toString()?.contains(part) == true }
    private fun screenshot(name: String) {
        val dir = File(context.cacheDir, "qa-bug-report").apply { mkdirs() }
        ins.uiAutomation.takeScreenshot()?.let { b -> File(dir, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }; b.recycle() }
    }
    private fun await(timeout: Long = 30000, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < end) { if (condition()) return; Thread.sleep(150) }
        screenshot("failure"); fail("Timed out: " + nodes().mapNotNull { it.text }.joinToString(" | "))
    }
    private fun click(text: String) {
        await {
            var n = find(text)
            while (n != null && !n.isClickable) n = n.parent
            n?.takeIf { it.isEnabled }?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        }; Thread.sleep(500)
    }

    // SR-11: the person's own last crash and start screen are put back afterwards, harness or not.
    private val crashFile get() = File(context.filesDir, "last-crash.txt")
    private val savedCrash = crashFile.takeIf { it.isFile }?.readBytes()
    private val savedStart = app.settings.startScreen.value

    @After fun done() {
        if (savedCrash != null) crashFile.writeBytes(savedCrash) else CrashLog(context.filesDir).clear()
        app.settings.setStartScreen(savedStart)
    }

    @Test fun aReportShowsWhatIsSentAndOpensAGitHubIssue() {
        CrashLog(context.filesDir).write(System.currentTimeMillis(), "0.0.15 (22)", IllegalStateException("QA crash for the report"))
        app.settings.setStartScreen(StartScreen.AGENDA)
        ins.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        await { find("AGENDA") != null }
        click("More options"); click("Report a bug")
        await { find("What happened?") != null }
        val field = nodes().first { it.isEditable && it.isVisibleToUser }
        field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "QA the widget shows yesterday") })
        await { has("QA the widget shows yesterday\n\n---\nPlanner ") }
        // The crash is only in the report once ticked.
        assertFalse(has("QA crash for the report"))
        click("Include the last crash".let { label -> nodes().first { it.text?.toString()?.startsWith(label) == true }.text.toString() })
        await { has("QA crash for the report") }
        screenshot("report-preview")
        // Catches the browser's link (and stops it opening).
        var seen: Intent? = null
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? =
                if (intent.action == Intent.ACTION_VIEW && intent.data?.host == "github.com") { seen = intent; Instrumentation.ActivityResult(0, null) } else null
        }
        ins.addMonitor(monitor)
        try {
            click("Open GitHub")
            await { seen != null }
            val link = seen!!.data.toString()
            assertTrue(link.startsWith("https://github.com/baslawson/Planner/issues/new?title="))
            val body = URLDecoder.decode(link.substringAfter("&body="), "UTF-8")
            assertTrue(body.startsWith("QA the widget shows yesterday"))
            assertTrue(body.contains("QA crash for the report"))
        } finally { ins.removeMonitor(monitor) }
        await { find("What happened?") == null }
        // SR-5: a crash that went in a report isn't offered again.
        assertNull(CrashLog(context.filesDir).read())
    }
}
