package com.example.itinerary.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.MonthDay
import java.time.ZoneOffset

// The contacts' birthdays as a phone calendar.
class ContactBirthdaysTest {
    private fun utc(day: LocalDate) = day.atStartOfDay(ZoneOffset.UTC).toInstant()

    @Test fun readsTheWaysContactsAppsSaveTheDate() {
        assertEquals(ContactBirthdays.Birthday("Sam", MonthDay.of(5, 17), 1990), ContactBirthdays.parse(" Sam ", "1990-05-17"))
        assertEquals(ContactBirthdays.Birthday("Sam", MonthDay.of(5, 17), 1990), ContactBirthdays.parse("Sam", "1990-05-17T00:00:00Z"))
        assertEquals(ContactBirthdays.Birthday("Alex", MonthDay.of(2, 29), null), ContactBirthdays.parse("Alex", "--02-29"))
        assertEquals(ContactBirthdays.Birthday("Kim", MonthDay.of(12, 1), 1985), ContactBirthdays.parse("Kim", "19851201"))
        // Bug hunt 28 P11: dotted and slashed dates.
        assertEquals(ContactBirthdays.Birthday("Kim", MonthDay.of(5, 17), 1990), ContactBirthdays.parse("Kim", "17.05.1990"))
        val usual = ContactBirthdays.monthFirstLocale
        try {
            ContactBirthdays.monthFirstLocale = { false }
            assertEquals(MonthDay.of(5, 4), ContactBirthdays.parse("Kim", "04/05/1990")!!.day)
            assertEquals(MonthDay.of(5, 17), ContactBirthdays.parse("Kim", "05/17/1990")!!.day) // can only be month first
            ContactBirthdays.monthFirstLocale = { true }
            assertEquals(MonthDay.of(4, 5), ContactBirthdays.parse("Kim", "04/05/1990")!!.day)
            assertEquals(MonthDay.of(5, 17), ContactBirthdays.parse("Kim", "17/05/1990")!!.day) // can only be day first
        } finally { ContactBirthdays.monthFirstLocale = usual }
        assertNull(ContactBirthdays.parse("Bad", "1990-13-40"))
        assertNull(ContactBirthdays.parse("Bad", "next Tuesday"))
        assertNull(ContactBirthdays.parse("", "1990-05-17"))
        assertNull(ContactBirthdays.parse("None", null))
    }

    @Test fun eachYearInTheWindowWithTheAgeAndLeapDaysOnThe28th() {
        val people = listOf(
            ContactBirthdays.Birthday("Sam", MonthDay.of(5, 17), 1990),
            ContactBirthdays.Birthday("Alex", MonthDay.of(2, 29), null),
            ContactBirthdays.Birthday("Baby", MonthDay.of(1, 10), 2027),
            ContactBirthdays.Birthday("Sam", MonthDay.of(5, 17), 1990),
        )
        val dates = ContactBirthdays.instances(people, utc(LocalDate.of(2026, 1, 1)), utc(LocalDate.of(2028, 3, 1)))
        assertEquals(listOf(
            "2026-02-28 Alex's birthday", "2026-05-17 Sam's birthday (36)",
            "2027-01-10 Baby's birthday", "2027-02-28 Alex's birthday", "2027-05-17 Sam's birthday (37)",
            "2028-01-10 Baby's birthday (1)", "2028-02-29 Alex's birthday",
        ), dates.map { "${java.time.Instant.ofEpochMilli(it.begin).atZone(ZoneOffset.UTC).toLocalDate()} ${it.title}" })
        dates.forEach { assertEquals(true, it.allDay); assertEquals(86_400_000L, it.end - it.begin); assertEquals(ContactBirthdays.CALENDAR_ID, it.calendarId) }
        // Shown as Planner shows phone dates: one all-day event.
        val event = PhoneEvents.toEvent(dates.first(), ZoneOffset.UTC)!!
        assertEquals(LocalDate.of(2026, 2, 28), event.date); assertNull(event.startTime); assertNull(event.endDate)
    }
}
