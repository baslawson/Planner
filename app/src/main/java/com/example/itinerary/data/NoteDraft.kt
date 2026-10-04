package com.example.itinerary.data

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * A note editor's unsaved state, kept on disk so it survives Android closing Planner: the note as being edited
 * ([Draft.note]), whether it is new, the version it started from ([Draft.base], for "changed elsewhere"), and a photo
 * being taken. N6-1: one draft per note, as for tasks. Each Planner window (a share opened in the mail app's task, a
 * note reminder tapped with App lock on) can have a note editor of its own, so one editor never clears or reopens
 * another's draft; the Notes page reopens a draft only while no editor has its note open ([isOpen]). Their files count
 * as in use. NW-5: [Draft.owner] is the Notes page whose editor wrote it, so after Android closed Planner each window
 * reopens its own, and another window's is only offered.
 */
class NoteDraftStore(context: Context) {
    data class Draft(val note: PlannerNote, val creating: Boolean, val base: PlannerNote?, val pendingPhoto: String?,
                     val owner: String? = null)

    private val dir = File(context.filesDir, "note-drafts")
    private fun file(noteId: String) = AtomicFile(File(dir, UUID.nameUUIDFromBytes(noteId.toByteArray()).toString() + ".json"))
    // Before N6-1 one file held the one draft. One left from then is still read (behind a newer draft of its note) and
    // goes when its note's draft is cleared, so an update loses nothing.
    private val single = AtomicFile(File(context.filesDir, "note-draft.json"))
    private fun singleDraft(): Draft? = readOrDrop(single)

    // NW-6: a file that is there but can't be read as a draft (cut short, or from a newer version) would be read again on
    // every check and never cleared (its note id is unknown), so it goes. One that can't be opened just now stays.
    private fun readOrDrop(file: AtomicFile): Draft? {
        val text = runCatching { file.readTextOrNull() }.getOrNull() ?: return null
        return decode(text) ?: run { runCatching { file.delete() }; null }
    }

    // A draft still waiting for the writer is the current one (it leaves the waiting list only once it is on disk).
    fun read(noteId: String): Draft? {
        (writer.pending(noteId) as String?)?.let { return decode(it) }
        return synchronized(lock) { readOrDrop(file(noteId)) ?: singleDraft()?.takeIf { it.note.id == noteId } }
    }

    /** Every note's draft, the newest first. */
    fun readAll(): List<Draft> {
        // Waiting ones first (E5-2): one written meanwhile leaves the waiting list only once it is in the folder.
        val waiting = writer.pendingValues().mapNotNull { decode(it as String) }
        val stored = synchronized(lock) {
            dir.listFiles().orEmpty().filter { it.extension == "json" }.sortedByDescending { it.lastModified() }
                .mapNotNull { readOrDrop(AtomicFile(it)) } + listOfNotNull(singleDraft())
        }
        return (waiting + stored).distinctBy { it.note.id }
    }

    /** The draft the Notes page [page] reopens (see [recoverableNoteDraft]); with no page, any not open in an editor.
     *  [pageRestored]: the page's window came back from saved state (see [anotherWindowsDraft]). */
    fun recoverable(editingId: String?, page: String? = null, pageRestored: Boolean = false): Draft? =
        recoverableNoteDraft(editingId, page, ::isOpen, ::read, ::readAll) { anotherWindows(it, page, pageRestored) }
    /** NW-5: another window's draft, which the Notes page [page] offers rather than opens (see [offeredNoteDraft]). */
    fun offered(page: String, pageRestored: Boolean = false): Draft? =
        offeredNoteDraft(page, ::isOpen, readAll()) { anotherWindows(it, page, pageRestored) }

    /** Written now, on this thread. */
    fun write(draft: Draft) { val id = draft.note.id; val encoded = encode(draft); writer.now(id) { writeFile(id, encoded) } }
    /** E5-5: written off the main thread, in order with [clear], so a write already on its way can't land after a
     *  Discard or a Save and close; [flush] writes it at once. A failed write calls [onFailure] on the writer's thread. */
    fun schedule(draft: Draft, onFailure: (Exception) -> Unit) {
        val id = draft.note.id
        val encoded = encode(draft)
        writer.schedule(id, encoded, onFailure) { writeFile(id, encoded) }
    }
    fun flush() = writer.flush()
    /** [noteId]'s draft only. Also drops one still waiting to be written, so none lands after this. */
    fun clear(noteId: String) {
        writer.now(noteId) { synchronized(lock) { file(noteId).delete(); if (singleDraft()?.note?.id == noteId) single.delete() } }
    }
    /** Every note's draft (tests, starting afresh). */
    fun clearAll() {
        writer.pendingValues().mapNotNull { decode(it as String)?.note?.id }.forEach(::clear)
        synchronized(lock) { dir.listFiles().orEmpty().forEach { it.delete() }; single.delete() }
    }

    private fun decode(text: String): Draft? = runCatching {
        val json = JSONObject(text)
        fun note(name: String) = json.optJSONArray(name)?.let { NoteCodec.decodeLenient(it).firstOrNull() }
        Draft(note("note") ?: return null, json.optBoolean("creating"), note("base"),
            json.optString("pendingPhoto").takeIf { it.isNotBlank() }, json.optString("owner").takeIf { it.isNotBlank() })
    }.getOrNull()
    private fun encode(draft: Draft): String = JSONObject().put("note", NoteCodec.encode(listOf(draft.note))).put("creating", draft.creating)
        .put("base", draft.base?.let { NoteCodec.encode(listOf(it)) } ?: JSONObject.NULL).put("pendingPhoto", draft.pendingPhoto.orEmpty())
        .put("owner", draft.owner.orEmpty()).toString()
    private fun writeFile(noteId: String, encoded: String) = synchronized(lock) {
        check(dir.exists() || dir.mkdirs())
        file(noteId).writeText(encoded)
    }

    // Files the drafts hold, which the unused-file clean-up must leave alone.
    fun files(): Set<String> = readAll().flatMap { d -> d.note.attachments.map { it.fileName } + listOfNotNull(d.pendingPhoto) }.toSet()

    companion object {
        private val lock = Any()
        // One for the process, as for event and task drafts, keyed by note id. No pause of its own: the editor waits for
        // typing to stop.
        private val writer = DraftWriter(delayMs = 0)
        // How many note editors are on screen in all windows (each window counts its own in WindowEditors).
        private val open = EditorCounter()
        val openEditors: StateFlow<Int> = open.open
        fun editorOpened() = open.opened()
        fun editorClosed() = open.closed()

        // N6-1: which editor has each note open, in any Planner window. The first one owns it: a second editor on the same
        // note would share its draft (and its Discard would clear the first one's), and the Notes page leaves its draft be.
        private val owners = HashMap<String, Any>()
        fun claim(noteId: String, editor: Any): Boolean = synchronized(owners) { owners.getOrPut(noteId) { editor } === editor }
        fun release(noteId: String, editor: Any) { synchronized(owners) { if (owners[noteId] === editor) owners.remove(noteId) } }
        fun isOpen(noteId: String): Boolean = synchronized(owners) { noteId in owners }

        // NT-2: the Planner windows (AppNav ids) this process has shown: those on screen now, and every one so far. A
        // draft of a window that went while Planner ran (its task swiped away) is no other window's any more.
        private val liveWindows = HashSet<String>()
        private val seenWindows = HashSet<String>()
        fun windowOpened(id: String) { synchronized(liveWindows) { liveWindows += id; seenWindows += id } }
        fun windowClosed(id: String) { synchronized(liveWindows) { liveWindows -= id } }
        private fun anotherWindows(owner: String?, page: String?, pageRestored: Boolean) = synchronized(liveWindows) {
            anotherWindowsDraft(owner, page, owner in liveWindows, owner in seenWindows, pageRestored)
        }
    }
}

/**
 * NT-2: whether a draft written in the window [owner] is still that window's, so the Notes page [page] only offers it.
 * Yes while that window is on screen in this process ([live]). One this process showed and that has gone ([seen], not
 * live) won't come back for it. One from before Planner was last started may: when this window came back from its saved
 * state too ([pageRestored], Android closed Planner with both open, NW-5), that window may still be restored. A window
 * started afresh (Planner swiped away, a reboot) takes it as its own, as before drafts named their window.
 */
internal fun anotherWindowsDraft(owner: String?, page: String?, live: Boolean, seen: Boolean, pageRestored: Boolean): Boolean =
    owner != null && owner != page && (live || !seen && pageRestored)

/**
 * The draft a Notes page reopens as it opens: rebuilt with a note open ([editingId], after Android closed Planner) only
 * that note's, else the newest. Never one whose note an editor has open, in this window or another ([isOpen]): that
 * editor is still writing it. NW-5: with no note open, only a draft of this page ([page]) or of none (written before
 * drafts named their page): another window's is that window's to reopen when it comes back, so it is only offered
 * ([offeredNoteDraft]). NT-2: one of a window that is gone is this page's too ([anotherWindows]). With no [page] (the
 * share's check), any.
 */
internal fun recoverableNoteDraft(editingId: String?, page: String?, isOpen: (String) -> Boolean, read: (String) -> NoteDraftStore.Draft?,
                                  readAll: () -> List<NoteDraftStore.Draft>,
                                  anotherWindows: (String?) -> Boolean = { it != null && it != page }): NoteDraftStore.Draft? =
    if (editingId != null) read(editingId)?.takeUnless { isOpen(it.note.id) }
    else readAll().firstOrNull { !isOpen(it.note.id) && (page == null || !anotherWindows(it.owner)) }

/** The newest draft of another Notes page than [page] whose note no editor has open: the page offers to open it. NT-2:
 *  only one [anotherWindows] says is still that window's (see [anotherWindowsDraft]); the page reopens the rest. */
internal fun offeredNoteDraft(page: String, isOpen: (String) -> Boolean, drafts: List<NoteDraftStore.Draft>,
                              anotherWindows: (String?) -> Boolean = { it != null && it != page }): NoteDraftStore.Draft? =
    drafts.firstOrNull { anotherWindows(it.owner) && !isOpen(it.note.id) }
