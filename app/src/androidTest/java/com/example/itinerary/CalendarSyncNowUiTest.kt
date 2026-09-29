package com.example.itinerary

import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.example.itinerary.data.*
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Settings → Calendars → Sync now says when it's done (and what went wrong), right by the button. Uses a subscribed
 *  link on a local HTTPS server, so no real calendar is involved. */
@Suppress("DEPRECATION")
class CalendarSyncNowUiTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = ins.targetContext
    private val app get() = context.applicationContext as ItineraryApp
    private fun nodes(): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(n: AccessibilityNodeInfo) { result += n; for (i in 0 until n.childCount) n.getChild(i)?.let(::visit) }
        ins.uiAutomation.freshRoot?.let(::visit); return result
    }
    private fun find(text: String) = nodes().firstOrNull { it.isVisibleToUser && (it.text?.toString() == text || it.contentDescription?.toString() == text) }
    private fun starts(prefix: String) = nodes().any { it.isVisibleToUser && it.text?.toString()?.startsWith(prefix) == true }
    private fun screenshot(name: String) {
        val dir = File(context.cacheDir, "qa-sync-now-evidence").apply { mkdirs() }
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

    @Test fun syncNowSaysWhenItsDoneAndWhatWentWrong() {
        val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val clientCertificates = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val server = MockWebServer()
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(certificate).build().sslSocketFactory(), false)
        val failing = java.util.concurrent.atomic.AtomicBoolean(false)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = if (failing.get()) MockResponse().setResponseCode(500)
                else MockResponse().setResponseCode(200).setHeader("Content-Type", "text/calendar")
                    .setBody("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nX-WR-CALNAME:QA Feed\r\nEND:VCALENDAR\r\n")
        }
        server.start()
        val original = app.calendarSync.linkClient
        try {
            app.calendarSync.linkClient = CalendarLinkClient(OkHttpClient.Builder()
                .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager).build())
            runBlocking { app.calendarSync.addLink(server.url("/feed.ics").toString(), null) }
            app.settings.lastViewCalendar = false
            ins.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            await { find("AGENDA") != null }
            click("Settings")
            click("Calendars")
            await { find("Sync now") != null }
            assertFalse("Nothing is claimed before a sync", starts("Synced"))
            click("Sync now")
            await { starts("Synced just now · ") }
            screenshot("done")
            // The time is there, so a second sync visibly happened even when nothing changed.
            val first = nodes().first { it.text?.toString()?.startsWith("Synced just now · ") == true }.text.toString()
            assertTrue(first.matches(Regex("Synced just now · \\d{1,2}:\\d{2}( ?[AaPp][Mm])?")))
            failing.set(true)
            click("Sync now")
            await { starts("Synced at ") && nodes().any { it.text?.toString()?.contains("1 calendar couldn't be updated") == true } }
            screenshot("failed")
        } finally {
            app.calendarSync.linkClient = original
            server.shutdown()
        }
    }
}
