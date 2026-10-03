package com.example.itinerary.ui
import com.example.itinerary.ui.MatrixOutlinedButton as OutlinedButton
import com.example.itinerary.ui.MatrixFilterChip as FilterChip

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
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
 * The open note editor's body, attachments and starting version, kept in memory rather than in the saved-instance
 * Bundle: a long note with recognised text could pass the Bundle's size limit (TransactionTooLargeException). They
 * outlive a rotation here; when Android closes Planner they go, and the on-disk draft (NoteDraftStore) has them.
 * One note editor is open at a time. Its key is in the Bundle, so only that editor, recreated, finds them again.
 */
internal object NoteEditorMemory {
    class Body(content: TextFieldValue, attachments: List<Attachment>, base: PlannerNote?) {
        var content by mutableStateOf(content)
        var attachments by mutableStateOf(attachments)
        var base by mutableStateOf(base)
    }
    private var key: String? = null
    private var noteId: String? = null
    private var body: Body? = null

    fun keep(key: String, noteId: String, body: Body) { this.key = key; this.noteId = noteId; this.body = body }
    fun restore(key: String, noteId: String): Body? = body?.takeIf { this.key == key && this.noteId == noteId }
    /** Whether an editor of [noteId] left its state here: recreated after a rotation, not after Android closed Planner. */
    fun holds(noteId: String) = body != null && this.noteId == noteId
    fun forget(key: String) { if (this.key == key) forgetAll() }
    // What Android closing Planner does to it; also for tests.
    fun forgetAll() { key = null; noteId = null; body = null }
}

/**
 * A note, full screen: title, notebook, colour and pin, then the body in Markdown — written in Edit, read (and its
 * checklist ticked) in Preview — with the editors' Delete · Close · Save bar. A new note opens in Edit, a saved one in
 * Preview. Save keeps the editor open ("Saved"); Close with changes asks first.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NoteEditor(initial: PlannerNote, creating: Boolean, notebooks: List<String>, allTags: List<String> = emptyList(),
               recovered: NoteDraftStore.Draft? = null, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as ItineraryApp
    val repo = app.repository
    val scope = rememberCoroutineScope()
    val draftStore = remember { NoteDraftStore(context) }
    // What the fields start from: a recovered draft (Android closed Planner mid-edit), else the note.
    val start = recovered?.note ?: initial
    // The body, attachments and base live in NoteEditorMemory, not in the saved state (see there).
    val memoryKey = rememberSaveable { java.util.UUID.randomUUID().toString() }
    val body = remember { NoteEditorMemory.restore(memoryKey, initial.id) ?: NoteEditorMemory.Body(TextFieldValue(start.content), start.attachments,
        if (recovered != null) recovered.base else if (creating) null else initial).also { NoteEditorMemory.keep(memoryKey, initial.id, it) } }
    // The stored version these edits started from (null: a new note not saved yet). Save checks it's still current.
    var base by body::base
    var title by rememberSaveable { mutableStateOf(start.title) }
    var content by body::content
    var notebook by rememberSaveable { mutableStateOf(start.notebook) }
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
    var reminderAt by rememberSaveable { mutableStateOf(start.reminderAt) }
    var choosingReminderDate by rememberSaveable { mutableStateOf(false) }
    var reminderDateDraft by rememberSaveable { mutableStateOf<String?>(null) }
    var choosingReminderTime by rememberSaveable { mutableStateOf(false) }
    var preview by rememberSaveable { mutableStateOf(!creating && recovered == null && initial.content.isNotBlank()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(if (recovered != null) "Recovered unsaved changes. Save them, or Close and Discard." else null) }
    var askingToSave by rememberSaveable { mutableStateOf(false) }
    var justSaved by remember { mutableStateOf(false) }
    // Changed elsewhere in the same place as here: how it is now, while the user chooses.
    var conflict by remember { mutableStateOf<PlannerNote?>(null) }
    fun load(note: PlannerNote) {
        title = note.title; content = TextFieldValue(note.content, TextRange(note.content.length)); notebook = note.notebook; color = note.color
        pinned = note.pinned; tags = note.tags; setAttachments(note.attachments); reminderAt = note.reminderAt; priority = note.priority
    }
    // A notebook typed in other capitals goes into the existing one ("home" → "Home").
    val current = (base ?: start).copy(title = title, content = content.text, notebook = Notes.existingSpelling(notebooks, notebook.trim()), color = color, pinned = pinned,
        tags = tags, attachments = attachments, reminderAt = reminderAt, priority = priority)
    // The stored version cleaned once, not again on every letter typed (UI-10).
    val cleanBase = remember(base) { base?.let(Notes::clean) }
    val unsaved = cleanBase?.let { Notes.clean(current) != it } ?: Notes.hasContent(current)
    // As stored now: a change made elsewhere (sync, a reminder's Done) shows here at once while nothing is edited.
    val continueLists by app.settings.continueLists.collectAsStateWithLifecycle()
    val stored by remember(initial.id) { repo.observeNote(initial.id) }.collectAsStateWithLifecycle(initialValue = base)
    // Once seen in the database: a just-saved new note isn't "deleted" for the moment before its first reading arrives.
    var seenStored by remember { mutableStateOf(base != null) }
    LaunchedEffect(stored) {
        val now = stored
        if (now != null) {
            seenStored = true
            if (base != null && now != base && !unsaved) { load(now); base = now }
        }
    }
    val deletedElsewhere = base != null && seenStored && stored == null
    val canSave = !busy && Notes.hasContent(current)
    val attachmentStore = app.attachmentStore
    // Counted as an open editor (a widget day waits for it), and its state kept on disk shortly after each change.
    DisposableEffect(Unit) { NoteDraftStore.editorOpened(); onDispose { NoteDraftStore.editorClosed() } }
    LaunchedEffect(current, base, pendingPhoto, unsaved) {
        kotlinx.coroutines.delay(400)
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { if (unsaved || pendingPhoto != null) draftStore.write(NoteDraftStore.Draft(current, base == null, base, pendingPhoto)) else draftStore.clear() }
        }
    }
    // Files this editor has held: once it saves, discards or deletes, those nothing uses any more are removed (one still
    // in a saved note, Recently deleted or elsewhere stays).
    fun releaseFiles() {
        val files = (initial.attachments + start.attachments + attachments + base?.attachments.orEmpty()).map { it.fileName } + listOfNotNull(pendingPhoto)
        app.appScope.launch { runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { draftStore.clear() }; repo.releaseTaskFiles(files) } }
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
    fun addTag() {
        // A tag that exists in other capitals is that tag ("Errands" adds #errands).
        val tag = Notes.existingSpelling(allTags, Notes.cleanTag(newTag))
        if (tag.isNotEmpty() && tags.none { it.equals(tag, ignoreCase = true) } && tags.size < Notes.MAX_TAGS) tags = tags + tag
        newTag = ""
    }
    LaunchedEffect(unsaved) { if (unsaved) justSaved = false }

    fun saved(note: PlannerNote, then: () -> Unit) {
        // The fields change only if saving changed them (a merge, trimmed text), so typing isn't disturbed otherwise.
        val edited = Notes.clean(current)
        val same = note.title == edited.title && note.content == edited.content && note.notebook == edited.notebook && note.color == edited.color && note.priority == edited.priority &&
            note.pinned == edited.pinned && note.tags == edited.tags && note.attachments == edited.attachments && note.reminderAt == edited.reminderAt
        base = note; if (!same) load(note); justSaved = true
        releaseFiles(); then()
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
    fun save(then: () -> Unit = {}) {
        if (busy) return
        // A reminder set or changed here must be ahead; one already saved that has rung can stay as it is.
        if (reminderAt != null && reminderAt != base?.reminderAt && reminderAt!! <= System.currentTimeMillis()) {
            error = "Choose a future reminder date and time."; return
        }
        busy = true; error = null
        scope.launch {
            try {
                // Deleted elsewhere: saved again as it is here (a new note), so nothing typed is lost.
                saved(repo.saveNote(current, create = base == null || deletedElsewhere, expected = base), then)
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
    fun dismiss() { NoteEditorMemory.forget(memoryKey); onDismiss() }
    fun discard() { releaseFiles(); dismiss() }
    fun close() { if (unsaved) askingToSave = true else discard() }
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
    // While the note box is typed in, the Markdown buttons sit on top of the keyboard instead of scrolling away.
    var noteFocused by remember { mutableStateOf(false) }
    val toolsPinned = !preview && noteFocused && WindowInsets.isImeVisible
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            ScrollHints(scroll, Modifier.weight(1f).fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    HeadingText(if (base == null) "New note" else "Edit note", style = MaterialTheme.typography.headlineMedium)
                    OutlinedTextField(title, { title = it.replace('\n', ' ').take(Notes.MAX_TITLE) }, Modifier.fillMaxWidth(),
                        label = { Text("Title") }, singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
                    // Edit / Preview, and in Edit the Markdown shortcuts.
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        listOf(false to "Edit", true to "Preview").forEachIndexed { index, (isPreview, label) ->
                            SegmentedButton(selected = preview == isPreview, onClick = { preview = isPreview },
                                shape = SegmentedButtonDefaults.itemShape(index, 2),
                                colors = SegmentedButtonDefaults.colors(activeContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                                    activeContentColor = MaterialTheme.colorScheme.primary)) { Text(label) }
                        }
                    }
                    if (preview) {
                        if (content.text.isBlank()) Text("Nothing written yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else MarkdownView(content.text, Modifier.fillMaxWidth(), onToggle = { line ->
                            content = TextFieldValue(Markdown.toggle(content.text, line), content.selection)
                        })
                    } else {
                        if (!toolsPinned) MarkdownToolbar { edit -> content = edit(content) }
                        OutlinedTextField(content, { typed ->
                                // Enter in a checklist or list item starts the next item (or ends the list on an empty one).
                                val next = Markdown.continueList(content.text, typed.text, typed.selection.start)
                                    ?.takeIf { typed.selection.collapsed && continueLists }?.let { TextFieldValue(it.text, TextRange(it.start)) } ?: typed
                                content = if (next.text.length <= Notes.MAX_CONTENT) next else content
                            },
                            Modifier.fillMaxWidth().onFocusChanged { noteFocused = it.isFocused }, label = { Text("Note") }, minLines = 8,
                            textStyle = MaterialTheme.typography.bodyLarge,
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
                        Text("Markdown: **bold**, *italic*, # heading, - list, - [ ] checklist.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(Modifier.fillMaxWidth().toggleable(value = continueLists, role = Role.Switch,
                                onValueChange = app.settings::setContinueLists),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text("Continue lists on Enter", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            Switch(checked = continueLists, onCheckedChange = null)
                        }
                    }
                    HorizontalDivider()
                    // Existing notebooks open under the box and narrow as you type; a new name is typed as before.
                    SuggestField(notebook, { notebook = it.replace('\n', ' ').take(Notes.MAX_NOTEBOOK) }, "Notebook (optional)",
                        suggestions = Notes.suggest(notebooks, notebook).filter { it != notebook.trim() }, onPick = { notebook = it })
                    Text("Tags", style = MaterialTheme.typography.titleSmall)
                    if (tags.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        tags.forEach { tag ->
                            FilterChip(selected = true, onClick = { tags = tags - tag }, label = { Text("#$tag") },
                                trailingIcon = { Icon(Icons.Filled.Close, contentDescription = "Remove tag $tag", Modifier.size(16.dp)) })
                        }
                    }
                    SuggestField(newTag, { newTag = it.replace('\n', ' ').take(Notes.MAX_TAG + 1) }, "Add a tag",
                        suggestions = Notes.suggest(allTags, Notes.cleanTag(newTag), taken = tags),
                        onPick = { tag -> if (tags.size < Notes.MAX_TAGS) tags = tags + tag; newTag = "" }, shown = { "#$it" },
                        enabled = tags.size < Notes.MAX_TAGS,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { addTag() }),
                        trailingIcon = { if (newTag.isNotBlank()) IconButton(onClick = ::addTag) { Icon(Icons.Filled.Add, contentDescription = "Add tag") } })
                    NoteReminderSection(reminderAt, base?.takeIf { it.reminderAt == reminderAt }?.snoozedAt(System.currentTimeMillis()),
                        enabled = !busy, onSet = { reminderAt = it; error = null }, onCustom = { choosingReminderDate = true })
                    Text("Importance", style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        com.example.itinerary.data.TaskPriority.entries.forEach { option ->
                            FilterChip(selected = priority == option, onClick = { priority = option }, label = { Text(option.label) })
                        }
                    }
                    Text("Colour", style = MaterialTheme.typography.titleSmall)
                    ColorChoices(color, onCustom = { pickingColor = true }) { color = it }
                    FilterChip(selected = pinned, onClick = { pinned = !pinned }, label = { Text(if (pinned) "Pinned to the top" else "Pin to the top") },
                        leadingIcon = if (pinned) ({ Icon(Icons.Filled.Check, contentDescription = null, Modifier.size(18.dp)) }) else null)
                    HorizontalDivider()
                    HeadingText("Attachments", style = MaterialTheme.typography.titleMedium)
                    attachments.forEach { attachment ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            MatrixTextButton(enabled = !busy, modifier = Modifier.weight(1f), onClick = { openAttachment(context, attachmentStore, attachment) }) { Text(attachment.name) }
                            MatrixTextButton(enabled = !busy, onClick = { setAttachments(attachments.filterNot { it.fileName == attachment.fileName }) }) { Text("Remove") }
                        }
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
                }
            }
            HorizontalDivider()
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Beside Save, where it can't be missed.
                if (deletedElsewhere) Text("This note was deleted elsewhere. Save keeps your version as a new note.", color = MaterialTheme.colorScheme.error)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                EditorActions(onDelete = if (base != null && !deletedElsewhere) ({ delete() }) else null,
                    onClose = ::close, onSave = { save() }, deleteEnabled = !busy, closeEnabled = !busy,
                    saveEnabled = canSave && (unsaved || deletedElsewhere)) { SaveLabel(busy, saved = justSaved && !unsaved) }
            }
            if (toolsPinned) {
                HorizontalDivider()
                MarkdownToolbar(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) { edit -> content = edit(content) }
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
            extra = listOf(DialogAction("Use the other version", danger = true) { conflict = null; base = latest; load(latest); error = null })) {
            Text("This note was changed elsewhere (by Nextcloud sync or a reminder) while you were editing, in the same place as your changes. " +
                "Keep your version, or use the other one and lose your changes.")
        }
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
private fun NoteReminderSection(reminderAt: Long?, snoozedUntil: Long?, enabled: Boolean, onSet: (Long?) -> Unit, onCustom: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as ItineraryApp
    val notifications = rememberNotificationState()
    val exactAllowed by rememberExactAlarmsAllowed(app.reminderScheduler)
    var stale by remember { mutableIntStateOf(0) }
    val presets = remember(reminderAt, stale) { com.example.itinerary.data.taskReminderPresets(null) }
    val nine = java.time.LocalTime.of(9, 0).label(LocalTimeFormat.current, context)
    ReminderSectionFrame(notifications.enabled, notifications.enable,
        hints = listOfNotNull(if (reminderAt != null && !exactAllowed) LATE_REMINDER_HINT else null),
        chips = if (reminderAt != null) emptyList() else presets.map { (preset, _) ->
            (if (preset == com.example.itinerary.data.TaskReminderPreset.LATER_TODAY) "In 1 hour" else "Tomorrow $nine") to {
                // Timed from the tap; a choice that has passed since goes.
                val at = com.example.itinerary.data.taskReminderPresetAt(preset, null)
                if (at != null) onSet(at) else stale += 1
            }
        } + ("Custom" to onCustom),
        enabled = enabled) {
        reminderAt?.let { at ->
            ReminderRow(noteReminderLabel(at), snoozedUntil?.let { "Snoozed until ${noteReminderLabel(it)}. Changing the reminder ends the snooze." },
                onRemove = { onSet(null) }, enabled = enabled)
        }
    }
}

@Composable
internal fun noteReminderLabel(timestamp: Long): String {
    val at = java.time.Instant.ofEpochMilli(timestamp).atZone(java.time.ZoneId.systemDefault())
    return "${at.toLocalDate().dayLabel(LocalDateFormat.current)}, ${at.toLocalTime().label(LocalTimeFormat.current, LocalContext.current)}"
}

// A toolbar edit as the box's new value. The keyboard's composing range (the word it underlines) is dropped: it was
// over the old text, and kept it could make the next key replace the wrong letters.
internal fun toolbarValue(edit: Markdown.Edit) = TextFieldValue(edit.text, TextRange(edit.start, edit.end))

// Bold, italic, strike, heading, list, checklist and code, applied to the selection (or where the cursor is).
@Composable
private fun MarkdownToolbar(modifier: Modifier = Modifier, apply: ((TextFieldValue) -> TextFieldValue) -> Unit) {
    fun wrap(mark: String): (TextFieldValue) -> TextFieldValue = { v -> toolbarValue(Markdown.wrap(v.text, v.selection.start, v.selection.end, mark)) }
    fun prefix(mark: String): (TextFieldValue) -> TextFieldValue = { v -> toolbarValue(Markdown.prefixLines(v.text, v.selection.start, v.selection.end, mark)) }
    // Checklist is ☑ with U+FE0E, so it is drawn as text in the app's font rather than as a colour emoji.
    val tools = listOf(
        Triple("B", "Bold", wrap("**")), Triple("I", "Italic", wrap("*")), Triple("S", "Strikethrough", wrap("~~")),
        Triple("H", "Heading", prefix("# ")), Triple("•", "Bulleted list", prefix("- ")),
        Triple("\u2611\uFE0E", "Checklist", prefix("- [ ] ")), Triple("</>", "Code", wrap("`")),
    )
    Row(modifier.horizontalScroll(rememberScrollState()).semantics { contentDescription = "Formatting" },
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        tools.forEach { (label, name, edit) ->
            OutlinedButton(onClick = { apply(edit) }, modifier = Modifier.semantics { contentDescription = name }.defaultMinSize(minWidth = 44.dp),
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
internal fun ColorChoices(selected: Int?, onCustom: () -> Unit, onSelect: (Int?) -> Unit) {
    val ring = MaterialTheme.colorScheme.primary
    @Composable
    fun Swatch(name: String, chosen: Boolean, fill: Modifier, tick: Color, onClick: () -> Unit, content: @Composable () -> Unit = {}) {
        Box(Modifier.size(40.dp).clip(CircleShape).then(fill)
            .border(if (chosen) 3.dp else 1.dp, if (chosen) ring else MaterialTheme.colorScheme.outline, CircleShape)
            .clickable(role = Role.RadioButton, onClick = onClick)
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
        Swatch("Custom colour", false, Modifier.background(androidx.compose.ui.graphics.Brush.sweepGradient(
            listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red))), Color.White, onClick = onCustom) {
            Icon(Icons.Filled.Add, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
        }
    }
}
