package com.example.itinerary.data

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Drafts live outside the database; incomplete checklist rows and blank titles are valid here. */
class TaskDraftStore(context: Context) {
    private val dir = File(context.filesDir, "task-drafts")
    private fun file(key: String) = AtomicFile(File(dir, UUID.nameUUIDFromBytes(key.toByteArray()).toString() + ".json"))
    // A draft still waiting for the writer (B1) is the current one.
    fun read(key: String): JSONObject? = (writer.pending(key) as String?)?.let(::JSONObject) ?: synchronized(lock) {
        file(key).readTextOrNull()?.let(::JSONObject)
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
        file(key).writeText(encoded)
    }
    // The files drafts hold, waiting ones included, which the unused-file clean-up must leave alone.
    fun files(): Set<String> {
        // Waiting ones first (E5-2): one written meanwhile leaves the waiting list only once it is in the folder.
        val waiting = writer.pendingValues().map { it as String }
        val stored = synchronized(lock) {
            dir.listFiles().orEmpty().filter { it.extension == "json" }.mapNotNull { runCatching { it.readText() }.getOrNull() }
        }
        return (waiting + stored).flatMap { text ->
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
        private val open = EditorCounter()
        val openEditors: StateFlow<Int> = open.open
        fun editorOpened() = open.opened()
        fun editorClosed() = open.closed()
    }
}
