package com.example.itinerary.ui

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

/**
 * Undo and Redo for what is typed in an editor (wish list #2): one history for the whole editor, in the order things were
 * typed, so Undo takes back the last typing whichever field it was in. Typing in one field without a pause or a new word
 * is one step. Each field reports its text with [Track]; Undo puts the earlier text back through that field's setter.
 * The history lives in memory for as long as the editor is open, rotation included (not in the saved state, which a
 * long note would make too big).
 */
@Stable
class EditorUndo internal constructor() {
    // One step: a field's typing, or several fields the app changed at once (a template, a bill suggestion).
    private data class Part(val field: String, val before: String, val after: String)
    // [restore]: what else the app changed in that step and how to put it back and again (a template's other fields, ER-2).
    private data class Step(val parts: List<Part>, val at: Long, val typed: Boolean, val restore: Pair<() -> Unit, () -> Unit>? = null)
    private val undos = mutableStateListOf<Step>()
    private val redos = mutableStateListOf<Step>()
    private val setters = HashMap<String, (String) -> Unit>()
    // Whether a field's next text still belongs to the typing step that began with an earlier one (ER-1).
    private val merges = HashMap<String, (String, String) -> Boolean>()
    // The text each field last reported.
    private val seen = HashMap<String, String>()
    // Changes the app is making: joined into one step until [joinUntil], or not recorded at all when [quiet] (ED-7).
    private var joinUntil = 0L
    private var quiet = false

    val canUndo: Boolean get() = undos.isNotEmpty()
    val canRedo: Boolean get() = redos.isNotEmpty()

    internal fun report(field: String, text: String, set: (String) -> Unit, now: Long = System.currentTimeMillis(),
                        merge: ((String, String) -> Boolean)? = null) {
        setters[field] = set
        if (merge != null) merges[field] = merge
        val before = seen.put(field, text) ?: return
        if (before == text) return
        if (now < joinUntil && quiet) return
        redos.clear()
        val last = undos.lastOrNull()
        val part = last?.parts?.singleOrNull()
        when {
            now < joinUntil && last != null && !last.typed && last.at >= joinUntil - JOIN_MS ->
                undos[undos.lastIndex] = last.copy(parts = last.parts.filterNot { it.field == field } +
                    Part(field, last.parts.find { it.field == field }?.before ?: before, text))
            now >= joinUntil && last != null && last.typed && part!!.field == field && part.after == before &&
                now - last.at < GROUP_MS && !startsWord(before, text) && merges[field]?.invoke(part.before, text) != false ->
                undos[undos.lastIndex] = Step(listOf(part.copy(after = text)), now, typed = true)
            else -> {
                undos += Step(listOf(Part(field, before, text)), if (now < joinUntil) joinUntil - JOIN_MS else now, typed = now >= joinUntil)
                trim()
            }
        }
    }

    // At most MAX_STEPS steps and about MAX_CHARS characters kept: each step holds whole texts (ED-11).
    private fun trim() {
        var chars = undos.sumOf { s -> s.parts.sumOf { it.before.length + it.after.length } }
        while (undos.size > 1 && (undos.size > MAX_STEPS || chars > MAX_CHARS)) {
            chars -= undos.removeAt(0).parts.sumOf { it.before.length + it.after.length }
        }
    }

    /** The next changes, made by the app in several fields at once, are one step (ED-7). */
    fun together(now: Long = System.currentTimeMillis()) { joinUntil = now + JOIN_MS; quiet = false }

    /**
     * The app is about to change fields it doesn't track as text: one step with the text fields it changes next, which
     * [undo] puts back and [redo] does again (ER-2).
     */
    fun around(undo: () -> Unit, redo: () -> Unit, now: Long = System.currentTimeMillis()) {
        together(now)
        redos.clear()
        undos += Step(emptyList(), now, typed = false, restore = undo to redo)
        trim()
    }

    /** The next changes aren't the person's (a sync change loaded): no step, and the history so far goes (ED-7). */
    fun reload(now: Long = System.currentTimeMillis()) { undos.clear(); redos.clear(); joinUntil = now + JOIN_MS; quiet = true }

    // A field that is gone (a deleted checklist entry): its steps are skipped, not put into whichever field took its
    // place (ED-2). A rebuilt editor reports its fields again before anything can be undone.
    internal fun untrack(field: String) { setters.remove(field); seen.remove(field) }

    fun undo() = move(undos, redos) { it.before }
    fun redo() = move(redos, undos) { it.after }

    private fun move(from: MutableList<Step>, to: MutableList<Step>, text: (Part) -> String) {
        while (true) {
            val step = from.removeLastOrNull() ?: return
            val live = step.parts.filter { it.field in setters }
            if (live.isEmpty() && step.restore == null) continue
            to += step
            joinUntil = 0
            step.restore?.let { if (from === undos) it.first() else it.second() }
            live.forEach { part -> seen[part.field] = text(part); setters.getValue(part.field)(text(part)) }
            return
        }
    }

    companion object {
        const val MAX_STEPS = 100
        const val MAX_CHARS = 2_000_000
        const val GROUP_MS = 1_000L
        const val JOIN_MS = 500L
        // A space or a new line typed after a word starts a new step, so Undo goes back a word at a time.
        internal fun startsWord(before: String, after: String): Boolean {
            if (after.length <= before.length) return false
            val at = after.commonPrefixWith(before).length
            return after.substring(at, at + after.length - before.length).any(Char::isWhitespace)
        }
    }
}

// Histories by editor, so a rotated editor keeps its own; a closed one's goes with it (see rememberEditorUndo).
private object EditorUndoMemory {
    val kept = LinkedHashMap<String, EditorUndo>()
}

@Composable
fun rememberEditorUndo(): EditorUndo {
    val key = rememberSaveable { java.util.UUID.randomUUID().toString() }
    val undo = remember { synchronized(EditorUndoMemory) { EditorUndoMemory.kept.getOrPut(key) { EditorUndo() }.also {
        while (EditorUndoMemory.kept.size > 8) EditorUndoMemory.kept.remove(EditorUndoMemory.kept.keys.first())
    } } }
    // Gone for good (not rebuilt): its history goes too.
    // The Activity itself, also from inside a Dialog (whose context only wraps it), so a rotation keeps it there too (ED-4).
    val activity = androidx.compose.ui.platform.LocalContext.current.findActivity()
    DisposableEffect(key) { onDispose { if (activity?.isChangingConfigurations != true) synchronized(EditorUndoMemory) { EditorUndoMemory.kept.remove(key) } } }
    return undo
}

/** Reports [field]'s [text] to [undo]; [set] puts an earlier text back; [merge], when typing goes on in one step. */
@Composable
fun Track(undo: EditorUndo, field: String, text: String, merge: ((String, String) -> Boolean)? = null, set: (String) -> Unit) {
    val setter by rememberUpdatedState(set)
    SideEffect { undo.report(field, text, { setter(it) }, merge = merge) }
    DisposableEffect(undo, field) { onDispose { undo.untrack(field) } }
}

/**
 * Ctrl+Z and Ctrl+Shift+Z (or Ctrl+Y) on a physical keyboard, for an editor's whole form: only while [enabled] (not while
 * saving, ED-3) and when there is something to take back or redo; otherwise the focused field's own undo has the key (ED-6).
 */
fun androidx.compose.ui.Modifier.undoKeys(undo: EditorUndo, enabled: Boolean = true): androidx.compose.ui.Modifier = onPreviewKeyEvent { event ->
    if (!enabled || event.type != androidx.compose.ui.input.key.KeyEventType.KeyDown || !event.isCtrlPressed) return@onPreviewKeyEvent false
    when {
        event.key == androidx.compose.ui.input.key.Key.Z && event.isShiftPressed || event.key == androidx.compose.ui.input.key.Key.Y ->
            undo.canRedo.also { if (it) undo.redo() }
        event.key == androidx.compose.ui.input.key.Key.Z -> undo.canUndo.also { if (it) undo.undo() }
        else -> false
    }
}

/** Where the cursor goes when [now] replaces [before]: after the part that differs. */
fun undoCursor(before: String, now: String): Int {
    val start = before.commonPrefixWith(now).length
    val end = before.drop(start).commonSuffixWith(now.drop(start)).length
    return (now.length - end).coerceIn(start, now.length)
}

// A checklist as text for [Track], and back (blank rows included, unlike ChecklistCodec): rows split by the record
// separator, id / done / text by the unit separator — characters no one types (and taken out if pasted).
private const val ROW = '\u001E'
private const val CELL = '\u001F'
fun undoChecklist(entries: List<com.example.itinerary.data.ChecklistEntry>): String = entries.joinToString(ROW.toString()) {
    it.id + CELL + (if (it.done) "1" else "0") + CELL + it.text.filterNot { c -> c == ROW || c == CELL }
}

fun undoChecklist(text: String): List<com.example.itinerary.data.ChecklistEntry> =
    if (text.isEmpty()) emptyList() else text.split(ROW).mapNotNull { row ->
        val cells = row.split(CELL, limit = 3)
        if (cells.size < 3) null else com.example.itinerary.data.ChecklistEntry(cells[0], cells[2], cells[1] == "1")
    }

// Typing in one checklist row goes on in one step; adding, removing, ticking or moving to another row starts a new one (ER-1).
fun checklistTyping(start: String, now: String): Boolean {
    val a = undoChecklist(start); val b = undoChecklist(now)
    return a.size == b.size && a.zip(b).all { (x, y) -> x.id == y.id && x.done == y.done } && a.zip(b).count { (x, y) -> x.text != y.text } <= 1
}
