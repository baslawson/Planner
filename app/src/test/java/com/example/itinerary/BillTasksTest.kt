package com.example.itinerary

import com.example.itinerary.data.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class BillTasksTest {
    private val today = LocalDate.of(2026, 9, 26)
    private fun bill(id: Long, date: LocalDate = today, paid: Boolean = false, skipped: Boolean = false) =
        PlanEvent(1, id, date, null, "Bill $id", 0, null, category = "Bills", paid = paid, skipped = skipped)

    @Test fun unpaidOverdueBillsStayInEveryTaskRangeOnTheirActualDueDate() {
        val overdue = bill(1, today.minusDays(20))
        for (range in AgendaRange.entries) {
            assertEquals(listOf(overdue), BillTasks.visible(listOf(overdue), range, "", today, false))
        }
        assertEquals(today.minusDays(20), overdue.date)
    }
    @Test fun completedOverdueBillsAndTasksCanBeShownInEveryRange() {
        val paid = bill(10, today.minusDays(40), paid = true)
        val skipped = bill(11, today.minusDays(2), skipped = true)
        val task = PlannerTask(title = "Old completed task", dueDate = today.minusDays(40), done = true)
        for (range in AgendaRange.entries) {
            assertTrue(BillTasks.visible(listOf(paid, skipped), range, "", today, false).isEmpty())
            assertEquals(listOf(paid, skipped), BillTasks.visible(listOf(paid, skipped), range, "", today, true))
            assertTrue(Tasks.visible(listOf(task), range, "", today, false).isEmpty())
            assertEquals(listOf(task), Tasks.visible(listOf(task), range, "", today, true))
            assertTrue(BillTasks.visible(listOf(paid), range, "unmatched", today, true).isEmpty())
        }
        val future = bill(12, today.plusDays(20), paid = true)
        assertTrue(BillTasks.visible(listOf(future), AgendaRange.TODAY, "", today, true).isEmpty())
        assertTrue(BillTasks.visible(listOf(future), AgendaRange.THIS_WEEK, "", today, true).isEmpty())
    }
    @Test fun paidAndSkippedBillsAreCompletedAndRespectTheSelectedRange() {
        val rows = listOf(bill(1), bill(2, paid=true), bill(3, skipped=true), bill(4, today.minusDays(1), paid=true), bill(5,today.plusDays(2)))
        assertEquals(listOf(1L), BillTasks.visible(rows,AgendaRange.TODAY,"",today,false).map { it.id })
        assertEquals(setOf(1L,2L,3L,4L), BillTasks.visible(rows,AgendaRange.TODAY,"",today,true).map { it.id }.toSet())
        assertEquals(5, BillTasks.visible(rows,AgendaRange.ALL,"",today,true).size)
        assertEquals(listOf(1L), BillTasks.visible(rows,AgendaRange.THIS_WEEK,"",today,false).map { it.id })
        assertEquals(listOf(1L,5L), BillTasks.visible(rows,AgendaRange.UPCOMING,"",today,false).map { it.id })
    }
    @Test fun billTasksAreNotDuplicatedAsAgendaEventsAndTitleFilterIgnoresAccents() {
        val bill = bill(1).copy(title="Café bill")
        val event = bill(2).copy(category="Other")
        assertEquals(listOf(bill),BillTasks.visible(listOf(bill,event),AgendaRange.ALL,"CAFE",today,false))
        assertEquals(listOf(2L),Agenda.days(listOf(bill,event),AgendaRange.ALL,"",today).flatMap { it.entries }.map { it.event.id })
    }
    @Test fun taskSearchIncludesBillsWithoutDuplicatingTheirStoredRecords() {
        val trip = Trip(id=1,name="Stored owner",destination="",startDate=today,endDate=today)
        val bill = ItineraryItem(id=1,tripId=1,date=today,startTime=null,title="Electricity",category="Bills")
        val event = bill.copy(id=2,category="Other",title="Meeting")
        val task = PlannerTask(title="Call provider")
        val result = Search.prepare(listOf(trip),listOf(bill,event),emptyList(),listOf(task)).run("",setOf("Tasks"),today)
        assertEquals(listOf(bill), result.hits.map { it.item })
        assertEquals(listOf(task),result.taskHits)
    }
    @Test fun billDueTimesDoNotCreateCalendarConflictsOrOvernightOccupancy() {
        val bill = ItineraryItem(id=1,tripId=1,date=today,startTime=LocalTime.of(23,30),title="Bill",category="Bills",durationMinutes=120)
        assertTrue(overlappingEvents(listOf(bill),listOf(today),bill.startTime,60,emptySet()).isEmpty())
        assertEquals(listOf(bill),eventsOnDay(listOf(bill),today))
        assertTrue(eventsOnDay(listOf(bill),today.plusDays(1)).isEmpty())
    }
}
