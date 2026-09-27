package com.example.itinerary.ui

import com.example.itinerary.ui.MatrixTextButton as TextButton
import com.example.itinerary.ui.MatrixFilterChip as FilterChip
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import com.example.itinerary.ItineraryApp
import com.example.itinerary.data.*
import kotlinx.coroutines.*
import java.time.LocalDate
import java.time.ZonedDateTime

@Composable
fun QuickEntryDialog(
    today: LocalDate, onDismiss: () -> Unit,
    onAdd: suspend (QuickEntrySuggestion, Boolean, String) -> Unit,
    onReview: (QuickEntrySuggestion, Boolean) -> Unit,
) {
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val app = context.applicationContext as ItineraryApp
    val repository = app.repository
    val aiEnabled by app.settings.aiFeaturesEnabled.collectAsState()
    val store = remember { QuickDraftStore(context) }
    val loaded = remember { runCatching { store.read() } }
    var draft by remember { mutableStateOf(loaded.getOrNull() ?: QuickDraft(single = QuickInput(baseDate = today))) }
    var error by remember { mutableStateOf(if (loaded.isFailure) "Couldn't read the previous draft. Close and retry, or discard it to start again." else null) }
    var loadFailed by remember { mutableStateOf(loaded.isFailure) }
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var singleGeneration by rememberSaveable { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var aiBusy by remember { mutableStateOf(false) }
    var discard by remember { mutableStateOf(false) }
    var warnings by remember { mutableStateOf<List<String>>(emptyList()) }
    var permission by remember { mutableStateOf<CompletableDeferred<Boolean>?>(null) }
    val scope = rememberCoroutineScope()
    var now by remember { mutableStateOf(ZonedDateTime.now()) }
    LaunchedEffect(Unit) { while (true) { now = ZonedDateTime.now(); delay(30_000) } }
    fun update(next: QuickDraft) {
        if (next == draft || loadFailed) return
        try { store.write(next); draft = next; error = null }
        catch (_: Exception) { error = "Couldn't keep this draft. Free some storage and try again." }
    }
    fun row(id: String, change: (QuickRow) -> QuickRow) = update(draft.copy(rows = draft.rows.map { if (it.id == id) change(it) else it }))
    suspend fun confirm(candidates: List<QuickCandidate>): Boolean {
        val snapshot = repository.snapshot()
        val found = withContext(Dispatchers.Default) { quickConflicts(candidates, snapshot.items, snapshot.tasks) }
        if (found.isEmpty()) return true
        warnings = found
        val answer = CompletableDeferred<Boolean>()
        permission = answer
        return try { answer.await() } finally { permission = null; warnings = emptyList() }
    }
    LaunchedEffect(Unit) {
        if (!loadFailed && draft.rows.any { it.status == "attempted" }) {
            busy = true
            try {
                val snapshot = repository.snapshot()
                update(draft.copy(rows = reconcileQuickRows(draft.rows, snapshot.items, snapshot.tasks)))
            } catch (_: Exception) { error = "Couldn't check the last save. Retry when ready." }
            finally { busy = false }
        }
    }
    fun persist(next: QuickDraft) { store.write(next); draft = next }
    fun saveBatch() {
        if (busy) return
        busy = true; error = null
        scope.launch {
            try {
                // Reconcile a save whose database commit outlived its UI/draft write.
                val snapshot = repository.snapshot()
                persist(draft.copy(rows = reconcileQuickRows(draft.rows, snapshot.items, snapshot.tasks)))
                val selected = draft.rows.filter { it.selected && it.status != "saved" }
                val problem = selected.firstNotNullOfOrNull { it.problem(ZonedDateTime.now()) }
                if (problem != null) { error = problem; return@launch }
                if (selected.isEmpty()) return@launch
                if (!confirm(selected.map { QuickCandidate(it.id, it.input.suggestion(), it.input.task, "Entry ${draft.rows.indexOf(it) + 1}: ${it.input.suggestion().title}") })) return@launch
                withContext(NonCancellable) {
                    saveQuickRows(draft.rows, onPersist = { persist(draft.copy(rows = it)) }, onSave = onAdd)
                }
                val saved = draft.rows.count { it.status == "saved" }
                android.widget.Toast.makeText(context, "$saved entries saved", android.widget.Toast.LENGTH_SHORT).show()
                if (draft.rows.all { it.status == "saved" }) { store.clear(); onDismiss() }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = "Save stopped. Saved rows are kept; retry adds only the remaining entries." }
            finally { busy = false }
        }
    }
    val modes: @Composable () -> Unit = {
        TextButton(modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp), enabled = !busy && !loadFailed,
            onClick = {
            update(if (draft.multiple) draft.copy(multiple = false, single = draft.single.copy(text = draft.text))
                else draft.copy(multiple = true))
        }) {
            Text(if (draft.multiple) "Back to single entry" else "Paste multiple entries",
                style = MaterialTheme.typography.bodyMedium)
        }
    }

    val active = draft.rows.firstOrNull { it.id == editing }
    if (loadFailed) {
        AlertDialog(containerColor = MaterialTheme.colorScheme.background, tonalElevation = 0.dp, onDismissRequest = onDismiss, title = { Text("Quick entry draft") }, text = { Text(error.orEmpty()) },
            confirmButton = { TextButton(onClick = { discard = true }) { Text("Discard draft") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } })
    } else if (active != null) {
        key(active.id) {
            // All corrections use the same controls as single entry; Done never writes to the database.
            QuickEntryEditor(active.input.baseDate, onDismiss = { editing = null },
                onAdd = { _, _, _ -> row(active.id) { it.copy(typeChosen = true) }; editing = null },
                onReview = { _, _ -> }, initial = active.input, batchRow = true,
                inputBlocked = error != null, modeControls = { if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error) },
                onInput = { input -> row(active.id) { it.copy(input = input) } })
        }
    } else if (!draft.multiple) {
        key(singleGeneration) { QuickEntryEditor(draft.single.baseDate, onDismiss = onDismiss,
            onAdd = { suggestion, task, token ->
                busy = true
                try {
                    if (!confirm(listOf(QuickCandidate(token, suggestion, task)))) throw QuickSaveCancelled()
                    require(suggestion.quickProblem(task, ZonedDateTime.now()) == null)
                    onAdd(suggestion, task, token)
                    // A retained batch belongs to the same text: keep its review when saving a single entry.
                    if (draft.rows.isEmpty()) store.clear()
                    android.widget.Toast.makeText(context, if (task) "Task added" else "Event added", android.widget.Toast.LENGTH_SHORT).show()
                } finally { busy = false }
            },
            onReview = { s, t -> if (draft.rows.isEmpty()) store.clear(); onReview(s, t) },
            onContinue = { task ->
                persist(draft.copy(single = QuickInput(task = task, baseDate = LocalDate.now()), text = "", reviewing = draft.rows.isNotEmpty()))
                singleGeneration++
            },
            onDiscard = { discard = true },
            initial = draft.single, inputBlocked = error != null, onInput = { input -> update(draft.copy(single = input, text = input.text, reviewing = draft.reviewing && input.text == draft.text)) }, modeControls = {
                modes()
                if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error)
            }) }
    } else {
        val selected = draft.rows.filter { it.selected && it.status != "saved" }
        val tasks = selected.count { it.typeChosen && it.input.task }
        val events = selected.filter { it.typeChosen && !it.input.task }.sumOf { it.count.coerceAtLeast(0) }
        val issues = selected.count { it.status == "pending" && it.problem(now) != null }
        AlertDialog(containerColor = MaterialTheme.colorScheme.background, tonalElevation = 0.dp, onDismissRequest = { if (!busy) onDismiss() }, title = { if (draft.reviewing) Text("Review entries") else HeadingText("Quick entry") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    modes()
                    if (!draft.reviewing) {
                        Text("One entry per line. Bullets and numbering are optional. Nothing is added until you review it.")
                        OutlinedTextField(draft.text, { value ->
                            val single = draft.single.edited(value)
                            update(draft.copy(text = value, single = single))
                        }, enabled = !busy, label = { Text("Entries, one per line") }, minLines = 4, maxLines = 9)
                        QuickAiAction(draft.single.copy(text = draft.text), multiple = true,
                            enabled = !busy && draft.rows.isEmpty(), onWorking = { aiBusy = it }, onResult = { entries ->
                                keyboard?.hide()
                                update(draft.copy(reviewing = true, rows = entries.map { entry ->
                                    QuickRow(source = entry.source, input = QuickInput(text = entry.source, task = entry.task,
                                        baseDate = draft.single.baseDate, ai = entry), typeChosen = true)
                                }))
                            })
                        if (aiEnabled && draft.rows.isNotEmpty()) Text("Your reviewed entries are kept. Discard the draft to start a new AI list.", style = MaterialTheme.typography.bodySmall)
                        QuickBatch.problem(draft.text)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    } else {
                        Text("Selected: $tasks ${if (tasks == 1) "task" else "tasks"} · $events ${if (events == 1) "event" else "events"}", style = MaterialTheme.typography.titleSmall)
                        if (issues > 0) Text("$issues selected entries need attention.", color = MaterialTheme.colorScheme.error)
                        if (events + tasks > 1000) Text("Select at most 1,000 tasks and event occurrences at a time.", color = MaterialTheme.colorScheme.error)
                        Text("Untick any entry to leave it for later. Close keeps your review.", style = MaterialTheme.typography.bodySmall)
                        draft.rows.forEachIndexed { index, r ->
                            HorizontalDivider()
                            Row {
                                Checkbox(modifier = Modifier.semantics { contentDescription = "Select entry ${index + 1}" }, checked = r.selected, enabled = !busy && r.status == "pending", onCheckedChange = { value -> row(r.id) { it.copy(selected = value) } })
                                Text("${index + 1}. ${r.input.suggestion().title.ifBlank { "Empty entry" }}", modifier = Modifier.weight(1f))
                            }
                            if (r.status == "saved") Text("Saved") else {
                                if (r.status == "attempted") Text("Save awaiting confirmation. Retry safely checks it first.")
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FilterChip(modifier = Modifier.semantics { contentDescription = "Task ${index + 1}" }, selected = r.typeChosen && r.input.task, enabled = !busy && r.status == "pending", onClick = { row(r.id) { it.copy(input = it.input.copy(task = true), typeChosen = true) } }, label = { Text("Task") })
                                    FilterChip(modifier = Modifier.semantics { contentDescription = "Event ${index + 1}" }, selected = r.typeChosen && !r.input.task, enabled = !busy && r.status == "pending", onClick = { row(r.id) { it.copy(input = it.input.copy(task = false), typeChosen = true) } }, label = { Text("Event") })
                                }
                                val s = r.input.suggestion()
                                Text((if (r.input.task && !s.dateSpecified) "No due date" else s.date.fullLabel()) + (s.time?.let { " · $it" } ?: "") +
                                    if (s.repeat == RepeatRule.NONE) "" else if (r.input.task) " · ${s.repeat.label} after completion" else " · ${r.count} events", style = MaterialTheme.typography.bodySmall)
                                if (s.location.isNotBlank()) Text("Location: ${s.location}", style = MaterialTheme.typography.bodySmall)
                                s.durationMinutes?.let { Text("$it minutes", style = MaterialTheme.typography.bodySmall) }
                                if (s.reminderMinutes != null) Text("Reminder: ${s.quickReminders().single().label}", style = MaterialTheme.typography.bodySmall)
                                if (r.input.baseDate != today) Text("Dates based on ${r.input.baseDate.fullLabel()}", style = MaterialTheme.typography.bodySmall)
                                r.problem(now)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                                if (s.isPast(now, r.input.task)) Text("This date or time is in the past.", color = MaterialTheme.colorScheme.error)
                                TextButton(enabled = !busy && r.status == "pending", onClick = { editing = r.id }) { Text("Edit entry ${index + 1}") }
                            }
                        }
                        TextButton(enabled = !busy && draft.rows.all { it.status == "pending" }, onClick = { update(draft.copy(reviewing = false, text = draft.rows.joinToString("\n") { it.source })) }) { Text("Edit list") }
                    }
                    if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error)
                    TextButton(enabled = !busy, onClick = { discard = true }) { Text("Discard draft") }
                }
            },
            confirmButton = { TextButton(enabled = !busy && !aiBusy && if (draft.reviewing) selected.isNotEmpty() && issues == 0 && events + tasks <= 1000 else QuickBatch.problem(draft.text) == null,
                onClick = { keyboard?.hide(); if (draft.reviewing) saveBatch() else update(draft.copy(reviewing = true, rows = QuickBatch.review(draft.text, today, draft.rows))) }) {
                Text(if (busy) "Saving…" else if (draft.reviewing) "Add selected" else "Review entries")
            } }, dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Close") } })
    }
    if (permission != null) AlertDialog(containerColor = MaterialTheme.colorScheme.background, tonalElevation = 0.dp, onDismissRequest = { permission?.complete(false) }, title = { Text("Check before adding") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("These entries may duplicate or overlap your plans. You can go back to edit or deselect them.")
            warnings.forEach { Text(it) }
        } }, confirmButton = { TextButton(onClick = { permission?.complete(true) }) { Text("Add anyway") } },
        dismissButton = { TextButton(onClick = { permission?.complete(false) }) { Text("Go back") } })
    if (discard) AlertDialog(containerColor = MaterialTheme.colorScheme.background, tonalElevation = 0.dp, onDismissRequest = { discard = false }, title = { Text("Discard quick entry draft?") },
        text = { Text("Unfinished text and review choices will be removed. Entries already saved stay in your planner.") },
        confirmButton = { TextButton(onClick = {
            try { store.clear(); singleGeneration++; draft = QuickDraft(single = QuickInput(baseDate = today)); loadFailed = false; error = null; editing = null; discard = false }
            catch (_: Exception) { error = "Couldn't discard the draft. Try again."; discard = false }
        }) { Text("Discard") } }, dismissButton = { TextButton(onClick = { discard = false }) { Text("Keep draft") } })
}

internal class QuickSaveCancelled : Exception()
