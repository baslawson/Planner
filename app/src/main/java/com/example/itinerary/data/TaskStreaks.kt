package com.example.itinerary.data

/**
 * Streaks for repeating tasks (user, 15 Oct): how many occurrences in a row were done on time, shown as "🔥 5 in a row".
 * Each completed occurrence keeps a link to the one it made (nextTaskId), so the run is counted back along those links,
 * from the task itself when it is done, else from the occurrence before it.
 *
 * On time (bug hunt 29, user chose "a"): done on or before its due day, by the day it was ticked ([PlannerTask.doneOn]).
 * So moving the open occurrence changes nothing already earned, and every kind of repeat is judged alike. An occurrence
 * done before Planner kept that day, or with no due date, counts as on time.
 *
 * Worked out once per chain (each task's run is the one before it plus one), so a long history stays cheap.
 */
object TaskStreaks {
    // Runs shorter than this aren't shown.
    const val SHOWN_FROM = 2

    /** The streak of each repeating task that has one of at least [SHOWN_FROM], by task id. */
    fun streaks(tasks: List<PlannerTask>): Map<String, Int> {
        if (tasks.none { it.repeat != "NONE" }) return emptyMap()
        val before = HashMap<String, PlannerTask>()
        tasks.forEach { task -> task.nextTaskId?.let { before[it] = task } }
        // The run ending at each done task, filled in oldest first along each chain.
        val runs = HashMap<String, Int>()
        fun run(task: PlannerTask): Int {
            runs[task.id]?.let { return it }
            // Back to the first occurrence not yet counted (or not done, or done late), then forward again.
            val chain = ArrayList<PlannerTask>()
            val seen = HashSet<String>()
            var cursor: PlannerTask? = task
            while (cursor != null && cursor.id !in runs && chain.size < MAX_STEPS) {
                chain += cursor; seen += cursor.id
                cursor = before[cursor.id]?.takeIf { it.id !in seen }
            }
            var count = cursor?.let { runs[it.id] } ?: 0
            for (t in chain.asReversed()) {
                count = if (t.done && onTime(t)) count + 1 else 0
                runs[t.id] = count
            }
            return runs.getValue(task.id)
        }
        return tasks.filter { it.repeat != "NONE" }.mapNotNull { task ->
            val count = if (task.done) run(task) else before[task.id]?.let(::run) ?: 0
            if (count >= SHOWN_FROM) task.id to count else null
        }.toMap()
    }

    private const val MAX_STEPS = 100_000

    // Done by its due day (or with no due date, or done before Planner kept the day).
    internal fun onTime(task: PlannerTask): Boolean {
        val doneOn = task.doneOn ?: return true
        val due = task.dueDate ?: return true
        return doneOn <= due
    }

    /** "🔥 5 in a row" (TalkBack: "Done 5 times in a row"). */
    fun label(count: Int): String = "🔥 $count in a row"
    fun spoken(count: Int): String = "Done $count times in a row"
}
