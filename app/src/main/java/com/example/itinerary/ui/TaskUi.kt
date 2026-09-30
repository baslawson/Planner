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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
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

@Composable
fun TaskCard(task: PlannerTask, today: LocalDate, enabled: Boolean = true, onEdit: () -> Unit) {
    val context = LocalContext.current
    val repo = (context.applicationContext as ItineraryApp).repository
    val scope = rememberCoroutineScope()
    val allTasks by repo.tasks.collectAsStateWithLifecycle(initialValue = emptyList())
    val blockers = TaskDependencies.blockers(task, allTasks)
    var busy by remember(task.id) { mutableStateOf(false) }
    OutlinedCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth().clickable(enabled = enabled && !busy, onClick = onEdit).padding(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = task.done, enabled = enabled && !busy && (task.done || blockers.isEmpty()),
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
                if (!task.done) task.reminderAt?.let { timestamp ->
                    val reminder = Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault())
                    Text("Reminder: ${reminder.toLocalDate().dayLabel(LocalDateFormat.current)}, ${reminder.toLocalTime().label(LocalTimeFormat.current, context)}",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TaskEditor(initial: PlannerTask, creating: Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val store = remember { TaskDraftStore(context) }
    val draftKey = if (creating) "new" else initial.id
    val recovered = remember(draftKey) { runCatching { store.read(draftKey) }.getOrNull() }
    var decision by rememberSaveable(draftKey) { mutableStateOf(if (recovered == null) "fresh" else "ask") }
    val scope = rememberCoroutineScope()
    val repo = (context.applicationContext as ItineraryApp).repository
    if (decision == "ask") {
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
            TaskEditorContent(source, creating, draft, draftKey, store, onDismiss)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TaskEditorContent(initial: PlannerTask, creating: Boolean, draft: JSONObject?, draftKey: String,
                              draftStore: TaskDraftStore, onDismiss: () -> Unit) {
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
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun action(block: suspend () -> Unit) {
        if (busy) return
        busy = true; error = null
        scope.launch {
            try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    block(); finished = true; draftStore.clear(draftKey)
                    repo.releaseTaskFiles(attachments.map { it.fileName } + listOfNotNull(pendingPhoto))
                }
                onDismiss()
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
    BackHandler { if (!busy) onDismiss() }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            ScrollHints(rememberScrollState(), Modifier.weight(1f).fillMaxWidth()) { Column(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                HeadingText(if (creating) "Add task" else "Edit task", style = MaterialTheme.typography.headlineSmall)
                OutlinedTextField(title, onValueChange = { if (it.length <= 500) title = it.replace('\n', ' ') },
                    label = { Text("Task title") }, enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (!creating) {
                    TextButton(enabled = !busy && title.isNotBlank(), onClick = {
                        duplicate = initial.copy(title = title, notes = notes, priority = TaskPriority.valueOf(priority),
                            checklist = checklist, attachments = attachments, repeat = repeat, prerequisiteIds = prerequisiteIds,
                            repeatDays = repeatDays.toIntOrNull() ?: initial.repeatDays).duplicateForEditing()
                    }) { Text("Duplicate task") }
                    TextButton(enabled = !busy, onClick = {
                        if (runCatching { EditorDraftStore(context).read() }.getOrNull() != null)
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
                Text("Reminder (optional)", style = MaterialTheme.typography.labelLarge)
                TextButton(enabled = !busy, onClick = {
                    reminderSuggestion = taskReminderDefault(date?.let(LocalDate::parse)).toString()
                    choosingReminderDate = true
                }) {
                    Text(reminderAt?.let {
                        val reminder = Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())
                        "${reminder.toLocalDate().dayLabel(LocalDateFormat.current)}, ${reminder.toLocalTime().label(LocalTimeFormat.current, context)}"
                    } ?: "Add reminder")
                }
                if (reminderAt != null) {
                    TextButton(enabled = !busy, onClick = { reminderAt = null }) { Text("Remove reminder") }
                    if (initial.done) Text("Reminders are off while this task is completed.")
                    if (!notifications.enabled) {
                        Text("Notifications are off. Enable them to receive task reminders.")
                        TextButton(onClick = notifications.enable) { Text("Enable notifications") }
                    }
                    if (!exactAllowed) Text("Android may deliver this reminder late. Enable Alarms & reminders in app settings for precise timing.")
                }
                Text("Priority", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TaskPriority.entries.forEach { option ->
                        FilterChip(selected = priority == option.name, enabled = !busy, onClick = { priority = option.name }, label = { Text(option.label) })
                    }
                }
                OutlinedTextField(notes, onValueChange = { if (it.length <= 20_000) notes = it }, label = { Text("Notes (optional)") },
                    enabled = !busy, minLines = 4, maxLines = 8, modifier = Modifier.fillMaxWidth())
                TaskPrerequisites(initial.id, prerequisiteIds, enabled = !busy, onChange = { prerequisiteIds = it })
                ChecklistSection(checklist, onChange = { if (!busy) checklist = it })
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
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End,
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!creating) DangerButton(enabled = !busy, onClick = { confirmingDelete = true }) { Text("Delete") }
                    OutlinedButton(enabled = !busy, onClick = ::discard) { Text("Discard") }
                    Spacer(Modifier.width(12.dp))
                    Button(enabled = !busy && title.isNotBlank() && checklist.none { it.text.isBlank() } && TaskRepeat.valid(repeat) &&
                        (repeat != TaskRepeat.AFTER_COMPLETION.name || repeatDays.toIntOrNull() in 1..3650), onClick = {
                        // A new task (also one handed over from Quick entry) never saves a reminder that has passed.
                        if (reminderAt != null && (creating || reminderAt != initial.reminderAt) && reminderAt!! <= System.currentTimeMillis()) {
                            error = "Choose a future reminder date and time."
                        } else action { repo.saveTask(initial.copy(title = title, notes = notes, dueDate = date?.let(LocalDate::parse), priority = TaskPriority.valueOf(priority), reminderAt = reminderAt,
                            repeat = repeat, repeatDays = repeatDays.toIntOrNull()?.coerceIn(1, 3650) ?: 7,
                            repeatAnchorDay = if (date != initial.dueDate?.toString() || repeat != initial.repeat) 0 else initial.repeatAnchorDay,
                            checklist = checklist.map { it.copy(text = it.text.trim()) }, attachments = attachments, prerequisiteIds = prerequisiteIds), create = creating) }
                    }) { Text("Save") }
                }
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
    if (confirmingDelete) PlannerDialog("Delete task?", onDismissRequest = { confirmingDelete = false },
        primary = DialogAction("Delete", enabled = !busy, danger = true) { confirmingDelete = false; action { repo.deleteTask(initial.id) } },
        dismiss = DialogAction("Keep task") { confirmingDelete = false }) {
        Text("${initial.title} will be kept in Recently deleted for 30 days.")
    }
}
