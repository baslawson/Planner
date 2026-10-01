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








}
