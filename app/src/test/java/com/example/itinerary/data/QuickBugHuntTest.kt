package com.example.itinerary.data

import org.junit.Assert.*
import org.junit.Test
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** Bugs found on 30 Sep 2026: "remind me" wording, next occurrence, and clock changes. */
class QuickBugHuntTest {
    private val perth = ZoneId.of("Australia/Perth")
    private val now = ZonedDateTime.of(2026, 9, 30, 14, 3, 0, 0, perth)
    private fun quick(text: String, title: String = "", task: Boolean = true, at: ZonedDateTime = now) =
        QuickInput(text = text, task = task, baseDate = at.toLocalDate(), title = title).suggestion(at)

    @Test fun remindMeWorksAfterATypedTitle() {
        for (text in listOf("remind me tomorrow", "Remind me tomorrow", "tomorrow, remind me", "remind me to tomorrow", "please remind me tomorrow")) {
            val s = quick(text, title = "Call mum")
            assertNull(text, s.quickProblem(true, now))
            assertEquals(text, "Call mum", s.title)
            assertEquals(text, LocalDate.of(2026, 10, 1), s.date)
            assertEquals(text, 0, s.reminderMinutes)
        }
        assertEquals("Pay rent", quick("remind me friday", title = "Pay rent").title)
        assertEquals(LocalDate.of(2026, 10, 2), quick("remind me friday", title = "Pay rent").date)
    }

    @Test fun bareRemindMeAtTheEndStillCountsAsUnfinished() {
        // "Gym remind me" may be the start of "… 30 min before", so it is not saved as a reminder yet.
        assertNotNull(quick("call mum tomorrow remind me").error)
        assertNotNull(quick("tomorrow remind me", title = "Call mum").error)
    }

    @Test fun detailedRemindersAfterATitleKeepTheirMeaning() {
        val before = quick("tomorrow 4pm remind me 30 min before", title = "Dentist", task = false)
        assertEquals(30, before.reminderMinutes)
        assertEquals(LocalTime.of(16, 0), before.time)
        val clock = quick("tomorrow 4pm remind me at 3pm", title = "Dentist", task = false)
        assertEquals(60, clock.reminderMinutes)
        assertEquals("Dentist", clock.title)
    }

    @Test fun nextOccurrenceIncludesTodayWhenStillAhead() {
        val daily = QuickEntrySuggestion("Gym", now.toLocalDate().minusDays(1), LocalTime.of(18, 0), repeat = RepeatRule.DAILY)
        assertEquals(now.toLocalDate(), daily.nextRepeatDate(false, now))
        assertEquals(now.toLocalDate(), daily.copy(time = null).nextRepeatDate(true, now))
        // Today's 1pm has passed at 2:03pm, so tomorrow.
        assertEquals(now.toLocalDate().plusDays(1), daily.copy(time = LocalTime.of(13, 0)).nextRepeatDate(false, now))
        val weekly = QuickEntrySuggestion("Bins", now.toLocalDate().minusDays(7), null, repeat = RepeatRule.WEEKLY)
        assertEquals(now.toLocalDate(), weekly.nextRepeatDate(true, now))
    }

    @Test fun inHoursIsRealTimeAcrossAClockChange() {
        val sydney = ZoneId.of("Australia/Sydney")
        val night = ZonedDateTime.of(2026, 10, 4, 1, 30, 0, 0, sydney)
        val s = quick("Call in 2 hours", task = false, at = night)
        assertEquals(LocalTime.of(4, 30), s.time)
        assertEquals(120, Duration.between(night, s.date.atTime(s.time).atZone(sydney)).toMinutes())
    }

    @Test fun hoursBeforeIsRealTimeButDaysKeepTheClock() {
        val sydney = ZoneId.of("Australia/Sydney")
        val change = LocalDate.of(2026, 10, 4)
        val trigger = reminderTrigger(change, LocalTime.of(3, 30), 120, sydney)
        assertEquals(120, Duration.between(trigger, change.atTime(3, 30).atZone(sydney)).toMinutes())
        assertEquals(LocalTime.of(9, 0), reminderTrigger(change.plusDays(1), LocalTime.of(9, 0), 1440, sydney).toLocalTime())
    }

    @Test fun messagesNameTheRealButtons() {
        val s = quick("Bins remind me", title = "")
        listOf(quick("Swim every", task = false).error, quick("remind me every", task = false).error).filterNotNull().forEach {
            assertFalse(it, "Details →" in it || "More details" in it)
        }
        assertNotNull(s)
    }
}
