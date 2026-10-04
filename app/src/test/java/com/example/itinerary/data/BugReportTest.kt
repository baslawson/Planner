package com.example.itinerary.data

import org.junit.Assert.*
import org.junit.Test
import java.net.URLDecoder

// Wish list #6: what a bug report sends, and a link short enough for a browser.
class BugReportTest {
    private fun query(url: String, key: String) = URLDecoder.decode(url.substringAfter("$key=").substringBefore('&'), "UTF-8")

    @Test fun theReportHoldsOnlyWhatIsShown() {
        val body = BugReport.body("The widget shows yesterday.\nAfter midnight.", "0.0.16 (23)", "15 (API 35)", "Google Pixel 7 Pro", null)
        assertEquals("The widget shows yesterday.\nAfter midnight.\n\n---\nPlanner 0.0.16 (23) · Android 15 (API 35) · Google Pixel 7 Pro", body)
        val url = BugReport.url("The widget shows yesterday.\nAfter midnight.", "0.0.16 (23)", "15 (API 35)", "Google Pixel 7 Pro", null)
        assertTrue(url.startsWith("https://github.com/baslawson/Planner/issues/new?title="))
        assertEquals("The widget shows yesterday.", query(url, "title"))
        assertEquals(body, query(url, "body"))
        assertEquals("Bug report", BugReport.title("   \n "))
    }

    @Test fun aLongCrashIsCutToFitTheLink() {
        val crash = (1..2_000).joinToString("\n") { "    at com.example.Frame$it(Frame.kt:$it)" }
        val url = BugReport.url("Crashed on save", "0.0.16", "15", "Pixel", crash)
        assertTrue(url.length <= BugReport.MAX_URL)
        val body = query(url, "body")
        assertTrue(body.contains("Last crash:")); assertTrue(body.contains("Frame1("))
        assertTrue(body.contains("…"))
    }

    // SR-2: the preview is what the link sends; a cut crash keeps its root cause and says so.
    @Test fun thePreviewIsWhatTheLinkSends() {
        val crash = "Planner 0.0.16\njava.lang.RuntimeException: wrapped\n" +
            (1..2_000).joinToString("\n") { "    at com.example.Frame$it(Frame.kt:$it)" } + "\nCaused by: java.io.IOException: root\n    at x.Y(Y.kt:1)"
        val r = BugReport.report("Crashed on save", "0.0.16", "15", "Pixel", crash)
        assertTrue(r.url.length <= BugReport.MAX_URL)
        assertEquals(r.body, query(r.url, "body"))
        assertTrue(r.crashCut); assertFalse(r.crashLeftOut); assertFalse(r.descriptionCut)
        assertTrue(r.body.contains("Frame1(")); assertTrue(r.body.contains("…\nCaused by: java.io.IOException: root"))
        val short = BugReport.report("Crashed", "0.0.16", "15", "Pixel", "Planner 0.0.16\nboom")
        assertFalse(short.crashCut); assertFalse(short.crashLeftOut); assertTrue(short.body.endsWith("boom\n```"))
    }

    // SR-2, SR-3: with a long non-Latin text the crash goes first, then the text itself is cut to fit; both are said.
    @Test fun aLongNonLatinTextStillFitsTheLink() {
        val crash = (1..200).joinToString("\n") { "    at com.example.Frame$it(Frame.kt:$it)" }
        for (text in listOf("Привет мир ".repeat(273), "😀".repeat(1_500))) {
            val r = BugReport.report(text, "0.0.16", "15", "Pixel", crash)
            assertTrue(r.url.length <= BugReport.MAX_URL)
            assertEquals(r.body, query(r.url, "body"))
            assertFalse(r.body.contains("Last crash:"))
            assertTrue(r.crashLeftOut)
            assertFalse(query(r.url, "body").contains('?'))
        }
        val emoji = BugReport.report("😀".repeat(1_500), "0.0.16", "15", "Pixel", null)
        assertTrue(emoji.descriptionCut); assertFalse(emoji.crashLeftOut)
        assertTrue(emoji.body.startsWith("😀")); assertTrue(emoji.body.contains("…\n\n---\nPlanner"))
    }

    // SR-3: a cut never splits an emoji (its half would go in the link as "?").
    @Test fun cutsKeepWholeCharacters() {
        assertEquals("a", "a😀".takeWhole(2))
        assertEquals("a😀", "a😀".takeWhole(3))
        assertEquals("", "😀".takeWhole(1))
        assertEquals(79, BugReport.title("a" + "😀".repeat(100)).length)
    }

    // SR-6: a crash caused by a failed lookup (offline) keeps its trace; Log.getStackTraceString gave "" for it.
    @Test fun anOfflineCrashKeepsItsTrace() {
        val dir = kotlin.io.path.createTempDirectory("crash").toFile()
        try {
            val log = CrashLog(dir)
            log.write(1_000L, "0.0.16 (23)", IllegalStateException("sync", java.net.UnknownHostException("cloud.example")))
            val crash = log.read()!!
            assertEquals(1_000L, crash.at)
            assertTrue(crash.text.startsWith("Planner 0.0.16 (23)\njava.lang.IllegalStateException: sync"))
            assertTrue(crash.text.contains("Caused by: java.net.UnknownHostException"))
            log.clear(); assertNull(log.read())
        } finally { dir.deleteRecursively() }
    }

    // AB-1: a crash that went in a report is marked, not deleted; a new crash isn't marked.
    @Test fun aSentCrashIsMarkedAndKept() {
        val dir = kotlin.io.path.createTempDirectory("crash").toFile()
        try {
            val log = CrashLog(dir)
            log.write(1_000L, "0.0.16 (23)", IllegalStateException("first"))
            assertFalse(log.read()!!.sent)
            log.markSent(1_000L, now = 1_000L)
            assertTrue(log.read()!!.sent)
            // SR9-3: a sent one is offered for a week more, then no longer.
            assertNotNull(log.offered(1_000L + CrashLog.SENT_OFFERED_MS - 1))
            assertNull(log.offered(1_000L + CrashLog.SENT_OFFERED_MS))
            // SX-2: counted from when it was sent.
            log.markSent(1_000L, now = 50_000L)
            assertNotNull(log.offered(50_000L + CrashLog.SENT_OFFERED_MS - 1))
            assertNull(log.offered(50_000L + CrashLog.SENT_OFFERED_MS))
            log.write(2_000L, "0.0.16 (23)", IllegalStateException("second"))
            assertFalse(log.read()!!.sent)
            log.clear(); assertNull(log.read())
        } finally { dir.deleteRecursively() }
    }
}
