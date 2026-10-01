package com.example.itinerary.data

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
    companion object {
        private val lock = Any()

        // U4: which editor has each existing task open, in any Planner window. The first one owns it; a second editor
        // on the same task would share its draft, and its Discard draft would delete the first one's new files.
        private val owners = HashMap<String, Any>()
        fun claim(taskId: String, editor: Any): Boolean = synchronized(owners) { owners.getOrPut(taskId) { editor } === editor }
        fun release(taskId: String, editor: Any) { synchronized(owners) { if (owners[taskId] === editor) owners.remove(taskId) } }

        // U5: how many task editors are on screen now, new ones included. A widget day tap waits for them as it does
        // for event editors (D10): moving to the day would drop one without "Save changes?".
        private val open = MutableStateFlow(0)
        val openEditors: StateFlow<Int> = open.asStateFlow()
        fun editorOpened() = open.update { it + 1 }
        fun editorClosed() = open.update { (it - 1).coerceAtLeast(0) }
    }
}
