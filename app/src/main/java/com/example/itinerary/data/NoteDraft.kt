package com.example.itinerary.data

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.File

/**
 * The open note editor's unsaved state, kept on disk so it survives Android closing Planner: the note as being edited
 * ([note]), whether it is new, the version it started from ([base], for "changed elsewhere"), and a photo being taken.
 * One note editor is open at a time, so there is one draft; the Notes page reopens it. Its files count as in use.
 */
class NoteDraftStore(context: Context) {
    data class Draft(val note: PlannerNote, val creating: Boolean, val base: PlannerNote?, val pendingPhoto: String?)

    private val file = AtomicFile(File(context.filesDir, "note-draft.json"))

    // A draft still waiting for the writer is the current one (it leaves the waiting list only once it is on disk).
    fun read(): Draft? {
        val text = (writer.pending(KEY) as String?) ?: synchronized(lock) { file.readTextOrNull() } ?: return null
        return runCatching {
            val json = JSONObject(text)
            fun note(name: String) = json.optJSONArray(name)?.let { NoteCodec.decodeLenient(it).firstOrNull() }
            Draft(note("note") ?: return null, json.optBoolean("creating"), note("base"),
                json.optString("pendingPhoto").takeIf { it.isNotBlank() })
        }.getOrNull()
    }

    /** Written now, on this thread. */
    fun write(draft: Draft) { val encoded = encode(draft); writer.now(KEY) { writeFile(encoded) } }
    /** E5-5: written off the main thread, in order with [clear], so a write already on its way can't land after a
     *  Discard or a Save and close; [flush] writes it at once. A failed write calls [onFailure] on the writer's thread. */
    fun schedule(draft: Draft, onFailure: (Exception) -> Unit) {
        val encoded = encode(draft)
        writer.schedule(KEY, encoded, onFailure) { writeFile(encoded) }
    }
    fun flush() = writer.flush()
    /** Also drops a draft still waiting to be written, so none lands after this. */
    fun clear() { writer.now(KEY) { synchronized(lock) { file.delete() } } }

    private fun encode(draft: Draft): String = JSONObject().put("note", NoteCodec.encode(listOf(draft.note))).put("creating", draft.creating)
        .put("base", draft.base?.let { NoteCodec.encode(listOf(it)) } ?: JSONObject.NULL).put("pendingPhoto", draft.pendingPhoto.orEmpty()).toString()
    private fun writeFile(encoded: String) = synchronized(lock) { file.writeText(encoded) }

    // Files the draft holds, which the unused-file clean-up must leave alone.
    fun files(): Set<String> = read()?.let { d -> (d.note.attachments.map { it.fileName } + listOfNotNull(d.pendingPhoto)).toSet() }.orEmpty()

    companion object {
        private const val KEY = "note"
        private val lock = Any()
        // One for the process, as for event and task drafts. No pause of its own: the editor waits for typing to stop.
        private val writer = DraftWriter(delayMs = 0)
        // How many note editors are on screen: a widget day tap waits for them, as for event and task editors.
        private val open = EditorCounter()
        val openEditors: StateFlow<Int> = open.open
        fun editorOpened() = open.opened()
        fun editorClosed() = open.closed()
    }
}
