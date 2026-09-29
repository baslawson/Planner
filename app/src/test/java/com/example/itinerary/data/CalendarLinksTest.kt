package com.example.itinerary.data

import org.junit.Assert.*
import org.junit.Test
import java.time.*

// Calendar sync step 4: which links are accepted, and reading a subscribed calendar within the sync window.
class CalendarLinksTest {
    private val utc = ZoneOffset.UTC
    private val from = LocalDate.of(2026, 6, 1)
    private val until = LocalDate.of(2027, 10, 31)
    private fun ics(header: String = "", vararg events: String) = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\n$header" +
        events.joinToString("") { "BEGIN:VEVENT\r\n$it\r\nEND:VEVENT\r\n" } + "END:VCALENDAR\r\n"
    private fun window(text: String) = CalendarFileImport.window(text, utc, from, until)

    @Test fun onlyHttpsLinksWithoutLoginDetails() {
        assertEquals("https://example.com/cal.ics", CalendarLinks.normalize(" webcal://example.com/cal.ics ").toString())
        assertEquals("https://example.com/cal.ics", CalendarLinks.normalize("webcals://example.com/cal.ics").toString())
        assertEquals("https://example.com/cal.ics?token=abc", CalendarLinks.normalize("https://example.com/cal.ics?token=abc#part").toString())
        listOf("http://example.com/cal.ics", "HTTP://example.com/x", "ftp://example.com/x", "not a link", "",
            "https://me:secret@example.com/cal.ics").forEach {
            assertThrows("Must refuse $it", IllegalArgumentException::class.java) { CalendarLinks.normalize(it) }
        }
        val refused = runCatching { CalendarLinks.normalize("http://example.com/private-token-123") }.exceptionOrNull()!!.message!!
        assertFalse("A refusal must not repeat the link", refused.contains("private-token-123"))
    }

    @Test fun nameColourAndEventsInTheWindow() {
        val read = window(ics("X-WR-CALNAME:Public holidays\r\nX-APPLE-CALENDAR-COLOR:#1BADF8\r\n",
            "UID:a\r\nDTSTART;VALUE=DATE:20261225\r\nSUMMARY:Christmas Day",
            "UID:b\r\nDTSTART;VALUE=DATE:20200101\r\nSUMMARY:Long ago",
            "UID:c\r\nDTSTART;VALUE=DATE:20281225\r\nSUMMARY:Too far ahead",
            "UID:d\r\nDTSTART;VALUE=DATE:20260525\r\nDTEND;VALUE=DATE:20260610\r\nSUMMARY:Started before the window"))
        assertEquals("Public holidays", read.name)
        assertEquals(0xFF1BADF8.toInt(), read.color)
        assertEquals(setOf("Christmas Day", "Started before the window"), read.events.map { it.title }.toSet())
        assertEquals(0, read.skipped)
        // An empty window is fine; something that isn't a calendar is not.
        assertTrue(window(ics("", "DTSTART;VALUE=DATE:20100101\r\nSUMMARY:Old")).events.isEmpty())
        assertThrows(IllegalArgumentException::class.java) { window("<html>Sign in</html>") }
    }

    @Test fun oldRepeatingEventsStillReachTheWindow() {
        // Daily since 2010: thousands of past dates, but the window still gets every day in it.
        val daily = window(ics("", "UID:d\r\nDTSTART:20100101T090000Z\r\nDURATION:PT15M\r\nRRULE:FREQ=DAILY\r\nSUMMARY:Stand-up")).events
        assertEquals(from, daily.minOf { it.date })
        assertEquals(until, daily.maxOf { it.date })
        assertEquals(java.time.temporal.ChronoUnit.DAYS.between(from, until) + 1, daily.size.toLong())
        // A count that ran out before the window: nothing; one that ends inside it: only the dates left.
        assertTrue(window(ics("", "UID:e\r\nDTSTART:20250101T090000Z\r\nRRULE:FREQ=WEEKLY;COUNT=10\r\nSUMMARY:Course")).events.isEmpty())
        val weekly = window(ics("", "UID:f\r\nDTSTART:20260504T090000Z\r\nRRULE:FREQ=WEEKLY;COUNT=6\r\nSUMMARY:Course")).events
        assertEquals(listOf(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 8)), weekly.map { it.date }) // the 5th and 6th times
    }

    @Test fun longTimedEventsKeepTheirRealTimes() {
        val retreat = window(ics("", "UID:g\r\nDTSTART:20261005T200000Z\r\nDTEND:20261008T100000Z\r\nSUMMARY:Retreat")).events.single()
        assertNull(retreat.startTime)
        assertEquals(LocalDate.of(2026, 10, 8), retreat.endDate)
        assertEquals(LocalTime.of(20, 0), retreat.timedStart)
        assertEquals(LocalTime.of(10, 0), retreat.timedEnd)
    }
}
