package com.example.itinerary.data

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Drafts live outside the database; incomplete checklist rows and blank titles are valid here. */
class TaskDraftStore(context: Context) {
    private val dir = File(context.filesDir, "task-drafts")
    private fun file(key: String) = AtomicFile(File(dir, UUID.nameUUIDFromBytes(key.toByteArray()).toString() + ".json"))
    fun read(key: String): JSONObject? = synchronized(lock) {
        val file = file(key)
        if (!file.baseFile.exists()) null else JSONObject(file.openRead().bufferedReader().use { it.readText() })
    }
    fun write(key: String, json: JSONObject) = synchronized(lock) {
        check(dir.exists() || dir.mkdirs())
        val file = file(key)
        val stream = file.startWrite()
        try { stream.write(json.toString().toByteArray()); file.finishWrite(stream) }
        catch (e: Throwable) { file.failWrite(stream); throw e }
    }
    fun clear(key: String) = synchronized(lock) { file(key).delete() }
    fun files(): Set<String> = synchronized(lock) {
        dir.listFiles().orEmpty().filter { it.extension == "json" }.flatMap { file ->
            runCatching {
                val json = JSONObject(file.readText())
                DraftCodec.attachments(json.optJSONArray("attachments")).map { it.fileName } +
                    listOfNotNull(json.optString("pendingPhoto").takeIf { it.isNotBlank() })
            }.getOrDefault(emptyList())
        }.toSet()
    }
    companion object { private val lock = Any() }
}
