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
    // A draft still waiting for the writer (B1) is the current one.
    fun read(key: String): JSONObject? = (writer.pending(key) as String?)?.let(::JSONObject) ?: synchronized(lock) {
        val file = file(key)
        if (!file.baseFile.exists()) null else JSONObject(file.openRead().bufferedReader().use { it.readText() })
    }
    /** Written now, on this thread. */
    fun write(key: String, json: JSONObject) = writer.now(key) { writeFile(key, json.toString()) }
    /** [encoded] written off the main thread shortly after typing pauses ([flush] for at once); only the newest is. */
    fun schedule(key: String, encoded: String, onFailure: (Exception) -> Unit) =
        writer.schedule(key, encoded, onFailure) { writeFile(key, encoded) }
    fun flush() = writer.flush()
    /** Also drops a draft still waiting to be written, so none lands after this. */
    fun clear(key: String) { writer.now(key) { synchronized(lock) { file(key).delete() } } }
    private fun writeFile(key: String, encoded: String) = synchronized(lock) {
        check(dir.exists() || dir.mkdirs())
        val file = file(key)
        val stream = file.startWrite()
        try { stream.write(encoded.toByteArray()); file.finishWrite(stream) }
        catch (e: Throwable) { file.failWrite(stream); throw e }
    }
    // The files drafts hold, waiting ones included, which the unused-file clean-up must leave alone.
    fun files(): Set<String> {
        val stored = synchronized(lock) {
            dir.listFiles().orEmpty().filter { it.extension == "json" }.mapNotNull { runCatching { it.readText() }.getOrNull() }
        }
        return (stored + writer.pendingValues().map { it as String }).flatMap { text ->
            runCatching {
                val json = JSONObject(text)
                DraftCodec.attachments(json.optJSONArray("attachments")).map { it.fileName } +
                    listOfNotNull(json.optString("pendingPhoto").takeIf { it.isNotBlank() })
            }.getOrDefault(emptyList())
        }.toSet()
    }
    companion object {
        private val lock = Any()
        // One for the process: each editor and the file clean-up make their own store.
        private val writer = DraftWriter()

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
