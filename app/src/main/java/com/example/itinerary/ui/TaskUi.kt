package com.example.itinerary.ui
import com.example.itinerary.ui.MatrixFilterChip as FilterChip
import com.example.itinerary.ui.MatrixTextButton as TextButton
import com.example.itinerary.ui.MatrixOutlinedButton as OutlinedButton
import com.example.itinerary.ui.MatrixButton as Button

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import org.json.JSONObject
import org.json.JSONArray
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.flow.first
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.example.itinerary.ItineraryApp
import com.example.itinerary.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.LocalDate

// [selection]: a long press selects the task with the events and bills around it; while anything is selected a tap
// selects or deselects it, and its done box waits.
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun TaskCard(task: PlannerTask, today: LocalDate, enabled: Boolean = true, selection: EventSelection? = null, onEdit: () -> Unit) {
    val context = LocalContext.current
    val repo = (context.applicationContext as ItineraryApp).repository
    val scope = rememberCoroutineScope()
    val allTasks by repo.tasks.collectAsStateWithLifecycle(initialValue = emptyList())
    val blockers = TaskDependencies.blockers(task, allTasks)
    var busy by remember(task.id) { mutableStateOf(false) }
    val selecting = selection?.active == true
    val selected = selection != null && task.id in selection.taskIds
    OutlinedCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        colors = if (selected) CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer) else CardDefaults.outlinedCardColors()) {
        Row(Modifier.fillMaxWidth()
            .combinedClickable(enabled = enabled && !busy,
                onClick = { if (selecting) selection?.toggleTask(task.id) else onEdit() },
                onLongClickLabel = if (selection != null) "Select task" else null,
                onLongClick = selection?.let { { it.toggleTask(task.id) } })
            .semantics { if (selecting) this.selected = selected }
            .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = task.done, enabled = enabled && !busy && !selecting && (task.done || blockers.isEmpty()),
                modifier = Modifier.semantics { contentDescription = "Mark ${task.title} ${if (task.done) "incomplete" else "done"}" },
                onCheckedChange = { done ->
                    busy = true
                    scope.launch {
                        try { repo.setTaskDone(task.id, done) }
                        catch (e: CancellationException) { throw e }
                        catch (_: Exception) { android.widget.Toast.makeText(context, "Couldn't update this task. Please try again.", android.widget.Toast.LENGTH_LONG).show() }
                        finally { busy = false }
                    }
                })
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(task.title, style = MaterialTheme.typography.titleMedium,
                    textDecoration = if (task.done) TextDecoration.LineThrough else null)
                Text(if (task.done) "Task · Completed" else "Task", style = MaterialTheme.typography.labelSmall)
                if (!task.done && blockers.isNotEmpty()) Text("Waiting on ${blockers.size} prerequisite(s) · Open task to review", style = MaterialTheme.typography.bodySmall)
                if (task.priority != TaskPriority.NORMAL) {
                    Surface(color = if (task.priority == TaskPriority.HIGH) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
                        shape = MaterialTheme.shapes.small) {
                        Text("${task.priority.label} priority", Modifier.padding(horizontal = 8.dp, vertical = 3.dp), style = MaterialTheme.typography.labelMedium)
                    }
                }
                if (task.repeat != "NONE") Text("Repeat: ${TaskRepeat.label(task.repeat, task.repeatDays)}", style = MaterialTheme.typography.bodySmall)
                if (task.checklist.isNotEmpty()) Text(checklistProgress(task.checklist), style = MaterialTheme.typography.bodySmall)
                if (task.attachments.isNotEmpty()) Text("${task.attachments.size} attachment(s)", style = MaterialTheme.typography.bodySmall)
                val due = task.dueDate
                if (due != null) Text("Due ${due.dayLabel(LocalDateFormat.current)}${if (!task.done && due < today) " · Overdue" else ""}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (!task.done && due < today) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                else Text("No due date", style = MaterialTheme.typography.bodySmall)
                if (!task.done) task.activeReminderAt?.let { timestamp ->
                    Text("Reminder: ${if (task.snoozedAt(System.currentTimeMillis()) != null) "snoozed until " else ""}${taskReminderLabel(timestamp)}",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            if (selecting) Checkbox(checked = selected, onCheckedChange = null)
            else if (selection != null) TaskActionsMenu(task, today)
        }
    }
}

@Composable
private fun taskReminderLabel(timestamp: Long): String {
    val reminder = Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault())
    return "${reminder.toLocalDate().dayLabel(LocalDateFormat.current)}, ${reminder.toLocalTime().label(LocalTimeFormat.current, LocalContext.current)}"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
// [closeRequested]: something else wants this editor closed (a second task tapped on the widget, E4). It closes like
// Close: at once with nothing unsaved, otherwise after "Save changes?"; Keep editing there calls [onCloseCancelled].
fun TaskEditor(initial: PlannerTask, creating: Boolean, closeRequested: Boolean = false, onCloseCancelled: () -> Unit = {},
               onDismiss: () -> Unit) {
    val context = LocalContext.current
    val store = remember { TaskDraftStore(context) }
    val draftKey = if (creating) "new" else initial.id
    val recovered = remember(draftKey) { runCatching { store.read(draftKey) }.getOrNull() }
    var decision by rememberSaveable(draftKey) { mutableStateOf(if (recovered == null) "fresh" else "ask") }
    val scope = rememberCoroutineScope()
    val repo = (context.applicationContext as ItineraryApp).repository
    // After a Save the editor goes on with the task as stored (a new one becomes one to edit). Which task and which save
    // survive recreation, so a saved new task isn't offered as new again; each save starts the form afresh.
    var savedId by rememberSaveable { mutableStateOf<String?>(null) }
    var savedRound by rememberSaveable { mutableIntStateOf(0) }
    var savedTask by remember { mutableStateOf<PlannerTask?>(null) }
    val onSaved: suspend (String) -> Unit = { id ->
        val task = repo.task(id)
        if (task == null) onDismiss() else { savedTask = task; savedId = id; savedRound++ }
    }
    val savedKey = savedId
    // Counted for the widget's day (U5) from first composed to gone, whatever it shows.
    val windowEditors = LocalWindowEditors.current
    DisposableEffect(Unit) {
        TaskDraftStore.editorOpened(); windowEditors?.let { it.tasks++ }
        onDispose { TaskDraftStore.editorClosed(); windowEditors?.let { it.tasks-- } }
    }
    // U4: one editor per task. Another one on a task already open (the widget's over the agenda's, a second window's)
    // says so instead of opening on the same draft. A new task has nothing to share until it is saved.
    val claimId = if (creating) savedKey else initial.id
    val claim = remember(claimId) { claimId?.let(::TaskEditorClaim) }
    if (claim != null && !claim.owner) {
        if (closeRequested) LaunchedEffect(Unit) { onDismiss() }
        PlannerDialog("Task already open", onDismissRequest = onDismiss, dismiss = DialogAction("Close", onClick = onDismiss)) {
            Text("This task is open in another editor. Save or close it there first.")
        }
        return
    }
    if (savedKey != null) {
        if (savedTask == null) LaunchedEffect(savedKey) { repo.task(savedKey)?.let { savedTask = it } ?: onDismiss() }
        savedTask?.let { task ->
            key(savedRound) {
                // Only unsaved edits made after that save (kept across recreation) are in its draft.
                val draft = remember { runCatching { store.read(task.id) }.getOrNull() }
                CompositionLocalProvider(LocalEditingTaskId provides task.id) {
                    TaskEditorContent(task, false, draft, task.id, store, onDismiss, onSaved, closeRequested, onCloseCancelled,
                        justSaved = draft == null)
                }
            }
        }
    } else if (decision == "ask") {
        // Nothing is edited yet and the draft stays for next time, as when this question is dismissed.
        if (closeRequested) LaunchedEffect(Unit) { onDismiss() }
        PlannerDialog("Unfinished task", onDismissRequest = onDismiss,
            primary = DialogAction("Resume draft") { decision = "resume" },
            dismiss = DialogAction("Discard draft", danger = true) {
                store.clear(draftKey)
                val files = DraftCodec.attachments(recovered?.optJSONArray("attachments")).map { it.fileName } +
                    listOfNotNull(recovered?.optString("pendingPhoto")?.takeIf { it.isNotBlank() })
                scope.launch { repo.releaseTaskFiles(files) }
                decision = "fresh"
            }) {
            Text("Resume your saved draft for ${recovered?.optString("title").orEmpty().ifBlank { "this task" }}?")
        }
    } else {
        // On recreation, use the latest durable state, including unsaved attachment imports.
        val draft = remember(draftKey, decision) {
            if (decision == "resume") recovered else runCatching { store.read(draftKey) }.getOrNull()
        }
        val source = remember(draftKey, decision) {
            if (creating && draft != null) initial.copy(id = draft.getString("id")) else initial
        }
        CompositionLocalProvider(LocalEditingTaskId provides source.id) {
            TaskEditorContent(source, creating, draft, draftKey, store, onDismiss, onSaved, closeRequested, onCloseCancelled)
        }
    }
}

// U-N2: the task an editor host passes for [id]: the stored one ([live]), or once that is gone (deleted by sync while its
// editor is open) the last one seen, so the editor stays open with what was typed and says so, instead of vanishing and
// leaving its host's "editing" id set (which reopened it when the task was restored).
@Composable
fun rememberEditedTask(id: String, live: PlannerTask?): PlannerTask? {
    val last = remember(id) { arrayOfNulls<PlannerTask>(1) }
    if (live != null) last[0] = live
    return live ?: last[0]
}

// Holds [taskId] for one task editor from when it is first composed until it leaves (or its composition is dropped).
private class TaskEditorClaim(val taskId: String) : RememberObserver {
    val owner = TaskDraftStore.claim(taskId, this)
    override fun onRemembered() {}
    override fun onForgotten() = TaskDraftStore.release(taskId, this)
    override fun onAbandoned() = TaskDraftStore.release(taskId, this)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TaskEditorContent(initial: PlannerTask, creating: Boolean, draft: JSONObject?, draftKey: String,
                              draftStore: TaskDraftStore, onDismiss: () -> Unit, onSaved: suspend (String) -> Unit,
                              closeRequested: Boolean = false, onCloseCancelled: () -> Unit = {},
                              // Opened again right after a Save: the Save button says "Saved" until something changes.
                              justSaved: Boolean = false) {
    val context = LocalContext.current
    val app = context.applicationContext as ItineraryApp
    val repo = app.repository
    val notifications = rememberNotificationState()
    var exactAllowed by remember { mutableStateOf(app.reminderScheduler.canScheduleExact()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { exactAllowed = app.reminderScheduler.canScheduleExact() }
    var reminderAt by rememberSaveable(initial.id) { mutableStateOf(if (draft != null && !draft.isNull("reminderAt")) draft.getLong("reminderAt") else if (draft != null) null else initial.reminderAt) }
    var reminderSuggestion by rememberSaveable(initial.id) { mutableStateOf<String?>(null) }
    var choosingReminderDate by rememberSaveable { mutableStateOf(false) }
    var reminderDateDraft by rememberSaveable { mutableStateOf<String?>(null) }
    var choosingReminderTime by rememberSaveable { mutableStateOf(false) }
    var title by rememberSaveable(initial.id) { mutableStateOf(draft?.optString("title") ?: initial.title) }
    var notes by rememberSaveable(initial.id) { mutableStateOf(draft?.optString("notes") ?: initial.notes) }
    var date by rememberSaveable(initial.id) { mutableStateOf(if (draft != null) draft.optString("date").takeIf { it.isNotBlank() } else initial.dueDate?.toString()) }
    var priority by rememberSaveable(initial.id) { mutableStateOf(draft?.optString("priority") ?: initial.priority.name) }
    var repeat by rememberSaveable(initial.id) { mutableStateOf(draft?.optString("repeat") ?: initial.repeat) }
    var repeatDays by rememberSaveable(initial.id) { mutableStateOf(draft?.optString("repeatDays") ?: initial.repeatDays.toString()) }
    var checklist by remember { mutableStateOf(draft?.optJSONArray("checklist")?.let { array ->
        List(array.length()) { i -> array.getJSONObject(i).let { ChecklistEntry(it.getString("id"), it.getString("text"), it.getBoolean("done")) } }
    } ?: initial.checklist) }
    var prerequisiteIds by remember { mutableStateOf(draft?.optJSONArray("prerequisiteIds")?.let(StringListCodec::decode) ?: initial.prerequisiteIds) }
    var attachments by remember { mutableStateOf(if (draft != null) DraftCodec.attachments(draft.optJSONArray("attachments")) else initial.attachments) }
    var pendingPhoto by remember { mutableStateOf(draft?.optString("pendingPhoto")?.takeIf { it.isNotBlank() }) }
    var finished by remember { mutableStateOf(false) }
    var choosingDate by rememberSaveable { mutableStateOf(false) }
    var confirmingDelete by rememberSaveable { mutableStateOf(false) }
    var duplicate by remember { mutableStateOf<PlannerTask?>(null) }
    var schedule by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var askingToSave by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    // [keepOpen]: a plain Save goes on editing the task as stored instead of leaving.
    fun action(keepOpen: Boolean = false, block: suspend () -> Unit) {
        if (busy) return
        busy = true; error = null
        scope.launch {
            try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    block(); finished = true; draftStore.clear(draftKey)
                    repo.releaseTaskFiles(attachments.map { it.fileName } + listOfNotNull(pendingPhoto))
                    if (keepOpen) onSaved(initial.id)
                }
                if (!keepOpen) onDismiss()
            }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "Couldn't save the change. Please try again." }
            finally { busy = false }
        }
    }
    val attachmentStore = app.attachmentStore
    val snapshot = JSONObject().put("id", initial.id).put("title", title).put("notes", notes)
        .put("date", date.orEmpty()).put("priority", priority).put("reminderAt", reminderAt ?: JSONObject.NULL)
        .put("prerequisiteIds", JSONArray(prerequisiteIds))
        .put("repeat", repeat).put("repeatDays", repeatDays).put("checklist", JSONArray(ChecklistCodec.encode(checklist)))
        .put("attachments", DraftCodec.attachments(attachments)).put("pendingPhoto", pendingPhoto.orEmpty())
    val lastWritten = remember { arrayOfNulls<String>(1) }
    val encoded = snapshot.toString()
    // What the editor opened with. Until something differs no draft is kept, so opening a task (or a new one) and
    // going Back leaves nothing to resume and doesn't block sharing; edits undone again clear it.
    val untouched = remember { encoded }
    SideEffect {
        if (!finished && encoded != lastWritten[0]) {
            try {
                if (draft == null && encoded == untouched) draftStore.clear(draftKey) else draftStore.write(draftKey, snapshot)
                lastWritten[0] = encoded
            }
            catch (_: Exception) { error = "Couldn't protect this draft. Keep the app open and save your task." }
        }
    }
    fun discard() {
        action { /* Explicit discard clears the draft and releases only unowned files. */ }
    }
    // Unsaved changes: anything Save would store that differs from what this form opened with (the task as last saved,
    // or a new one as offered); a resumed draft always counts. Save greys out without them (a new task can still be
    // saved as it is) and Close only asks "Save changes?" with them.
    fun currentTask() = initial.copy(title = title, notes = notes, dueDate = date?.let(LocalDate::parse), priority = TaskPriority.valueOf(priority),
        reminderAt = reminderAt, repeat = repeat, repeatDays = repeatDays.toIntOrNull() ?: -1, checklist = checklist,
        attachments = attachments, prerequisiteIds = prerequisiteIds)
    val openedWith = remember { currentTask() }
    val unsaved = EditorRules.taskUnsaved(openedWith, currentTask(), recovered = draft != null)
    // U-N1/U-N2: the task as stored, watched while this form is open (each form after a Save or Reload watches afresh from
    // its own [initial], so its own save isn't taken for a change from elsewhere). Changed underneath (a sync pull): a
    // banner offers Reload and Save asks first, as the event editor does. Gone (deleted by sync): the form stays with what
    // was typed (kept as a draft), Save is off, and Duplicate task can keep it as a new one.
    val stored by remember { if (creating) kotlinx.coroutines.flow.flowOf(initial) else repo.observeTask(initial.id) }
        .collectAsStateWithLifecycle(initialValue = initial)
    val settled = !creating && !finished && !busy
    val changedElsewhere = settled && EditorRules.taskChangedElsewhere(initial, stored)
    val deletedElsewhere = settled && stored == null
    var askingStale by remember { mutableStateOf(false) }
    var closeAfterStale by remember { mutableStateOf(false) }
    var askingReload by remember { mutableStateOf(false) }
    val canSave = !busy && !deletedElsewhere && title.isNotBlank() && checklist.none { it.text.isBlank() } && TaskRepeat.valid(repeat) &&
        (repeat != TaskRepeat.AFTER_COMPLETION.name || repeatDays.toIntOrNull() in 1..3650)
    // The form again from the task as stored now; what was unsaved here is dropped, like Discard.
    fun reload() = action(keepOpen = true) { }
    fun save(close: Boolean = false, allowStale: Boolean = false) {
        if (changedElsewhere && !allowStale) { closeAfterStale = close; askingStale = true; return }
        // A new task (also one handed over from Quick entry) never saves a reminder that has passed.
        if (reminderAt != null && (creating || reminderAt != initial.reminderAt) && reminderAt!! <= System.currentTimeMillis()) {
            error = "Choose a future reminder date and time."
        } else action(keepOpen = !close) { repo.saveTask(initial.copy(title = title, notes = notes, dueDate = date?.let(LocalDate::parse), priority = TaskPriority.valueOf(priority), reminderAt = reminderAt,
            repeat = repeat, repeatDays = repeatDays.toIntOrNull()?.coerceIn(1, 3650) ?: 7,
            repeatAnchorDay = if (date != initial.dueDate?.toString() || repeat != initial.repeat) 0 else initial.repeatAnchorDay,
            checklist = checklist.map { it.copy(text = it.text.trim()) }, attachments = attachments, prerequisiteIds = prerequisiteIds), create = creating) }
    }
    // Close (and Back) leaves at once when nothing is unsaved; otherwise it asks first. A task deleted elsewhere can't be
    // saved: Close leaves, keeping anything unsaved as its draft for when it is restored.
    fun close() { if (deletedElsewhere) { if (unsaved) onDismiss() else discard() } else if (unsaved) askingToSave = true else discard() }
    // Asked from outside: the same as Close, once any save under way has finished.
    LaunchedEffect(closeRequested) {
        if (closeRequested) { snapshotFlow { busy }.first { !it }; if (!askingToSave) close() }
    }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            busy = true
            scope.launch {
                try {
                    val imported = attachmentStore.import(uri)
                    if (imported != null) attachments = attachments + imported
                    else error = "Couldn't attach this file. Please try again."
                } finally { busy = false }
            }
        }
    }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val name = pendingPhoto
        if (name != null) {
            if (success) attachments = attachments + Attachment(itemId = 0, name = "Task photo.jpg", fileName = name, mimeType = "image/jpeg")
            else attachmentStore.delete(name)
        }
        pendingPhoto = null
    }
    BackHandler { if (!busy) close() }
    val taskScroll = rememberScrollState()
    val checklistAnchor = remember { ChecklistAnchor() }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            ScrollHints(taskScroll, Modifier.weight(1f).fillMaxWidth(),
                overlay = { ChecklistJumpButton(checklist, checklistAnchor, taskScroll) }) { Column(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                HeadingText(if (creating) "Add task" else "Edit task", style = MaterialTheme.typography.headlineSmall)
                if (changedElsewhere || deletedElsewhere) Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(12.dp)) {
                    FlowRow(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                        verticalArrangement = Arrangement.Center, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(if (deletedElsewhere) EditorRules.taskDeletedElsewhereBanner() else EditorRules.taskChangedElsewhereBanner(),
                            color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.align(Alignment.CenterVertically).padding(vertical = 8.dp))
                        if (changedElsewhere) TextButton(enabled = !busy, onClick = { if (unsaved) askingReload = true else reload() }) { Text("Reload") }
                    }
                }
                OutlinedTextField(title, onValueChange = { if (it.length <= 500) title = it.replace('\n', ' ') },
                    label = { Text("Task title") }, enabled = !busy, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done), modifier = Modifier.fillMaxWidth())
                if (!creating) {
                    TextButton(enabled = !busy && title.isNotBlank(), onClick = {
                        duplicate = initial.copy(title = title, notes = notes, priority = TaskPriority.valueOf(priority),
                            checklist = checklist, attachments = attachments, repeat = repeat, prerequisiteIds = prerequisiteIds,
                            repeatDays = repeatDays.toIntOrNull() ?: initial.repeatDays).duplicateForEditing()
                    }) { Text("Duplicate task") }
                    TextButton(enabled = !busy, onClick = {
                        if (EditorDraftStore.openEditors.value > 0 || runCatching { EditorDraftStore(context).read() }.getOrNull() != null)
                            error = "Close your current event editor before scheduling another block."
                        else schedule = true
                    }) { Text("Schedule time") }
                    TaskTimeBlocks(initial.id)
                }
                Text("Due date (optional)", style = MaterialTheme.typography.labelLarge)
                TextButton(enabled = !busy, onClick = { choosingDate = true }) {
                    Text(date?.let { LocalDate.parse(it).dayLabel(LocalDateFormat.current) } ?: "Choose due date")
                }
                if (date != null) TextButton(enabled = !busy, onClick = { date = null }) { Text("Remove due date") }
                val repeatKind = TaskRepeat.of(repeat)
                SettingsDropdown(label = "Repeat", current = if (repeatKind.detailed) RepeatRule.parse(repeat)?.label ?: repeatKind.label else repeatKind.label,
                    options = TaskRepeat.entries.toList(), onSelect = { kind ->
                        if (!busy && kind != repeatKind) repeat = if (kind.detailed)
                            RepeatRule.Kind.valueOf(kind.name).startingRule(date?.let(LocalDate::parse) ?: LocalDate.now()).name else kind.name
                    }, entry = { Text(if (it.detailed) RepeatRule.Kind.valueOf(it.name).choiceLabel() else it.label) })
                if (repeatKind.detailed) RepeatRule.parse(repeat, complete = false)?.let { rule ->
                    RepeatDetails(rule, enabled = !busy) { repeat = it.name }
                }
                if (repeat == TaskRepeat.AFTER_COMPLETION.name) OutlinedTextField(repeatDays,
                    onValueChange = { if (it.length <= 4 && it.all(Char::isDigit)) repeatDays = it }, enabled = !busy,
                    label = { Text("Days after completion (1–3650)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (repeat != TaskRepeat.NONE.name) Text("Completing this task creates the next occurrence. Missed calendar dates are skipped.", style = MaterialTheme.typography.bodySmall)
                // The same layout as an event's Reminders section; a task has one reminder at a date and time.
                var presetsStale by remember { mutableIntStateOf(0) }
                val presets = remember(date, reminderAt, presetsStale) { taskReminderPresets(date?.let(LocalDate::parse)) }
                val nine = java.time.LocalTime.of(9, 0).label(LocalTimeFormat.current, context)
                ReminderSectionFrame(notifications.enabled, notifications.enable,
                    hints = listOfNotNull(if (reminderAt != null && !exactAllowed) LATE_REMINDER_HINT else null),
                    chips = if (reminderAt != null) emptyList() else presets.map { (preset, _) ->
                        when (preset) {
                            TaskReminderPreset.ON_THE_DAY -> "On the day $nine"
                            TaskReminderPreset.DAY_BEFORE -> "1 day before"
                            TaskReminderPreset.LATER_TODAY -> "In 1 hour"
                            TaskReminderPreset.TOMORROW -> "Tomorrow $nine"
                        } to {
                            // Timed from the tap, not from when the editor opened; a choice that has passed since goes.
                            val at = taskReminderPresetAt(preset, date?.let(LocalDate::parse))
                            if (at != null) { reminderAt = at; error = null } else presetsStale += 1
                        }
                    } + ("Custom" to {
                        reminderSuggestion = taskReminderDefault(date?.let(LocalDate::parse)).toString()
                        choosingReminderDate = true
                    }),
                    enabled = !busy) {
                    reminderAt?.let { at ->
                        // Saving keeps the snooze only while the reminder time is unchanged (Repository.saveTask).
                        val snoozed = initial.snoozedAt(System.currentTimeMillis())
                        ReminderRow(taskReminderLabel(at), when {
                            initial.done -> "Reminders are off while this task is completed."
                            snoozed != null && at == initial.reminderAt -> "Snoozed until ${taskReminderLabel(snoozed)}. Changing the reminder ends the snooze."
                            else -> null
                        }, onRemove = { reminderAt = null }, enabled = !busy)
                    }
                }
                Text("Priority", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TaskPriority.entries.forEach { option ->
                        FilterChip(selected = priority == option.name, enabled = !busy, onClick = { priority = option.name }, label = { Text(option.label) })
                    }
                }
                OutlinedTextField(notes, onValueChange = { if (it.length <= 20_000) notes = it }, label = { Text("Notes (optional)") },
                    enabled = !busy, minLines = 4, modifier = Modifier.fillMaxWidth())
                TaskPrerequisites(initial.id, prerequisiteIds, enabled = !busy, onChange = { prerequisiteIds = it })
                ChecklistSection(checklist, onChange = { if (!busy) checklist = it }, anchor = checklistAnchor)
                HorizontalDivider()
                HeadingText("Attachments", style = MaterialTheme.typography.titleMedium)
                attachments.forEach { attachment ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(enabled = !busy, modifier = Modifier.weight(1f), onClick = { openAttachment(context, attachmentStore, attachment) }) { Text(attachment.name) }
                        TextButton(enabled = !busy, onClick = {
                            attachments = attachments.filterNot { it.fileName == attachment.fileName }
                            // Persist the removal before checking shared-file ownership.
                            draftStore.write(draftKey, snapshot.put("attachments", DraftCodec.attachments(attachments)))
                            scope.launch { repo.releaseTaskFiles(listOf(attachment.fileName)) }
                        }) { Text("Remove") }
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = !busy && attachments.size < 100, onClick = { pickFile.launch(arrayOf("*/*")) }) { Text("Attach file") }
                    OutlinedButton(enabled = !busy && attachments.size < 100, onClick = {
                        val file = attachmentStore.newPhotoFile()
                        pendingPhoto = file.name
                        draftStore.write(draftKey, snapshot.put("pendingPhoto", file.name))
                        try { takePhoto.launch(attachmentStore.uriFor(file.name)) }
                        catch (_: Exception) { file.delete(); pendingPhoto = null; error = "No camera is available." }
                    }) { Text("Take photo") }
                }
            } }
            HorizontalDivider()
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                EditorActions(onDelete = if (!creating && !deletedElsewhere) ({ confirmingDelete = true }) else null,
                    onClose = ::close, onSave = { save() }, deleteEnabled = !busy, closeEnabled = !busy,
                    saveEnabled = canSave && (unsaved || creating)) { SaveLabel(busy, saved = justSaved && !unsaved) }
            }
        }
    }
    if (choosingDate) SingleDateDialog(date?.let(LocalDate::parse) ?: LocalDate.now(),
        onDismiss = { choosingDate = false }, onConfirm = { date = it.toString(); choosingDate = false })
    if (choosingReminderDate) SingleDateDialog(
        reminderAt?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate() }
            ?: reminderSuggestion?.let(ZonedDateTime::parse)?.toLocalDate() ?: LocalDate.now(),
        onDismiss = { choosingReminderDate = false },
        onConfirm = { reminderDateDraft = it.toString(); choosingReminderDate = false; choosingReminderTime = true })
    if (choosingReminderTime) TimePickerDialog(
        initial = reminderAt?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalTime() }
            ?: reminderSuggestion?.let(ZonedDateTime::parse)?.toLocalTime() ?: taskReminderDefault(null).toLocalTime(),
        onDismiss = { choosingReminderTime = false },
        onConfirm = { time ->
            reminderAt = taskReminderInstant(LocalDate.parse(reminderDateDraft!!), time)
            choosingReminderTime = false
            error = null
        })
    duplicate?.let { TaskEditor(it, true) { duplicate = null } }
    if (schedule) ScheduleTaskDialog(initial) { schedule = false }
    if (askingToSave) PlannerDialog("Save changes?", onDismissRequest = { askingToSave = false; onCloseCancelled() },
        primary = DialogAction("Save", enabled = canSave) { askingToSave = false; save(close = true) },
        dismiss = DialogAction("Keep editing") { askingToSave = false; onCloseCancelled() },
        extra = listOf(DialogAction("Discard", danger = true) { askingToSave = false; discard() })) {
        Text(if (canSave || busy) "This task has changes that aren't saved yet."
            else "This task has changes that can't be saved as they are. Keep editing to fix them, or discard them.")
    }
    // Save while the task changed underneath: keep this version (written over the stored one), or reload that.
    if (askingStale) PlannerDialog("Changed elsewhere",
        onDismissRequest = { askingStale = false; closeAfterStale = false },
        primary = DialogAction("Save anyway", enabled = !busy) { askingStale = false; save(close = closeAfterStale, allowStale = true) },
        dismiss = DialogAction("Reload", enabled = !busy) { askingStale = false; closeAfterStale = false; reload() },
    ) {
        Text("This task changed since you opened it, on Nextcloud or elsewhere in Planner. Save your version anyway?")
        Text("Reload shows it as it is now, without your unsaved changes.", style = MaterialTheme.typography.bodySmall)
    }
    if (askingReload) PlannerDialog("Reload?",
        onDismissRequest = { askingReload = false },
        primary = DialogAction("Reload", danger = true, enabled = !busy) { askingReload = false; reload() },
        dismiss = DialogAction("Keep editing") { askingReload = false },
    ) {
        Text("Your unsaved changes to this task will be lost.")
    }
    if (confirmingDelete) PlannerDialog("Delete task?", onDismissRequest = { confirmingDelete = false },
        primary = DialogAction("Delete", enabled = !busy, danger = true) { confirmingDelete = false; action { repo.deleteTask(initial.id) } },
        dismiss = DialogAction("Keep task") { confirmingDelete = false }) {
        Text("${initial.title} will be kept in Recently deleted for 30 days.")
    }
}

// A task's ⋮: Due tomorrow (with the Undo bar an event's move has), Share task and Delete task (Undo bar too). Done is
// the box on the card.
@Composable
private fun TaskActionsMenu(task: PlannerTask, today: LocalDate) {
    val context = LocalContext.current
    val repo = (context.applicationContext as ItineraryApp).repository
    val app = context.applicationContext as ItineraryApp
    var busy by remember(task.id) { mutableStateOf(false) }
    fun act(failure: String, block: suspend () -> Unit) {
        busy = true
        // The app's scope: the card may leave the list once its date changes.
        app.appScope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                android.widget.Toast.makeText(context, failure, android.widget.Toast.LENGTH_LONG).show() } }
            finally { busy = false }
        }
    }
    val tomorrow = today.plusDays(1)
    OverlayMenuAnchor(title = "Actions for ${task.title}", button = { open ->
        IconButton(enabled = !busy, onClick = open) {
            Icon(androidx.compose.material.icons.Icons.Default.MoreVert, contentDescription = "Actions for ${task.title}")
        }
    }, items = { close ->
        DropdownMenuItem(text = { Text(if (task.dueDate == tomorrow) "Already tomorrow" else "Due tomorrow") },
            enabled = !busy && task.dueDate != tomorrow,
            onClick = { close(); act("Couldn't change the date. Please try again.") { repo.moveTaskToTomorrow(task.id, today) } })
        DropdownMenuItem(text = { Text("Share task") }, onClick = { close(); shareTask(context, task.title, task.dueDate, task.notes) })
        DropdownMenuItem(text = { Text("Delete task", color = MaterialTheme.colorScheme.error) }, enabled = !busy,
            onClick = { close(); act("Couldn't delete this task. Please try again.") { repo.deleteTask(task.id) } })
    })
}
