package com.example.itinerary.data

import org.json.JSONArray
import org.json.JSONObject

object StringListCodec {
    fun decode(array: JSONArray): List<String> = List(array.length()) { array.getString(it) }
}

object TaskDependencies {
    /** Missing prerequisites remain blocked, so deleting one cannot silently unlock work. */
    fun blockers(task: PlannerTask, tasks: List<PlannerTask>): List<String> {
        val byId = tasks.associateBy { it.id }
        return task.prerequisiteIds.filter { byId[it]?.done != true }
    }
    /** The ids among [shown] that can't be completed yet ([blockers] in [all]): the widget offers no Complete for them. */
    fun blockedIds(shown: List<PlannerTask>, all: List<PlannerTask>): Set<String> =
        shown.filter { it.prerequisiteIds.isNotEmpty() && blockers(it, all).isNotEmpty() }.mapTo(HashSet()) { it.id }
    fun validateGraph(tasks: List<PlannerTask>) {
        val byId = tasks.associateBy { it.id }
        val visited = HashSet<String>()
        val active = HashSet<String>()
        // Iterative DFS also handles large imported task graphs without overflowing the stack.
        tasks.forEach { task ->
            if (task.id !in visited) {
                val stack = java.util.ArrayDeque<Pair<String, Boolean>>()
                stack.push(task.id to false)
                while (stack.isNotEmpty()) {
                    val (id, leaving) = stack.pop()
                    if (leaving) { active.remove(id); visited.add(id); continue }
                    require(id !in active) { "Tasks cannot depend on themselves or form a dependency loop." }
                    if (id in visited) continue
                    active.add(id); stack.push(id to true)
                    byId[id]?.prerequisiteIds?.forEach { stack.push(it to false) }
                }
            }
        }
    }
}

data class SavedSearch(val name: String, val query: String, val categories: Set<String>, val showCompleted: Boolean)
object SavedSearchCodec {
    fun encode(values: List<SavedSearch>): JSONArray = JSONArray().apply {
        values.forEach { put(JSONObject().put("name", it.name).put("query", it.query)
            .put("categories", JSONArray(it.categories.sorted())).put("showCompleted", it.showCompleted)) }
    }
    fun decode(array: JSONArray): List<SavedSearch> {
        require(array.length() <= 50) { "Keep up to 50 saved searches." }
        return List(array.length()) { i ->
            val json = array.getJSONObject(i)
            SavedSearch(json.getString("name"), json.getString("query"),
                StringListCodec.decode(json.getJSONArray("categories")).toSet(), json.optBoolean("showCompleted"))
                .also { require(it.name.isNotBlank() && it.name.length <= 80 && it.query.length <= 2000 && it.categories.size <= 100) }
        }.also { require(it.map { it.name.lowercase(java.util.Locale.ROOT) }.distinct().size == it.size) }
    }
}

data class SharedDraft(val title: String, val notes: String)
object SharedText {
    fun draft(text: String, subject: String? = null): SharedDraft {
        val body = text.trim()
        require(body.isNotBlank() && body.length <= 20_000) { "Share between 1 and 20,000 characters." }
        val title = subject?.trim()?.takeIf { it.isNotBlank() } ?: body.lineSequence().first()
        return SharedDraft(title.replace('\n', ' ').take(500), body)
    }
}
