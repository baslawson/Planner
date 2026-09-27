package com.example.itinerary.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime

/** An interpretation is a draft baseline, never permission to save. */
data class QuickAiEntry(
    val source: String, val task: Boolean, val title: String, val date: String?, val time: String?,
    val duration: Int?, val location: String, val reminder: Int?, val repeat: RepeatRule, val count: Int?,
) {
    fun suggestion(base: LocalDate) = QuickEntrySuggestion(title, date?.let(LocalDate::parse) ?: base,
        time?.let(LocalTime::parse), dateSpecified = date != null, durationMinutes = duration,
        location = location, reminderMinutes = reminder, repeat = repeat, repeatCount = count ?: 12,
        repeatCountSpecified = count != null)
    fun json() = JSONObject().put("source", source).put("kind", if (task) "task" else "event").put("title", title)
        .put("date", date ?: JSONObject.NULL).put("time", time ?: JSONObject.NULL).put("duration", duration ?: JSONObject.NULL)
        .put("location", location).put("reminder", reminder ?: JSONObject.NULL).put("repeat", repeat.name).put("count", count ?: JSONObject.NULL)
    companion object {
        fun decodeDraft(j: JSONObject): QuickAiEntry {
            val title = j.getString("title")
            val copy = JSONObject(j.toString()).put("title", title.ifBlank { "Untitled" })
            return decode(copy).copy(title = title)
        }
        fun decode(j: JSONObject): QuickAiEntry {
            require(j.keys().asSequence().toSet() == setOf("source", "kind", "title", "date", "time", "duration", "location", "reminder", "repeat", "count"))
            fun string(k: String): String = (j.get(k) as? String) ?: error("Invalid text")
            fun nullable(k: String): String? = if (j.isNull(k)) null else string(k)
            fun number(k: String, range: IntRange): Int? {
                if (j.isNull(k)) return null
                val n = j.get(k); require(n is Int && n in range); return n
            }
            val source = string("source"); val title = string("title"); val location = string("location")
            require(source.isNotBlank() && source.length <= 500 && title.isNotBlank() && title.length <= 500 && location.length <= 500)
            require(listOf(source, title, location).none { s -> s.any { it.isISOControl() && it != '\n' && it != '\t' } })
            val kind = string("kind"); require(kind in listOf("event", "task"))
            val date = nullable("date")?.also { require(Regex("\\d{4}-\\d{2}-\\d{2}").matches(it)); require(LocalDate.parse(it).year in 1..9999) }
            val time = nullable("time")?.also { require(Regex("\\d{2}:\\d{2}").matches(it)); LocalTime.parse(it) }
            val duration = number("duration", 1..1440); val reminder = number("reminder", 0..525600); val count = number("count", 2..365)
            val repeat = RepeatRule.valueOf(string("repeat"))
            require(repeat.name in setOf("NONE", "DAILY", "WEEKLY", "FORTNIGHTLY", "MONTHLY", "YEARLY"))
            require(kind != "event" || date != null)
            require(kind != "task" || time == null && duration == null && count == null)
            require(duration == null || time != null); require(reminder == null || date != null)
            require(repeat != RepeatRule.NONE || count == null)
            return QuickAiEntry(source, kind == "task", title, date, time, duration, location, reminder, repeat, count)
        }
    }
}

data class QuickAiResult(val status: String, val message: String, val entries: List<QuickAiEntry>) {
    companion object {
        fun decode(j: JSONObject, text: String, task: Boolean): QuickAiResult {
            require(j.keys().asSequence().toSet() == setOf("status", "message", "entries"))
            val status = j.get("status") as String; val message = j.get("message") as String
            require(status in setOf("ready", "clarify", "unsupported") && message.length <= 1000)
            val a = j.getJSONArray("entries"); require(a.length() <= 1)
            val entries = List(a.length()) { QuickAiEntry.decode(a.getJSONObject(it)) }
            require(entries.all { text.contains(it.source) && it.task == task })
            require(if (status == "ready") entries.isNotEmpty() && message.isEmpty() else entries.isEmpty() && message.isNotBlank())
            return QuickAiResult(status, message, entries)
        }
    }
}

/** Explicit field corrections win over a new AI baseline. */
fun QuickInput.withAi(entry: QuickAiEntry) = copy(ai = entry, literals = emptyList())
