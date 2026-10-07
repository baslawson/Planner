package com.example.itinerary.ui
import com.example.itinerary.ui.MatrixOutlinedButton as OutlinedButton
import com.example.itinerary.ui.MatrixFilterChip as FilterChip

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.itinerary.ItineraryApp
import com.example.itinerary.data.Attachment
import com.example.itinerary.data.NoteDraftStore
import com.example.itinerary.data.Markdown
import com.example.itinerary.data.Notes
import com.example.itinerary.data.PlannerNote
import com.example.itinerary.data.snoozedAt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal val NOTE_COLOR_NAMES = listOf("Green", "Teal", "Blue", "Purple", "Pink", "Red", "Orange", "Brown")

/**
 * Each open note editor's body, attachments and starting version, kept in memory rather than in the saved-instance
 * Bundle: a long note with recognised text could pass the Bundle's size limit (TransactionTooLargeException). They
 * outlive a rotation here; when Android closes Planner they go, and the on-disk draft (NoteDraftStore) has them.
 * One per editor, by a key kept in its Bundle, so only that editor, recreated, finds them again: each Planner window can
 * have a note editor (N6-1). One note has one editor, so a new one for a note replaces what an old one left.
 */
internal object NoteEditorMemory {
    class Body(content: TextFieldValue, attachments: List<Attachment>, base: PlannerNote?) {
        var content by mutableStateOf(content)
        var attachments by mutableStateOf(attachments)
        var base by mutableStateOf(base)
    }
    private class Kept(val noteId: String, val body: Body)
    // An editor whose window went without closing it never forgets its own: the oldest go past a few.
    private const val MAX = 8
    private val kept = LinkedHashMap<String, Kept>()
    // NW-4: the editors on screen now. Theirs never go past the few: a long-open editor would lose its body at the next
    // rotation, and its draft with it.
    private val live = HashSet<String>()

    fun keep(key: String, noteId: String, body: Body) {
        kept.values.removeAll { it.noteId == noteId }
        kept[key] = Kept(noteId, body)
        while (kept.size > MAX) kept.remove(kept.keys.firstOrNull { it !in live } ?: break)
    }
    fun opened(key: String) { live += key }
    fun closed(key: String) { live -= key }
    // NT-1: by key, not by note: an editor another window left behind (its task swiped away) isn't this page's.
    fun restore(key: String, noteId: String): Body? = kept[key]?.takeIf { it.noteId == noteId }?.body
    fun forget(key: String) { kept.remove(key) }
    // What Android closing Planner does to it; also for tests.
    fun forgetAll() { kept.clear(); live.clear() }
}

/**
 * A note, full screen: title, notebook, colour and pin, then the body in Markdown — written in Edit, read (and its
 * checklist ticked) in Preview — with the editors' Delete · Close · Save bar. A new note opens in Edit, a saved one in
 * Preview. Save keeps the editor open ("Saved"); Close with changes asks first.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NoteEditor(initial: PlannerNote, creating: Boolean, notebooks: List<String>, allTags: List<String> = emptyList(),
               recovered: NoteDraftStore.Draft? = null, onDuplicate: ((PlannerNote) -> Unit)? = null, copyOf: String? = null,
               editorKey: String? = null, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as ItineraryApp
    val repo = app.repository
    val scope = rememberCoroutineScope()
    val draftStore = remember { NoteDraftStore(context) }
    // N6-1: one editor per note. A second one (the note is open in another Planner window) says so instead of opening
    // on the same draft, where its Discard would clear the first one's.
    val claim = remember { NoteEditorClaim(initial.id) }
    if (!claim.owner) {
        PlannerDialog("Note already open", onDismissRequest = onDismiss, dismiss = DialogAction("Close", onClick = onDismiss)) {
            Text("This note is open in another Planner window. Save or close it there first.")
        }
        return
    }
    // What the fields start from: a recovered draft (Android closed Planner mid-edit), else the note.
    val start = recovered?.note ?: initial
    // The body, attachments and base live in NoteEditorMemory, not in the saved state (see there). NT-1: under the key the
    // Notes page gave, so the page knows its own editor's state from one another window left.
    val ownKey = rememberSaveable { java.util.UUID.randomUUID().toString() }
    val memoryKey = editorKey ?: ownKey
    val body = remember { NoteEditorMemory.restore(memoryKey, initial.id) ?: NoteEditorMemory.Body(TextFieldValue(start.content), start.attachments,
        if (recovered != null) recovered.base else if (creating) null else initial).also { NoteEditorMemory.keep(memoryKey, initial.id, it) } }
    DisposableEffect(memoryKey) { NoteEditorMemory.opened(memoryKey); onDispose { NoteEditorMemory.closed(memoryKey) } }
    // The stored version these edits started from (null: a new note not saved yet). Save checks it's still current.
    var base by body::base
    // Auto title: while Title is left empty (or still is the first line), it shows and saves the note's first line, and
    // follows it as that line changes. A title typed in stops that; deleting the title leaves the note without one.
    // A title deleted on purpose stays empty: that note has no auto title (remembered by id on this phone).
    fun autoTitleFor(note: PlannerNote) = note.id !in app.settings.noAutoTitle.value && (note.title.isBlank() || note.title == Notes.firstLine(note.content))
    // While the title is automatic [title] is "" (not a stale one), so Undo of a first typed title brings the auto title back.
    var title by rememberSaveable { mutableStateOf(if (autoTitleFor(start)) "" else start.title) }
    var content by body::content
    var autoTitle by rememberSaveable { mutableStateOf(autoTitleFor(start)) }
    val shownTitle = if (autoTitle) Notes.firstLine(content.text) else title
    var notebook by rememberSaveable { mutableStateOf(start.notebook) }
    // Undo and Redo of the typing in this note (wish list #2); the body's cursor goes where the change was.
    val undo = rememberEditorUndo()
    Track(undo, "title", title) { restored ->
        title = restored
        // Back to a title of its own, or (empty) to the auto title, unless the title was deleted on purpose.
        if (restored.isNotBlank()) { autoTitle = false; app.settings.setNoAutoTitle(initial.id, false) }
        else autoTitle = initial.id !in app.settings.noAutoTitle.value
    }
    Track(undo, "body", content.text) { text -> content = TextFieldValue(text, androidx.compose.ui.text.TextRange(undoCursor(content.text, text))) }
    Track(undo, "notebook", notebook) { notebook = it }
    var color by rememberSaveable { mutableStateOf(start.color) }
    var pickingColor by rememberSaveable { mutableStateOf(false) }
    var priority by rememberSaveable { mutableStateOf(start.priority) }
    var pinned by rememberSaveable { mutableStateOf(start.pinned) }
    var tags by rememberSaveable { mutableStateOf(start.tags) }
    var newTag by rememberSaveable { mutableStateOf("") }
    // The files themselves are already in the store.
    val attachments by body::attachments
    fun setAttachments(list: List<Attachment>) { body.attachments = list }
    var pendingPhoto by rememberSaveable { mutableStateOf(recovered?.pendingPhoto) }
    // U15-1: no reminder, no ringing, whatever an earlier build stored.
    var ringUntilDismissed by rememberSaveable { mutableStateOf(start.ringUntilDismissed && start.reminderAt != null) }
    // The reminder's sound otherwise (ReminderSound): 0 = Default.
    var ringSeconds by rememberSaveable { mutableStateOf(com.example.itinerary.data.ReminderSound.cleanSeconds(start.ringUntilDismissed, start.ringSeconds, start.reminderAt != null)) }
    var reminderAt by rememberSaveable { mutableStateOf(start.reminderAt) }
    var choosingReminderDate by rememberSaveable { mutableStateOf(false) }
    var reminderDateDraft by rememberSaveable { mutableStateOf<String?>(null) }
    var choosingReminderTime by rememberSaveable { mutableStateOf(false) }
    // Edit or Preview: as Settings → Notes says, by default the way this note was last left.
    var preview by rememberSaveable { mutableStateOf(app.settings.noteOpenView.value.opensInPreview(creating, recovered != null,
        initial.content.isNotBlank(), app.settings.noteLeftInPreview(initial.id))) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(if (recovered != null) "Recovered changes that weren't saved last time." else null) }
    var askingToSave by rememberSaveable { mutableStateOf(false) }
    // RS-1: Delete asks first when typing would be lost with it.
    var askingToDelete by rememberSaveable { mutableStateOf(false) }
    var justSaved by remember { mutableStateOf(false) }
    // Changed elsewhere in the same place as here: how it is now, while the user chooses.
    var conflict by remember { mutableStateOf<PlannerNote?>(null) }
    // EU-1, EU-2: whether loading [note] changes what is typed in the fields Undo follows. NO-2: as stored, so a body
    // ending in a new line or a notebook in other capitals isn't a change (spelt as [current] spells it).
    fun wordsDiffer(note: PlannerNote) =
        noteWordsDiffer(note, shownTitle, content.text, Notes.existingSpelling(notebooks, Notes.cleanNotebook(notebook)))
    // NO-2: [words] false leaves the typed fields (and the cursor) as they are: the stored words are the same once cleaned.
    fun load(note: PlannerNote, words: Boolean = true) {
        if (words) { autoTitle = autoTitleFor(note); title = if (autoTitle) "" else note.title; content = TextFieldValue(note.content, TextRange(note.content.length)); notebook = note.notebook }
        color = note.color
        pinned = note.pinned; tags = note.tags; setAttachments(note.attachments); reminderAt = note.reminderAt; ringUntilDismissed = note.ringUntilDismissed && note.reminderAt != null
        ringSeconds = com.example.itinerary.data.ReminderSound.cleanSeconds(note.ringUntilDismissed, note.ringSeconds, note.reminderAt != null); priority = note.priority
    }
    // A notebook typed in other capitals goes into the existing one ("home" → "Home").
    val current = (base ?: start).copy(title = shownTitle, content = content.text, notebook = Notes.existingSpelling(notebooks, Notes.cleanNotebook(notebook)), color = color, pinned = pinned,
        tags = tags, attachments = attachments, reminderAt = reminderAt, ringUntilDismissed = ringUntilDismissed, ringSeconds = ringSeconds, priority = priority)
    // The stored version cleaned once, not again on every letter typed (UI-10).
    val cleanBase = remember(base) { base?.let(Notes::clean) }
    // An auto title alone isn't a change: opening a saved note with no title (one from Nextcloud, say) doesn't save it.
    // It is saved with the first change made to the note.
    val unsaved = cleanBase?.let { stored ->
        Notes.clean(if (autoTitle && stored.title.isBlank()) current.copy(title = stored.title) else current) != stored
    } ?: Notes.hasContent(current)
    // As stored now: a change made elsewhere (sync, a reminder's Done) shows here at once while nothing is edited.
    val continueLists by app.settings.continueLists.collectAsStateWithLifecycle()
    val stored by remember(initial.id) { repo.observeNote(initial.id) }.collectAsStateWithLifecycle(initialValue = base)
    // Once seen in the database: a just-saved new note isn't "deleted" for the moment before its first reading arrives.
    var seenStored by remember { mutableStateOf(base != null) }
    LaunchedEffect(stored) {
        val now = stored
        if (now != null) {
            seenStored = true
            // Saved already but not known here as saved (a draft recovered after Android closed Planner just after the
            // note's first save, or a save that finished as the screen was rebuilt): it goes on as that note, so saving
            // updates it instead of making it again (which failed for good). What is typed stays.
            if (base == null) base = now
            // Not a step to undo: that would put back the text from before, over the other device's change (ED-7). EU-2: and
            // only when the words changed: a snooze, a pin or a sync's new time leaves the history as it is.
            if (base != null && now != base && !unsaved) {
                val words = wordsDiffer(now)
                if (words) undo.reload()
                load(now, words); base = now
            }
        }
    }
    val deletedElsewhere = base != null && seenStored && stored == null
    val canSave = !busy && Notes.hasContent(current)
    val attachmentStore = app.attachmentStore
    // Counted as an open editor (a widget day in this window waits for it), and its state kept on disk shortly after
    // each change.
    val windowEditors = LocalWindowEditors.current
    DisposableEffect(Unit) {
        NoteDraftStore.editorOpened(); windowEditors?.let { it.notes++ }
        onDispose { NoteDraftStore.editorClosed(); windowEditors?.let { it.notes-- } }
    }
    // E5-5: the draft goes to the shared draft writer (on its thread, in order with clear), from the main thread: once
    // the editor has closed (Discard, Save and close, Delete) nothing more is sent, and clear drops what was waiting.
    val closed = remember { booleanArrayOf(false) }
    val draftError = "Couldn't keep unsaved changes on this device."
    // NW-1: a draft this editor neither wrote nor started from (another window's, left behind) isn't its to clear for
    // having nothing typed yet. NW-5: each draft names its window, which reopens it after Android closed Planner.
    var ownsDraft by rememberSaveable { mutableStateOf(recovered != null) }
    fun keepDraft() {
        if (closed[0]) return
        runCatching {
            if (unsaved || pendingPhoto != null) {
                draftStore.schedule(NoteDraftStore.Draft(current, base == null, base, pendingPhoto, windowEditors?.id)) {
                    android.os.Handler(android.os.Looper.getMainLooper()).post { if (!closed[0]) error = draftError }
                }
                ownsDraft = true
            } else if (ownsDraft) draftStore.clear(initial.id)
        }
    }
    // ED-10: the way the note was left, recorded as it closes (not only a tap on Edit or Preview), once it is saved.
    val leftPreview by rememberUpdatedState(preview)
    val leftSaved by rememberUpdatedState(base != null)
    DisposableEffect(Unit) { onDispose { if (leftSaved) app.settings.setNoteLeftInPreview(initial.id, leftPreview) } }
    LaunchedEffect(current, base, pendingPhoto, unsaved) {
        kotlinx.coroutines.delay(400)
        keepDraft()
    }
    // Leaving or going to the background: what is typed is on disk before Android may close Planner.
    // Auto save on leaving too (set below, once autoSave is defined).
    val autoSaveNow = remember { arrayOf<() -> Unit>({}) }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_STOP) { keepDraft(); runCatching { draftStore.flush() }; autoSaveNow[0]() }
    DisposableEffect(Unit) { onDispose { runCatching { draftStore.flush() } } }
    // Files this editor has held: once it saves, discards or deletes, those nothing uses any more are removed (one still
    // in a saved note, Recently deleted or elsewhere stays). The draft is cleared first, here, so no write lands after it.
    // Every file this editor has held, also one attached, auto saved and then taken off (its removal couldn't release it
    // while the saved note still named it), so Close can release it once nothing uses it.
    val heldFiles = remember { mutableSetOf<String>() }
    SideEffect { (attachments + base?.attachments.orEmpty()).forEach { heldFiles += it.fileName } }
    fun releaseFiles() {
        val files = (initial.attachments + start.attachments + attachments + base?.attachments.orEmpty()).map { it.fileName } + heldFiles + listOfNotNull(pendingPhoto)
        // NT-1: only a draft this editor wrote or started from; another one of this note (another window's) stays.
        if (ownsDraft) runCatching { draftStore.clear(initial.id) }
        app.appScope.launch { runCatching { repo.releaseTaskFiles(files) } }
    }
    val pickFile = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            busy = true
            scope.launch {
                try {
                    val imported = attachmentStore.import(uri)
                    if (imported != null) setAttachments(attachments + imported) else error = "Couldn't attach this file. Please try again."
                } finally { busy = false }
            }
        }
    }
    val takePhoto = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.TakePicture()) { success ->
        val name = pendingPhoto
        if (name != null) {
            if (success) setAttachments(attachments + Attachment(itemId = 0, name = "Note photo.jpg", fileName = name, mimeType = "image/jpeg"))
            else attachmentStore.delete(name)
        }
        pendingPhoto = null
    }
    // SH-9: a photo still being taken when this editor was last open (Android closed Planner with the camera open): its
    // result has come (above, as the launcher is set up) or never will. Attached if it was taken, so Take photo isn't
    // left waiting for it.
    LaunchedEffect(Unit) {
        val name = pendingPhoto ?: return@LaunchedEffect
        pendingPhoto = null
        leftoverPhoto(attachmentStore.fileFor(name), "Note photo.jpg", attachments)?.let { setAttachments(attachments + it) }
    }
    fun addTag() {
        // A tag that exists in other capitals is that tag ("Errands" adds #errands).
        val tag = Notes.existingSpelling(allTags, Notes.cleanTag(newTag))
        if (tag.isNotEmpty() && tags.none { it.equals(tag, ignoreCase = true) } && tags.size < Notes.MAX_TAGS) tags = tags + tag
        newTag = ""
    }
    LaunchedEffect(unsaved) { if (unsaved) justSaved = false }

    fun saved(note: PlannerNote, then: () -> Unit, release: Boolean = true) {
        // The fields change only if saving changed them (a merge, trimmed text), so typing isn't disturbed otherwise.
        val edited = Notes.clean(current)
        val same = note.title == edited.title && note.content == edited.content && note.notebook == edited.notebook && note.color == edited.color && note.priority == edited.priority &&
            note.pinned == edited.pinned && note.tags == edited.tags && note.attachments == edited.attachments && note.reminderAt == edited.reminderAt && note.ringUntilDismissed == edited.ringUntilDismissed &&
            note.ringSeconds == edited.ringSeconds
        // EU-1: a merge brings in another device's words: loaded as no step, so Undo can't take them back unseen.
        base = note; if (!same) { val words = noteWordsDiffer(note, edited.title, edited.content, edited.notebook); if (words) undo.reload(); load(note, words) }; justSaved = true
        // An auto save keeps the files: the editor is still open and may use them (Close releases them).
        if (release) releaseFiles()
        then()
    }
    // [note] written over how it is now ([latest]): the user chose their version.
    fun saveOver(note: PlannerNote, latest: PlannerNote, then: () -> Unit = {}) {
        busy = true; error = null
        scope.launch {
            try { saved(repo.saveNote(note.copy(snoozedUntil = latest.snoozedUntil), create = false, expected = latest), then) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { error = "Couldn't save this note. Please try again." }
            finally { busy = false }
        }
    }
    // Auto save's state (see autoSave below): one under way, another wanted after it, and a Close waiting for it.
    var autoSaving by remember { mutableStateOf(false) }
    var autoAgain by remember { mutableStateOf(0) }
    var closeAfterAutoSave by remember { mutableStateOf(false) }
    // Discard of a new note while its first auto save is under way: once that is done, the note it made goes too.
    var discardAfterAutoSave by remember { mutableStateOf(false) }
    // Whether the Save button reads out its state (after a tap on it; not for auto saves).
    var announceSave by remember { mutableStateOf(false) }
    // A Save tapped during an auto save, with what was to follow it: run once that is done.
    var saveAfterAutoSave by remember { mutableStateOf<(() -> Unit)?>(null) }
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    fun save(then: () -> Unit = {}) {
        // An auto save under way finishes first (two at once could make the new note twice); this Save then follows it
        // (hunt 21 N3: it used to do nothing, silently).
        if (busy) return
        if (autoSaving) { saveAfterAutoSave = then; announceSave = true; return }
        announceSave = true
        // A reminder set or changed here must be ahead; one already saved that has rung can stay as it is.
        if (reminderAt != null && reminderAt != base?.reminderAt && reminderAt!! <= System.currentTimeMillis()) {
            error = "Choose a future reminder date and time."; return
        }
        busy = true; error = null
        scope.launch {
            try {
                // Deleted elsewhere: saved again as it is here (a new note), so nothing typed is lost.
                saved(repo.saveNote(current, create = base == null || deletedElsewhere, expected = base, after = copyOf.takeIf { base == null }), then)
            } catch (e: CancellationException) { throw e }
            catch (e: com.example.itinerary.data.NoteChangedException) {
                // Changed elsewhere meanwhile: merged when the two touched different things, else the user chooses.
                val merged = base?.let { com.example.itinerary.data.mergeNotes(it, current, e.latest) }
                if (merged != null) {
                    try { saved(repo.saveNote(merged, create = false, expected = e.latest), then); error = "Merged with a change made elsewhere." }
                    catch (e2: CancellationException) { throw e2 }
                    catch (_: Exception) { conflict = repo.note(current.id) ?: e.latest }
                } else conflict = e.latest
            }
            catch (_: Exception) { error = "Couldn't save this note. Please try again." }
            finally { busy = false }
        }
    }
    // Closed for good (not rotated): what the memory kept for it goes.
    fun dismiss() { closed[0] = true; NoteEditorMemory.forget(memoryKey); onDismiss() }
    fun discard() { releaseFiles(); dismiss() }
    // Closing saves (auto save: no "Save changes?"). It still asks when the note was deleted elsewhere, or everything in
    // a saved note was cleared (nothing to save), as those are choices.
    fun closeNow() {
        when {
            !unsaved -> discard()
            // A conflict being chosen already has its own dialog (E4): Close waits for that choice.
            conflict != null -> {}
            deletedElsewhere || !Notes.hasContent(current) -> askingToSave = true
            else -> save { dismiss() }
        }
    }
    // Closed during an auto save: the fields lock (as with Save) and it closes once that is done.
    fun close() { if (autoSaving) { closeAfterAutoSave = true; busy = true } else closeNow() }
    // Auto save: about a second after a change stops (and on leaving), in the background. Unlike Save it doesn't lock the
    // fields, so typing goes on; what is typed meanwhile goes in the next one. Not when the note was deleted elsewhere
    // or a conflict is being chosen (those still ask), nor while a photo is being taken.
    val latest by rememberUpdatedState(current)
    val latestUnsaved by rememberUpdatedState(unsaved)
    // [theirs] (a merge with a change made elsewhere) into the fields [mine] (what was sent) didn't change since: [now].
    fun takeTheirs(theirs: PlannerNote, mine: PlannerNote, now: PlannerNote) {
        var words = false
        // Hunt 21 N1: also while the title is automatic: a title given elsewhere comes in (and stops it being automatic,
        // unless it is the first line), or the next save would write the first line back over it.
        if (now.title == mine.title && theirs.title != mine.title) {
            autoTitle = autoTitleFor(theirs); title = if (autoTitle) "" else theirs.title; words = true
        }
        if (now.content == mine.content && theirs.content != mine.content) {
            content = TextFieldValue(theirs.content, TextRange(theirs.content.length)); words = true
        }
        if (now.notebook == mine.notebook && theirs.notebook != mine.notebook) { notebook = theirs.notebook; words = true }
        if (words) undo.reload() // EU-1: another device's words, not a step Undo could take back unseen
        if (now.color == mine.color) color = theirs.color
        if (now.pinned == mine.pinned) pinned = theirs.pinned
        if (now.tags == mine.tags) tags = theirs.tags
        if (now.attachments == mine.attachments) setAttachments(theirs.attachments)
        if (now.reminderAt == mine.reminderAt && now.ringUntilDismissed == mine.ringUntilDismissed && now.ringSeconds == mine.ringSeconds) {
            reminderAt = theirs.reminderAt; ringUntilDismissed = theirs.ringUntilDismissed && theirs.reminderAt != null
            ringSeconds = com.example.itinerary.data.ReminderSound.cleanSeconds(theirs.ringUntilDismissed, theirs.ringSeconds, theirs.reminderAt != null)
        }
        if (now.priority == mine.priority) priority = theirs.priority
    }
    fun autoSave() {
        if (busy || autoSaving || closed[0] || !unsaved || !Notes.hasContent(current) || deletedElsewhere || conflict != null || pendingPhoto != null) return
        // A reminder set here that has already gone waits for a new time (Save says so).
        if (reminderAt != null && reminderAt != base?.reminderAt && reminderAt!! <= System.currentTimeMillis()) return
        val snapshot = current; val was = base
        autoSaving = true
        announceSave = false
        var ok = false
        scope.launch {
            try {
                val note = try { repo.saveNote(snapshot, create = was == null, expected = was, after = copyOf.takeIf { was == null }) }
                catch (e: com.example.itinerary.data.NoteChangedException) {
                    // Changed elsewhere meanwhile: merged when the two touched different things, else the user chooses.
                    val merged = was?.let { com.example.itinerary.data.mergeNotes(it, snapshot, e.latest) }
                    if (merged == null) { conflict = e.latest; null }
                    else repo.saveNote(merged, create = false, expected = e.latest).also { error = "Merged with a change made elsewhere." }
                }
                if (note != null) {
                    ok = true
                    val now = latest
                    // Nothing typed since: the fields take what was saved (a merge, trimmed text), as Save does.
                    if (now == snapshot) saved(note, {}, release = false)
                    else {
                        // Typed on meanwhile: what the other side changed in a merge (in fields not touched here since)
                        // comes in too, or the next save would quietly put the old values back (hunt 20, E2).
                        base = note
                        if (note != Notes.clean(snapshot)) takeTheirs(note, snapshot, now)
                    }
                    // The draft goes as soon as all is saved, not only once the screen's next frame comes (none while
                    // Planner is in the background), so it can't come back as a new note never saved (E1).
                    if (now == snapshot && ownsDraft) runCatching { draftStore.clear(initial.id) }
                    if (error == "Couldn't save this note. Please try again.") error = null
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { error = "Couldn't save this note. Please try again." }
            finally {
                autoSaving = false
                // Again only after one that worked: a failing one waits for the next change, not a retry every second.
                if (ok && !closeAfterAutoSave && latestUnsaved) autoAgain++
            }
        }
    }
    autoSaveNow[0] = ::autoSave
    LaunchedEffect(current, autoAgain) { kotlinx.coroutines.delay(1_000); autoSave() }
    // A Close that waited for an auto save: run once the screen has its result (the saved note as base), not from the
    // save's own coroutine, which still sees the note as it was before.
    LaunchedEffect(closeAfterAutoSave, autoSaving) { if (closeAfterAutoSave && !autoSaving) { closeAfterAutoSave = false; busy = false; closeNow() } }
    LaunchedEffect(saveAfterAutoSave, autoSaving) {
        val then = saveAfterAutoSave
        if (then != null && !autoSaving) { saveAfterAutoSave = null; if (unsaved) save(then) else { justSaved = true; then() } }
    }
    // E3: Discard really discards a new note: not saved, and if its first auto save got there first, removed again.
    fun discardNote() { if (autoSaving) { discardAfterAutoSave = true; busy = true } else discard() }
    LaunchedEffect(discardAfterAutoSave, autoSaving) {
        if (!discardAfterAutoSave || autoSaving) return@LaunchedEffect
        discardAfterAutoSave = false
        val made = base?.takeIf { !deletedElsewhere }
        if (made == null) { busy = false; discard(); return@LaunchedEffect }
        try { repo.deleteNote(made.id); releaseFiles(); dismiss() }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { error = "Couldn't discard this note. Please try again." }
        finally { busy = false }
    }

    fun delete() {
        val id = base?.id ?: return discard()
        busy = true
        scope.launch {
            try { repo.deleteNote(id); releaseFiles(); dismiss() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { error = "Couldn't delete this note. Please try again." }
            finally { busy = false }
        }
    }
    BackHandler { if (!busy) close() }

    val scroll = rememberScrollState()
    val noteFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    val density = androidx.compose.ui.platform.LocalDensity.current
    var titleHeight by remember { mutableStateOf(0.dp) }
    var menuOpen by remember { mutableStateOf(false) }
    // The note details panel (notebook, tags, reminder, importance, colour, attachments). Back closes it first.
    var detailsOpen by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = detailsOpen) { detailsOpen = false }
    val colors = MaterialTheme.colorScheme
    val highlight = remember(colors) { markdownHighlighting(colors.onSurfaceVariant.copy(alpha = 0.55f), colors.primary, colors.surfaceVariant) }
    // Switching between Edit and Preview goes back to the top, where the note starts.
    fun setPreview(on: Boolean) { preview = on; app.settings.setNoteLeftInPreview(initial.id, on); scope.launch { scroll.scrollTo(0) } }
    // A copy of what is here now as a new note (not saved yet); the original stays as last saved. The copy's editor
    // takes over this one's files, so they aren't released here.
    fun duplicate() {
        val copy = Notes.copyOf(current)
        if (ownsDraft) runCatching { draftStore.clear(initial.id) } // NT-1, as in releaseFiles
        dismiss(); onDuplicate?.invoke(copy)
    }
    val plain = androidx.compose.material3.TextFieldDefaults.colors(
        focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent, unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
        disabledContainerColor = androidx.compose.ui.graphics.Color.Transparent, focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
        unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent, disabledIndicatorColor = androidx.compose.ui.graphics.Color.Transparent)
    // Bug notes 7: laid out like Quillpad's editor. A bar of icons (Close, Undo, Redo, pin, reminder, Save, and ⋮ for
    // Duplicate and Delete); the title and the note without boxes, the Markdown styled as it's typed; an eye button for
    // Preview. The note uses the whole screen, the Markdown buttons along the bottom. When it was created and changed,
    // and the note's details (notebook, tags, reminder, importance, colour,
    // attachments) are in a panel from a button at the bottom left. Save stays a tap of its own: unsaved typing is kept
    // as a draft, as before.
    Surface(Modifier.fillMaxSize(), color = colors.background) {
      Box(Modifier.fillMaxSize()) {
        // With the details panel open, the note behind it is left out for TalkBack, as behind a dialog.
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().undoKeys(undo, enabled = !busy)
            .then(if (detailsOpen) Modifier.clearAndSetSemantics {} else Modifier)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp)
                .semantics { contentDescription = if (base == null) "New note" else "Edit note"; heading() },
                verticalAlignment = Alignment.CenterVertically) {
                IconButton(enabled = !busy, onClick = ::close) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close") }
                Spacer(Modifier.weight(1f))
                IconButton(enabled = !busy && undo.canUndo, onClick = undo::undo) { Icon(UndoIcons.undo, contentDescription = "Undo") }
                IconButton(enabled = !busy && undo.canRedo, onClick = undo::redo) { Icon(UndoIcons.redo, contentDescription = "Redo") }
                IconButton(enabled = !busy, onClick = { pinned = !pinned }) {
                    Icon(androidx.compose.ui.res.painterResource(if (pinned) com.example.itinerary.R.drawable.ic_pin else com.example.itinerary.R.drawable.ic_pin_outline),
                        contentDescription = if (pinned) "Pinned to the top" else "Pin to the top",
                        tint = if (pinned) colors.primary else androidx.compose.material3.LocalContentColor.current)
                }
                IconButton(enabled = !busy, onClick = { choosingReminderDate = true }) {
                    Icon(Icons.Filled.Notifications, contentDescription = reminderAt?.let { "Reminder: ${momentLabel(it)}" } ?: "Set a reminder",
                        tint = if (reminderAt != null) colors.primary else androidx.compose.material3.LocalContentColor.current)
                }
                val saved = justSaved && !unsaved
                IconButton(enabled = canSave && (unsaved || deletedElsewhere), onClick = { save() },
                    // Read out only after a tap on Save, not after each auto save while typing (E6).
                    modifier = Modifier.semantics { if (announceSave) liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite }) {
                    Icon(Icons.Filled.Check, contentDescription = if (busy || autoSaving) "Saving…" else if (saved) "Saved" else "Save",
                        tint = if (saved) colors.tertiary else androidx.compose.material3.LocalContentColor.current)
                }
                Box {
                    IconButton(enabled = !busy, onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Note options") }
                    androidx.compose.material3.DropdownMenu(menuOpen, { menuOpen = false }) {
                        if (base != null && onDuplicate != null) androidx.compose.material3.DropdownMenuItem(text = { Text("Duplicate note") },
                            enabled = Notes.hasContent(current), onClick = { menuOpen = false; duplicate() })
                        if (base != null && !deletedElsewhere) androidx.compose.material3.DropdownMenuItem(text = { Text("Delete", color = colors.error) },
                            onClick = { menuOpen = false; if (unsaved) askingToDelete = true else delete() })
                        if (base == null || deletedElsewhere) androidx.compose.material3.DropdownMenuItem(text = { Text("Discard") },
                            onClick = { menuOpen = false; discardNote() })
                    }
                }
            }
            // The note uses the whole screen: its box reaches down to the formatting bar (or to the bottom in Preview).
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val viewport = maxHeight
            ScrollHints(scroll, Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp).lockedWhile(busy), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    androidx.compose.material3.TextField(shownTitle, { typed ->
                            // Any change makes it the user's own title; deleting it all leaves the note without one (no
                            // auto title, remembered for this note).
                            val wasAuto = autoTitle
                            title = typed.replace('\n', ' ').take(Notes.MAX_TITLE); autoTitle = false
                            app.settings.setNoAutoTitle(initial.id, title.isBlank())
                            // Hunt 21 N2: deleting an automatic title changes no typed text (it was "" underneath), so it is
                            // made a step of its own: Undo brings the automatic title back.
                            if (wasAuto && title.isBlank()) undo.around(
                                undo = { autoTitle = true; app.settings.setNoAutoTitle(initial.id, false) },
                                redo = { autoTitle = false; app.settings.setNoAutoTitle(initial.id, true) }) },
                        Modifier.fillMaxWidth().onSizeChanged { titleHeight = with(density) { it.height.toDp() } },
                        placeholder = { Text("Title", style = MaterialTheme.typography.headlineSmall) }, singleLine = true, enabled = !busy,
                        textStyle = MaterialTheme.typography.headlineSmall, colors = plain,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
                    if (preview) {
                        Column(Modifier.padding(horizontal = 16.dp)) {
                            if (content.text.isBlank()) Text("Nothing written yet.", color = colors.onSurfaceVariant)
                            else MarkdownView(content.text, Modifier.fillMaxWidth(), onToggle = if (busy) null else { line ->
                                content = TextFieldValue(Markdown.toggle(content.text, line), content.selection)
                            })
                        }
                    } else {
                        androidx.compose.material3.TextField(content, { typed ->
                                // Enter in a checklist or list item starts the next item (or ends the list on an empty one).
                                val next = Markdown.continueList(content.text, typed.text, typed.selection.start)
                                    ?.takeIf { typed.selection.collapsed && continueLists }?.let { TextFieldValue(it.text, TextRange(it.start)) } ?: typed
                                content = if (next.text.length <= Notes.MAX_CONTENT) next else content
                            },
                            // At least the rest of the screen, less the room left under it for the round buttons.
                            Modifier.fillMaxWidth().heightIn(min = (viewport - titleHeight - BUTTON_ROOM - 20.dp).coerceAtLeast(120.dp))
                                .focusRequester(noteFocus), placeholder = { Text("Note") }, enabled = !busy,
                            textStyle = MaterialTheme.typography.bodyLarge, colors = plain, visualTransformation = highlight,
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
                    }
                    // The room under the note, so its last lines scroll clear of the round buttons. A tap there goes on
                    // typing at the end of the note, as a tap in the box below the text does.
                    Spacer(Modifier.fillMaxWidth().height(BUTTON_ROOM).then(if (preview) Modifier else
                        Modifier.clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null,
                            onClickLabel = "Type at the end of the note") {
                            if (!busy) { content = content.copy(selection = TextRange(content.text.length)); noteFocus.requestFocus() }
                        }))
                }
            }
            }
            // Under the note, where they can't be missed.
            // Bug hunt 19: clear of the round buttons in both bottom corners.
            if (deletedElsewhere || error != null) Column(Modifier.fillMaxWidth().padding(horizontal = 88.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (deletedElsewhere) Text("This note was deleted elsewhere. Save keeps your version as a new note.", color = colors.error)
                error?.let { Text(it, color = colors.error) }
            }
            // The Markdown buttons: always at the bottom in Edit, on top of the keyboard while it's up.
            if (!preview) {
                HorizontalDivider()
                MarkdownToolbar(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), enabled = !busy) { edit -> content = edit(content) }
            }
        }
        val fabBottom = if (!preview) 76.dp else 20.dp
        // Note details: the options below the note, in a panel from a round button at the bottom left, so the note
        // itself has the room. A dot on the button when the note has any of them set.
        val hasDetails = notebook.isNotBlank() || tags.isNotEmpty() || reminderAt != null || color != null ||
            priority != com.example.itinerary.data.TaskPriority.NORMAL || attachments.isNotEmpty()
        if (!detailsOpen) androidx.compose.material3.FloatingActionButton(onClick = { if (!busy) { focus.clearFocus(); detailsOpen = true } },
            modifier = Modifier.align(Alignment.BottomStart).navigationBarsPadding().imePadding().padding(start = 20.dp, bottom = fabBottom)
                .semantics { if (hasDetails) stateDescription = "Some set" },
            containerColor = colors.primaryContainer, contentColor = colors.primary, shape = CircleShape) {
            BadgedBox(badge = { if (hasDetails) Badge(containerColor = colors.primary) }) {
                Icon(androidx.compose.ui.res.painterResource(com.example.itinerary.R.drawable.ic_tune),
                    contentDescription = "Note details")
            }
        }
        // The eye: Preview (rendered, checklists tickable) and back to Edit. The space under the note lets its last
        // lines scroll clear of it.
        if (!detailsOpen) androidx.compose.material3.FloatingActionButton(onClick = { if (!busy) setPreview(!preview) },
            modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().imePadding().padding(end = 20.dp, bottom = fabBottom),
            containerColor = colors.primaryContainer, contentColor = colors.primary, shape = CircleShape) {
            if (preview) Icon(Icons.Filled.Edit, contentDescription = "Edit")
            else Icon(androidx.compose.ui.res.painterResource(com.example.itinerary.R.drawable.ic_visibility), contentDescription = "Preview")
        }
        if (detailsOpen) {
            // A dimmed note behind; tapping it closes the panel, as Back does.
            Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.4f))
                .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null,
                    onClickLabel = "Close note details") { detailsOpen = false })
            Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth().heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.85f).dp),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp), color = colors.surface, tonalElevation = 2.dp) {
              Column(Modifier.navigationBarsPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    HeadingText("Note details", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    IconButton(onClick = { detailsOpen = false }) { Icon(Icons.Filled.Close, contentDescription = "Close note details") }
                }
                // The app's scroll bar, as on every page that scrolls.
                ScrollHints(rememberScrollState(), Modifier.weight(1f, fill = false), fitContent = true) {
                  Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 24.dp).lockedWhile(busy),
                    verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        base?.let { saved ->
                            // Bug hunt 19: a note from an old backup may have no creation time (0, 1 Jan 1970): left out.
                            Text(listOfNotNull(saved.created.takeIf { it > 0 }?.let { "Created ${momentLabel(it)}" },
                                saved.modified.takeIf { it > 0 }?.let { "Last modified ${momentLabel(it)}" }).joinToString("\n"),
                                style = MaterialTheme.typography.bodySmall.copy(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic), color = colors.onSurfaceVariant)
                        }
                        SwitchRow("Continue lists on Enter", continueLists, app.settings::setContinueLists, Modifier, enabled = !busy,
                            style = MaterialTheme.typography.bodyMedium)
                        Text("Markdown: **bold**, *italic*, # heading, - list, - [ ] checklist, > quote.",
                            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        HorizontalDivider()
                        // Existing notebooks open under the box and narrow as you type; a new name is typed as before.
                        SuggestField(notebook, { notebook = it.replace('\n', ' ').take(Notes.MAX_NOTEBOOK) }, "Notebook (optional)",
                            suggestions = Notes.suggest(notebooks, notebook).filter { it != notebook.trim() }, onPick = { notebook = it }, enabled = !busy)
                        Text("Tags", style = MaterialTheme.typography.titleSmall)
                        if (tags.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            tags.forEach { tag ->
                                FilterChip(selected = true, enabled = !busy, onClick = { tags = tags - tag }, label = { Text("#$tag") },
                                    trailingIcon = { Icon(Icons.Filled.Close, contentDescription = "Remove tag $tag", Modifier.size(16.dp)) })
                            }
                        }
                        SuggestField(newTag, { newTag = it.replace('\n', ' ').take(Notes.MAX_TAG + 1) }, "Add a tag",
                            suggestions = Notes.suggest(allTags, Notes.cleanTag(newTag), taken = tags),
                            onPick = { tag -> if (tags.size < Notes.MAX_TAGS) tags = tags + tag; newTag = "" }, shown = { "#$it" },
                            enabled = !busy && tags.size < Notes.MAX_TAGS,
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { addTag() }),
                            trailingIcon = { if (newTag.isNotBlank()) IconButton(enabled = !busy, onClick = ::addTag) { Icon(Icons.Filled.Add, contentDescription = "Add tag") } })
                        NoteReminderSection(reminderAt, com.example.itinerary.data.ReminderSound.of(ringUntilDismissed, ringSeconds), onSound = { ringUntilDismissed = it.ring; ringSeconds = it.seconds }, snoozedUntil = base?.takeIf { it.reminderAt == reminderAt }?.snoozedAt(System.currentTimeMillis()),
                            enabled = !busy, onSet = { reminderAt = it; error = null }, onCustom = { choosingReminderDate = true })
                        Text("Importance", style = MaterialTheme.typography.titleSmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            com.example.itinerary.data.TaskPriority.entries.forEach { option ->
                                FilterChip(selected = priority == option, enabled = !busy, onClick = { priority = option }, label = { Text(option.label) })
                            }
                        }
                        Text("Colour", style = MaterialTheme.typography.titleSmall)
                        ColorChoices(color, enabled = !busy, onCustom = { pickingColor = true }) { color = it }
                        HorizontalDivider()
                        HeadingText("Attachments", style = MaterialTheme.typography.titleMedium)
                        attachments.forEach { attachment ->
                            AttachmentRow(attachment, attachmentStore, enabled = !busy, onOpen = { openAttachment(context, attachmentStore, attachment) },
                                onRemove = {
                                    val remaining = attachments.filterNot { it.fileName == attachment.fileName }
                                    // Persist the removal before cleanup: neither this draft nor a recovered editor may still name it.
                                    try {
                                        draftStore.write(NoteDraftStore.Draft(current.copy(attachments = remaining), base == null, base,
                                            pendingPhoto, windowEditors?.id))
                                        ownsDraft = true
                                        setAttachments(remaining)
                                        app.appScope.launch { runCatching { repo.releaseTaskFiles(listOf(attachment.fileName)) } }
                                    } catch (_: Exception) { error = draftError }
                                })
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(enabled = !busy && attachments.size < 100, onClick = { pickFile.launch(arrayOf("*/*")) }) { Text("Attach file") }
                            OutlinedButton(enabled = !busy && attachments.size < 100, onClick = {
                                // A second tap while the camera opens would leave the first photo file behind.
                                if (pendingPhoto != null) return@OutlinedButton
                                val file = attachmentStore.newPhotoFile()
                                pendingPhoto = file.name
                                try { takePhoto.launch(attachmentStore.uriFor(file.name)) }
                                catch (_: Exception) { file.delete(); pendingPhoto = null; error = "No camera is available." }
                            }) { Text("Take photo") }
                        }
                        // The panel's own errors (a file that couldn't be added) show here too, by what caused them.
                        error?.let { Text(it, color = colors.error) }
                  }
                }
              }
            }
        }
      }
    }
    if (pickingColor) CustomColorDialog(
        // Starts from the note's colour, or the card's blue.
        initial = Color(color ?: Notes.colors[2]),
        onDismiss = { pickingColor = false },
        onConfirm = { c -> color = c.toArgb(); pickingColor = false },
    )
    if (choosingReminderDate) SingleDateDialog(
        reminderAt?.let { java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).toLocalDate() } ?: java.time.LocalDate.now(),
        onDismiss = { choosingReminderDate = false },
        onConfirm = { reminderDateDraft = it.toString(); choosingReminderDate = false; choosingReminderTime = true })
    if (choosingReminderTime) TimePickerDialog(
        initial = reminderAt?.let { java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).toLocalTime() }
            ?: com.example.itinerary.data.taskReminderDefault(null).toLocalTime(),
        onDismiss = { choosingReminderTime = false },
        onConfirm = { time ->
            reminderAt = com.example.itinerary.data.taskReminderInstant(java.time.LocalDate.parse(reminderDateDraft!!), time)
            choosingReminderTime = false; error = null
        })
    conflict?.let { latest ->
        PlannerDialog("Changed elsewhere", onDismissRequest = { conflict = null },
            primary = DialogAction("Keep my version", enabled = !busy) { conflict = null; saveOver(current, latest) },
            dismiss = DialogAction("Cancel") { conflict = null },
            extra = listOf(DialogAction("Use the other version", danger = true) { conflict = null; base = latest; undo.reload(); load(latest); error = null })) {
            Text("This note was changed elsewhere (by Nextcloud sync or a reminder) while you were editing, in the same place as your changes. " +
                "Keep your version, or use the other one and lose your changes.")
        }
    }
    if (askingToDelete) PlannerDialog("Delete note?", onDismissRequest = { askingToDelete = false },
        primary = DialogAction("Delete", danger = true) { askingToDelete = false; delete() },
        dismiss = DialogAction("Cancel") { askingToDelete = false }) {
        Text("The note as last saved will be kept in Recently deleted for 30 days. Changes you haven't saved will be lost.")
    }
    if (askingToSave) PlannerDialog("Save changes?", onDismissRequest = { askingToSave = false },
        primary = DialogAction("Save", enabled = canSave) { askingToSave = false; save(::dismiss) },
        dismiss = DialogAction("Keep editing") { askingToSave = false },
        extra = listOf(DialogAction("Discard", danger = true) { askingToSave = false; discard() })) {
        Text("Your changes to this note haven't been saved.")
    }
}

// A note's one reminder: In 1 hour, Tomorrow 09:00 or Custom; once set, its time with Remove (and a snooze, if any).
@Composable
private fun NoteReminderSection(reminderAt: Long?, sound: com.example.itinerary.data.ReminderSound, onSound: (com.example.itinerary.data.ReminderSound) -> Unit, snoozedUntil: Long?, enabled: Boolean, onSet: (Long?) -> Unit, onCustom: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as ItineraryApp
    val notifications = rememberNotificationState()
    val exactAllowed by rememberExactAlarmsAllowed(app.reminderScheduler)
    val defaultSound = rememberDefaultReminderSound()
    var stale by remember { mutableIntStateOf(0) }
    val presets = remember(reminderAt, stale) { com.example.itinerary.data.taskReminderPresets(null) }
    val nine = java.time.LocalTime.of(9, 0).label(LocalTimeFormat.current, context)
    ReminderSectionFrame(notifications.enabled, notifications.enable,
        hints = listOfNotNull(reminderAlarmHint(exactAllowed, reminderAt != null, rings(sound.ring, sound.seconds, defaultSound))),
        chips = if (reminderAt != null) emptyList() else presets.map { (preset, _) ->
            (if (preset == com.example.itinerary.data.TaskReminderPreset.LATER_TODAY) "In 1 hour" else "Tomorrow $nine") to {
                // Timed from the tap; a choice that has passed since goes.
                val at = com.example.itinerary.data.taskReminderPresetAt(preset, null)
                if (at != null) onSet(at) else stale += 1
            }
        } + ("Pick date and time…" to onCustom),
        enabled = enabled) {
        reminderAt?.let { at ->
            ReminderRow(momentLabel(at), snoozedUntil?.let { "Snoozed until ${momentLabel(it)}. Changing the reminder ends the snooze." },
                onRemove = { onSet(null); onSound(com.example.itinerary.data.ReminderSound.DEFAULT) }, enabled = enabled) {
                ReminderSoundChoice(sound, defaultSound, enabled, onSound) }
        }
    }
}

// A toolbar edit as the box's new value. The keyboard's composing range (the word it underlines) is dropped: it was
// over the old text, and kept it could make the next key replace the wrong letters.
internal fun toolbarValue(edit: Markdown.Edit) = TextFieldValue(edit.text, TextRange(edit.start, edit.end))

// Bold, italic, strike, heading, list, checklist and code, applied to the selection (or where the cursor is).
@Composable
private fun MarkdownToolbar(modifier: Modifier = Modifier, enabled: Boolean = true, apply: ((TextFieldValue) -> TextFieldValue) -> Unit) {
    fun wrap(mark: String): (TextFieldValue) -> TextFieldValue = { v -> toolbarValue(Markdown.wrap(v.text, v.selection.start, v.selection.end, mark)) }
    fun prefix(mark: String): (TextFieldValue) -> TextFieldValue = { v -> toolbarValue(Markdown.prefixLines(v.text, v.selection.start, v.selection.end, mark)) }
    // Checklist is ☑ with U+FE0E, so it is drawn as text in the app's font rather than as a colour emoji.
    val tools = listOf(
        Triple("B", "Bold", wrap("**")), Triple("I", "Italic", wrap("*")), Triple("S", "Strikethrough", wrap("~~")),
        Triple("H", "Heading", prefix("# ")), Triple("•", "Bulleted list", prefix("- ")),
        Triple("\u2611\uFE0E", "Checklist", prefix("- [ ] ")), Triple("\u201C", "Quote", prefix("> ")), Triple("</>", "Code", wrap("`")),
    )
    Row(modifier.horizontalScroll(rememberScrollState()).semantics { contentDescription = "Formatting" },
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        tools.forEach { (label, name, edit) ->
            // Plain buttons, as on Quillpad's bar.
            androidx.compose.material3.TextButton(enabled = enabled, onClick = { apply(edit) }, modifier = Modifier.semantics { contentDescription = name }.defaultMinSize(minWidth = 44.dp),
                contentPadding = PaddingValues(horizontal = 10.dp)) {
                Text(label, fontFamily = if (label == "</>") FontFamily.Monospace else null,
                    style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

// No colour, the card colours, the note's own custom colour, then Custom (the colour picker), as round swatches; the
// chosen one is ringed.
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ColorChoices(selected: Int?, enabled: Boolean = true, onCustom: () -> Unit, onSelect: (Int?) -> Unit) {
    val ring = MaterialTheme.colorScheme.primary
    @Composable
    fun Swatch(name: String, chosen: Boolean, fill: Modifier, tick: Color, onClick: () -> Unit, content: @Composable () -> Unit = {}) {
        Box(Modifier.size(40.dp).clip(CircleShape).then(fill)
            .border(if (chosen) 3.dp else 1.dp, if (chosen) ring else MaterialTheme.colorScheme.outline, CircleShape)
            .clickable(enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = name; this.selected = chosen },
            contentAlignment = Alignment.Center) {
            if (chosen) Icon(Icons.Filled.Check, contentDescription = null, tint = tick, modifier = Modifier.size(20.dp)) else content()
        }
    }
    // Wraps onto a second line rather than scrolling sideways.
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        (listOf<Int?>(null) + Notes.colors).forEachIndexed { index, argb ->
            Swatch(if (argb == null) "No colour" else NOTE_COLOR_NAMES[index - 1], argb == selected,
                Modifier.background(argb?.let { Color(it) } ?: MaterialTheme.colorScheme.surfaceContainerHigh),
                argb?.let { onColour(Color(it)) } ?: ring, onClick = { onSelect(argb) })
        }
        // A colour of the note's own, chosen with Custom: a swatch of its own, picked like the others.
        if (selected != null && selected !in Notes.colors)
            Swatch("Custom colour (chosen)", true, Modifier.background(Color(selected)), onColour(Color(selected)), onClick = onCustom)
        Swatch("Custom colour", false, Modifier.background(CUSTOM_COLOUR_BRUSH), Color.White, onClick = onCustom) {
            Icon(Icons.Filled.Add, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
        }
    }
}

// Holds [noteId] for one note editor from when it is first composed until it leaves (or its composition is dropped).
private class NoteEditorClaim(val noteId: String) : RememberObserver {
    val owner = NoteDraftStore.claim(noteId, this)
    override fun onRemembered() {}
    override fun onForgotten() = NoteDraftStore.release(noteId, this)
    override fun onAbandoned() = NoteDraftStore.release(noteId, this)
}

// EU-1, EU-2: whether [note]'s title, body or notebook differ from what the fields hold, the text Undo follows.
internal fun noteWordsDiffer(note: PlannerNote, title: String, content: String, notebook: String): Boolean {
    // NO-2: both sides as Notes.clean stores them: "milk" + a new line typed is "milk" saved, and no change of words.
    val stored = Notes.clean(note)
    val typed = Notes.clean(note.copy(title = title, content = content, notebook = notebook))
    return stored.title != typed.title || stored.content != typed.content || stored.notebook != typed.notebook
}

// Room left under the note (and the details panel's last lines) for the round buttons in the bottom corners.
private val BUTTON_ROOM = 96.dp
