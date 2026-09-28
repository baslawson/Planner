package com.example.itinerary.data

import org.junit.Assert.*
import org.junit.Test
import java.time.*

/** Everyday wording that used to be saved wrongly or rejected (found by probing common phrasings). */
class QuickWordingTest {
    private val today = LocalDate.of(2026, 9, 28) // a Monday
    private val now = LocalDateTime.of(2026, 9, 28, 10, 7)
    private fun parse(text: String) = QuickEntry.parse(text, today, now = now, dayFirst = true)
    private fun ok(text: String) = parse(text).also { assertNull(text, it.error) }

    @Test fun commonTomorrowSpellingsAreTomorrow() {
        for (word in listOf("tmrw", "tmr", "tomoz", "tomorow", "tommorow", "tommorrow")) {
            val r = ok("Coffee $word 10am")
            assertEquals(word, "Coffee", r.title); assertEquals(word, today.plusDays(1), r.date)
            assertEquals(LocalTime.of(10, 0), r.time)
            assertTrue(word, quickCompletions("Coffee $word", "Coffee $word".length, emptyList(), false).isEmpty())
        }
    }

    @Test fun weekAbbreviationsAndFortnights() {
        assertEquals(today.plusWeeks(2), ok("Dentist in 2 wks").date)
        assertEquals("Dentist", ok("Dentist in 2 wks").title)
        assertEquals(today.plusWeeks(1), ok("Dentist in a wk").date)
        assertEquals(today.plusWeeks(2), ok("Holiday in a fortnight").date)
        assertEquals(today.plusWeeks(4), ok("Holiday in two fortnights").date)
        assertEquals(today.plusWeeks(2), ok("Holiday a fortnight from today").date)
    }

    @Test fun spokenClockTimes() {
        val half = parse("Tea half past 3 tomorrow")
        assertEquals("Tea", half.title); assertEquals(listOf(LocalTime.of(3, 30), LocalTime.of(15, 30)), half.timeChoices)
        assertEquals(LocalTime.of(16, 45), ok("Meeting quarter to 5pm Friday").time)
        assertEquals("Meeting", ok("Meeting quarter to 5pm Friday").title)
        assertEquals(LocalTime.of(9, 15), ok("Call quarter past 9am").time)
        assertEquals(LocalTime.of(14, 20), ok("Call 20 past 2pm").time)
        assertEquals(LocalTime.of(11, 50), ok("Call ten to 12pm").time)
        assertEquals(LocalTime.of(0, 45), ok("Call quarter to 1am").time)
        assertEquals(listOf(LocalTime.of(4, 45), LocalTime.of(16, 45)), parse("Meeting quarter to 5 Friday").timeChoices)
        val clock = parse("Meeting at 3 o'clock tomorrow")
        assertEquals("Meeting", clock.title); assertEquals(listOf(LocalTime.of(3, 0), LocalTime.of(15, 0)), clock.timeChoices)
        assertEquals(LocalTime.of(15, 0), ok("Meeting 3 o’clock pm").time)
        // Ordinary words stay in the title.
        assertEquals("Half past caring", ok("Half past caring").title)
    }

    @Test fun atSignWorksLikeAt() {
        val r = ok("Meeting @ 3pm Friday")
        assertEquals("Meeting", r.title); assertEquals(LocalTime.of(15, 0), r.time); assertEquals(LocalDate.of(2026, 10, 2), r.date)
        val place = ok("Dinner 7pm @ Nandos")
        assertEquals("Dinner", place.title); assertEquals("Nandos", place.location); assertEquals(LocalTime.of(19, 0), place.time)
        assertEquals("Doctor", ok("Doctor next Tuesday @9:15am").title)
        // An email address is not a place.
        val mail = ok("Email bob@example.com tomorrow")
        assertEquals("Email bob@example.com", mail.title); assertEquals("", mail.location)
    }

    @Test fun bareWeekdaysRepeatsOnWeekdays() {
        val r = ok("School run weekdays 8am")
        assertEquals("School run", r.title); assertEquals(RepeatRule.WEEKDAYS, r.repeat); assertEquals(LocalTime.of(8, 0), r.time)
        assertEquals("Weekday lunch", ok("Weekday lunch").title)
    }

    @Test fun forAPeriodSetsTheNumberOfRepeats() {
        val r = ok("Tennis 7pm every Thursday for 10 weeks")
        assertEquals("Tennis", r.title); assertEquals(10, r.repeatCount); assertTrue(r.repeatCountSpecified)
        assertEquals(14, ok("Vitamins every day for 2 weeks").repeatCount)
        assertEquals(10, ok("Standup weekdays 9am for 2 weeks").repeatCount)
        assertEquals(5, ok("Cleaner every fortnight for 10 weeks").repeatCount)
        assertEquals(6, ok("Rent monthly for six months").repeatCount)
        assertEquals(3, ok("Checkup yearly for 3 years").repeatCount)
        // Without a repeat it is ordinary title text, as before.
        assertEquals("Holiday for 2 weeks", ok("Holiday for 2 weeks").title)
        assertNotNull(parse("Tennis every Thursday for 1 week").error)
    }

    @Test fun vagueTimeWithAClockTimeUsesBoth() {
        ok("Movie tonight 8pm").let { assertEquals("Movie", it.title); assertEquals(today, it.date); assertEquals(LocalTime.of(20, 0), it.time) }
        ok("Movie 8.30pm tonight").let { assertEquals(today, it.date); assertEquals(LocalTime.of(20, 30), it.time) }
        ok("Run tomorrow morning at 7").let { assertEquals("Run", it.title); assertEquals(today.plusDays(1), it.date); assertEquals(LocalTime.of(7, 0), it.time) }
        ok("Drinks this evening at 6pm").let { assertEquals(today, it.date); assertEquals(LocalTime.of(18, 0), it.time) }
        ok("Dinner tomorrow night 7pm").let { assertEquals(today.plusDays(1), it.date); assertEquals(LocalTime.of(19, 0), it.time) }
        assertEquals(LocalTime.of(19, 30), ok("Dinner tomorrow evening 7:30").time)
        assertEquals(LocalTime.of(14, 0), ok("Call this afternoon at 2").time)
        assertEquals(LocalTime.of(9, 0), ok("Gym in the morning at 9").time)
        assertEquals(LocalTime.of(18, 0), ok("Drinks tomorrow evening 6-8pm").time)
        // A clock time outside the period is a contradiction.
        assertNotNull(parse("Dinner tomorrow evening 3pm").error)
        assertNotNull(parse("Run tomorrow morning 7pm").error)
        // Two vague phrases, or a vague meal time with a clock time, still need editing.
        assertNotNull(parse("Lunch after lunch 2pm").error)
        // Alone, a vague phrase still asks for a time.
        assertNotNull(parse("Dinner tomorrow evening").timePrompt)
    }

    @Test fun onBeforeANumericDate() {
        val r = ok("Dentist on 12/10 at 3pm")
        assertEquals("Dentist", r.title); assertEquals(LocalDate.of(2026, 10, 12), r.date); assertEquals(LocalTime.of(15, 0), r.time)
    }

    @Test fun theDayOrWeekBeforeReminders() {
        assertEquals(1440, ok("Flight Friday 6am remind me the day before").reminderMinutes)
        assertEquals("Flight", ok("Flight Friday 6am remind me the day before").title)
        assertEquals(10080, ok("Passport renewal 1 Dec notify me the week before").reminderMinutes)
        assertEquals(60, ok("Call mum Sunday 3pm remind me an hour before").reminderMinutes)
    }

    @Test fun hyphenatedWordsAreNotUnfinishedPhrases() {
        ok("Hotel check-in at 2pm").let { assertEquals("Hotel check-in", it.title); assertEquals(LocalTime.of(14, 0), it.time) }
        ok("Hotel check-in at quarter past 2pm").let { assertEquals("Hotel check-in", it.title); assertEquals(LocalTime.of(14, 15), it.time) }
        ok("Sign-on tomorrow").let { assertEquals("Sign-on", it.title); assertEquals(today.plusDays(1), it.date) }
        assertNotNull(parse("Dentist on").error)
    }

    @Test fun nextWeekWithAWeekday() {
        val tuesday = LocalDate.of(2026, 10, 6)
        for (text in listOf("Meeting next week Tuesday 10am", "Meeting next week on Tuesday 10am", "Meeting Tuesday next week 10am")) {
            val r = ok(text)
            assertEquals(text, "Meeting", r.title); assertEquals(text, tuesday, r.date); assertEquals(LocalTime.of(10, 0), r.time)
        }
        assertEquals(parse("Meeting next Tuesday").date, ok("Meeting next week Tuesday").date)
        // On its own, next week is still not a date.
        assertNotNull(parse("Review next week").error)
    }

    @Test fun thisWeekend() {
        assertEquals(LocalDate.of(2026, 10, 3), ok("Mow lawn this weekend").date)
        assertEquals("Mow lawn", ok("Mow lawn this weekend").title)
        val saturday = LocalDate.of(2026, 10, 3)
        assertEquals(saturday, QuickEntry.parse("Mow lawn this weekend", saturday).date)
        assertEquals(saturday.plusDays(1), QuickEntry.parse("Mow lawn this weekend", saturday.plusDays(1)).date)
        assertNotNull(parse("Mow lawn every weekend").error)
    }

    @Test fun endOfTheMonth() {
        for (text in listOf("Rent due end of month", "Rent due end of the month", "Rent due at the end of the month", "Rent due by the end of the month")) {
            val r = ok(text)
            assertEquals(text, "Rent due", r.title); assertEquals(text, LocalDate.of(2026, 9, 30), r.date)
        }
        assertEquals(LocalDate.of(2027, 2, 28), QuickEntry.parse("Rent end of month", LocalDate.of(2027, 2, 3)).date)
        assertNotNull(parse("Review end of the week").error)
    }
}
