package com.example.itinerary.data

/**
 * The Habits page (user, 15 Oct): every repeating task still to do, with its streak (TaskStreaks), how many of its recent
 * occurrences were done on time, and the last few as marks: on time, late, or the one still to do. Worked out from the
 * links between occurrences (nextTaskId) and the day each was done (PlannerTask.doneOn); nothing is stored.
 */
object Habits {
    enum class Mark { ON_TIME, LATE, TO_DO }

    /** [recent]: the last [SHOWN] occurrences, oldest first, ending with the one to do; [onTime] of [done] done ones in it. */
    data class Habit(val task: PlannerTask, val streak: Int, val onTime: Int, val done: Int, val recent: List<Mark>)

    const val SHOWN = 10
    private const val MAX_BACK = 1000

    fun of(tasks: List<PlannerTask>): List<Habit> {
        val before = HashMap<String, PlannerTask>()
        tasks.forEach { t -> t.nextTaskId?.let { before[it] = t } }
        val streaks = TaskStreaks.streaks(tasks)
        val ids = tasks.mapTo(HashSet()) { it.id }
        // Only the latest of each chain: one ticked off and unticked again still has its next one (bug hunt 30).
        return tasks.filter { it.repeat != "NONE" && !it.done && (it.nextTaskId == null || it.nextTaskId !in ids) }.map { task ->
            val chain = ArrayList<PlannerTask>()
            val seen = HashSet<String>().apply { add(task.id) }
            var cursor = before[task.id]
            while (cursor != null && chain.size < SHOWN - 1 && seen.add(cursor.id) && seen.size < MAX_BACK) { chain += cursor; cursor = before[cursor.id] }
            val past = chain.asReversed().map { if (!it.done) Mark.TO_DO else if (TaskStreaks.onTime(it)) Mark.ON_TIME else Mark.LATE }
            val done = past.count { it != Mark.TO_DO }
            Habit(task, streaks[task.id] ?: 0, past.count { it == Mark.ON_TIME }, done, past + Mark.TO_DO)
        }.sortedWith(compareByDescending<Habit> { it.streak }.thenByDescending { it.onTime }.thenBy { it.task.title.lowercase() })
    }

    /** What TalkBack says for [habit]'s marks: "Last 10: 8 on time, 1 late, 1 to do". */
    fun spoken(habit: Habit): String {
        val late = habit.recent.count { it == Mark.LATE }
        val toDo = habit.recent.count { it == Mark.TO_DO }
        return "Last ${habit.recent.size}: ${habit.onTime} on time" + (if (late > 0) ", $late late" else "") + (if (toDo > 0) ", $toDo to do" else "")
    }
}
