package com.example.itinerary.data

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

// Second bug hunt of 1 Oct 2026, quick entry (qa/bughunt-20261001-2, Q-N1 to Q-N11 except N9): words that name something
// stay in the title, and more ways of writing a date are read.
class QuickFixesNamesAndDatesTest {
    private val today = LocalDate.of(2026, 10, 1) // a Thursday
    private fun parse(text: String, dayFirst: Boolean? = true) =
        QuickEntry.parse(text, today, now = LocalDateTime.of(2026, 10, 1, 10, 0), dayFirst = dayFirst, zone = ZoneId.of("Australia/Perth"))
    private fun ok(text: String, dayFirst: Boolean? = true) = parse(text, dayFirst).also { assertTrue("$text: ${it.error}", it.error == null || it.clarificationOnly) }
    private fun day(month: Int, day: Int, year: Int = 2026) = LocalDate.of(year, month, day)
    private fun at(hour: Int, minute: Int = 0) = LocalTime.of(hour, minute)

    // Q-N1: a frequency word that is part of a name stays in the title, and the entry is one event.
    @Test fun aFrequencyWordInANameIsTitleText() {
        for ((text, title, date) in listOf(
            Triple("Weekly report due Friday", "Weekly report", day(10, 2)),
            Triple("Monthly meeting Friday 10am", "Monthly meeting", day(10, 2)),
            Triple("Read The Daily Telegraph tomorrow", "Read The Daily Telegraph", day(10, 2)),
            Triple("Daily Mail delivery tomorrow", "Daily Mail delivery", day(10, 2)),
            Triple("Quarterly review Friday", "Quarterly review", day(10, 2)),
            Triple("Read the weekly newsletter Monday", "Read the weekly newsletter", day(10, 5)),
        )) ok(text).let {
            assertEquals(text, title, it.title); assertEquals(text, date, it.date); assertEquals(text, RepeatRule.NONE, it.repeat)
        }
        ok("Shopping at the Weekly Market Saturday").let {
            assertEquals("Shopping", it.title); assertEquals("the Weekly Market", it.location); assertEquals(day(10, 3), it.date); assertEquals(RepeatRule.NONE, it.repeat)
        }
        ok("Lunch at the Every Day Cafe Friday").let {
            assertEquals("Lunch", it.title); assertEquals("the Every Day Cafe", it.location); assertEquals(day(10, 2), it.date); assertEquals(RepeatRule.NONE, it.repeat)
        }
        ok("Every Day Cafe lunch Friday").let { assertEquals("Every Day Cafe lunch", it.title); assertEquals(RepeatRule.NONE, it.repeat) }
        ok("Monthly meeting Friday 10am").let { assertEquals(at(10), it.time) }
    }

    @Test fun aFrequencyWordOnItsOwnStillRepeats() {
        for ((text, title, rule) in listOf(
            Triple("Gym weekly", "Gym", RepeatRule.WEEKLY),
            Triple("Standup daily 9am", "Standup", RepeatRule.DAILY),
            Triple("Pay rent monthly", "Pay rent", RepeatRule.MONTHLY),
            Triple("Water plants every day", "Water plants", RepeatRule.DAILY),
            Triple("Team meeting every Monday", "Team meeting", RepeatRule.WEEKLY),
            Triple("Bins weekly on Tuesday", "Bins", RepeatRule.WEEKLY),
            Triple("Rates quarterly", "Rates", RepeatRule.everyMonths(3)),
            Triple("Daily 9am standup", "Standup", RepeatRule.DAILY),
            Triple("Weekly on Friday team sync", "Team sync", RepeatRule.WEEKLY),
            Triple("Gym every day at 6am", "Gym", RepeatRule.DAILY),
            Triple("Every day walk the dog 7am", "Walk the dog", RepeatRule.DAILY),
            Triple("Call mum weekly please", "Call mum please", RepeatRule.WEEKLY),
            Triple("Rent monthly for six months", "Rent", RepeatRule.MONTHLY),
        )) ok(text).let { assertEquals(text, title, it.title); assertEquals(text, rule, it.repeat) }
        ok("Bins weekly on Tuesday").let { assertEquals(day(10, 6), it.date) }
    }

    // Q-N2: the words between "remind me" and "to" are the when only if they read as a date or time.
    @Test fun remindMeKeepsATitleThatHasToInIt() {
        for ((text, title, date) in listOf(
            Triple("Remind me about the trip to Paris tomorrow", "About the trip to Paris", day(10, 2)),
            Triple("Remind me how to cook rice tomorrow", "How to cook rice", day(10, 2)),
            Triple("Remind me Sam's flight to Perth Friday", "Sam's flight to Perth", day(10, 2)),
        )) ok(text).let {
            assertEquals(text, title, it.title); assertEquals(text, date, it.date); assertTrue(text, it.taskHint); assertEquals(text, 0, it.reminderMinutes)
        }
    }

    @Test fun remindMeWithTheWhenBeforeToStillWorks() {
        ok("Remind me tomorrow to call mum").let { assertEquals("Call mum", it.title); assertEquals(day(10, 2), it.date); assertTrue(it.taskHint) }
        ok("Remind me in 2 hours to call mum").let { assertEquals("Call mum", it.title); assertEquals(at(12), it.time) }
        ok("Remind me on Friday at 3pm to pay rent").let { assertEquals("Pay rent", it.title); assertEquals(day(10, 2), it.date); assertEquals(at(15), it.time) }
        ok("Remind me to call mum").let { assertEquals("Call mum", it.title); assertTrue(it.taskHint) }
        ok("Remind me 12 Oct to renew rego").let { assertEquals("Renew rego", it.title); assertEquals(day(10, 12), it.date) }
        // An unsupported when is still pointed out rather than kept as the title.
        assertEquals("‘next week’ needs a specific date, time or supported repeat. Edit it, or open More options → Adjust recognised text to keep it in the title.",
            parse("Remind me next week to book").error)
    }

    // Q-N3: an amount next to a currency code is never a time.
    @Test fun anAmountBesideACurrencyCodeIsNotATime() {
        ok("Pay Sam AUD 20.50 Friday").let { assertEquals("Pay Sam AUD 20.50", it.title); assertEquals(day(10, 2), it.date); assertNull(it.time); assertFalse(it.ambiguousTime) }
        ok("Pay EUR 9.30 tomorrow").let { assertEquals("Pay EUR 9.30", it.title); assertNull(it.time); assertFalse(it.ambiguousTime) }
        ok("Pay Sam 20.50 AUD Friday").let { assertEquals("Pay Sam 20.50 AUD", it.title); assertNull(it.time) }
        ok("Gym USD 120 pm Friday").let { assertEquals("Gym USD 120 pm", it.title); assertNull(it.time) }
        ok("Pay USD 1,200 rent tomorrow 9am").let { assertEquals("Pay USD 1,200 rent", it.title); assertEquals(at(9), it.time) }
        // A time beside the amount is still read.
        ok("Pay 50 EUR tomorrow at 9.30").let { assertEquals("Pay 50 EUR", it.title); assertEquals(listOf(at(9, 30), at(21, 30)), it.timeChoices) }
    }

    // Q-N4: "due" and "by" introduce a day number as "on" does.
    @Test fun dueOrByBeforeADayNumberIsADate() {
        for ((text, title, date) in listOf(
            Triple("Rent due 15th", "Rent", day(10, 15)),
            Triple("Rent due by 15th", "Rent", day(10, 15)),
            Triple("Rent due on 15th", "Rent", day(10, 15)),
            Triple("Submit report by 5th", "Submit report", day(10, 5)),
            Triple("Submit report by 5th at 2pm", "Submit report", day(10, 5)),
        )) ok(text).let { assertEquals(text, title, it.title); assertEquals(text, date, it.date); assertTrue(text, it.dateSpecified) }
        ok("Submit report by 5th at 2pm").let { assertEquals(at(14), it.time) }
        // Before an ordinary word it is still title text.
        ok("Meeting 3rd floor Friday").let { assertEquals("Meeting 3rd floor", it.title); assertEquals(day(10, 2), it.date) }
        ok("Awards due 3rd place Friday").let { assertEquals("Awards due 3rd place", it.title); assertEquals(day(10, 2), it.date) }
    }





}
