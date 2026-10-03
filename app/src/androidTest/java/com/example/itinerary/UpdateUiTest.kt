package com.example.itinerary

import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.example.itinerary.data.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** The update pop-up against a fake GitHub: offered at start-up with the release notes, Later, Check now in Settings,
 *  Update now → progress → Install now, which (first) opens Android's "install unknown apps" page and then Android's own
 *  installer. The APK served is this test app's own file, so the installer has a real app to show; it is cancelled. */
@Suppress("DEPRECATION")
class UpdateUiTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = ins.targetContext
    private val app get() = context.applicationContext as ItineraryApp
    private fun nodes(): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(n: AccessibilityNodeInfo) { result += n; for (i in 0 until n.childCount) n.getChild(i)?.let(::visit) }
        ins.uiAutomation.freshRoot?.let(::visit); return result
    }
    private fun find(text: String) = nodes().firstOrNull { it.isVisibleToUser && (it.text?.toString() == text || it.contentDescription?.toString() == text) }
    private fun screenshot(name: String) {
        val dir = File(context.cacheDir, "qa-updates").apply { mkdirs() }
        ins.uiAutomation.takeScreenshot()?.let { b -> File(dir, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }; b.recycle() }
    }
    private fun await(timeout: Long = 20000, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < end) { if (runCatching(condition).getOrDefault(false)) return; Thread.sleep(150) }
        screenshot("failure"); fail("Timed out: " + nodes().filter { it.isVisibleToUser }.mapNotNull { it.text ?: it.contentDescription }.joinToString(" | "))
    }
    private fun click(text: String) {
        await { var n = find(text); while (n != null && !n.isClickable) n = n.parent; n?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true }
        Thread.sleep(400)
    }
    private fun scrollTo(text: String) = await {
        find(text) != null || nodes().firstOrNull { it.isScrollable && !it.isEditable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD).let { Thread.sleep(300); false }
    }
    private fun shell(command: String) = ins.uiAutomation.executeShellCommand(command).use { pfd ->
        android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().readText() }
    private fun resumed() = shell("dumpsys activity activities").lineSequence().firstOrNull { "topResumedActivity" in it }.orEmpty()

    @Test fun offeredAtStartThenDownloadedCheckedAndHandedToAndroid() {
        val fake = FakeGitHub(apk = File(context.packageCodePath).readBytes())
        val updates = app.updates
        val original = updates.api
        Updates.prefs(context).edit().clear().commit()
        updates.api = ReleaseApi(fake.http, fake.base); updates.supported = true
        // Changing this permission restarts the app, so it is only read here: the run starts with it not given.
        assertTrue(shell("appops get ${context.packageName} REQUEST_INSTALL_PACKAGES").let { "allow" !in it })
        try {
            app.settings.lastViewCalendar = false
            ins.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            // Start-up check: the pop-up, with the release notes.
            await { find("Planner 0.0.15 is available") != null }
            await { find("Faster") != null || nodes().any { it.text?.toString()?.contains("Faster") == true } }
            assertNotNull(find("You have ${updates.installedVersion}."))
            assertNotNull(find("Skip this version")); screenshot("offered")
            // The support ask sits with the new version's notes, and opens the Ko-fi page.
            assertTrue(nodes().any { it.text?.toString()?.startsWith("Planner is free and open source") == true })
            val filter = android.content.IntentFilter(Intent.ACTION_VIEW).apply { addCategory(Intent.CATEGORY_DEFAULT); addDataScheme("https"); addDataAuthority("ko-fi.com", null) }
            val monitor = android.app.Instrumentation.ActivityMonitor(filter, android.app.Instrumentation.ActivityResult(0, null), true)
            ins.addMonitor(monitor)
            try { click("Support on Ko-fi"); await(10000) { monitor.hits == 1 } } finally { ins.removeMonitor(monitor) }
            assertNotNull(find("Planner 0.0.15 is available")) // the pop-up stays
            click("Later"); await { find("Planner 0.0.15 is available") == null }

            // Settings → Updates → Check now offers it again.
            await { find("AGENDA") != null }
            if (find("Settings") == null) click("More options")
            click("Settings"); scrollTo("Check now")
            assertNotNull(find("Check for updates when Planner starts")); assertNotNull(find("Download updates automatically"))
            click("Check now")
            await { find("Planner 0.0.15 is available") != null }
            click("Update now")
            await(60000) { find("Install now") != null }
            screenshot("ready")
            assertNotNull(find("Skip this version")) // UP-2: a ready update can be skipped too
            assertEquals(fake.apk.size.toLong(), (updates.state.value as Updates.State.Ready).file.length())

            // Not yet allowed to install apps: Android's page for that opens. (Allowing it there restarts the app, so that
            // part is the stage test below, with the permission set from outside.)
            click("Install now")
            await { "com.android.settings" in resumed() }
            screenshot("allow-page")
            shell("input keyevent KEYCODE_BACK"); await { context.packageName in resumed() }
        } finally {
            updates.supported = false; updates.later() // no offer left over for the next test; updates.api = original
            Updates.prefs(context).edit().clear().commit()
            File(context.cacheDir, "updates").deleteRecursively()
            fake.server.shutdown()
        }
    }

    // By hand, after `adb shell appops set io.github.baslawson.planner.uitest REQUEST_INSTALL_PACKAGES allow` (which
    // restarts the app): a checked download from before the restart is offered at once, and Install now opens Android's
    // own installer (cancelled here). Put the permission back to default afterwards.
    @HarnessStage @Test fun allowedThenInstallerAsks() {
        val fake = FakeGitHub(apk = File(context.packageCodePath).readBytes())
        val updates = app.updates
        Updates.prefs(context).edit().clear().commit()
        updates.api = ReleaseApi(fake.http, fake.base); updates.supported = true
        try {
            assertTrue(context.packageManager.canRequestPackageInstalls())
            kotlinx.coroutines.runBlocking { updates.check(); updates.download((updates.state.value as Updates.State.Available).release) }
            app.settings.lastViewCalendar = false
            ins.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            await { find("Install now") != null }
            click("Install now")
            await { find("Update this app?") != null || "packageinstaller" in resumed() }
            Thread.sleep(1500); screenshot("android-installer")
            // Cancelled with the installer's own button (Back doesn't always reach its dialog).
            click("Cancel")
            await { find("Update this app?") == null }
        } finally {
            updates.supported = false; updates.later() // no offer left over for the next test
            Updates.prefs(context).edit().clear().commit()
            File(context.cacheDir, "updates").deleteRecursively()
            fake.server.shutdown()
        }
    }
}
