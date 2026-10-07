package com.example.itinerary.ui

import kotlinx.coroutines.flow.first
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Check
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
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
// [newNote]: a note not saved yet (made from a share) to open in the editor, once; [onNewNoteOpened] says it was taken.
fun NotesScreen(onBack: () -> Unit, openNoteId: String? = null, onNoteOpened: () -> Unit = {},
                newNote: PlannerNote? = null, onNewNoteOpened: () -> Unit = {}, startNewNote: Boolean = false, onNewNoteStarted: () -> Unit = {},
                onOpenSettings: () -> Unit = {},
                onEditorOpened: () -> Unit = {}) {
    val context = LocalContext.current
    val app = context.applicationContext as ItineraryApp
    val repo = app.repository
    val notes by repo.notes.collectAsStateWithLifecycle(initialValue = null)
    var query by rememberSaveable { mutableStateOf("") }
    var showSearch by rememberSaveable { mutableStateOf(false) }
    // The Show choices open as a box under the toolbar, like the search field; closing it keeps the choice.
    var showFilters by rememberSaveable { mutableStateOf(false) }
    val searchFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    fun closeSearch() {
        query = ""; showSearch = false
        focusManager.clearFocus(); keyboard?.hide()
    }
    // Opens on the last Show choice; one whose notebook or tag has gone falls back to all notes (below).
    var filterKey by rememberSaveable { mutableStateOf(app.settings.noteFilter) }
    // NW-8: All notes shown for a share's note is for now, not the Show choice kept for next time. NT-3: so only a choice
    // the user makes (and the fall back below) is kept, not whatever the page shows: rebuilt after Android closed
    // Planner, a share's window would otherwise keep All notes.
    fun showFilter(key: String) { filterKey = key; app.settings.noteFilter = key }
    // The note open in the editor: its id, and whether it is a new one not saved yet.
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(editingId) { if (editingId != null) onEditorOpened() }
    var editingNew by rememberSaveable { mutableStateOf(false) }
    val all = notes.orEmpty()
    val notebooks = remember(all) { Notes.notebooks(all) }
    val tags = remember(all) { Notes.tags(all) }
    // A notebook or tag that has emptied (its last note moved or deleted) falls back to all notes.
    val filter = filterOf(filterKey).let {
        if (it is NoteFilter.Notebook && it.name !in notebooks || it is NoteFilter.Tag && it.name !in tags) NoteFilter.All else it
    }
    val sort by app.settings.noteSort.collectAsStateWithLifecycle()
    val asList by app.settings.notesAsList.collectAsStateWithLifecycle()
    // Typography already includes the app size and font choice; density adds only Android's font scale.
    val textScale = (MaterialTheme.typography.bodyLarge.fontSize.value / 16f *
        androidx.compose.ui.platform.LocalDensity.current.fontScale).coerceAtLeast(1f)
    // Each note's text normalised once per change to the notes while a search is typed (UI-3), and not at all without
    // one: every save, pin or sync would otherwise redo it for nothing (L5-1).
    val searching = query.isNotBlank()
    val searchFields = remember(all, searching) { if (searching) Notes.searchFields(all) else emptyMap() }
    val shown = remember(all, filter, query, sort, searchFields) { Notes.visible(all, filter, query, sort, searchFields) }

    // Long press, held still: notes ticked for Pin, Archive, Move to notebook or Delete together. Kept across rotation;
    // cleared when the filter or the search changes, and a note that leaves the page leaves the selection.
    var selectedIds by rememberSaveable { mutableStateOf(emptyList<String>()) }
    // Looked up by set, not by list, so Select all on many notes stays quick (UI-9); the list keeps the order ticked.
    val selectedSet = remember(selectedIds) { selectedIds.toHashSet() }
    var selectionFor by rememberSaveable { mutableStateOf("$filterKey|$query") }
    LaunchedEffect(filterKey, query) { if (selectionFor != "$filterKey|$query") { selectionFor = "$filterKey|$query"; selectedIds = emptyList() } }
    LaunchedEffect(shown, notes) { if (notes != null) shown.mapTo(HashSet()) { it.id }.let { ids -> selectedIds.filter { it in ids } }
        .let { if (it != selectedIds) selectedIds = it } }
    val selecting = selectedIds.isNotEmpty()
    // Selection ends first; the next Backs close search, then the Show choices, before leaving Notes.
    androidx.activity.compose.BackHandler(showFilters && !selecting && editingId == null) { showFilters = false }
    androidx.activity.compose.BackHandler(showSearch && !selecting && editingId == null) { closeSearch() }
    androidx.activity.compose.BackHandler(selecting) { selectedIds = emptyList() }
    LaunchedEffect(showSearch, editingId) {
        if (showSearch && editingId == null) {
            searchFocus.requestFocus()
            keyboard?.show()
        }
    }
    fun toggle(id: String) { selectedIds = if (id in selectedSet) selectedIds - id else selectedIds + id }

    // Long press, held and moved: the card is dragged to a new place. While it moves (and until the saved order comes
    // back) the page shows the dragged order; not while searching (only matches show) or selecting.
    val drag = remember { NoteDragState() }
    val displayed = drag.order?.let { order -> shown.associateBy { it.id }.let { byId -> order.mapNotNull { byId[it] } } }
        ?.takeIf { it.size == shown.size } ?: shown
    val currentShown by rememberUpdatedState(shown)
    val currentDisplayed by rememberUpdatedState(displayed)
    // The saved order has come back, or the page changed after it was saved (or saving changed nothing): the page shows
    // its own order again, so a later Sort or Pin shows at once.
    LaunchedEffect(shown) {
        if (drag.id == null && drag.order != null && (shown.map { it.id } == drag.order || shown.size != drag.order?.size || drag.saving == 0)) drag.order = null
    }
    val canDrag = query.isBlank() && !selecting
    val currentCanDrag by rememberUpdatedState(canDrag)
    // The notes put in [order] (dropped there, or moved by a screen reader's action).
    fun place(order: List<String>) {
        val places = Notes.reorder(currentShown, order)
        // Dropped where it shows: in another sort that is now the user's own order.
        if (app.settings.noteSort.value != com.example.itinerary.data.NoteSort.MY_ORDER)
            app.settings.setNoteSort(com.example.itinerary.data.NoteSort.MY_ORDER)
        // Nothing moved: no saved order will come back to take the dragged one's place.
        if (places.isEmpty()) { drag.order = null; return }
        // Shown at once (a screen reader's move too, so a second Move later goes on from here — E5-6); one count per
        // save still running, so a second drop saving meanwhile doesn't put the first back for a moment (E5-7).
        drag.order = order
        drag.saving++
        app.appScope.launch {
            try { repo.placeNotes(places) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { drag.order = null
                android.widget.Toast.makeText(context, "Couldn't move this note. Please try again.", android.widget.Toast.LENGTH_LONG).show() } }
            finally { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main + kotlinx.coroutines.NonCancellable) { drag.saving-- } }
        }
    }
    // ...and stays there, rather than jumping back if a note joins that notebook or tag again later.
    LaunchedEffect(filter, notes) { if (notes != null && filter == NoteFilter.All && filterKey != "all") showFilter("all") }
    // A note open in the editor that vanishes (deleted by sync, say) keeps its editor, which then offers to save it anew.
    val lastSeen = remember { mutableStateMapOf<String, PlannerNote>() }
    // Duplicate: the editor's copy until it is saved, and the message saying so with Open (the ⋮ menu or the bar).
    // Kept across the activity being recreated (D6-8), without its words: those are in the editor's memory or draft.
    var pendingCopy by rememberSaveable(stateSaver = androidx.compose.runtime.saveable.Saver<PlannerNote?, String>(
        save = { it?.let { note -> com.example.itinerary.data.NoteCodec.encode(listOf(note.copy(content = ""))).toString() } ?: "" },
        restore = { text -> text.takeIf { it.isNotEmpty() }?.let { runCatching { com.example.itinerary.data.NoteCodec.decodeLenient(org.json.JSONArray(it)).firstOrNull() }.getOrNull() } },
    )) { mutableStateOf<PlannerNote?>(null) }
    // NW-3: the note the editor's copy was made from, which Save places it next to.
    var copyOfId by rememberSaveable { mutableStateOf<String?>(null) }
    val duplicatedBar = remember { SnackbarHostState() }
    val barScope = rememberCoroutineScope()
    fun duplicated(copies: List<PlannerNote>) {
        if (copies.isEmpty()) return
        selectedIds = emptyList()
        barScope.launch {
            val text = if (copies.size == 1) "Note duplicated" else "${copies.size} notes duplicated"
            val result = duplicatedBar.showSnackbar(text, actionLabel = if (copies.size == 1) "Open" else null, duration = SnackbarDuration.Short)
            if (result == SnackbarResult.ActionPerformed) { editingNew = false; editingId = copies.single().id }
        }
    }
    // An editor Android closed mid-edit: its draft reopens it once, here. That includes the editor still open in the
    // saved state, whose body is only in the draft (NoteEditorMemory); a rotated editor has it in memory instead.
    // N6-1: never the draft of a note open in an editor, here or in another Planner window: that editor is still on it.
    var recovered by remember { mutableStateOf<com.example.itinerary.data.NoteDraftStore.Draft?>(null) }
    // NT-1: the key the editor here keeps its body under in NoteEditorMemory, new for each editor opened. Only this
    // page's own editor, rebuilt (a rotation), has its state there; another window's left there says nothing of this one.
    var editorKey by rememberSaveable { mutableStateOf(UUID.randomUUID().toString()) }
    fun ownEditorKept(id: String) = NoteEditorMemory.restore(editorKey, id) != null
    var draftChecked by remember { mutableStateOf(editingId?.let(::ownEditorKept) == true) }
    // NW-5: this window's own drafts reopen; another window's (that window may come back for it) is offered, once each.
    // NT-2: a window that is gone has none: its drafts reopen here.
    val window = LocalWindowEditors.current
    val page = window?.id
    val pageRestored = window?.restored == true
    val offeredIds = remember { HashSet<String>() }
    // NX-1: the `changes` value this page last looked at, from when it was composed (a window rebuilt has its old page's
    // bump in it already). NX-2: and the one [recovered] was read at: one read before a later change may be out of date.
    val looks = remember { DraftLooks(com.example.itinerary.data.NoteDraftStore.changes.value) }
    var recoveredAt by remember { mutableIntStateOf(0) }
    suspend fun lookForDraft() {
        val restoring = editingId
        val at = com.example.itinerary.data.NoteDraftStore.changes.value
        looks.looked(at)
        val store = com.example.itinerary.data.NoteDraftStore(context)
        val (draft, other) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { store.recoverable(restoring, page, pageRestored).let { own ->
                own to (if (own == null && restoring == null && page != null) store.offered(page, pageRestored) else null) } }.getOrNull() } ?: return
        if (draft != null) {
            // Read now, not before: a card tapped meanwhile opens its own note.
            if (editingId != null && editingId != draft.note.id) return
            recovered = draft; recoveredAt = at; editingNew = draft.creating; editingId = draft.note.id
        } else if (other != null && offeredIds.add(other.note.id)) barScope.launch {
            // NO-3: a window this process hasn't shown may never come back (a share swiped away before Android closed
            // Planner), so it isn't named then.
            val where = if (com.example.itinerary.data.NoteDraftStore.windowLive(other.owner)) " in another Planner window" else ""
            val result = duplicatedBar.showSnackbar("A note$where has unsaved changes", actionLabel = "Open",
                duration = SnackbarDuration.Long)
            if (result == SnackbarResult.ActionPerformed && editingId == null) { editingNew = other.creating; editingId = other.note.id }
        }
    }
    LaunchedEffect(Unit) {
        try { if (!draftChecked) lookForDraft() } finally { draftChecked = true }
    }
    // NW-2: a draft left meanwhile (a share's window swiped away with its note open) is found on coming back here, not
    // only when the page first opens.
    val draftScope = rememberCoroutineScope()
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        if (draftChecked && editingId == null) draftScope.launch { if (editingId == null) lookForDraft() }
    }
    // NT-2: and again when another window's editor lets its note go or that window goes, which comes after this resume.
    // NX-1: one that comes during the first look waits for it, then looks again. NX-2: only while this page is on
    // screen (a stopped window would read a draft another window may take meanwhile); a change made while it was
    // stopped is looked at as it starts again.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            lookOnChanges(com.example.itinerary.data.NoteDraftStore.changes, looks, checked = { snapshotFlow { draftChecked }.first { it } },
                canLook = { editingId == null }, look = { lookForDraft() })
        }
    }
    // NW-1: a note opened here (its card, a reminder, Open) with a draft on disk, from another window or left from before,
    // opens with that draft rather than over it. The editor waits for this read.
    // NT-1: skipped only for this page's own editor rebuilt (its body is in memory), not for one another window left
    // there; and read again each time a note opens (draftReadFor goes as the editor closes), before its editor claims it.
    var draftReadFor by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(editingId, draftChecked) {
        val id = editingId ?: run { draftReadFor = null; return@LaunchedEffect }
        if (!draftChecked) return@LaunchedEffect
        // NX-2: a draft reopened before another editor let a note go or a window went (this page stopped meanwhile) is
        // read again: that window may have saved it, changed it further or discarded it since.
        val stale = recovered?.note?.id == id && recoveredAt != com.example.itinerary.data.NoteDraftStore.changes.value
        if ((recovered?.note?.id != id || stale) && !ownEditorKept(id) && !com.example.itinerary.data.NoteDraftStore.isOpen(id)) {
            val at = com.example.itinerary.data.NoteDraftStore.changes.value
            val draft = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { com.example.itinerary.data.NoteDraftStore(context).read(id) }.getOrNull() }
            if (editingId != id) return@LaunchedEffect
            if (draft != null) { recovered = draft; recoveredAt = at; if (all.none { it.id == id }) editingNew = draft.creating }
            else if (stale) {
                recovered = null
                // NX-2: a new note whose draft was discarded meanwhile leaves nothing to open.
                if (all.none { it.id == id } && pendingCopy?.id != id) { editingId = null; editingNew = false; return@LaunchedEffect }
            }
        }
        draftReadFor = id
    }
    // U-13: the note a reminder's tap asked for, once the notes and any draft are read. Another note open in the editor
    // stays open (opening this one would close it); a note deleted since leaves just the page.
    // While another note is open the reminder's note waits, and opens once that editor closes (the notification is
    // gone by then, so "tap it again" couldn't work — D6-2).
    var waitingNoteId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(openNoteId, notes != null, draftChecked) {
        val id = openNoteId ?: return@LaunchedEffect
        val list = notes ?: return@LaunchedEffect
        if (!draftChecked) return@LaunchedEffect
        when {
            editingId == id -> {}
            editingId != null -> { waitingNoteId = id
                android.widget.Toast.makeText(context, "The reminder's note opens when you close this one.", android.widget.Toast.LENGTH_LONG).show() }
            list.none { it.id == id } -> android.widget.Toast.makeText(context, "This note may have been deleted.", android.widget.Toast.LENGTH_LONG).show()
            else -> { editingNew = false; editingId = id }
        }
        onNoteOpened()
    }
    // The + menu's "Add note" on Agenda or Calendar: a new note, as this page's "New note" starts one, once the notes and
    // any recovered draft are read. A note already open (a draft recovered) stays, with a message.
    LaunchedEffect(startNewNote, notes != null, draftChecked) {
        if (!startNewNote || notes == null || !draftChecked) return@LaunchedEffect
        if (editingId != null) android.widget.Toast.makeText(context, "Close this note first, then add a new one.", android.widget.Toast.LENGTH_LONG).show()
        else { editingNew = true; editingId = UUID.randomUUID().toString() }
        onNewNoteStarted()
    }
    // A share made into a note: it opens like a duplicate, as a new note that is only saved by Save. The share checked
    // that no note was open; one opened meanwhile (a draft recovered) stays, and the share is dropped with a message.
    // N6-5: the page shows all notes then (no notebook, tag, Archive or search), so the note is there once saved.
    LaunchedEffect(newNote, notes != null, draftChecked) {
        val note = newNote ?: return@LaunchedEffect
        if (notes == null || !draftChecked) return@LaunchedEffect
        if (editingId != null) {
            android.widget.Toast.makeText(context, "A note is already open. Close it, then share again.", android.widget.Toast.LENGTH_LONG).show()
            // Shared files it brought go with it, unless something else uses them.
            if (note.attachments.isNotEmpty()) app.appScope.launch { runCatching { app.repository.releaseTaskFiles(note.attachments.map { it.fileName }) } }
        } else { filterKey = "all"; query = ""; pendingCopy = note; copyOfId = null
            editingNew = true; editingId = note.id }
        onNewNoteOpened()
    }
    LaunchedEffect(editingId, waitingNoteId, notes) {
        val waiting = waitingNoteId ?: return@LaunchedEffect
        if (editingId != null || notes == null) return@LaunchedEffect
        waitingNoteId = null
        if (notes.orEmpty().any { it.id == waiting }) { editingNew = false; editingId = waiting }
    }
    // NW-7, ED-10: the way each note was left, kept only for notes there are (deleted, synced away or replaced by a
    // restore, theirs go; one back from Recently deleted opens as a note never left does).
    LaunchedEffect(notes != null) {
        val ids = notes?.mapTo(HashSet()) { it.id } ?: return@LaunchedEffect
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { app.settings.pruneNoteViews(ids) } }
    }
    val pendingUndo by repo.pendingDeletions.collectAsStateWithLifecycle()

    val filterChoices = remember(notebooks, tags) {
        listOf<NoteFilter>(NoteFilter.All) + notebooks.map { NoteFilter.Notebook(it) } + tags.map { NoteFilter.Tag(it) } + NoteFilter.Archive
    }
    val filterCounts = remember(all) { noteFilterCounts(all) }
    fun filterName(choice: NoteFilter) = when (choice) {
        NoteFilter.All -> "All notes"
        is NoteFilter.Notebook -> choice.name
        is NoteFilter.Tag -> "#${choice.name}"
        NoteFilter.Archive -> "Archive"
    }

    // Notes sync: a pass on opening the page (when it's on), and its cloud in the top bar syncs now or opens its settings.
    val sync = app.noteSync
    val syncOn by sync.enabled.collectAsStateWithLifecycle()
    val syncState by sync.state.collectAsStateWithLifecycle()
    var showSync by rememberSaveable { mutableStateOf(false) }
    // Opening the page syncs, unless "Sync changes automatically" is off (then a tap on the cloud does).
    val autoSync by app.settings.autoSync.collectAsStateWithLifecycle()
    LaunchedEffect(syncOn) { if (syncOn && autoSync) sync.request(delayMs = 0) }
    val overlayMenu = remember { OverlayMenuState() }
    // The editor opens over the page.
    Box(Modifier.fillMaxSize()) {
    OverlayMenuScreen(overlayMenu) {
        Scaffold(
            snackbarHost = { SnackbarHost(duplicatedBar) },
            topBar = {
                TopAppBar(
                    // The same heading as Agenda and Calendar.
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            HeadingText("NOTES", modifier = Modifier.weight(1f, fill = false),
                                style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, shrinkToFit = true)
                            NotesSyncCloud(syncOn, syncState.running, syncState.error != null,
                                onSyncNow = { app.appScope.launch { sync.sync() } },
                                // R18-S5: one more pass after this one only if Planner's notes are still out of step by
                                // then (NoteSync.followUp); a change on Nextcloud meanwhile waits for the next check.
                                onWhileSyncing = {
                                    showSyncToast(app, NOTES_SYNCING_MESSAGE)
                                    sync.request(delayMs = 0)
                                },
                                onOpenSettings = { showSync = true })
                        }
                    },
                    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                    actions = {
                        IconButton(onClick = { if (showSearch) closeSearch() else showSearch = true }) {
                            Icon(if (showSearch) Icons.Filled.Close else Icons.Filled.Search,
                                contentDescription = if (showSearch) "Close search" else "Search notes")
                        }
                        IconButton(onClick = { showFilters = !showFilters },
                            modifier = Modifier.semantics { stateDescription = filterName(filter) }) {
                            Icon(if (showFilters) Icons.Filled.Close else FilterIcon,
                                contentDescription = if (showFilters) "Close Show choices" else "Show")
                        }
                        NotesToolbarMenu("Sort", SortIcon, sort.label, com.example.itinerary.data.NoteSort.entries, sort,
                            onSelect = app.settings::setNoteSort) { Text(it.label) }
                        // The ⋮ menu, as on Agenda and Calendar: grid or list (the item names the layout it switches to)
                        // and Settings.
                        OverlayMenuAnchor(title = "More options", items = { close ->
                            // H17-A2: the symbol is hidden from TalkBack, which reads the item's words instead.
                            DropdownMenuItem(text = { Text(if (asList) "Show as grid" else "Show as list") },
                                leadingIcon = { Text(if (asList) "▦" else "☰", style = MaterialTheme.typography.titleLarge,
                                    modifier = Modifier.clearAndSetSemantics {}) },
                                onClick = { close(); app.settings.setNotesAsList(!asList) })
                            DropdownMenuItem(text = { Text("Settings") },
                                leadingIcon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                                onClick = { close(); onOpenSettings() })
                        }) { open ->
                            IconButton(onClick = open) { Icon(Icons.Filled.MoreVert, contentDescription = "More options") }
                        }
                    },
                )
            },
            bottomBar = {
                if (selecting) NoteSelectionBar(displayed.filter { it.id in selectedSet }, displayed, notebooks, onDuplicated = ::duplicated) { selectedIds = it }
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
                if (showSearch) OutlinedTextField(query, { query = it.replace('\n', ' ') },
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp).focusRequester(searchFocus),
                    label = { Text("Search notes") }, singleLine = true,
                    trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, contentDescription = "Clear search") } })
                if (showFilters) NotesShowBox(filterName(filter), filterChoices, filter, onSelect = { showFilter(it.key()) },
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = if (showSearch) 8.dp else 0.dp)) { choice ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(filterName(choice), Modifier.weight(1f))
                        Text((filterCounts[choice] ?: 0).toString(), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    // Screen readers can't drag: in My order a card offers Move earlier / Move later instead.
                    val placeOf = remember(displayed) { displayed.withIndex().associate { it.value.id to it.index } }
                    fun canMove(index: Int, by: Int) = displayed.getOrNull(index + by)?.pinned == displayed.getOrNull(index)?.pinned
                    LazyStaggeredScrollHints(grid, Modifier.fillMaxSize()) {
                    LazyVerticalStaggeredGrid(
                        columns = if (asList) StaggeredGridCells.Fixed(1) else StaggeredGridCells.Adaptive(160.dp * textScale),
                        state = grid,
                        modifier = Modifier.fillMaxSize(),
                        // Room under the last cards for the New note button.
                        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 96.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalItemSpacing = 10.dp,
                    ) {
                        items(displayed, key = { it.id }) { note ->
                            val index = placeOf[note.id] ?: 0
                            val moves = if (!canDrag || sort != com.example.itinerary.data.NoteSort.MY_ORDER) emptyList() else listOfNotNull(
                                androidx.compose.ui.semantics.CustomAccessibilityAction("Move earlier") {
                                    Notes.moved(currentDisplayed, note.id, -1)?.let(::place) != null }.takeIf { canMove(index, -1) },
                                androidx.compose.ui.semantics.CustomAccessibilityAction("Move later") {
                                    Notes.moved(currentDisplayed, note.id, 1)?.let(::place) != null }.takeIf { canMove(index, 1) })
                            NoteCard(note, selecting = selecting, selected = note.id in selectedSet, moves = moves,
                                // The dragged card follows the finger, not the grid's slide animation.
                                modifier = (if (drag.id == note.id) Modifier else Modifier.animateItem()).noteLongPress(note, drag, grid, { currentDisplayed }, { currentCanDrag }, slop, edge,
                                    onSelect = { toggle(note.id) }, onDrop = ::place,
                                    haptic = haptic, scroll = { by -> scope.launch { grid.scrollBy(by) } }),
                                onOpen = {
                                    // The touch that ended a long press isn't also a tap. The card's click sees the lift first,
                                    // while the long press is still on (drag.id), so both are checked.
                                    if (drag.id != null || android.os.SystemClock.uptimeMillis() - drag.releasedAt < 400) Unit
                                    else if (selecting) toggle(note.id) else { editingNew = false; editingId = note.id }
                                },
                                onSelect = { toggle(note.id) }, onDuplicated = ::duplicated)
                        }
                    }
                    }
                    }
                }
            }
        }
    }
    if (showSync) NoteSyncDialog { showSync = false }
    // Not before the notes and any draft are read: reopened after Android closed Planner, the editor would otherwise take
    // a note already saved for a new one (and every Save would fail), or open without its draft.
    if (notes != null && draftChecked && draftReadFor == editingId) editingId?.let { id ->
        val existing = all.firstOrNull { it.id == id }
        if (existing != null) lastSeen[id] = existing
        // Saved once: no longer new, should the page be rebuilt (rotation, or Android closing Planner).
        LaunchedEffect(id, existing != null) { if (existing != null) editingNew = false }
        val draft = recovered?.takeIf { it.note.id == id }
        // A new note starts in the notebook being looked at.
        val start = existing ?: lastSeen[id] ?: draft?.note ?: pendingCopy?.takeIf { it.id == id } ?: if (editingNew) PlannerNote(id = id, notebook = (filter as? NoteFilter.Notebook)?.name.orEmpty(),
            tags = listOfNotNull((filter as? NoteFilter.Tag)?.name)) else null
        if (start != null) key(id) {
            NoteEditor(start, creating = editingNew && existing == null && lastSeen[id] == null, notebooks = notebooks, allTags = tags,
                recovered = draft, copyOf = copyOfId.takeIf { pendingCopy?.id == id }, editorKey = editorKey,
                // Duplicate note: the editor goes, and a new one opens on the copy (not saved yet), as in the task editor.
                onDuplicate = { copy -> pendingCopy = copy.copy(position = Notes.copyPosition(existing ?: start, all)); copyOfId = id
                    editingNew = true; editingId = copy.id }) {
                editingId = null; editingNew = false; recovered = null; lastSeen.remove(id)
                // NT-1: the next editor is a new one, and its note's draft is read again.
                editorKey = UUID.randomUUID().toString(); draftReadFor = null }
        } else LaunchedEffect(id) { editingId = null; draftReadFor = null }
    }
    }
}

// Toolbar choices share the same menu, selected indication and scroll bar.
@Composable
private fun <T> NotesToolbarMenu(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector,
    currentLabel: String, choices: List<T>, current: T, onSelect: (T) -> Unit,
    choiceText: @Composable (T) -> Unit) {
    Box {
        var open by remember { mutableStateOf(false) }
        val scroll = rememberScrollState()
        IconButton(onClick = { open = true }, modifier = Modifier.semantics { stateDescription = currentLabel }) {
            Icon(icon, contentDescription = label)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, scrollState = scroll,
            modifier = Modifier.heightIn(max = MENU_MAX_HEIGHT).scrollBar(scroll, inset = 8.dp)) {
            choices.forEach { choice ->
                DropdownMenuItem(text = { choiceText(choice) },
                    leadingIcon = { if (current == choice) Icon(Icons.Filled.Check, contentDescription = "Selected") },
                    modifier = Modifier.semantics { selected = current == choice },
                    onClick = { onSelect(choice); open = false })
            }
        }
    }
}

// Notes sync at a glance, tapped like the cloud on Agenda and Calendar: a tap syncs now while all is well and says a sync
// is running while one runs (A4: it used to do nothing); with sync off or a problem it opens the sync settings, as a long
// press always does.
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun NotesSyncCloud(on: Boolean, running: Boolean, problem: Boolean, onSyncNow: () -> Unit, onWhileSyncing: () -> Unit,
    onOpenSettings: () -> Unit) {
    val label = when {
        !on -> "Notes sync: off"
        running -> "Notes sync: syncing"
        problem -> "Notes sync: problem"
        else -> "Notes sync: up to date"
    }
    val synced = on && !running && !problem
    Box(
        Modifier.size(48.dp).clip(androidx.compose.foundation.shape.CircleShape)
            .combinedClickable(
                // R18-S4: turned off while a pass still runs, it's "off": a tap opens the settings, not "syncing".
                onClick = { if (synced) onSyncNow() else if (on && running) onWhileSyncing() else onOpenSettings() },
                onClickLabel = if (synced) "Sync now" else if (on && running) "Show sync status" else "Open sync settings",
                onLongClick = onOpenSettings, onLongClickLabel = "Open sync settings",
                role = androidx.compose.ui.semantics.Role.Button),
        contentAlignment = Alignment.Center,
    ) {
        when {
            !on -> SyncCloud(CloudLook.STRUCK, MaterialTheme.colorScheme.onSurfaceVariant, label)
            running -> SyncCloud(CloudLook.RAINING, MaterialTheme.colorScheme.primary, label)
            problem -> SyncCloud(CloudLook.STRUCK, MaterialTheme.colorScheme.error, label)
            else -> SyncCloud(CloudLook.SYNCED, MaterialTheme.colorScheme.primary, label)
        }
    }
}

// The Show choices as a box like the search field: it names the current choice, and tapping it drops the list down
// under it, as wide as the box.
@Composable
private fun <T> NotesShowBox(currentLabel: String, choices: List<T>, current: T, onSelect: (T) -> Unit,
    modifier: Modifier = Modifier, choiceText: @Composable (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    BoxWithConstraints(modifier.fillMaxWidth()) {
        // H17-A5: one TalkBack stop, the drop-down below; the box itself is only what it looks like.
        OutlinedTextField(currentLabel, {}, Modifier.fillMaxWidth().clearAndSetSemantics {}, readOnly = true, singleLine = true,
            label = { Text("Show") }, trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) })
        // Over the box, so a tap anywhere on it opens the list rather than placing a cursor.
        Box(Modifier.matchParentSize().padding(top = 8.dp)
            .semantics { contentDescription = "Choose what to show"; stateDescription = currentLabel }
            .clickable(role = androidx.compose.ui.semantics.Role.DropdownList) { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, scrollState = scroll,
            modifier = Modifier.width(maxWidth).heightIn(max = MENU_MAX_HEIGHT).scrollBar(scroll, inset = 8.dp)) {
            choices.forEach { choice ->
                DropdownMenuItem(text = { choiceText(choice) },
                    leadingIcon = { if (current == choice) Icon(Icons.Filled.Check, contentDescription = "Selected") },
                    modifier = Modifier.semantics { selected = current == choice },
                    onClick = { onSelect(choice); open = false })
            }
        }
    }
}

// What a note card shows from its text, from one Markdown parse per change to it (UI-4). The preview is kept whole:
// the card shows 8 lines, but TalkBack reads all of it.
internal class NoteCardText(val label: String, val preview: String, val done: Int, val total: Int)
internal fun noteCardText(note: PlannerNote): NoteCardText {
    val blocks = Markdown.parse(note.content)
    val plain = Markdown.plain(blocks)
    val body = plain.lines().filter { it.isNotBlank() }
    val (done, total) = Markdown.checklist(blocks)
    // The first line is the name when there is no title; the preview goes on from the next.
    return NoteCardText(Notes.label(note) { plain }, (if (note.title.isBlank()) body.drop(1) else body).joinToString("\n"), done, total)
}

// How many notes each Show choice lists (Notes.visible with no search): archived notes count only under Archive.
internal fun noteFilterCounts(notes: List<PlannerNote>): Map<NoteFilter, Int> {
    val counts = HashMap<NoteFilter, Int>()
    fun add(filter: NoteFilter) { counts[filter] = (counts[filter] ?: 0) + 1 }
    notes.forEach { note ->
        if (note.archived) add(NoteFilter.Archive)
        else {
            add(NoteFilter.All)
            add(NoteFilter.Notebook(note.notebook))
            // A tag given twice still counts the note once.
            note.tags.distinct().forEach { add(NoteFilter.Tag(it)) }
        }
    }
    return counts
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun NoteCard(note: PlannerNote, selecting: Boolean, selected: Boolean, modifier: Modifier, onOpen: () -> Unit, onSelect: () -> Unit,
                     moves: List<androidx.compose.ui.semantics.CustomAccessibilityAction> = emptyList(), onDuplicated: (List<PlannerNote>) -> Unit = {}) {
    val tint = note.color?.let { Color(it) }
    val text = tint?.let(::onColour) ?: MaterialTheme.colorScheme.onSurface
    val soft = if (tint != null) text.copy(alpha = 0.9f) else MaterialTheme.colorScheme.onSurfaceVariant
    val card = remember(note.title, note.content) { noteCardText(note) }
    val label = card.label
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = tint ?: MaterialTheme.colorScheme.surfaceContainer,
        border = if (selected) BorderStroke(3.dp, MaterialTheme.colorScheme.primary)
            else BorderStroke(1.dp, if (tint != null) tint else MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.fillMaxWidth()
            .combinedClickable(onClickLabel = if (selecting) (if (selected) "Deselect note" else "Select note") else "Open note", onClick = onOpen)
            // The long press itself is the card's own gesture (select, or drag); this offers selecting to screen readers.
            .semantics { onLongClick("Select note") { onSelect(); true }; if (selecting) this.selected = selected
                if (moves.isNotEmpty()) customActions = moves },
    ) {
        Column(Modifier.padding(start = 14.dp, top = 8.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(label, Modifier.weight(1f).padding(top = 6.dp), style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold, color = text, maxLines = 3, overflow = TextOverflow.Ellipsis)
                if (note.pinned) Icon(Icons.Filled.Star, contentDescription = "Pinned", tint = if (tint != null) text else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp).size(18.dp))
                if (selecting) Checkbox(checked = selected, onCheckedChange = null, modifier = Modifier.padding(4.dp))
                else NoteActionsMenu(note, label, text, onDuplicated)
            }
            Column(Modifier.padding(end = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val preview = card.preview
                if (preview.isNotBlank()) Text(preview, style = MaterialTheme.typography.bodyMedium, color = soft, maxLines = 8, overflow = TextOverflow.Ellipsis)
                if (note.priority != com.example.itinerary.data.TaskPriority.NORMAL) Surface(
                    color = if (note.priority == com.example.itinerary.data.TaskPriority.HIGH) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(6.dp)) {
                    Text("${note.priority.label} importance", Modifier.padding(horizontal = 8.dp, vertical = 3.dp), style = MaterialTheme.typography.labelMedium)
                }
                if (card.total > 0) Text("☑ ${card.done} of ${card.total} done", style = MaterialTheme.typography.labelMedium, color = text)
                if (note.notebook.isNotBlank()) Text(note.notebook, style = MaterialTheme.typography.labelMedium, color = soft,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (note.tags.isNotEmpty()) Text(note.tags.joinToString(" ") { "#$it" }, style = MaterialTheme.typography.labelMedium,
                    color = soft, maxLines = 2, overflow = TextOverflow.Ellipsis)
                note.reminderAt?.let { at ->
                    val due = momentLabel(note.snoozedUntil ?: at)
                    Text("⏰ $due", style = MaterialTheme.typography.labelMedium, color = text, modifier = Modifier.semantics { contentDescription = "Reminder, $due" })
                }
                if (note.attachments.isNotEmpty()) Text("📎 ${note.attachments.size}", style = MaterialTheme.typography.labelMedium, color = soft,
                    modifier = Modifier.semantics { contentDescription = "${note.attachments.size} attachment${if (note.attachments.size == 1) "" else "s"}" })
            }
        }
    }
}

// A note card's ⋮: Pin / Unpin, Archive / Unarchive, Duplicate, and Delete (to Recently deleted, with the Undo bar).
@Composable
private fun NoteActionsMenu(note: PlannerNote, label: String, tint: Color, onDuplicated: (List<PlannerNote>) -> Unit) {
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
        DropdownMenuItem(text = { Text("Duplicate") }, onClick = {
            close(); act("Couldn't duplicate this note. Please try again.") {
                val copies = app.repository.duplicateNotes(listOf(note.id))
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { onDuplicated(copies) } } })
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
        SwitchRow("Sync notes", on, { want -> app.appScope.launch { sync.setEnabled(want) } }, enabled = signedIn == true || on,
            style = MaterialTheme.typography.bodyLarge)
        if (on) {
            val message = when {
                state.running -> "Syncing…"
                state.error != null -> state.error!!
                state.lastSynced != null -> "Synced " + momentLabel(state.lastSynced!!, short = true) + syncCounts(state.conflicts, state.skipped)
                else -> "Not synced yet."
            }
            Text(message, style = MaterialTheme.typography.bodyMedium,
                color = if (state.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.keptRemote > 0) Text("${state.keptRemote} deletion${if (state.keptRemote == 1) "" else "s"} waiting: Nextcloud's note files couldn't be verified safely. " +
                "Try syncing again, or delete those notes in Nextcloud.", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

// After "Synced …": the conflict copies made and the notes left as they are, each only when there are some (N6-2: the
// second was lost whenever there was a first).
internal fun syncCounts(conflicts: Int, skipped: Int): String = listOfNotNull(
    if (conflicts > 0) "$conflicts conflict cop${if (conflicts == 1) "y" else "ies"} made (changed in both places)" else null,
    if (skipped > 0) "$skipped note${if (skipped == 1) "" else "s"} left as they are (text or notebook doesn't fit Planner, or Nextcloud wouldn't take the change)" else null,
).joinToString("") { " · $it" }

/** White or black, whichever reads better on [background] (4.5:1 or more on all the card colours): a card's text and marks. */
internal fun onColour(background: Color): Color =
    if (contrastRatio(Color.White, background) >= contrastRatio(Color.Black, background)) Color.White else Color.Black

/** NX-1: the `changes` value a Notes page last looked for drafts at. A different one means a look is due. */
internal class DraftLooks(start: Int) {
    var at = start; private set
    fun due(now: Int) = now != at
    fun looked(now: Int) { at = now }
}

/**
 * NT-2: a Notes page looks for drafts again when another editor lets its note go or a window goes ([changes]). NX-1: a
 * change that comes before the page's first look is done ([checked] waits for it) is not dropped, and one that comes
 * during a look ([look] notes the value it starts at in [looks]) makes another. Only with no note open ([canLook]).
 */
internal suspend fun lookOnChanges(changes: kotlinx.coroutines.flow.StateFlow<Int>, looks: DraftLooks, checked: suspend () -> Unit,
                                   canLook: () -> Boolean, look: suspend () -> Unit) {
    changes.collect { now ->
        if (!looks.due(now)) return@collect
        checked()
        if (canLook() && looks.due(changes.value)) look()
    }
}
