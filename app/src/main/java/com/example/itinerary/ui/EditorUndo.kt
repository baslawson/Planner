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
    private data class Step(val field: String, val before: String, var after: String, var at: Long)
    private val undos = mutableStateListOf<Step>()
    private val redos = mutableStateListOf<Step>()
    private val setters = HashMap<String, (String) -> Unit>()
    // The text each field last reported, and the text an Undo or Redo is about to put there (not a new step).
    private val seen = HashMap<String, String>()
    private val applying = HashMap<String, String>()

    val canUndo: Boolean get() = undos.isNotEmpty()
    val canRedo: Boolean get() = redos.isNotEmpty()

    internal fun report(field: String, text: String, set: (String) -> Unit, now: Long = System.currentTimeMillis()) {
        setters[field] = set
        val before = seen.put(field, text) ?: return
        if (before == text) return
        if (applying.remove(field) == text) return
        redos.clear()
        val last = undos.lastOrNull()
        if (last != null && last.field == field && last.after == before && now - last.at < GROUP_MS && !startsWord(before, text)) {
            last.after = text; last.at = now
            undos[undos.lastIndex] = last.copy()
        } else {
            undos += Step(field, before, text, now)
            if (undos.size > MAX_STEPS) undos.removeAt(0)
        }
    }

    fun undo() { val step = undos.removeLastOrNull() ?: return; redos += step; put(step.field, step.before) }
    fun redo() { val step = redos.removeLastOrNull() ?: return; undos += step; put(step.field, step.after) }

    private fun put(field: String, text: String) {
        val set = setters[field] ?: return
        applying[field] = text; seen[field] = text
        set(text)
    }

    companion object {
        const val MAX_STEPS = 100
        const val GROUP_MS = 1_000L
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
    val activity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
    DisposableEffect(key) { onDispose { if (activity?.isChangingConfigurations != true) synchronized(EditorUndoMemory) { EditorUndoMemory.kept.remove(key) } } }
    return undo
}

/** Reports [field]'s [text] to [undo]; [set] puts an earlier text back. */
@Composable
fun Track(undo: EditorUndo, field: String, text: String, set: (String) -> Unit) {
    val setter by rememberUpdatedState(set)
    SideEffect { undo.report(field, text, { setter(it) }) }
}

/** Ctrl+Z and Ctrl+Shift+Z (or Ctrl+Y) on a physical keyboard, for an editor's whole form. */
fun androidx.compose.ui.Modifier.undoKeys(undo: EditorUndo): androidx.compose.ui.Modifier = onPreviewKeyEvent { event ->
    if (event.type != androidx.compose.ui.input.key.KeyEventType.KeyDown || !event.isCtrlPressed) return@onPreviewKeyEvent false
    when {
        event.key == androidx.compose.ui.input.key.Key.Z && event.isShiftPressed || event.key == androidx.compose.ui.input.key.Key.Y -> { undo.redo(); true }
        event.key == androidx.compose.ui.input.key.Key.Z -> { undo.undo(); true }
        else -> false
    }
}

/** Where the cursor goes when [now] replaces [before]: after the part that differs. */
fun undoCursor(before: String, now: String): Int {
    val start = before.commonPrefixWith(now).length
    val end = before.drop(start).commonSuffixWith(now.drop(start)).length
    return (now.length - end).coerceIn(start, now.length)
}
