package com.example.itinerary.data

import org.junit.Assert.*
import org.junit.Test
import java.time.*

// Calendar sync, step 1: reading what Nextcloud sends, and turning outside events into rows the screens can show.
class OutsideEventReaderTest {
    private val perth = ZoneId.of("Australia/Perth")
    private fun ics(vararg events: String) = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\n" +
        events.joinToString("") { "BEGIN:VEVENT\r\n$it\r\nEND:VEVENT\r\n" } + "END:VCALENDAR\r\n"
    private fun read(vararg data: String, zone: ZoneId = perth) = OutsideEventReader.read(data.toList(), zone)

    @Test fun timedEventsAreConvertedToThePhonesClock() {
        val utc = read(ics("UID:a\r\nDTSTART:20260928T010000Z\r\nDTEND:20260928T023000Z\r\nSUMMARY:Dentist\\, check\r\n  up\r\nLOCATION:Main St\r\nDESCRIPTION:One\\nTwo")).events.single()
        assertEquals(LocalDate.of(2026, 9, 28), utc.date)
        assertEquals(LocalTime.of(9, 0), utc.startTime)
        assertEquals(90, utc.durationMinutes)
        assertEquals("Dentist, check up", utc.title)
        assertEquals("Main St", utc.location)
        assertEquals("One\nTwo", utc.notes)
        val zoned = read(ics("DTSTART;TZID=Europe/London:20260928T100000\r\nDURATION:PT45M\r\nSUMMARY:Call")).events.single()
        assertEquals(LocalTime.of(17, 0), zoned.startTime)
        assertEquals(45, zoned.durationMinutes)
        // No end at all: a moment, not an invented length. Seconds are dropped.
        val point = read(ics("DTSTART:20260928T010030Z\r\nSUMMARY:Ping")).events.single()
        assertEquals(LocalTime.of(9, 0), point.startTime)
        assertNull(point.durationMinutes)
    }

    @Test fun allDayEventsUseTheExclusiveEnd() {
        val one = read(ics("DTSTART;VALUE=DATE:20261003\r\nDTEND;VALUE=DATE:20261004\r\nSUMMARY:Holiday")).events.single()
        assertNull(one.startTime); assertNull(one.endDate)
        assertEquals(LocalDate.of(2026, 10, 3), one.date)
        val trip = read(ics("DTSTART;VALUE=DATE:20261003\r\nDTEND;VALUE=DATE:20261008\r\nSUMMARY:Trip")).events.single()
        assertEquals(LocalDate.of(2026, 10, 7), trip.endDate)
        val weeks = read(ics("DTSTART;VALUE=DATE:20261003\r\nDURATION:P2W\r\nSUMMARY:Course")).events.single()
        assertEquals(LocalDate.of(2026, 10, 16), weeks.endDate)
        val noEnd = read(ics("DTSTART;VALUE=DATE:20261003\r\nSUMMARY:Birthday")).events.single()
        assertNull(noEnd.endDate)
    }

    @Test fun timedEventsLongerThanADayAreShownAcrossTheirDaysWithTheirTimes() {
        val conference = read(ics("DTSTART:20261005T090000\r\nDTEND:20261007T170000\r\nSUMMARY:Conference"), zone = ZoneOffset.UTC).events.single()
        assertNull(conference.startTime)
        assertEquals(LocalDate.of(2026, 10, 5), conference.date)
        assertEquals(LocalDate.of(2026, 10, 7), conference.endDate)
        assertEquals(LocalTime.of(9, 0), conference.timedStart)
        assertEquals(LocalTime.of(17, 0), conference.timedEnd)
        assertEquals(LocalDateTime.of(2026, 10, 7, 17, 0), conference.timedEndAt())
        // Ending exactly at midnight: the day before was the last one it covered.
        val midnight = read(ics("DTSTART:20261005T200000\r\nDTEND:20261008T000000\r\nSUMMARY:Retreat"), zone = ZoneOffset.UTC).events.single()
        assertEquals(LocalDate.of(2026, 10, 7), midnight.endDate)
        assertEquals(LocalDateTime.of(2026, 10, 8, 0, 0), midnight.timedEndAt())
        // Exactly 24 hours is still an ordinary timed event.
        val day = read(ics("DTSTART:20261005T090000\r\nDTEND:20261006T090000\r\nSUMMARY:Shift"), zone = ZoneOffset.UTC).events.single()
        assertEquals(LocalTime.of(9, 0), day.startTime)
        assertEquals(1440, day.durationMinutes)
    }

    @Test fun expandedRepeatsCancelledAndBrokenEvents() {
        // The server sends each date of a repeating event as its own instance with a RECURRENCE-ID.
        val weekly = (0 until 3).map { ics("UID:w\r\nRECURRENCE-ID:2026100${it + 1}T010000Z\r\nDTSTART:2026100${it + 1}T010000Z\r\nDTEND:2026100${it + 1}T020000Z\r\nSUMMARY:Gym") }
        val cancelled = ics("DTSTART:20261010T010000Z\r\nSTATUS:CANCELLED\r\nSUMMARY:Off")
        val broken = ics("DTSTART:not-a-date\r\nSUMMARY:Broken")
        val missingStart = ics("SUMMARY:No start")
        val result = read(*(weekly + cancelled + broken + missingStart + "garbage").toTypedArray())
        assertEquals(listOf(1, 2, 3), result.events.map { it.date.dayOfMonth })
        assertEquals(3, result.skipped) // broken, missing start and garbage; the cancelled one is simply left out
    }

    @Test fun alarmTextAndUnknownZonesDontSpoilTheEvent() {
        val event = read(ics("DTSTART;TZID=W. Australia Standard Time:20261005T090000\r\nSUMMARY:Meeting\r\n" +
            "BEGIN:VALARM\r\nACTION:DISPLAY\r\nDESCRIPTION:Alarm text\r\nTRIGGER:-PT15M\r\nEND:VALARM")).events.single()
        assertEquals("Meeting", event.title)
        assertEquals("", event.notes)
        assertEquals(LocalTime.of(9, 0), event.startTime) // unknown zone: read as the phone's own
        val untitled = read(ics("DTSTART:20261005T010000Z\r\nSUMMARY:   ")).events.single()
        assertEquals("(No title)", untitled.title)
        val long = read(ics("DTSTART:20261005T010000Z\r\nSUMMARY:${"x".repeat(900)}")).events.single()
        assertEquals(500, long.title.length)
    }

    @Test fun outsideEventsBecomeReadOnlyRowsAndPlannerCopies() {
        val colour = 0xFF112233.toInt()
        val conference = OutsideEvent(id = 7, sourceId = 1, date = LocalDate.of(2026, 10, 5), startTime = null,
            endDate = LocalDate.of(2026, 10, 7), timedStart = LocalTime.of(9, 0), timedEnd = LocalTime.of(17, 0), title = "Conference")
        val item = conference.toItem(colour)
        assertEquals(-7L, item.id)
        assertTrue(OutsideCalendars.isOutside(item.id))
        assertEquals(OutsideCalendars.TRIP_ID, item.tripId)
        assertEquals(colour, item.customColor)
        assertEquals(Categories.OTHER, item.category)
        assertEquals(-7L, conference.toPlanEvent(colour).id)
        // Busy from its real start to its real end for Find free time.
        val busy = conference.busyItem(colour)
        assertEquals(LocalTime.of(9, 0), busy.startTime)
        assertEquals((2 * 24 + 8) * 60, busy.durationMinutes)
        val free = FreeTime.find(listOf(busy), LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 7), LocalTime.of(8, 0), LocalTime.of(18, 0),
            60, false, false, 60, LocalDateTime.of(2026, 10, 1, 0, 0))
        assertEquals(listOf(FreeWindow(LocalDateTime.of(2026, 10, 5, 8, 0), LocalDateTime.of(2026, 10, 5, 9, 0)),
            FreeWindow(LocalDateTime.of(2026, 10, 7, 17, 0), LocalDateTime.of(2026, 10, 7, 18, 0))), free)
        // A copy is a new, ordinary Planner event that the editor's rules accept.
        val copy = conference.plannerCopy()
        assertEquals(0L, copy.id)
        assertEquals(0L, copy.tripId)
        assertNull(copy.customColor)
        MultiDay.validate(copy)
        assertEquals(listOf(item), eventsOnDay(listOf(item), LocalDate.of(2026, 10, 6)))
    }
}
