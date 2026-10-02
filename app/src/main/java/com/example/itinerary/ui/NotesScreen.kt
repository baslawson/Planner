package com.example.itinerary.ui
import com.example.itinerary.ui.MatrixFilterChip as FilterChip

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
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

    val overlayMenu = remember { OverlayMenuState() }
    // The editor opens over the page.
    Box(Modifier.fillMaxSize()) {
    OverlayMenuScreen(overlayMenu) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { HeadingText("Notes", style = MaterialTheme.typography.titleLarge) },
                    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                )
            },
            floatingActionButton = {
                if (filter != NoteFilter.Archive) ExtendedFloatingActionButton(
                    onClick = { editingNew = true; editingId = UUID.randomUUID().toString() },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) }, text = { Text("New note") },
                    containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.primary,
                    // Named for screen readers: the button's own text isn't announced (seen as an unlabelled button).
                    modifier = Modifier.navigationBarsPadding().semantics(mergeDescendants = true) { contentDescription = "New note" },
                )
            },
        ) { inner ->
            Column(Modifier.fillMaxSize().padding(inner).imePadding()) {
                OutlinedTextField(query, { query = it.replace('\n', ' ') }, Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    label = { Text("Search notes") }, singleLine = true,
                    trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, contentDescription = "Clear search") } })
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = filter == NoteFilter.All, onClick = { filterKey = "all" }, label = { Text("All notes") })
                    notebooks.forEach { name ->
                        FilterChip(selected = filter == NoteFilter.Notebook(name), onClick = { filterKey = NoteFilter.Notebook(name).key() }, label = { Text(name) })
                    }
                    tags.forEach { name ->
                        FilterChip(selected = filter == NoteFilter.Tag(name), onClick = { filterKey = NoteFilter.Tag(name).key() }, label = { Text("#$name") })
                    }
                    FilterChip(selected = filter == NoteFilter.Archive, onClick = { filterKey = "archive" }, label = { Text("Archive") })
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
                    else -> LazyVerticalStaggeredGrid(
                        columns = StaggeredGridCells.Adaptive(160.dp),
                        state = rememberLazyStaggeredGridState(),
                        modifier = Modifier.fillMaxSize(),
                        // Room under the last cards for the New note button.
                        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 96.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalItemSpacing = 10.dp,
                    ) {
                        items(shown, key = { it.id }) { note -> NoteCard(note, onOpen = { editingNew = false; editingId = note.id }) }
                    }
                }
            }
        }
    }
    editingId?.let { id ->
        val existing = all.firstOrNull { it.id == id }
        // A new note starts in the notebook being looked at.
        val start = existing ?: if (editingNew) PlannerNote(id = id, notebook = (filter as? NoteFilter.Notebook)?.name.orEmpty(),
            tags = listOfNotNull((filter as? NoteFilter.Tag)?.name)) else null
        if (start != null) key(id) {
            NoteEditor(start, creating = editingNew && existing == null, notebooks = notebooks, allTags = tags) { editingId = null; editingNew = false }
        } else if (notes != null) LaunchedEffect(id) { editingId = null }
    }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun NoteCard(note: PlannerNote, onOpen: () -> Unit) {
    val tint = note.color?.let { Color(it) }
    val text = if (tint != null) Color.White else MaterialTheme.colorScheme.onSurface
    val soft = if (tint != null) Color.White.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant
    val label = Notes.label(note)
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = tint ?: MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, if (tint != null) tint else MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().combinedClickable(onClickLabel = "Open note", onClick = onOpen),
    ) {
        Column(Modifier.padding(start = 14.dp, top = 8.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(label, Modifier.weight(1f).padding(top = 6.dp), style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold, color = text, maxLines = 3, overflow = TextOverflow.Ellipsis)
                if (note.pinned) Icon(Icons.Filled.Star, contentDescription = "Pinned", tint = if (tint != null) Color.White else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp).size(18.dp))
                NoteActionsMenu(note, label, text)
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
