package com.example.itinerary

import com.example.itinerary.data.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class TasksTest {
    private val today = LocalDate.of(2026,9,26)
    private fun task(id: String, due: LocalDate? = today, done: Boolean = false, priority: TaskPriority = TaskPriority.NORMAL) =
        PlannerTask(id, "Task $id", due, priority, done = done)
    @Test fun overdueTasksStayVisibleWithoutMovingTheirDueDate() {
        val overdue=task("overdue",today.minusDays(5));val later=task("later",today.plusDays(3))
        val rows=listOf(overdue,task("today"),later,task("anytime",null),task("done",today.minusDays(1),true))
        assertEquals(setOf("overdue","today","anytime"),Tasks.visible(rows,AgendaRange.TODAY,"",today,false).map { it.id }.toSet())
        assertEquals(setOf("overdue","today","later","anytime"),Tasks.visible(rows,AgendaRange.UPCOMING,"",today,false).map { it.id }.toSet())
        assertEquals(today.minusDays(5),Tasks.visible(rows,AgendaRange.TODAY,"",today,false).single { it.id=="overdue" }.dueDate)
        assertEquals(5,Tasks.visible(rows,AgendaRange.ALL,"",today,true).size)
    }
    @Test fun weekEndsOnSundayAndCompletedTasksAreOptIn() {
        val rows=listOf(task("sat"),task("sun",today.plusDays(1)),task("mon",today.plusDays(2)),task("done",done=true))
        assertEquals(setOf("sat","sun"),Tasks.visible(rows,AgendaRange.THIS_WEEK,"",today,false).map { it.id }.toSet())
        assertEquals(setOf("sat","sun","done"),Tasks.visible(rows,AgendaRange.THIS_WEEK,"",today,true).map { it.id }.toSet())
    }
    @Test fun prioritySortsWithinDayAndTitleFilterIgnoresAccents() {
        val rows=listOf(task("low",priority=TaskPriority.LOW),task("normal"),task("high",priority=TaskPriority.HIGH),task("done",done=true,priority=TaskPriority.HIGH))
        assertEquals(listOf("high","normal","low","done"),Tasks.visible(rows,AgendaRange.TODAY,"",today,true).map { it.id })
        assertEquals(1,Tasks.visible(listOf(task("a").copy(title="Café visit")),AgendaRange.ALL,"CAFE",today,false).size)
    }
    @Test fun searchIncludesNotesPrioritiesDatesAndUndatedTasksWithoutInventingEvents() {
        val rows=listOf(task("dated",today.plusDays(1)).copy(title="Call mechanic",notes="Ask about tyres",priority=TaskPriority.HIGH),
            task("anytime",null).copy(title="Research tyres"),task("complete",done=true).copy(title="Old tyres"))
        val index=Search.prepare(emptyList(),emptyList(),emptyList(),rows)
        assertEquals(3,index.run("tyres",emptySet(),today).taskHits.size)
        assertEquals(listOf("dated"),index.run("tyres tomorrow",emptySet(),today).taskHits.map { it.id })
        assertEquals(listOf("dated"),index.run("high mechanic",setOf("Tasks"),today).taskHits.map { it.id })
        assertTrue(index.run("tyres",setOf("Bills"),today).taskHits.isEmpty())
        assertEquals(3,index.run("",setOf("Tasks"),today).taskHits.size)
        assertTrue(index.run("tyres",emptySet(),today).hits.isEmpty())
        val invalid=index.run("2026-02-30 tyres",emptySet(),today)
        assertTrue(invalid.taskHits.isEmpty());assertEquals(listOf("2026-02-30"),invalid.invalidDates)
    }
    @Test fun reminderValidationAllowsLegacyAndFutureTimesButRejectsCorruptValues() {
        Tasks.validate(task("legacy"))
        Tasks.validate(task("future").copy(reminderAt = 1_800_000_000_000L))
        for (timestamp in listOf(0L, -1L, Long.MAX_VALUE)) {
            try { Tasks.validate(task("invalid").copy(reminderAt = timestamp)); fail("Invalid reminder accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun taskValidationRejectsBlankTitlesAndExcessiveNotes() {
        for (row in listOf(task("").copy(title="Valid"),task("a").copy(title="  "),task("b").copy(notes="x".repeat(20001)))) {
            try { Tasks.validate(row);fail("Invalid task was accepted") } catch (_: IllegalArgumentException) { }
        }
    }
}
