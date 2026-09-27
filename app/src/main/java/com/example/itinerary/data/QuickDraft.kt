package com.example.itinerary.data

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

/** Small private draft; never shares an editor draft or changes the database schema. */
data class QuickDraft(
    val multiple: Boolean = false, val text: String = "", val single: QuickInput = QuickInput(),
    val reviewing: Boolean = false, val rows: List<QuickRow> = emptyList(),
)

class QuickDraftStore(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "quick-entry-draft.json"))
    fun read(): QuickDraft? = synchronized(lock) {
        if (!file.baseFile.exists()) null else {
            val j = JSONObject(file.openRead().bufferedReader().use { it.readText() })
            val rows = j.getJSONArray("rows")
            QuickDraft(j.getBoolean("multiple"), j.getString("text"), decode(j.getJSONObject("single")),
                j.getBoolean("reviewing"), List(rows.length()) { n ->
                    val r = rows.getJSONObject(n)
                    QuickRow(r.getString("id"), r.getString("source"), decode(r.getJSONObject("input")),
                        r.getBoolean("typeChosen"), r.getBoolean("selected"), r.getString("status"))
                })
        }
    }
    fun write(draft: QuickDraft) = synchronized(lock) {
        val j = JSONObject().put("multiple", draft.multiple).put("text", draft.text).put("single", encode(draft.single))
            .put("reviewing", draft.reviewing).put("rows", JSONArray().apply {
                draft.rows.forEach { r -> put(JSONObject().put("id", r.id).put("source", r.source).put("input", encode(r.input))
                    .put("typeChosen", r.typeChosen).put("selected", r.selected).put("status", r.status)) }
            })
        val stream = file.startWrite()
        try { stream.write(j.toString().toByteArray()); file.finishWrite(stream) }
        catch (e: Throwable) { file.failWrite(stream); throw e }
    }
    fun clear() = synchronized(lock) { file.delete() }
    companion object {
        private val lock = Any()
        private fun encode(i: QuickInput) = JSONObject().put("text", i.text).put("task", i.task)
            .put("baseDate", i.baseDate.toString()).put("literals", JSONArray(i.literals.flatMap { listOf(it.first, it.last + 1) }))
            .put("date", i.dateOverride ?: JSONObject.NULL).put("time", i.timeOverride ?: JSONObject.NULL)
            .put("ai", i.ai?.json() ?: JSONObject.NULL)
            .put("count", i.countText ?: JSONObject.NULL).put("removeReminder", i.removeReminder).put("duration", i.durationText ?: JSONObject.NULL)
        private fun decode(j: JSONObject): QuickInput {
            val a = j.getJSONArray("literals")
            fun nullable(key: String) = if (j.isNull(key)) null else j.getString(key)
            return QuickInput(j.getString("text"), j.getBoolean("task"), List(a.length() / 2) { a.getInt(it * 2) until a.getInt(it * 2 + 1) },
                nullable("date"), nullable("time"), nullable("count"), j.getBoolean("removeReminder"), LocalDate.parse(j.getString("baseDate")), nullable("duration"), if (j.isNull("ai")) null else QuickAiEntry.decodeDraft(j.getJSONObject("ai")))
        }
    }
}
