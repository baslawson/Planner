package com.example.itinerary.data

import org.junit.Assert.*
import org.junit.Test
import java.time.*

/** The eleven Quick entry parser suggestions of 27 Sep 2026. Today is Sunday 27 September 2026. */
class QuickSuggestionsTest {
    private val today = LocalDate.of(2026, 9, 27)
    private fun parse(text: String, now: LocalDateTime? = null, dayFirst: Boolean? = null) =
        QuickEntry.parse(text, today, now = now, dayFirst = dayFirst)

    @Test fun shortWeekdayAndClockWordsStayInTitlesBeforeOrdinaryWords() {
        val sun = parse("Buy sun cream")
        assertNull(sun.error); assertEquals("Buy sun cream", sun.title); assertFalse(sun.dateSpecified)
        assertEquals("Sat nav update", parse("Sat nav update tomorrow").title)
        assertEquals(today.plusDays(1), parse("Sat nav update tomorrow").date)
        val wed = parse("Wed anniversary dinner Friday 7pm")
        assertNull(wed.error); assertEquals("Wed anniversary dinner", wed.title); assertEquals(LocalDate.of(2026, 10, 2), wed.date)
        val mass = parse("Midnight Mass Dec 24")
        assertEquals("Midnight Mass", mass.title); assertNull(mass.time); assertEquals(LocalDate.of(2026, 12, 24), mass.date)
        // Still scheduling beside scheduling words, at the end, or before a time.
        assertEquals(LocalDate.of(2026, 10, 3), parse("Gym sat 6pm").date)
        assertEquals(LocalDate.of(2026, 10, 3), parse("Gym sat").date)
        assertEquals(LocalDate.of(2026, 10, 3), parse("Brunch sat with Tom").date)
        assertEquals(LocalDate.of(2026, 9, 30), parse("Gym on wed").date)
        assertEquals(LocalTime.MIDNIGHT, parse("Study midnight").time)
        assertEquals(LocalTime.NOON, parse("Lunch tomorrow noon at Riverside Cafe").time)
        assertEquals("Sun Valley", parse("Ski trip at Sun Valley tomorrow").location)
    }

    @Test fun unpaddedColonTimesAskMorningOrEvening() {
        val movie = parse("Movie 7:30")
        assertNull(movie.time); assertTrue(movie.ambiguousTime); assertTrue(movie.clarificationOnly)
        assertEquals(listOf(LocalTime.of(7, 30), LocalTime.of(19, 30)), movie.timeChoices)
        assertEquals(listOf(LocalTime.of(0, 15), LocalTime.of(12, 15)), parse("Lunch at 12:15").timeChoices)
        for ((text, time) in listOf("Movie 07:30" to LocalTime.of(7, 30), "Movie 19:30" to LocalTime.of(19, 30),
            "Movie 7:30pm" to LocalTime.of(19, 30), "Movie 0:30" to LocalTime.of(0, 30), "Call 15h30" to LocalTime.of(15, 30))) {
            assertEquals(text, time, parse(text).time); assertFalse(text, parse(text).ambiguousTime)
        }
        val picked = QuickInput("Movie 7:30", baseDate = today, timeOverride = "19:30").suggestion()
        assertNull(picked.error); assertEquals(LocalTime.of(19, 30), picked.time)
        assertEquals(LocalTime.of(14, 0), parse("Meeting 3pm—actually 2:00pm").time)
    }

    @Test fun ordinalDaysAndMonthlyDayRepeats() {
        assertEquals(LocalDate.of(2026, 10, 5), parse("Dentist on the 5th").date)
        assertEquals("Dentist", parse("Dentist on the 5th").title)
        assertEquals(LocalDate.of(2026, 9, 27), parse("Pay the 27th").date)
        assertEquals(LocalDate.of(2026, 10, 31), parse("Party the 31st").date)
        assertEquals(LocalDate.of(2026, 10, 5), parse("Dentist 5th of October").date)
        assertEquals(LocalDate.of(2027, 10, 5), parse("Dentist the 5th of October 2027").date)
        assertNotNull(parse("Party the 32nd").error)
        assertEquals("Sam's 5th birthday", parse("Sam's 5th birthday").title)
        val rent = parse("Pay rent 1st of every month")
        assertNull(rent.error); assertEquals("Pay rent", rent.title); assertEquals(RepeatRule.MONTHLY, rent.repeat)
        assertEquals(LocalDate.of(2026, 10, 1), rent.date)
        assertEquals(LocalDate.of(2026, 10, 31), parse("Report on the 31st of each month").date)
        assertEquals(LocalDate.of(2026, 10, 15), parse("Rent every month on the 15th").date)
        assertNotNull(parse("Rent 1st of every month 2026-10-02").error)
        assertNotNull(parse("Rent 1st of every month every week").error)
    }

    @Test fun extraWeekdaySpellingsAndVagueRangesNeedAChoice() {
        assertEquals(LocalDate.of(2026, 9, 30), parse("Gym weds 6pm").date); assertEquals("Gym", parse("Gym weds 6pm").title)
        assertEquals(LocalDate.of(2026, 10, 1), parse("Gym thur 6pm").date)
        for (phrase in listOf("next week", "this month", "next year", "the end of the week")) {
            val s = parse("Review $phrase")
            assertNotNull(phrase, s.error)
            val p = s.phrases.single { it.kind == QuickPhraseKind.UNSUPPORTED }
            assertNull(phrase, QuickEntry.parse("Review $phrase", today, listOf(p.start until p.end)).error)
        }
        assertEquals(LocalDate.of(2026, 9, 28), parse("Review next monday").date)
        assertNotNull(parse("Call tonight").timePrompt)
    }

    @Test fun inAPlaceIsTitleTextButInANumberedUnitIsNot() {
        val park = parse("Coffee in a park tomorrow 10am")
        assertNull(park.error); assertEquals("Coffee in a park", park.title); assertEquals(LocalTime.of(10, 0), park.time)
        assertNull(parse("Team lunch in an office tomorrow").error)
        assertNull(parse("Lunch in a meeting room").error)
        assertEquals(today.plusWeeks(10), parse("Call in 5 fortnights").date)
        assertNotNull(parse("Call in 90 seconds").error)
    }

    @Test fun weekdayBesideADateIsACrossCheck() {
        for (text in listOf("Dentist Friday 2 October 3pm", "Dentist Fri, Oct 2 3pm", "Dentist Friday the 2nd 3pm", "Dentist on Friday 2nd October 2026")) {
            val s = parse(text)
            assertNull(text, s.error); assertEquals(text, LocalDate.of(2026, 10, 2), s.date); assertEquals(text, "Dentist", s.title)
        }
        assertEquals("2 October 2026 is a Friday, not a Thursday. Correct the date or the weekday.", parse("Dentist Thursday 2 October").error)
        val numeric = parse("Dentist Fri 2/10")
        assertNull(numeric.error); assertEquals(LocalDate.of(2026, 10, 2), numeric.date); assertTrue(numeric.dateChoices.isEmpty())
        assertNotNull(parse("Dentist Mon 2/10").error)
        // Two weekdays or a weekday with a relative date are still two dates.
        assertNotNull(parse("Dentist Friday Saturday 3pm").error)
        assertNotNull(parse("Dentist Friday in 3 days").error)
        assertNotNull(parse("Dentist Friday lunch 2 October").error)
    }

    @Test fun remindMeToSuggestsATaskAndReminds() {
        val call = parse("Remind me to call mum tomorrow")
        assertNull(call.error); assertEquals("Call mum", call.title); assertTrue(call.taskHint)
        assertEquals(today.plusDays(1), call.date); assertEquals(0, call.reminderMinutes); assertTrue(call.reminderImplied)
        assertFalse(call.timed())
        val timed = parse("please remind me to call mum tomorrow at 3pm")
        assertTrue(timed.timed()); assertEquals(LocalTime.of(15, 0), timed.time); assertEquals(0, timed.reminderMinutes)
        val undated = parse("Remind me to buy milk")
        assertEquals("Buy milk", undated.title); assertNull(undated.reminderMinutes); assertTrue(undated.taskHint)
        val explicit = parse("Remind me to call mum tomorrow 3pm remind me 10 minutes before")
        assertEquals(10, explicit.reminderMinutes); assertFalse(explicit.reminderImplied)
        assertFalse(parse("Call mum, remind me 1 hour before tomorrow 3pm").taskHint)
        assertNotNull(parse("Call and remind me to buy milk").error)
        // A task reminder at 09:00 today that has passed is dropped instead of blocking the entry.
        val lateMorning = ZonedDateTime.of(today.atTime(11, 0), ZoneId.of("UTC"))
        val input = QuickInput("Remind me to call mum today", task = true, baseDate = today)
        assertNull(input.suggestion(lateMorning).reminderMinutes)
        assertNull(input.suggestion(lateMorning).quickProblem(true, lateMorning))
        assertEquals(0, input.suggestion(ZonedDateTime.of(today.atTime(8, 0), ZoneId.of("UTC"))).reminderMinutes)
        // Quick entry suggests Task for an untimed "remind me to", Event when a time is given.
        QuickEntry.parse("Remind me to buy milk tomorrow", today).let { assertTrue(it.taskHint && !it.timed()) }
        QuickEntry.parse("Remind me to call at 3pm tomorrow", today).let { assertTrue(it.taskHint && it.timed()) }
    }

    @Test fun relativeTimesCountFromNowRoundedUp() {
        val now = today.atTime(14, 32, 10)
        for ((text, expected) in listOf("Call mum in 30 minutes" to LocalTime.of(15, 5), "Call mum in an hour" to LocalTime.of(15, 35),
            "Call in half an hour" to LocalTime.of(15, 5), "Call in 1h 30m" to LocalTime.of(16, 5), "Call in 2 hours for 15 minutes" to LocalTime.of(16, 35))) {
            val s = parse(text, now)
            assertNull(text, s.error); assertEquals(text, expected, s.time); assertEquals(text, today, s.date); assertTrue(s.dateSpecified)
        }
        assertEquals("Call mum", parse("Call mum in 30 minutes", now).title)
        assertEquals(LocalTime.of(14, 35), parse("Call in 3 minutes", today.atTime(14, 30)).time)
        val late = parse("Call in 2 hours", today.atTime(23, 0))
        assertEquals(today.plusDays(1), late.date); assertEquals(LocalTime.of(1, 0), late.time)
        assertNotNull(parse("Call in 30 minutes").error)
        assertNotNull(parse("Call tomorrow in 30 minutes", now).error)
        assertNotNull(parse("Call in 30 minutes at 5pm", now).error)
        assertNotNull(parse("Call in 25 hours", now).error)
        // QuickInput only supplies the clock for entries based on today.
        val zone = ZoneId.of("UTC")
        assertEquals(LocalTime.of(15, 5), QuickInput("Call in 30 minutes", baseDate = today).suggestion(now.atZone(zone)).time)
        assertNotNull(QuickInput("Call in 30 minutes", baseDate = today.minusDays(1)).suggestion(now.atZone(zone)).error)
    }

    @Test fun relativeMonthsAndYears() {
        assertEquals(LocalDate.of(2026, 11, 27), parse("Meeting in 2 months").date)
        assertEquals(LocalDate.of(2026, 10, 27), parse("Review a month from today").date)
        assertEquals(LocalDate.of(2027, 9, 27), parse("Renew in a year").date)
        assertEquals(LocalDate.of(2027, 2, 28), QuickEntry.parse("Pay in one month", LocalDate.of(2027, 1, 31)).date)
    }

    @Test fun numericDatesFollowTheDateFormatSetting() {
        assertEquals(listOf(LocalDate.of(2027, 4, 3), LocalDate.of(2027, 3, 4)), parse("Dentist 3/4").dateChoices)
        val dayFirst = parse("Dentist 3/4", dayFirst = true)
        assertTrue(dayFirst.dateChoices.isEmpty()); assertEquals(LocalDate.of(2027, 4, 3), dayFirst.date); assertNull(dayFirst.error)
        assertEquals(LocalDate.of(2027, 3, 4), parse("Dentist 3/4", dayFirst = false).date)
        assertEquals(LocalDate.of(2027, 4, 13), parse("Dentist 13/4", dayFirst = false).date)
        assertEquals(true, DateFormatChoice.NUMERIC_DMY.numericDayFirst()); assertEquals(true, DateFormatChoice.DAY_MONTH_YEAR.numericDayFirst())
        assertEquals(false, DateFormatChoice.NUMERIC_MDY.numericDayFirst()); assertEquals(false, DateFormatChoice.MONTH_DAY_YEAR.numericDayFirst())
        assertNull(DateFormatChoice.ISO.numericDayFirst())
        assertEquals(false, DateFormatChoice.SYSTEM.numericDayFirst(java.util.Locale.US))
        assertEquals(true, DateFormatChoice.SYSTEM.numericDayFirst(java.util.Locale.UK))
    }

    @Test fun everyWeekdayRepeatsMondayToFriday() {
        val sync = parse("Team sync every weekday 9am")
        assertNull(sync.error); assertEquals("Team sync", sync.title); assertEquals(RepeatRule.WEEKDAYS, sync.repeat)
        assertEquals(LocalDate.of(2026, 9, 28), sync.date)
        assertEquals(RepeatRule.WEEKDAYS, parse("Standup on weekdays 9am").repeat)
        assertEquals(LocalDate.of(2026, 10, 1), parse("Standup each weekday 2026-10-01 9am").date)
        assertEquals("The start date doesn't match the weekday repeat. Choose a matching date.", parse("Standup every weekday 2026-10-03 9am").error)
        assertEquals(listOf(28, 29, 30, 1, 2, 5, 6), RepeatRule.WEEKDAYS.dates(LocalDate.of(2026, 9, 26), 7).map { it.dayOfMonth })
        val friday = PlannerTask(title = "Report", dueDate = LocalDate.of(2026, 10, 2), repeat = "WEEKDAYS")
        assertEquals(LocalDate.of(2026, 10, 5), friday.nextOccurrence(LocalDate.of(2026, 10, 2))!!.dueDate)
        assertEquals(LocalDate.of(2026, 10, 7), friday.nextOccurrence(LocalDate.of(2026, 10, 6))!!.dueDate)
        assertEquals("Weekdays", QuickInput("Standup every weekday", task = true, baseDate = today).suggestion().quickTask().let { TaskRepeat.valueOf(it.repeat).label })
    }

    @Test fun fourDigit24HourTimesWhereTheyReadAsTimes() {
        for ((text, time) in listOf("Gym tomorrow 0600" to LocalTime.of(6, 0), "Night shift 0000" to LocalTime.MIDNIGHT,
            "Meeting at 1500" to LocalTime.of(15, 0), "Meeting tomorrow 1500" to LocalTime.of(15, 0), "Dinner Friday 1930" to LocalTime.of(19, 30),
            "Gym 1800hrs" to LocalTime.of(18, 0), "Gym 1800 hrs" to LocalTime.of(18, 0), "Gym 0600h" to LocalTime.of(6, 0),
            "Meeting tomorrow 1500—actually 1600" to LocalTime.of(16, 0), "Meeting 0900, actually 1000" to LocalTime.of(10, 0))) {
            val s = parse(text)
            assertNull(text, s.error); assertEquals(text, time, s.time); assertFalse(text, s.ambiguousTime)
            assertFalse(text, s.title.any(Char::isDigit)); assertFalse(text, s.title.contains("hrs"))
        }
        for ((text, minutes) in listOf("Shift 0900-1700" to 480, "Shift 0900 to 1700" to 480, "Shift from 1500 until 1730" to 150,
            "Night shift 2200-0600" to 480, "Shift 1500–1700" to 120)) {
            val s = parse(text)
            assertNull(text, s.error); assertEquals(text, minutes, s.durationMinutes); assertEquals(text, text.substringBefore(" 0").substringBefore(" 1").substringBefore(" 2").substringBefore(" from"), s.title)
        }
        // Not time-like, or not a valid time: kept in the title.
        for (text in listOf("Meeting 1500", "Tax return 2027", "Buy 1500 screws", "Flight QF1234 tomorrow", "Code 2460 tomorrow", "Pin 1575 at 0600")) {
            val s = parse(text)
            assertNull(text, s.error)
            assertTrue(text, Regex("\\d{4}").containsMatchIn(s.title))
        }
        assertNull(parse("Meeting 1500").time); assertNull(parse("Tax return 2027").time)
        assertEquals(LocalTime.of(6, 0), parse("Pin 1575 at 0600").time)
        // Years inside dates are still years.
        assertEquals(LocalDate.of(2027, 10, 5), parse("Dentist 5 October 2027 0900").date)
        assertEquals(LocalTime.of(9, 0), parse("Dentist 5 October 2027 0900").time)
        assertEquals(LocalDate.of(2027, 10, 5), parse("Dentist 2027-10-05 1500").date)
        assertEquals(LocalTime.of(15, 0), parse("Dentist 2027-10-05 1500").time)
        // Mixed styles and quoted text follow the existing rules.
        assertNotNull(parse("Shift 0900-5pm").error)
        assertEquals("Room 0600", parse("\"Room 0600\" tomorrow").title)
        val kept = parse("Gym tomorrow 0600")
        val phrase = kept.phrases.single { it.kind == QuickPhraseKind.TIME }
        assertNull(QuickEntry.parse("Gym tomorrow 0600", today, listOf(phrase.start until phrase.end)).time)
    }
}
