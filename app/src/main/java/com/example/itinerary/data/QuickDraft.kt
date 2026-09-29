package com.example.itinerary.data

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

/** Small private draft; never shares an editor draft or changes the database schema. */
data class QuickDraft(val single: QuickInput = QuickInput())

class QuickDraftStore(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "quick-entry-draft.json"))
    // Drafts from versions with Paste multiple entries also held a list; only the single entry is kept.
    fun read(): QuickDraft? = synchronized(lock) {
        if (!file.baseFile.exists()) null
        else QuickDraft(decode(JSONObject(file.openRead().bufferedReader().use { it.readText() }).getJSONObject("single")))
    }
    fun write(draft: QuickDraft) = synchronized(lock) {
        // The list fields stay, empty, so an older app version can still open this draft.
        val j = JSONObject().put("multiple", false).put("text", draft.single.entryText).put("single", encode(draft.single))
            .put("reviewing", false).put("rows", JSONArray())
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
            .put("count", i.countText ?: JSONObject.NULL).put("removeReminder", i.removeReminder).put("duration", i.durationText ?: JSONObject.NULL).put("title", i.title).put("typeChosen", i.typeChosen)
        private fun decode(j: JSONObject): QuickInput {
            val a = j.getJSONArray("literals")
            fun nullable(key: String) = if (j.isNull(key)) null else j.getString(key)
            return QuickInput(j.getString("text"), j.getBoolean("task"), List(a.length() / 2) { a.getInt(it * 2) until a.getInt(it * 2 + 1) },
                nullable("date"), nullable("time"), nullable("count"), j.getBoolean("removeReminder"), LocalDate.parse(j.getString("baseDate")), nullable("duration"), if (j.isNull("ai")) null else QuickAiEntry.decodeDraft(j.getJSONObject("ai")),
                // Drafts saved before the Title box have no title.
                j.optString("title", ""), j.optBoolean("typeChosen", false))
        }
    }
}
