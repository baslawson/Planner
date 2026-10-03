package com.example.itinerary.ui

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.selection.triStateToggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toOffset
import androidx.compose.ui.zIndex
import com.example.itinerary.ItineraryApp
import com.example.itinerary.data.Notes
import com.example.itinerary.data.PlannerNote
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * A note card being dragged on the Notes page. [order] is the page's order while it moves (and until the saved order
 * arrives); [total] is how far the finger went since the long press. The card is drawn at its place in the grid plus
 * that, less how far the grid has since moved its place (a swap, or scrolling), so it stays under the finger.
 */
internal class NoteDragState {
    var id by mutableStateOf<String?>(null)
    var order by mutableStateOf<List<String>?>(null)
    // How many dropped orders are still being saved: until then a page in another order is an older reading.
    var saving = 0
    var total by mutableStateOf(Offset.Zero)
    var start = Offset.Zero
    var size = Offset.Zero
    var moved = false
    // When the last long press ended: the tap that Android may still report for the same touch is ignored.
    var releasedAt = 0L
}

private fun LazyStaggeredGridState.offsetOf(id: String) =
    layoutInfo.visibleItemsInfo.firstOrNull { it.key == id }?.offset?.toOffset()

/**
 * A note card's long press. Held still and let go: [onSelect]. Held and moved (when [canDrag]): the card follows the
 * finger, swaps places with the cards of [order] it passes over (pinned and unpinned notes stay apart), and the
 * grid scrolls near its edges; let go, [onDrop] gets the new order. A tap is left to the card's own click.
 */
internal fun Modifier.noteLongPress(
    note: PlannerNote, drag: NoteDragState, grid: LazyStaggeredGridState, order: () -> List<PlannerNote>, canDrag: () -> Boolean,
    slop: Float, edge: Float, onSelect: () -> Unit, onDrop: (List<String>) -> Unit, haptic: () -> Unit,
    scroll: (Float) -> Unit,
): Modifier = this
    .then(if (drag.id == note.id) Modifier.zIndex(1f).graphicsLayer {
        val now = grid.offsetOf(note.id) ?: drag.start
        translationX = drag.total.x + drag.start.x - now.x
        translationY = drag.total.y + drag.start.y - now.y
        shadowElevation = 12f; scaleX = 1.03f; scaleY = 1.03f
    } else Modifier)
    // Keyed on the note only: a key that changed mid-gesture (selecting turns dragging off) would restart it and cancel
    // the press a second time.
    .pointerInput(note.id) {
        detectDragGesturesAfterLongPress(
            onDragStart = {
                haptic()
                drag.id = note.id; drag.total = Offset.Zero; drag.moved = false
                drag.start = grid.offsetOf(note.id) ?: Offset.Zero
                drag.size = Offset(size.width.toFloat(), size.height.toFloat())
            },
            onDrag = { change, amount ->
                change.consume()
                drag.total += amount
                if (!canDrag()) return@detectDragGesturesAfterLongPress
                if (!drag.moved && drag.total.getDistance() < slop) return@detectDragGesturesAfterLongPress
                drag.moved = true
                val current = drag.order ?: order().map { it.id }
                if (drag.order == null) drag.order = current
                // The middle of the dragged card, in the grid's own coordinates; the card under it swaps places.
                val centre = drag.start + drag.total + drag.size / 2f
                val notes = order().associateBy { it.id }
                val target = grid.layoutInfo.visibleItemsInfo.firstOrNull { item ->
                    item.key != note.id && Rect(item.offset.toOffset(), Size(item.size.width.toFloat(), item.size.height.toFloat())).contains(centre)
                }?.key as? String
                // Pinned as the note is now: the gesture outlives a Pin from the ⋮ menu, and [note] is as it was then.
                val pinned = notes[note.id]?.pinned ?: note.pinned
                if (target != null && notes[target]?.pinned == pinned) {
                    val to = current.indexOf(target)
                    if (to >= 0) drag.order = current.filter { it != note.id }.toMutableList().apply { add(to, note.id) }
                }
                val viewport = grid.layoutInfo.viewportSize.height
                if (centre.y < edge) scroll(-edge / 3) else if (centre.y > viewport - edge) scroll(edge / 3)
            },
            onDragEnd = { finish(note, drag, onSelect, onDrop) },
            onDragCancel = { finish(note, drag, onSelect, onDrop) },
        )
    }

private fun finish(note: PlannerNote, drag: NoteDragState, onSelect: () -> Unit, onDrop: (List<String>) -> Unit) {
    if (drag.id != note.id) return // already finished (a cancel can follow the end)
    drag.releasedAt = android.os.SystemClock.uptimeMillis()
    val moved = drag.moved
    val order = drag.order
    drag.id = null; drag.total = Offset.Zero; drag.moved = false
    if (!moved) { drag.order = null; onSelect() } else if (order != null) onDrop(order)
}

/**
 * The bar under the Notes page while notes are selected: the count with a box that selects all [shown] (or none), Cancel,
 * and what can be done to them all: Pin / Unpin, Archive / Unarchive, Move to notebook, and Delete (asked first).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NoteSelectionBar(selected: List<PlannerNote>, shown: List<PlannerNote>, notebooks: List<String>,
                              onSelect: (List<String>) -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as ItineraryApp
    var busy by remember { mutableStateOf(false) }
    var moving by rememberSaveable { mutableStateOf(false) }
    var confirming by rememberSaveable { mutableStateOf(false) }
    val ids = selected.map { it.id }
    fun act(failure: String, block: suspend () -> Unit) {
        busy = true
        app.appScope.launch {
            try { block(); kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { onSelect(emptyList()) } }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                android.widget.Toast.makeText(context, failure, android.widget.Toast.LENGTH_LONG).show() } }
            finally { busy = false }
        }
    }
    val all = when (selected.size) { 0 -> ToggleableState.Off; shown.size -> ToggleableState.On; else -> ToggleableState.Indeterminate }
    val count = if (selected.size == 1) "1 note" else "${selected.size} notes"
    // Laid out like the app's pop-ups (PlannerDialog): quiet buttons in rows, the big red Delete underneath.
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            HorizontalDivider()
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    // The count is the select-all box's label, as on the agenda.
                    Row(Modifier.weight(1f)
                        .triStateToggleable(state = all, enabled = !busy, role = Role.Checkbox,
                            onClick = { onSelect(if (all == ToggleableState.On) emptyList() else shown.map { it.id }) })
                        .semantics { contentDescription = "Select all" },
                        verticalAlignment = Alignment.CenterVertically) {
                        TriStateCheckbox(state = all, onClick = null, enabled = !busy)
                        Text("${selected.size} selected")
                    }
                    MatrixQuietButton(onClick = { onSelect(emptyList()) }, enabled = !busy) { Text("Cancel") }
                }
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    val pin = selected.any { !it.pinned }
                    MatrixQuietButton(enabled = !busy && selected.isNotEmpty(), onClick = {
                        act("Couldn't change these notes. Please try again.") { app.repository.updateNotes(ids) { it.copy(pinned = pin) } } }) {
                        Text(if (pin) "Pin" else "Unpin")
                    }
                    val archive = selected.any { !it.archived }
                    MatrixQuietButton(enabled = !busy && selected.isNotEmpty(), onClick = {
                        act("Couldn't change these notes. Please try again.") { app.repository.updateNotes(ids) { it.copy(archived = archive) } } }) {
                        Text(if (archive) "Archive" else "Unarchive")
                    }
                    MatrixQuietButton(enabled = !busy && selected.isNotEmpty(), onClick = { moving = true }) { Text("Move to notebook") }
                }
                DangerButton(onClick = { confirming = true }, modifier = Modifier.fillMaxWidth(), enabled = !busy && selected.isNotEmpty()) {
                    Text("Delete $count")
                }
            }
        }
    }
    if (moving && selected.isNotEmpty()) {
        var name by rememberSaveable { mutableStateOf("") }
        PlannerDialog("Move $count to a notebook", { moving = false },
            primary = DialogAction("Move", enabled = !busy) {
                val target = Notes.existingSpelling(notebooks, Notes.cleanNotebook(name))
                moving = false
                act("Couldn't move these notes. Please try again.") { app.repository.updateNotes(ids) { it.copy(notebook = target) } }
            },
            dismiss = DialogAction("Cancel") { moving = false }) {
            Text("Type a notebook, or pick one. Leave it empty to take the notes out of their notebooks.",
                style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
            SuggestField(name, { name = it.replace('\n', ' ').take(Notes.MAX_NOTEBOOK) }, "Notebook",
                suggestions = Notes.suggest(notebooks, name).filter { it != name.trim() }, onPick = { name = it })
        }
    }
    if (confirming && selected.isNotEmpty()) {
        PlannerDialog("Delete $count?", { if (!busy) confirming = false },
            primary = DialogAction("Delete $count", enabled = !busy, danger = true) {
                confirming = false
                act("Couldn't delete these notes. Please try again.") { app.repository.deleteNotes(ids) }
            },
            dismiss = DialogAction("Keep notes", enabled = !busy) { confirming = false },
            scroll = null) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Delete the selected notes, including their reminders and attachments? They stay in Recently deleted for 30 days.")
                LazyScrollHints(Modifier.heightIn(max = 240.dp)) { hintState -> LazyColumn(Modifier.fillMaxWidth(), state = hintState) {
                    items(selected, key = { it.id }) { note ->
                        Text(Notes.label(note), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(vertical = 4.dp))
                    }
                } }
            }
        }
    }
}

/** The haptic tick at a long press, for [noteLongPress]. */
@Composable
internal fun rememberLongPressHaptic(): () -> Unit {
    val haptic = LocalHapticFeedback.current
    return remember(haptic) { { haptic.performHapticFeedback(HapticFeedbackType.LongPress) } }
}
