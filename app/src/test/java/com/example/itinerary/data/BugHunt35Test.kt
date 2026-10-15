package com.example.itinerary.data

import com.example.itinerary.reminders.MorningSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

// Bug hunt 35 (full app): the parts that can be checked off the device.
class BugHunt35Test {
    private val day = LocalDate.of(2026, 10, 15)

    // S1: today's summary not shown yet with its time just gone comes in a minute, not tomorrow.
    @Test fun summaryCatchesUp() {
        val at = LocalTime.of(7, 30)
        assertTrue(MorningSummary.catchUp(day.atTime(7, 45), at, shownToday = false))
        assertFalse(MorningSummary.catchUp(day.atTime(7, 45), at, shownToday = true))
        assertFalse(MorningSummary.catchUp(day.atTime(7, 0), at, shownToday = false))
        assertFalse(MorningSummary.catchUp(day.atTime(11, 0), at, shownToday = false)) // past the 3 hours
        // A late evening time: until midnight.
        assertTrue(MorningSummary.catchUp(day.atTime(23, 50), LocalTime.of(22, 30), shownToday = false))
    }

    // T1: an overdue open occurrence ends the streak.
    @Test fun overdueEndsTheStreak() {
        fun gym(id: String, due: LocalDate, done: Boolean, next: String?) = PlannerTask(id = id, title = "Gym", dueDate = due, repeat = "DAILY",
            done = done, doneOn = if (done) due else null, nextTaskId = next)
        val tasks = (0 until 5).map { gym("g$it", day.minusDays(8L - it), true, "g${it + 1}") } + gym("g5", day.minusDays(3), false, null)
        assertEquals(0, TaskStreaks.streaks(tasks, day)["g5"] ?: 0)
        assertEquals(5, TaskStreaks.streaks(tasks, day.minusDays(3))["g5"])
    }

    // T4: an address with ( ) in it.
    @Test fun linkWithBrackets() {
        val runs = Markdown.inline("See [Foo](https://en.wikipedia.org/wiki/Foo_(bar)) now")
        assertEquals("https://en.wikipedia.org/wiki/Foo_(bar)", runs.single { it.url != null }.url)
        assertEquals(" now", runs.last().text)
    }

    // T5: each word, anywhere in the note.
    @Test fun notesSearchByWords() {
        val notes = listOf(PlannerNote(id = "a", title = "Shopping", content = "Eggs and **milk**"), PlannerNote(id = "b", title = "Other", content = "milk"))
        assertEquals(listOf("a"), Notes.visible(notes, NoteFilter.All, "milk eggs").map { it.id })
    }

    // T6: international and underscore host names.
    @Test fun moreWebAddresses() {
        assertNotNull(Links.normalize("bücher.de"))
        assertNotNull(Links.normalize("https://my_site.example.com/page"))
        assertEquals("bücher.de", Links.defaultName("https://bücher.de"))
        assertEquals(null, Links.normalize("javascript:alert(1)"))
    }
}
