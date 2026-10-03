package com.example.itinerary.ui

import com.example.itinerary.data.PlannerTask
import com.example.itinerary.data.TaskDependencies
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

// UI-1: the counts the screens hand their task cards match what each card used to work out for itself.
class TaskBlockerCountsTest {
    @Test fun matchesBlockersPerTaskOnRandomGraphs() {
        val random = Random(20261003)
        repeat(200) {
            val ids = List(random.nextInt(0, 30)) { "t$it" }
            // Some prerequisites point at tasks not in the list (deleted ones), which still block.
            val tasks = ids.map { id ->
                PlannerTask(id = id, title = id, done = random.nextInt(3) == 0,
                    prerequisiteIds = List(random.nextInt(0, 4)) { if (random.nextInt(5) == 0) "gone$it" else "t${random.nextInt(0, 30)}" })
            }
            val counts = taskBlockerCounts(tasks)
            tasks.forEach { task -> assertEquals(TaskDependencies.blockers(task, tasks).size, counts[task.id] ?: 0) }
            assertEquals(tasks.count { TaskDependencies.blockers(it, tasks).isNotEmpty() }, counts.size)
        }
    }
}
