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

    fun read(): Draft? = synchronized(lock) {
        runCatching {
            val json = JSONObject(file.readTextOrNull() ?: return null)
            fun note(name: String) = json.optJSONArray(name)?.let { NoteCodec.decodeLenient(it).firstOrNull() }
            Draft(note("note") ?: return null, json.optBoolean("creating"), note("base"),
                json.optString("pendingPhoto").takeIf { it.isNotBlank() })
        }.getOrNull()
    }

    fun write(draft: Draft) = synchronized(lock) {
        val json = JSONObject().put("note", NoteCodec.encode(listOf(draft.note))).put("creating", draft.creating)
            .put("base", draft.base?.let { NoteCodec.encode(listOf(it)) } ?: JSONObject.NULL).put("pendingPhoto", draft.pendingPhoto.orEmpty())
        file.writeText(json.toString())
    }

    fun clear() = synchronized(lock) { file.delete() }

    // Files the draft holds, which the unused-file clean-up must leave alone.
    fun files(): Set<String> = read()?.let { d -> (d.note.attachments.map { it.fileName } + listOfNotNull(d.pendingPhoto)).toSet() }.orEmpty()

    companion object {
        private val lock = Any()
        // How many note editors are on screen: a widget day tap waits for them, as for event and task editors.
        private val open = EditorCounter()
        val openEditors: StateFlow<Int> = open.open
        fun editorOpened() = open.opened()
        fun editorClosed() = open.closed()
    }
}
