package com.example.itinerary.data

import org.junit.Assert.*
import org.junit.Test
import java.time.*

class WorkflowToolsTest {
    private val day = LocalDate.of(2026, 10, 1)
    @Test fun buffersReserveTimeAcrossMidnightAndTouchingEdges() {
        val event = ItineraryItem(tripId=1, date=day, startTime=LocalTime.of(0, 15), title="Travel", durationMinutes=30,
            bufferBeforeMinutes=30, bufferAfterMinutes=45)
        val windows = FreeTime.find(listOf(event), day, day, LocalTime.MIDNIGHT, LocalTime.of(3,0), 30,
            false, false, 60, day.atStartOfDay())
        assertEquals(listOf(FreeWindow(day.atTime(1,30), day.atTime(3,0))), windows)
        assertEquals(listOf(event), overlappingEvents(listOf(event), listOf(day.minusDays(1)), LocalTime.of(23,30), 30, emptySet()))
        assertTrue(overlappingEvents(listOf(event), listOf(day), LocalTime.of(1,30), 30, emptySet()).isEmpty())
        assertEquals(listOf(event), overlappingEvents(listOf(event), listOf(day), LocalTime.of(2,0), 30, emptySet(), before=45))
    }
    @Test fun unbufferedUnknownDurationRetainsPointConflictSemantics() {
        val event = ItineraryItem(tripId=1,date=day,startTime=LocalTime.NOON,title="Point")
        assertTrue(overlappingEvents(listOf(event), listOf(day), LocalTime.of(12,1), null, emptySet()).isEmpty())
        assertEquals(listOf(event),overlappingEvents(listOf(event),listOf(day),LocalTime.of(12,1),null,emptySet(),before=2))
    }
    @Test fun dependenciesSupportDiamondsAndRejectCycles() {
        val a=PlannerTask(id="a", title="A")
        val b=PlannerTask(id="b", title="B",prerequisiteIds=listOf("a"))
        val c=PlannerTask(id="c", title="C",prerequisiteIds=listOf("a"))
        val d=PlannerTask(id="d", title="D",prerequisiteIds=listOf("b","c"))
        TaskDependencies.validateGraph(listOf(a,b,c,d))
        assertThrows(IllegalArgumentException::class.java) { TaskDependencies.validateGraph(listOf(a.copy(prerequisiteIds=listOf("d")),b,c,d)) }
        assertThrows(IllegalArgumentException::class.java) { TaskDependencies.validateGraph(listOf(a.copy(prerequisiteIds=listOf("a")))) }
        assertEquals(listOf("a"),TaskDependencies.blockers(b,emptyList()))
        assertTrue(TaskDependencies.blockers(b,listOf(a.copy(done=true))).isEmpty())
        assertEquals(listOf("a"),b.duplicateForEditing().prerequisiteIds)
        assertEquals(listOf("a"),b.copy(repeat="DAILY").nextOccurrence(day)!!.prerequisiteIds)
    }
    @Test fun longDependencyChainDoesNotOverflow() {
        val tasks=(0..5000).map { PlannerTask(id="$it", prerequisiteIds=if(it==0)emptyList() else listOf("${it-1}")) }
        TaskDependencies.validateGraph(tasks.reversed())
    }
    @Test fun sharedTextKeepsLinkAndBodyForReview() {
        val text="Read article\nhttps://example.test/article?x=1&y=2"
        assertEquals(SharedDraft("Read article", text), SharedText.draft(text))
        assertEquals("From browser",SharedText.draft(text,"From browser").title)
        assertThrows(IllegalArgumentException::class.java) { SharedText.draft(" ") }
        assertThrows(IllegalArgumentException::class.java) { SharedText.draft("a".repeat(20001)) }
        assertEquals(500, SharedText.draft("a".repeat(600)).title.length)
    }
}
