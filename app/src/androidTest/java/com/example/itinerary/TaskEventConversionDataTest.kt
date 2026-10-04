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

    // TE-9: Undo also takes back what the new task was used for meanwhile: its next occurrence, and tasks set to wait on it.
    @Test fun undoAfterTheNewTaskWasUsed() = runBlocking {
        val eventId = repo.saveItemId(ItineraryItem(tripId = 0, date = day, startTime = null, title = "QA used event"),
            emptyList(), emptyList(), emptyList(), emptyList(), EventSaveOptions())
        val task = PlannerTask(title = "QA used event", dueDate = day, repeat = "WEEKLY").also { repo.saveTask(it) }
        repo.replaceEventsWithTask(setOf(eventId), task.id)
        val next = repo.saveTaskIf(task.id) { it.copy(done = true) }!!.nextTaskId!!
        val waiting = PlannerTask(title = "QA waits on new", dueDate = day, prerequisiteIds = listOf(next)).also { repo.saveTask(it) }
        repo.undoDeletion(repo.pendingDeletions.value.single { it.madeInto?.taskId == task.id }.token)
        assertNull(repo.task(task.id)); assertNull(repo.task(next))
        assertEquals(emptyList<String>(), repo.task(waiting.id)!!.prerequisiteIds)
        assertNotNull(repo.eventDetails(eventId))
        repo.deleteTask(waiting.id); repo.deleteWithUndo(eventId)
    }

    // TE-9: converting the new item onward drops the first conversion's Undo, which would bring back a duplicate.
    @Test fun convertingOnwardSettlesTheFirstUndo() = runBlocking {
        val eventId = repo.saveItemId(ItineraryItem(tripId = 0, date = day, startTime = null, title = "QA onward"),
            emptyList(), emptyList(), emptyList(), emptyList(), EventSaveOptions())
        val task = PlannerTask(title = "QA onward", dueDate = day).also { repo.saveTask(it) }
        repo.replaceEventsWithTask(setOf(eventId), task.id)
        val first = repo.pendingDeletions.value.single { it.madeInto?.taskId == task.id }
        val eventAgain = repo.saveItemId(ItineraryItem(tripId = 0, date = day, startTime = null, title = "QA onward"),
            emptyList(), emptyList(), emptyList(), emptyList(), EventSaveOptions())
        repo.replaceTaskWithEvent(task.id, eventAgain)
        assertTrue(repo.pendingDeletions.value.none { it.token == first.token })
        repo.undoDeletion(repo.pendingDeletions.value.single { it.madeInto?.fromTaskId == task.id }.token)
        assertNotNull(repo.task(task.id)); assertNull(repo.eventDetails(eventAgain)); assertNull(repo.eventDetails(eventId))
        repo.deleteTask(task.id)
    }

    // CV-3, CW-5: deleting the new item during the conversion's Undo drops that Undo, so the two Undos can't bring back
    // both; the delete's own Undo brings back the new item.
    @Test fun deletingTheNewItemSettlesTheConversion() = runBlocking {
        val task = PlannerTask(title = "QA delete made", dueDate = day).also { repo.saveTask(it) }
        val eventId = repo.saveItemId(ItineraryItem(tripId = 0, date = day, startTime = null, title = "QA delete made"),
            emptyList(), emptyList(), emptyList(), emptyList(), EventSaveOptions())
        repo.replaceTaskWithEvent(task.id, eventId)
        val conversion = repo.pendingDeletions.value.single { it.madeInto?.fromTaskId == task.id }
        repo.deleteWithUndo(eventId)
        assertTrue(repo.pendingDeletions.value.none { it.token == conversion.token })
        repo.undoDeletion(repo.pendingDeletions.value.single { it.items.any { e -> e.id == eventId } }.token)
        assertNotNull(repo.eventDetails(eventId)); assertNull(repo.task(task.id))
        repo.deleteWithUndo(eventId)
    }
}
