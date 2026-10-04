package com.example.itinerary

import androidx.test.platform.app.InstrumentationRegistry
import com.example.itinerary.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

// Wish list #1: replacing a task with its event (and events with their task) moves the original to Recently deleted with
// an Undo that takes both back; tasks waiting on the task are freed, and wait again after Undo.
class TaskEventConversionDataTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ItineraryApp
    private val repo get() = app.repository
    private val day = LocalDate.now().plusDays(5)

    @Test fun aTaskReplacedByItsEventAndUndone() = runBlocking {
        val task = PlannerTask(title = "QA convert task", dueDate = day).also { repo.saveTask(it) }
        val waiting = PlannerTask(title = "QA waits", dueDate = day, prerequisiteIds = listOf(task.id)).also { repo.saveTask(it) }
        val eventId = repo.saveItemId(ItineraryItem(tripId = 0, date = day, startTime = null, title = "QA convert task"),
            emptyList(), emptyList(), emptyList(), emptyList(), EventSaveOptions())
        repo.replaceTaskWithEvent(task.id, eventId)
        assertNull(repo.task(task.id))
        assertEquals(emptyList<String>(), repo.task(waiting.id)!!.prerequisiteIds)
        val bundle = repo.pendingDeletions.value.single { it.tasks.any { t -> t.id == task.id } }
        assertEquals(MadeInto(eventIds = listOf(eventId), freed = listOf(waiting.id), fromTaskId = task.id), bundle.madeInto)
        repo.undoDeletion(bundle.token)
        assertNotNull(repo.task(task.id))
        assertNull(repo.eventDetails(eventId))
        assertEquals(listOf(task.id), repo.task(waiting.id)!!.prerequisiteIds)
        repo.deleteTask(waiting.id); repo.deleteTask(task.id)
    }

    @Test fun aSeriesReplacedByOneTaskAndUndone() = runBlocking {
        repo.saveItemId(ItineraryItem(tripId = 0, date = day, startTime = null, title = "QA convert series", repeatRule = "WEEKLY"),
            emptyList(), emptyList(), emptyList(), emptyList(), EventSaveOptions(repeat = RepeatRule(RepeatRule.Kind.WEEKLY), count = 3))
        val first = repo.snapshot().items.filter { it.title == "QA convert series" }.minBy { it.date }
        val ids = repo.seriesIds(first.id)
        assertEquals(3, ids.size)
        val task = PlannerTask(title = "QA convert series", dueDate = day, repeat = "WEEKLY").also { repo.saveTask(it) }
        repo.replaceEventsWithTask(ids, task.id)
        assertTrue(repo.snapshot().items.none { it.title == "QA convert series" })
        val bundle = repo.pendingDeletions.value.single { it.madeInto?.taskId == task.id }
        assertEquals(3, bundle.items.size)
        repo.undoDeletion(bundle.token)
        assertEquals(3, repo.snapshot().items.count { it.title == "QA convert series" })
        assertNull(repo.task(task.id))
        repo.snapshot().items.filter { it.title == "QA convert series" }.forEach { repo.deleteWithUndo(it.id) }
    }
}
