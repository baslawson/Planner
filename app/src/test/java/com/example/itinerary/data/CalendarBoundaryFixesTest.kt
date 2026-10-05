package com.example.itinerary.data

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.Duration

class CalendarBoundaryFixesTest {
    private val berlin = ZoneId.of("Europe/Berlin")
    private val spring = LocalDate.of(2026, 3, 28)
    private fun file(vararg events: String) = "BEGIN:VCALENDAR\nVERSION:2.0\n" +
        events.joinToString("\n") { "BEGIN:VEVENT\n$it\nEND:VEVENT" } + "\nEND:VCALENDAR\n"
    private fun event(duration: String, date: String = "20260328") =
        "UID:r\nSUMMARY:Meeting\nDTSTART;TZID=Europe/Berlin:${date}T090000\n$duration"
    private fun items(text: String, zone: ZoneId = berlin, today: LocalDate = spring) =
        CalendarFileImport.events(CalendarFileImport.read(text, zone, today).entries, today, true)

    @Test fun generatedGapDoesNotMoveTheMeetingOrConsumeCount() {
        val text = file("UID:r\nDTSTART;TZID=Europe/Berlin:20260328T023000\nRRULE:FREQ=DAILY;COUNT=3")
        val imported = items(text)
        assertEquals(listOf(spring, spring.plusDays(2), spring.plusDays(3)), imported.map { it.date })
        assertEquals(setOf(LocalTime.of(2, 30)), imported.map { it.startTime }.toSet())
        val window = CalendarFileImport.window(text, berlin, spring.plusDays(2), spring.plusDays(10))
        assertEquals(listOf(spring.plusDays(2), spring.plusDays(3)), window.events.map { it.date })
    }

    @Test fun explicitGapStartStillResolvesAndAllDayGapDateRemains() {
        val explicit = items(file("UID:r\nDTSTART;TZID=Europe/Berlin:20260329T023000"))
        assertEquals(LocalTime.of(3, 30), explicit.single().startTime)
        val repeated = items(file("UID:r\nDTSTART;TZID=Europe/Berlin:20260329T023000\nRRULE:FREQ=DAILY;COUNT=2"))
        assertEquals(listOf(LocalTime.of(3, 30), LocalTime.of(2, 30)), repeated.sortedBy { it.date }.map { it.startTime })
        val day = LocalDate.of(2018, 11, 4)
        val allDay = items(file("UID:r\nDTSTART;VALUE=DATE:20181103\nRRULE:FREQ=DAILY;COUNT=3"),
            ZoneId.of("America/Sao_Paulo"), day.minusDays(1))
        assertEquals(listOf(day.minusDays(1), day, day.plusDays(1)), allDay.map { it.date })
    }

    @Test fun badIdentityIsSkippedOnceAndReadableSiblingsRemain() {
        val text = file("UID:r\nDTSTART:20261006T090000Z\nRRULE:FREQ=DAILY;COUNT=3",
            "UID:r\nRECURRENCE-ID:BAD\nDTSTART:20261007T100000Z",
            "UID:r\nRECURRENCE-ID:20261008T090000Z\nDTSTART:20261008T110000Z")
        val day = LocalDate.of(2026, 10, 6)
        val result = CalendarFileImport.read(text, ZoneId.of("UTC"), day)
        assertEquals(1, result.skipped)
        val imported = CalendarFileImport.events(result.entries, day, true)
        assertEquals(listOf(LocalTime.of(9, 0), LocalTime.of(9, 0), LocalTime.of(11, 0)), imported.map { it.startTime })
        val window = CalendarFileImport.window(text, ZoneId.of("UTC"), day, day.plusDays(5))
        assertEquals(1, window.skipped)
        assertEquals(3, window.events.size)
    }

    @Test fun invalidCancelledAndOrphanIdentitiesAreNotImported() {
        val result = CalendarFileImport.read(file("UID:r\nDTSTART:20261006T090000Z\nRRULE:FREQ=DAILY;COUNT=2",
            "UID:r\nRECURRENCE-ID:BAD\nSTATUS:CANCELLED",
            "UID:orphan\nRECURRENCE-ID:BAD\nDTSTART:20261007T100000Z"), ZoneId.of("UTC"), spring)
        assertEquals(2, result.skipped)
        assertEquals(2, result.entries.single().dates.size)
    }

    @Test fun nominalDayKeepsClockEndWhileElapsedDayDoesNot() {
        val nominal = items(file(event("DURATION:P1D"))).single()
        assertEquals(LocalTime.of(9, 0), nominal.startTime)
        assertEquals(1440, nominal.durationMinutes)
        val elapsed = items(file(event("DURATION:PT24H"))).single()
        assertNull(elapsed.startTime)
        assertTrue(elapsed.notes.contains("10:00"))
        val autumn = items(file(event("DURATION:P1D", "20261024")), today = LocalDate.of(2026, 10, 24)).single()
        assertEquals(1440, autumn.durationMinutes)
    }

    @Test fun nominalDurationIsAppliedInSourceZoneAndPerOccurrence() {
        val text = file(event("DURATION:P1D\nRRULE:FREQ=DAILY;COUNT=3"))
        val imported = items(text)
        assertEquals(listOf(1440, 1440, 1440), imported.map { it.durationMinutes })
        // Source-zone calendar day is 23 elapsed hours across the spring transition, even on a UTC phone.
        val utc = items(file(event("DURATION:P1D")), ZoneId.of("UTC")).single()
        assertEquals(1380, utc.durationMinutes)
        val begin = spring.atTime(9, 0).atZone(berlin)
        assertEquals(Duration.ofHours(23), Duration.between(begin, Ics.eventDuration("P1D").end(begin)))
        assertEquals(Duration.ofHours(25), Duration.between(begin, Ics.eventDuration("P1DT2H").end(begin)))
        assertEquals(spring.plusWeeks(1).atTime(9, 0), Ics.eventDuration("P1W").end(begin).toLocalDateTime())
    }

    @Test fun explicitEndRemainsAnExactRecurrenceDuration() {
        val text = file(event("DTEND;TZID=Europe/Berlin:20260329T090000\nRRULE:FREQ=DAILY;COUNT=3"))
        assertEquals(listOf(1440, 1380, 1380), items(text).map { it.durationMinutes })
    }

    @Test fun outsideAndTwoWayReadersUseSourceZoneNominalDays() {
        val text = file(event("DURATION:P1D"))
        for ((zone, minutes) in listOf(berlin to 1440, ZoneId.of("UTC") to 1380)) {
            assertEquals(minutes, OutsideEventReader.read(listOf(text), zone).events.single().durationMinutes)
            assertEquals(minutes, ServerEvents.parse(text, zone).item!!.durationMinutes)
        }
        assertNull(ServerEvents.parse(file(event("DURATION:PT24H")), berlin).item)
    }
}
