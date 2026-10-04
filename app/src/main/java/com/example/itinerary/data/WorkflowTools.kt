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

// [body]: the words to read dates and amounts from: the message itself, without an email's headers (its sent date among
// them), a quoted earlier message or a signature.
data class SharedDraft(val title: String, val notes: String, val body: String = notes)
object SharedText {
    private const val MAX_LENGTH = 20_000
    private const val MAX_TITLE = 500
    fun draft(text: String, subject: String? = null): SharedDraft {
        val body = text.trim()
        require(body.isNotBlank() && body.length <= MAX_LENGTH) { "Share between 1 and 20,000 characters." }
        // An email shared with its headers on top (Thunderbird): its subject is the title, and of the headers only who
        // sent it stays, above the message.
        val email = EmailHeaders.read(body)
        // Dates are read from the message alone, not a quoted earlier one or a signature (SH-4).
        val message = EmailHeaders.message(email?.body ?: body)
        val title = subject?.trim()?.takeIf { it.isNotBlank() } ?: email?.subject
            ?: email?.let { e -> e.body.lineSequence().firstOrNull { it.isNotBlank() } ?: e.from }
            ?: body.lineSequence().first()
        val notes = email?.let { listOf(it.from, it.body).filter(String::isNotBlank).joinToString("\n\n") } ?: body
        return SharedDraft(title.trim().replace('\n', ' ').take(MAX_TITLE), notes, message)
    }

    // What Planner keeps of a share while it's up, saved state included (Q-5: a share of a few hundred thousand
    // characters made the saved state too large and crashed Planner on leaving it). Only what [draft] can use: a text
    // too long to use keeps a stand-in one character too long (ending in its last non-space character, so trimming can't
    // bring it under), which [draft] refuses the same way.
    fun kept(text: String): String = text.trim().let { if (it.length <= MAX_LENGTH) it else it.take(MAX_LENGTH) + it.last() }
    fun keptSubject(subject: String?): String? = subject?.trim()?.take(MAX_TITLE)
}
