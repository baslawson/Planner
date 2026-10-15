package com.example.itinerary.reminders

import com.example.itinerary.data.Habits
import com.example.itinerary.data.ItineraryItem
import com.example.itinerary.data.PlannerTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

// Up next (which event, when to leave) and the Habits page (marks, counts, order).
class UpNextAndHabitsTest {
    private val day = LocalDate.of(2026, 10, 15)
    private fun event(title: String, date: LocalDate, hour: Int, minute: Int = 0, buffer: Int = 0, category: String = "Other", skipped: Boolean = false) =
        ItineraryItem(tripId = 0, date = date, startTime = LocalTime.of(hour, minute), title = title, category = category,
            bufferBeforeMinutes = buffer, skipped = skipped)

    @Test fun upNextIsTheNextTimedEventWithinADay() {
        val now = day.atTime(9, 0)
        val events = listOf(
            event("Gone", day, 8), event("Rent", day, 9, 30, category = "Bills"), event("Skipped", day, 9, 45, skipped = true),
            event("Dentist", day, 10, buffer = 25), event("Lunch", day, 12),
            ItineraryItem(tripId = 0, date = day, startTime = null, title = "All day"))
        val next = UpNext.next(events, now)!!
        assertEquals("Dentist", next.event.title)
        assertEquals(day.atTime(10, 0), next.start)
        // With a travel buffer: the countdown is to when to leave.
        assertEquals(day.atTime(9, 35), next.leaveAt)
        assertEquals(day.atTime(9, 35), next.countdownTo)
        // Leaving time already gone: to the start itself.
        assertNull(UpNext.next(events, day.atTime(9, 40))!!.leaveAt)
        // Tomorrow morning counts, two days on doesn't.
        assertEquals("Early flight", UpNext.next(listOf(event("Early flight", day.plusDays(1), 6)), day.atTime(22, 0))!!.event.title)
        assertNull(UpNext.next(listOf(event("Far", day.plusDays(2), 9)), day.atTime(22, 0)))
    }

    // Bug hunt 30 B1, B5: the one to act on first wins; "Leave soon" only within the hour.
    @Test fun upNextGoesByWhenToLeave() {
        val now = day.atTime(9, 0)
        val events = listOf(event("Coffee", day, 10), event("Airport", day, 11, buffer = 90))
        val next = UpNext.next(events, now)!!
        assertEquals("Airport", next.event.title)
        assertEquals(day.atTime(9, 30), next.leaveAt)
        assertEquals(true, UpNext.leaveSoon(next, now))
        val early = UpNext.next(listOf(event("Flight", day.plusDays(1), 8, buffer = 120)), day.atTime(21, 0))!!
        assertEquals(false, UpNext.leaveSoon(early, day.atTime(21, 0)))
        assertEquals(true, UpNext.leaveSoon(early, day.plusDays(1).atTime(5, 0)))
    }

    // Bug hunt 31: within a day by when to leave, not by the start: a flight in 25 h with a 4-hour journey (leave in
    // 21 h) comes before an event in 23 h.
    @Test fun upNextCountsTheDayFromWhenToLeave() {
        val now = day.atTime(9, 0)
        val events = listOf(event("Meeting", day.plusDays(1), 8), event("Flight", day.plusDays(1), 10, buffer = 240))
        assertEquals("Flight", UpNext.next(events, now)!!.event.title)
        assertNull(UpNext.next(listOf(event("Flight", day.plusDays(1), 10, buffer = 30)), now))
    }

    // Bug hunt 30 B3: an occurrence ticked off then unticked leaves its next one; the habit shows once.
    @Test fun habitShowsOnceAfterUnticking() {
        val a = PlannerTask(id = "a", title = "Gym", dueDate = day, repeat = "WEEKLY", nextTaskId = "b")
        val b = PlannerTask(id = "b", title = "Gym", dueDate = day.plusWeeks(1), repeat = "WEEKLY")
        val habits = Habits.of(listOf(a, b))
        assertEquals(listOf("b"), habits.map { it.task.id })
        assertEquals(listOf(Habits.Mark.TO_DO, Habits.Mark.TO_DO), habits.single().recent)
    }

    @Test fun habitsShowEachRepeatingTasksRecentOccurrences() {
        fun gym(id: String, due: LocalDate, late: Long?, next: String?) = PlannerTask(id = id, title = "Gym", dueDate = due, repeat = "WEEKLY",
            done = late != null, doneOn = late?.let { due.plusDays(it) }, nextTaskId = next)
        val mon = LocalDate.of(2026, 9, 7)
        val tasks = listOf(gym("a", mon, 0, "b"), gym("b", mon.plusWeeks(1), 2, "c"), gym("c", mon.plusWeeks(2), 0, "d"),
            gym("d", mon.plusWeeks(3), -1, "e"), gym("e", mon.plusWeeks(4), null, null),
            PlannerTask(id = "p", title = "Pills", dueDate = day, repeat = "DAILY"),
            PlannerTask(id = "x", title = "One-off", dueDate = day))
        val habits = Habits.of(tasks)
        assertEquals(listOf("Gym", "Pills"), habits.map { it.task.title })
        val gym = habits.first()
        assertEquals(listOf(Habits.Mark.ON_TIME, Habits.Mark.LATE, Habits.Mark.ON_TIME, Habits.Mark.ON_TIME, Habits.Mark.TO_DO), gym.recent)
        assertEquals(2, gym.streak); assertEquals(3, gym.onTime); assertEquals(4, gym.done)
        assertEquals("Last 5: 3 on time, 1 late, 1 to do", Habits.spoken(gym))
        // A new habit: just the one to do.
        assertEquals(listOf(Habits.Mark.TO_DO), habits[1].recent)
        // At most the last ten.
        val long = (0 until 30).map { i -> gym("g$i", mon.plusWeeks(i.toLong()), if (i < 29) 0 else null, if (i < 29) "g${i + 1}" else null) }
        assertEquals(Habits.SHOWN, Habits.of(long).single().recent.size)
    }
}
