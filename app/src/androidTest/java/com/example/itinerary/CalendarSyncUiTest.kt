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
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Calendar sync step 1 in the real app: outside events on the agenda and calendar, the read-only view with Copy to
 *  Planner, and Settings → Calendars against a local HTTPS CalDAV server (never a real Nextcloud). */
@Suppress("DEPRECATION")
class CalendarSyncUiTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = ins.targetContext
    private val app get() = context.applicationContext as ItineraryApp
    private val tomorrow get() = LocalDate.now().plusDays(1)
    private fun data() = runBlocking { app.repository.snapshot() }
    private fun outside() = runBlocking { app.database.outsideDao().sources() to app.database.query("SELECT title FROM outside_events", null).use { c -> List(c.count) { c.moveToPosition(it); c.getString(0) } } }
    private fun nodes(): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(n: AccessibilityNodeInfo) { result += n; for (i in 0 until n.childCount) n.getChild(i)?.let(::visit) }
        ins.uiAutomation.freshRoot?.let(::visit); return result
    }
    private fun find(text: String) = nodes().firstOrNull { it.isVisibleToUser && (it.text?.toString() == text || it.contentDescription?.toString() == text) }
    private fun has(prefix: String) = nodes().any { it.isVisibleToUser && it.text?.toString()?.startsWith(prefix) == true }
    private fun screenshot(name: String) {
        val dir = File(context.cacheDir, "qa-calendar-sync-evidence").apply { mkdirs() }
        ins.uiAutomation.takeScreenshot()?.let { b -> File(dir, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }; b.recycle() }
    }
    private fun await(timeout: Long = 30000, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < end) { if (condition()) return; Thread.sleep(150) }
        screenshot("failure"); fail("Timed out: " + nodes().mapNotNull { it.text }.joinToString(" | "))
    }
    // Clicks the first clickable thing labelled [text] (a heading with the same words is passed over), scrolling if needed.
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
    private fun open() {
        app.settings.lastViewCalendar = false
        ins.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        await { find("AGENDA") != null }
    }
    private fun actionsFor(title: String) = nodes().any { it.contentDescription?.toString() == "Actions for $title" }

    @Test fun outsideEventsAreShownReadOnlyAndCanBeCopied() = runBlocking {
        val dao = app.database.outsideDao()
        val work = dao.insertSource(CalendarSource(account = "https://cloud.test/|qa", href = "/remote.php/dav/calendars/qa/work/", name = "QA Work",
            color = 0xFF2E7D32.toInt(), enabled = true))
        dao.insertEvents(listOf(
            OutsideEvent(sourceId = work, date = tomorrow, startTime = java.time.LocalTime.of(10, 0), durationMinutes = 30, title = "QA Outside standup", location = "Room 4"),
            OutsideEvent(sourceId = work, date = tomorrow, startTime = null, endDate = tomorrow.plusDays(2),
                timedStart = java.time.LocalTime.of(9, 0), timedEnd = java.time.LocalTime.of(17, 0), title = "QA Outside conference"),
        ))
        app.repository.saveItem(ItineraryItem(tripId = 0, date = tomorrow, startTime = java.time.LocalTime.of(11, 0), title = "QA Own event"))
        open()
        await { find("QA Outside standup") != null && find("QA Own event") != null }
        assertTrue(has("From QA Work"))
        await { has("Starts 9:00") || has("Starts 09:00") } // the long event's real times
        // Its ⋮ has only Copy to Planner and Share (see CardMenusUiTest), nothing that changes it.
        assertTrue(actionsFor("QA Outside standup"))
        assertTrue(actionsFor("QA Own event"))
        screenshot("agenda")

        click("QA Outside standup") // opens the calendar on its day
        await { find("CALENDAR") != null && find("QA Outside standup") != null }
        assertTrue(actionsFor("QA Outside standup"))
        screenshot("calendar")
        click("QA Outside standup") // the read-only view, not the editor
        await { find("Copy to Planner") != null }
        assertTrue(has("From QA Work"))
        assertNotNull(find("Room 4"))
        screenshot("read-only")
        click("Copy to Planner")
        await { find("Save") != null }
        screenshot("copy-editor")
        click("Save")
        await { data().items.any { it.title == "QA Outside standup" } }
        click("Close") // Save keeps the editor open on the copy; nothing is unsaved, so Close leaves at once
        await { find("Edit event") == null }
        val copy = data().items.single { it.title == "QA Outside standup" }
        assertTrue(copy.id > 0)
        assertEquals(tomorrow, copy.date)
        assertEquals(java.time.LocalTime.of(10, 0), copy.startTime)
        assertEquals(30, copy.durationMinutes)
        assertEquals("Room 4", copy.location)
        // The calendar's own event is untouched.
        assertEquals(listOf("QA Outside standup", "QA Outside conference").sorted(), outside().second.sorted())
    }

    @Test fun settingsCalendarsTicksDownloadsAndDisconnectClears() {
        val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientCertificates = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val server = MockWebServer()
        val home = "/remote.php/dav/calendars/qa/"
        val day = tomorrow.format(DateTimeFormatter.BASIC_ISO_DATE)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl!!.encodedPath
                fun ms(body: String) = MockResponse().setResponseCode(207).setBody("""<?xml version="1.0"?><d:multistatus xmlns:d="DAV:" xmlns:cal="urn:ietf:params:xml:ns:caldav" xmlns:cs="http://calendarserver.org/ns/">$body</d:multistatus>""")
                return when {
                    request.method == "PROPFIND" && path == home -> ms(listOf("personal" to "QA Personal", "team" to "QA Team").joinToString("") { (slug, name) ->
                        """<d:response><d:href>$home$slug/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/><cal:calendar/></d:resourcetype><d:displayname>$name</d:displayname><cs:getctag>1</cs:getctag></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"""
                    })
                    request.method == "REPORT" && path == "${home}personal/" -> ms("""<d:response><d:href>${home}personal/1.ics</d:href><d:propstat><d:prop><cal:calendar-data>BEGIN:VCALENDAR&#13;
VERSION:2.0&#13;
BEGIN:VEVENT&#13;
UID:1&#13;
DTSTART:${day}T140000&#13;
DTEND:${day}T150000&#13;
SUMMARY:QA Synced dentist&#13;
END:VEVENT&#13;
END:VCALENDAR&#13;
</cal:calendar-data></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.useHttps(serverCertificates.sslSocketFactory(), false)
        server.start()
        val store = NextcloudAccountStore(context)
        val originalClient = app.calendarSync.client
        try {
            app.calendarSync.client = NextcloudClient(OkHttpClient.Builder()
                .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager).build())
            store.save(NextcloudAccount.create(server.url("/").toString(), "qa", "qa-test-password"))
            open()
            click("Settings")
            click("Calendars")
            await { find("QA Personal") != null && find("QA Team") != null }
            screenshot("calendars-found")
            assertTrue(outside().first.none { it.enabled })
            click("QA Personal")
            await { has("Synced ") && find("Calendars are up to date.") != null }
            screenshot("calendars-ticked")
            assertEquals(listOf("QA Synced dentist"), outside().second)
            click("Close")
            click("Save") // closes Settings
            await { find("QA Synced dentist") != null && has("From QA Personal") }
            screenshot("agenda-synced")

            click("Settings")
            click("Nextcloud backup")
            click("Disconnect")
            await { outside().first.isEmpty() && outside().second.isEmpty() }
            click("Close")
            click("Save")
            await { find("AGENDA") != null && find("QA Synced dentist") == null }
            screenshot("after-disconnect")
        } finally {
            app.calendarSync.client = originalClient
            store.clear()
            server.shutdown()
        }
    }
}
