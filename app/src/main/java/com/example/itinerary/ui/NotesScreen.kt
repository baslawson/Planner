package com.example.itinerary.ui
import com.example.itinerary.ui.MatrixFilterChip as FilterChip

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.itinerary.ItineraryApp
import com.example.itinerary.data.Markdown
import com.example.itinerary.data.NoteFilter
import com.example.itinerary.data.Notes
import com.example.itinerary.data.PlannerNote
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.UUID

// The filter, kept across rotation as text: "all", "archive", "nb:<notebook>" or "tag:<tag>".
private fun NoteFilter.key() = when (this) { NoteFilter.All -> "all"; NoteFilter.Archive -> "archive"; is NoteFilter.Notebook -> "nb:$name"; is NoteFilter.Tag -> "tag:$name" }
private fun filterOf(key: String): NoteFilter = when {
    key == "archive" -> NoteFilter.Archive
    key.startsWith("nb:") -> NoteFilter.Notebook(key.removePrefix("nb:"))
    key.startsWith("tag:") -> NoteFilter.Tag(key.removePrefix("tag:"))
    else -> NoteFilter.All
}

/**
 * Notes, from the ⋮ menu: a grid of note cards (pinned first, then the latest), a search, and chips for all notes, each
 * notebook and the archive. A card opens its note; its ⋮ pins, archives or deletes it (Undo bar). + starts a new note.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesScreen(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as ItineraryApp
    val repo = app.repository
    val notes by repo.notes.collectAsStateWithLifecycle(initialValue = null)
    var query by rememberSaveable { mutableStateOf("") }
    var filterKey by rememberSaveable { mutableStateOf("all") }
    // The note open in the editor: its id, and whether it is a new one not saved yet.
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var editingNew by rememberSaveable { mutableStateOf(false) }
    val all = notes.orEmpty()
    val notebooks = remember(all) { Notes.notebooks(all) }
    val tags = remember(all) { Notes.tags(all) }
    // A notebook or tag that has emptied (its last note moved or deleted) falls back to all notes.
    val filter = filterOf(filterKey).let {
        if (it is NoteFilter.Notebook && it.name !in notebooks || it is NoteFilter.Tag && it.name !in tags) NoteFilter.All else it
    }
    val shown = remember(all, filter, query) { Notes.visible(all, filter, query) }

    // Long press, held still: notes ticked for Pin, Archive, Move to notebook or Delete together. Kept across rotation;
    // cleared when the filter or the search changes, and a note that leaves the page leaves the selection.
    var selectedIds by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var selectionFor by rememberSaveable { mutableStateOf("$filterKey|$query") }
    LaunchedEffect(filterKey, query) { if (selectionFor != "$filterKey|$query") { selectionFor = "$filterKey|$query"; selectedIds = emptyList() } }
    LaunchedEffect(shown, notes) { if (notes != null) selectedIds.filter { id -> shown.any { it.id == id } }.let { if (it != selectedIds) selectedIds = it } }
    val selecting = selectedIds.isNotEmpty()
    androidx.activity.compose.BackHandler(selecting) { selectedIds = emptyList() }
    fun toggle(id: String) { selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id }

    // Long press, held and moved: the card is dragged to a new place. While it moves (and until the saved order comes
    // back) the page shows the dragged order; not while searching (only matches show) or selecting.
    val drag = remember { NoteDragState() }
    val displayed = drag.order?.let { order -> shown.associateBy { it.id }.let { byId -> order.mapNotNull { byId[it] } } }
        ?.takeIf { it.size == shown.size } ?: shown
    val currentShown by rememberUpdatedState(shown)
    val currentDisplayed by rememberUpdatedState(displayed)
    LaunchedEffect(shown) { if (drag.id == null && drag.order != null && (shown.map { it.id } == drag.order || shown.size != drag.order?.size)) drag.order = null }
    val canDrag = query.isBlank() && !selecting
    val currentCanDrag by rememberUpdatedState(canDrag)
    // ...and stays there, rather than jumping back if a note joins that notebook or tag again later.
    LaunchedEffect(filter, notes) { if (notes != null && filter == NoteFilter.All && filterKey != "all") filterKey = "all" }
    // A note open in the editor that vanishes (deleted by sync, say) keeps its editor, which then offers to save it anew.
    val lastSeen = remember { mutableStateMapOf<String, PlannerNote>() }
    // An editor Android closed mid-edit: its draft reopens it once, here.
    var recovered by remember { mutableStateOf<com.example.itinerary.data.NoteDraftStore.Draft?>(null) }
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        if (editingId != null) return@LaunchedEffect
        val draft = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { com.example.itinerary.data.NoteDraftStore(context).read() }.getOrNull() } ?: return@LaunchedEffect
        recovered = draft; editingNew = draft.creating; editingId = draft.note.id
    }
    val pendingUndo by repo.pendingDeletions.collectAsStateWithLifecycle()

    // Notes sync: a pass on opening the page (when it's on), and its cloud in the top bar opens its settings.
    val sync = app.noteSync
    val syncOn by sync.enabled.collectAsStateWithLifecycle()
    val syncState by sync.state.collectAsStateWithLifecycle()
    var showSync by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(syncOn) { if (syncOn) sync.request(delayMs = 0) }
    val overlayMenu = remember { OverlayMenuState() }
    // The editor opens over the page.
    Box(Modifier.fillMaxSize()) {
    OverlayMenuScreen(overlayMenu) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { HeadingText("Notes", style = MaterialTheme.typography.titleLarge) },
                    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                    actions = {
                        val label = when {
                            !syncOn -> "Notes sync: off"
                            syncState.running -> "Notes sync: syncing"
                            syncState.error != null -> "Notes sync: problem"
                            else -> "Notes sync: up to date"
                        }
                        IconButton(onClick = { showSync = true }) {
                            when {
                                !syncOn -> SyncCloud(CloudLook.STRUCK, MaterialTheme.colorScheme.onSurfaceVariant, label)
                                syncState.running -> SyncCloud(CloudLook.RAINING, MaterialTheme.colorScheme.primary, label)
                                syncState.error != null -> SyncCloud(CloudLook.STRUCK, MaterialTheme.colorScheme.error, label)
                                else -> SyncCloud(CloudLook.SYNCED, MaterialTheme.colorScheme.primary, label)
                            }
                        }
                    },
                )
            },
            bottomBar = {
                if (selecting) NoteSelectionBar(displayed.filter { it.id in selectedIds }, displayed, notebooks) { selectedIds = it }
            },
            floatingActionButton = {
                if (filter != NoteFilter.Archive && !selecting) ExtendedFloatingActionButton(
                    onClick = { editingNew = true; editingId = UUID.randomUUID().toString() },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) }, text = { Text("New note") },
                    containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.primary,
                    // Named for screen readers: the button's own text isn't announced (seen as an unlabelled button).
                    // Above the Undo bar while one shows, rather than under it.
                    modifier = Modifier.navigationBarsPadding().padding(bottom = if (pendingUndo.isNotEmpty()) 64.dp else 0.dp)
                        .semantics(mergeDescendants = true) { contentDescription = "New note" },
                )
            },
        ) { inner ->
            Column(Modifier.fillMaxSize().padding(inner).imePadding()) {
                OutlinedTextField(query, { query = it.replace('\n', ' ') }, Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    label = { Text("Search notes") }, singleLine = true,
                    trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, contentDescription = "Clear search") } })
                // One filter at a time, chosen from a list that grows downwards: all notes, each notebook, each #tag, the archive.
                val choices = remember(notebooks, tags) {
                    listOf<NoteFilter>(NoteFilter.All) + notebooks.map { NoteFilter.Notebook(it) } + tags.map { NoteFilter.Tag(it) } + NoteFilter.Archive
                }
                fun name(choice: NoteFilter) = when (choice) {
                    NoteFilter.All -> "All notes"
                    is NoteFilter.Notebook -> choice.name
                    is NoteFilter.Tag -> "#${choice.name}"
                    NoteFilter.Archive -> "Archive"
                }
                Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    SettingsDropdown("Show", name(filter), choices, onSelect = { filterKey = it.key() }) { choice ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(name(choice), Modifier.weight(1f))
                            Text(Notes.visible(all, choice, "").size.toString(), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                when {
                    notes == null -> {}
                    shown.isEmpty() -> Text(
                        when {
                            query.isNotBlank() -> "No notes match \"${query.trim()}\"."
                            filter == NoteFilter.Archive -> "No archived notes."
                            else -> "No notes yet. Tap New note to write one."
                        },
                        Modifier.padding(horizontal = 16.dp, vertical = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else -> {
                    val grid = rememberLazyStaggeredGridState()
                    val scope = rememberCoroutineScope()
                    val haptic = rememberLongPressHaptic()
                    val density = androidx.compose.ui.platform.LocalDensity.current
                    val slop = androidx.compose.ui.platform.LocalViewConfiguration.current.touchSlop
                    val edge = with(density) { 64.dp.toPx() }
                    LazyVerticalStaggeredGrid(
                        columns = StaggeredGridCells.Adaptive(160.dp),
                        state = grid,
                        modifier = Modifier.fillMaxSize(),
                        // Room under the last cards for the New note button.
                        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 96.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalItemSpacing = 10.dp,
                    ) {
                        items(displayed, key = { it.id }) { note ->
                            NoteCard(note, selecting = selecting, selected = note.id in selectedIds,
                                // The dragged card follows the finger, not the grid's slide animation.
                                modifier = (if (drag.id == note.id) Modifier else Modifier.animateItem()).noteLongPress(note, drag, grid, { currentDisplayed }, { currentCanDrag }, slop, edge,
                                    onSelect = { toggle(note.id) },
                                    onDrop = { order ->
                                        val places = Notes.reorder(currentShown, order)
                                        app.appScope.launch {
                                            try { repo.placeNotes(places) }
                                            catch (e: CancellationException) { throw e }
                                            catch (_: Exception) { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { drag.order = null
                                                android.widget.Toast.makeText(context, "Couldn't move this note. Please try again.", android.widget.Toast.LENGTH_LONG).show() } }
                                        }
                                    },
                                    haptic = haptic, scroll = { by -> scope.launch { grid.scrollBy(by) } }),
                                onOpen = {
                                    // The touch that ended a long press isn't also a tap. The card's click sees the lift first,
                                    // while the long press is still on (drag.id), so both are checked.
                                    if (drag.id != null || android.os.SystemClock.uptimeMillis() - drag.releasedAt < 400) Unit
                                    else if (selecting) toggle(note.id) else { editingNew = false; editingId = note.id }
                                },
                                onSelect = { toggle(note.id) })
                        }
                    }
                    }
                }
            }
        }
    }
    if (showSync) NoteSyncDialog { showSync = false }
    editingId?.let { id ->
        val existing = all.firstOrNull { it.id == id }
        if (existing != null) lastSeen[id] = existing
        val draft = recovered?.takeIf { it.note.id == id }
        // A new note starts in the notebook being looked at.
        val start = existing ?: lastSeen[id] ?: draft?.note ?: if (editingNew) PlannerNote(id = id, notebook = (filter as? NoteFilter.Notebook)?.name.orEmpty(),
            tags = listOfNotNull((filter as? NoteFilter.Tag)?.name)) else null
        if (start != null) key(id) {
            NoteEditor(start, creating = editingNew && existing == null && lastSeen[id] == null, notebooks = notebooks, allTags = tags,
                recovered = draft) { editingId = null; editingNew = false; recovered = null; lastSeen.remove(id) }
        } else if (notes != null) LaunchedEffect(id) { editingId = null }
    }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun NoteCard(note: PlannerNote, selecting: Boolean, selected: Boolean, modifier: Modifier, onOpen: () -> Unit, onSelect: () -> Unit) {
    val tint = note.color?.let { Color(it) }
    // White or black, whichever reads better on the card's colour (4.5:1 or more on all of them).
    val text = tint?.let { if (contrast(Color.White, it) >= contrast(Color.Black, it)) Color.White else Color.Black } ?: MaterialTheme.colorScheme.onSurface
    val soft = if (tint != null) text.copy(alpha = 0.9f) else MaterialTheme.colorScheme.onSurfaceVariant
    val label = remember(note.title, note.content) { Notes.label(note) }
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = tint ?: MaterialTheme.colorScheme.surfaceContainer,
        border = if (selected) BorderStroke(3.dp, MaterialTheme.colorScheme.primary)
            else BorderStroke(1.dp, if (tint != null) tint else MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.fillMaxWidth()
            .combinedClickable(onClickLabel = if (selecting) (if (selected) "Deselect note" else "Select note") else "Open note", onClick = onOpen)
            // The long press itself is the card's own gesture (select, or drag); this offers selecting to screen readers.
            .semantics { onLongClick("Select note") { onSelect(); true }; if (selecting) this.selected = selected },
    ) {
        Column(Modifier.padding(start = 14.dp, top = 8.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(label, Modifier.weight(1f).padding(top = 6.dp), style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold, color = text, maxLines = 3, overflow = TextOverflow.Ellipsis)
                if (note.pinned) Icon(Icons.Filled.Star, contentDescription = "Pinned", tint = if (tint != null) Color.White else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp).size(18.dp))
                if (selecting) Checkbox(checked = selected, onCheckedChange = null, modifier = Modifier.padding(4.dp))
                else NoteActionsMenu(note, label, text)
            }
            Column(Modifier.padding(end = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val body = remember(note.content) { Markdown.plain(note.content).lines().filter { it.isNotBlank() } }
                // The first line is the name when there is no title; the preview goes on from the next.
                val preview = (if (note.title.isBlank()) body.drop(1) else body).joinToString("\n")
                if (preview.isNotBlank()) Text(preview, style = MaterialTheme.typography.bodyMedium, color = soft, maxLines = 8, overflow = TextOverflow.Ellipsis)
                val (done, total) = remember(note.content) { Markdown.checklist(note.content) }
                if (total > 0) Text("☑ $done of $total done", style = MaterialTheme.typography.labelMedium, color = text)
                if (note.notebook.isNotBlank()) Text(note.notebook, style = MaterialTheme.typography.labelMedium, color = soft,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (note.tags.isNotEmpty()) Text(note.tags.joinToString(" ") { "#$it" }, style = MaterialTheme.typography.labelMedium,
                    color = soft, maxLines = 2, overflow = TextOverflow.Ellipsis)
                note.reminderAt?.let { at ->
                    val due = noteReminderLabel(note.snoozedUntil ?: at)
                    Text("⏰ $due", style = MaterialTheme.typography.labelMedium, color = text, modifier = Modifier.semantics { contentDescription = "Reminder, $due" })
                }
                if (note.attachments.isNotEmpty()) Text("📎 ${note.attachments.size}", style = MaterialTheme.typography.labelMedium, color = soft,
                    modifier = Modifier.semantics { contentDescription = "${note.attachments.size} attachment${if (note.attachments.size == 1) "" else "s"}" })
            }
        }
    }
}

// A note card's ⋮: Pin / Unpin, Archive / Unarchive, and Delete (to Recently deleted, with the Undo bar).
@Composable
private fun NoteActionsMenu(note: PlannerNote, label: String, tint: Color) {
    val context = LocalContext.current
    val app = context.applicationContext as ItineraryApp
    var busy by remember(note.id) { mutableStateOf(false) }
    fun act(failure: String, block: suspend () -> Unit) {
        busy = true
        app.appScope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                android.widget.Toast.makeText(context, failure, android.widget.Toast.LENGTH_LONG).show() } }
            finally { busy = false }
        }
    }
    OverlayMenuAnchor(title = "Actions for $label", button = { open ->
        IconButton(enabled = !busy, onClick = open) { Icon(Icons.Filled.MoreVert, contentDescription = "Actions for $label", tint = tint) }
    }, items = { close ->
        DropdownMenuItem(text = { Text(if (note.pinned) "Unpin" else "Pin to the top") }, onClick = {
            close(); act("Couldn't change this note. Please try again.") { app.repository.updateNote(note.id) { it.copy(pinned = !it.pinned) } } })
        DropdownMenuItem(text = { Text(if (note.archived) "Unarchive" else "Archive") }, onClick = {
            close(); act("Couldn't change this note. Please try again.") { app.repository.updateNote(note.id) { it.copy(archived = !it.archived) } } })
        DropdownMenuItem(text = { Text("Delete note", color = MaterialTheme.colorScheme.error) }, onClick = {
            close(); act("Couldn't delete this note. Please try again.") { app.repository.deleteNote(note.id) } })
    })
}

/** Notes sync: on or off, its state, and Sync now. Uses the Nextcloud login from Settings → Nextcloud. */
@Composable
private fun NoteSyncDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as ItineraryApp
    val sync = app.noteSync
    val on by sync.enabled.collectAsStateWithLifecycle()
    val state by sync.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    // Read off the main thread: whether there is a Nextcloud login to sync with.
    val signedIn by produceState<Boolean?>(null) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { com.example.itinerary.data.NextcloudAccountStore(context).load() != null }.getOrDefault(false) }
    }
    PlannerDialog("Sync notes with Nextcloud", onDismiss,
        primary = if (on) DialogAction(if (state.running) "Syncing…" else "Sync now", enabled = !state.running && signedIn == true) {
            // The app's scope: closing the dialog doesn't stop a sync half way.
            app.appScope.launch { sync.sync() } } else null,
        dismiss = DialogAction("Close", onClick = onDismiss)) {
        Text("Your notes stay in step with the Notes app on your Nextcloud, and with Quillpad or any app that uses it. " +
            "Notebooks are its categories and pinned notes its favourites; colours, tags, reminders, attachments and the archive stay on this phone.",
            style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(12.dp))
        if (signedIn == false) Text("Sign in to Nextcloud first, in Settings → Nextcloud.", color = MaterialTheme.colorScheme.error)
        Row(Modifier.fillMaxWidth().toggleable(value = on, enabled = signedIn == true || on, role = androidx.compose.ui.semantics.Role.Switch,
            onValueChange = { want -> app.appScope.launch { sync.setEnabled(want) } }).padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("Sync notes", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Switch(checked = on, onCheckedChange = null, enabled = signedIn == true || on)
        }
        if (on) {
            val format = LocalTimeFormat.current
            val message = when {
                state.running -> "Syncing…"
                state.error != null -> state.error!!
                state.lastSynced != null -> "Synced " + java.time.Instant.ofEpochMilli(state.lastSynced!!).atZone(java.time.ZoneId.systemDefault())
                    .let { "${it.toLocalDate().shortLabel()}, ${it.toLocalTime().label(format, context)}" } +
                    if (state.conflicts > 0) " · ${state.conflicts} conflict cop${if (state.conflicts == 1) "y" else "ies"} made (changed in both places)" else "" +
                    if (state.skipped > 0) " · ${state.skipped} note${if (state.skipped == 1) "" else "s"} left as they are (too long for Planner, or Nextcloud wouldn't take the change)" else ""
                else -> "Not synced yet."
            }
            Text(message, style = MaterialTheme.typography.bodyMedium,
                color = if (state.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// WCAG contrast ratio of two colours.
private fun contrast(a: Color, b: Color): Float {
    val la = a.luminance() + 0.05f; val lb = b.luminance() + 0.05f
    return maxOf(la, lb) / minOf(la, lb)
}
