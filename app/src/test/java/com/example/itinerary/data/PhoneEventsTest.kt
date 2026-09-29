package com.example.itinerary.data

import org.junit.Assert.*
import org.junit.Test
import java.time.*

// Calendar sync step 3: turning the dates Android lists for a phone calendar into outside events.
class PhoneEventsTest {
    private val perth = ZoneId.of("Australia/Perth")
    private fun utc(y: Int, m: Int, d: Int, h: Int = 0, min: Int = 0) = LocalDateTime.of(y, m, d, h, min).toInstant(ZoneOffset.UTC).toEpochMilli()
    private fun instance(begin: Long, end: Long, allDay: Boolean = false, title: String? = "Event", cancelled: Boolean = false, declined: Boolean = false) =
        PhoneInstance(1, begin, end, allDay, title, " Room 2 ", "Notes", cancelled, declined)

    @Test fun allDayDatesAreReadInUtcWhateverThePhonesZone() {
        // Android stores all-day dates as UTC midnights; in Perth that's 8 am, and in New York the evening before.
        val one = PhoneEvents.toEvent(instance(utc(2026, 10, 3), utc(2026, 10, 4), allDay = true), ZoneId.of("America/New_York"))!!
        assertEquals(LocalDate.of(2026, 10, 3), one.date)
        assertNull(one.startTime); assertNull(one.endDate)
        val trip = PhoneEvents.toEvent(instance(utc(2026, 10, 3), utc(2026, 10, 8), allDay = true), perth)!!
        assertEquals(LocalDate.of(2026, 10, 7), trip.endDate)
        assertEquals("Room 2", trip.location)
        assertEquals("Notes", trip.notes)
    }

    @Test fun timedDatesUseThePhonesClock() {
        val call = PhoneEvents.toEvent(instance(utc(2026, 10, 5, 1, 0), utc(2026, 10, 5, 1, 45)), perth)!!
        assertEquals(LocalDate.of(2026, 10, 5), call.date)
        assertEquals(LocalTime.of(9, 0), call.startTime)
        assertEquals(45, call.durationMinutes)
        // Longer than a day: across its days, with its real times.
        val course = PhoneEvents.toEvent(instance(utc(2026, 10, 5, 1, 0), utc(2026, 10, 7, 9, 0)), perth)!!
        assertNull(course.startTime)
        assertEquals(LocalDate.of(2026, 10, 7), course.endDate)
        assertEquals(LocalTime.of(9, 0), course.timedStart)
        assertEquals(LocalTime.of(17, 0), course.timedEnd)
    }

    @Test fun cancelledDeclinedAndUntitled() {
        assertNull(PhoneEvents.toEvent(instance(utc(2026, 10, 5, 1), utc(2026, 10, 5, 2), cancelled = true), perth))
        assertNull(PhoneEvents.toEvent(instance(utc(2026, 10, 5, 1), utc(2026, 10, 5, 2), declined = true), perth))
        assertEquals("(No title)", PhoneEvents.toEvent(instance(utc(2026, 10, 5, 1), utc(2026, 10, 5, 2), title = "  "), perth)!!.title)
        assertEquals("(No title)", PhoneEvents.toEvent(instance(utc(2026, 10, 5, 1), utc(2026, 10, 5, 2), title = null), perth)!!.title)
    }

    @Test fun calendarsSyncedByDavx5CarryADuplicateHint() {
        assertEquals("me@gmail.com", PhoneEvents.detail(PhoneCalendar(1, "Personal", "me@gmail.com", "com.google", null)))
        assertTrue(PhoneEvents.detail(PhoneCalendar(2, "Work", "cloud", "bitfire.at.davdroid", null)).contains("show twice"))
        assertEquals("This phone", PhoneEvents.detail(PhoneCalendar(3, "Local", "", "LOCAL", null)))
    }
}
