package com.example.itinerary

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
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
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Calendar sync step 4 in the real app: Settings → Calendars → Add link against a local HTTPS feed, then Remove. */
@Suppress("DEPRECATION")
class CalendarLinkUiTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = ins.targetContext
    private val app get() = context.applicationContext as ItineraryApp
    private fun nodes(): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(n: AccessibilityNodeInfo) { result += n; for (i in 0 until n.childCount) n.getChild(i)?.let(::visit) }
        ins.uiAutomation.freshRoot?.let(::visit); return result
    }
    private fun find(text: String) = nodes().firstOrNull { it.isVisibleToUser && (it.text?.toString() == text || it.contentDescription?.toString() == text) }
    private fun has(part: String) = nodes().any { it.isVisibleToUser && it.text?.toString()?.contains(part) == true }
    private fun screenshot(name: String) {
        val dir = File(context.cacheDir, "qa-calendar-link-evidence").apply { mkdirs() }
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
    // The link is the first text box in the Add calendar link pop-up (the name is the second).
    private fun setLink(value: String) {
        await { nodes().any { it.isEditable && it.isVisibleToUser } }
        val field = nodes().first { it.isEditable && it.isVisibleToUser }
        assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        })); Thread.sleep(300)
    }

    @Test fun addALinkSeeItsEventsAndRemoveIt() {
        val tomorrow = LocalDate.now().plusDays(1).format(DateTimeFormatter.BASIC_ISO_DATE)
        val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val clientCertificates = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val server = MockWebServer()
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(certificate).build().sslSocketFactory(), false)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(200).setHeader("Content-Type", "text/calendar")
                .setBody("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nX-WR-CALNAME:QA Club fixtures\r\nBEGIN:VEVENT\r\nUID:m\r\nDTSTART:${tomorrow}T150000\r\n" +
                    "DURATION:PT90M\r\nSUMMARY:QA Home match\r\nLOCATION:Oval\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n")
        }
        server.start()
        val original = app.calendarSync.linkClient
        try {
            app.calendarSync.linkClient = CalendarLinkClient(OkHttpClient.Builder()
                .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager).build())
            app.settings.lastViewCalendar = false
            ins.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            await { find("AGENDA") != null }
            click("Settings")
            click("Calendars")
            click("Add link")
            await { find("Add calendar link") != null }
            // An http link is refused before anything is downloaded.
            setLink(server.url("/fixtures.ics").toString().replaceFirst("https://", "http://"))
            click("Subscribe")
            await { has("Plain http:// links can be read or changed on the way") }
            assertEquals(0, server.requestCount)
            setLink(server.url("/fixtures.ics?token=qa").toString().replaceFirst("https://", "webcal://"))
            click("Subscribe")
            await { find("Add calendar link") == null && find("QA Club fixtures") != null }
            screenshot("subscribed")
            click("Close")
            click("Save")
            await { find("QA Home match") != null && has("From QA Club fixtures") }
            assertNull(find("Actions for QA Home match"))
            screenshot("agenda")

            click("Settings")
            click("Calendars")
            click("Remove")
            await { find("Remove QA Club fixtures?") != null }
            screenshot("remove-confirm")
            nodes().filter { it.isVisibleToUser && it.text?.toString() == "Remove" }.last().let { var n: AccessibilityNodeInfo? = it
                while (n != null && !n.isClickable) n = n.parent; n!!.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
            await { find("QA Club fixtures") == null }
            click("Close")
            click("Save")
            await { find("AGENDA") != null && find("QA Home match") == null }
            assertTrue(runBlocking { app.database.outsideDao().sources() }.none { it.kind == OutsideCalendars.KIND_LINK })
        } finally {
            app.calendarSync.linkClient = original
            server.shutdown()
        }
    }
}
