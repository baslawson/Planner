package com.example.itinerary.ui

import com.example.itinerary.ui.MatrixTextButton as TextButton
import com.example.itinerary.ui.MatrixOutlinedButton as OutlinedButton
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.itinerary.ItineraryApp
import com.example.itinerary.data.*
import java.time.LocalDate

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SavedSearchControls(query: String, categories: Set<String>, showCompleted: Boolean, onOpen: (SavedSearch) -> Unit) {
    val settings = (LocalContext.current.applicationContext as ItineraryApp).settings
    val saved by settings.savedSearches.collectAsStateWithLifecycle()
    var choosing by rememberSaveable { mutableStateOf(false) }
    var naming by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    fun update(values: List<SavedSearch>) = try { settings.setSavedSearches(values); true }
        catch (e: Exception) { error = e.message ?: "Couldn't save searches."; false }
    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(enabled = query.isNotBlank() || categories.isNotEmpty(), onClick = { name = ""; naming = true; error = null }) { Text("Save search") }
        TextButton(onClick = { choosing = true; error = null }) { Text("Saved searches (${saved.size})") }
    }
    if (naming) PlannerDialog("Save search", onDismissRequest = { naming = false },
        primary = DialogAction(if (saved.any { it.name.equals(name.trim(), true) }) "Replace" else "Save", enabled = name.isNotBlank()) {
            val value = SavedSearch(name.trim(), query, categories, showCompleted)
            if (update(saved.filterNot { it.name.equals(value.name, true) } + value)) naming = false
        },
        dismiss = DialogAction("Cancel") { naming = false }) {
            OutlinedTextField(name, { name = it.replace('\n', ' ').take(80) }, label = { Text("Search name") },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done))
            Text("Keeps your query, categories and completed filter. Relative dates update each time you open it.")
            if (saved.any { it.name.equals(name.trim(), true) }) Text("This replaces the saved search with this name.")
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
    if (choosing) PlannerDialog("Saved searches", onDismissRequest = { choosing = false },
        dismiss = DialogAction("Close") { choosing = false }, scroll = null) {
            if (saved.isEmpty()) Text("Set a search and filters, then choose Save search.")
            LazyScrollHints(Modifier.heightIn(max = 380.dp)) { hintState -> LazyColumn(Modifier.fillMaxWidth(), state = hintState) { items(saved, key = { it.name }) { value ->
                Row(Modifier.fillMaxWidth()) {
                    TextButton(modifier = Modifier.weight(1f), onClick = { onOpen(value); choosing = false }) { Text(value.name) }
                    TextButton(onClick = { update(saved - value) }) { Text("Remove") }
                }
            } } }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
fun TaskPrerequisites(taskId: String, ids: List<String>, enabled: Boolean, onChange: (List<String>) -> Unit) {
    val app = LocalContext.current.applicationContext as ItineraryApp
    val tasks by app.repository.tasks.collectAsStateWithLifecycle(initialValue = emptyList())
    var choosing by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var opening by rememberSaveable { mutableStateOf<String?>(null) }
    Text("Prerequisites", style = MaterialTheme.typography.titleMedium)
    ids.forEach { id ->
        val task = tasks.find { it.id == id }
        Row(Modifier.fillMaxWidth()) {
            TextButton(enabled = task != null && enabled, modifier = Modifier.weight(1f), onClick = { opening = id }) {
                Text(task?.let { "${if (it.done) "Done" else "Waiting"}: ${it.title}" } ?: "Deleted prerequisite — restore or remove")
            }
            TextButton(enabled = enabled, onClick = { onChange(ids - id) }) { Text("Remove") }
        }
    }
    OutlinedButton(enabled = enabled && ids.size < 100, onClick = { query = ""; choosing = true }) { Text("Add prerequisite") }
    if (ids.isNotEmpty()) Text("Complete these tasks before marking this task done. Repeats and duplicates keep these same prerequisites.", style = MaterialTheme.typography.bodySmall)
    if (choosing) {
        val choices = remember(tasks, taskId, ids, query) {
            tasks.filter { candidate -> candidate.id != taskId && candidate.id !in ids && candidate.title.contains(query, true) &&
                runCatching { TaskDependencies.validateGraph(tasks.filterNot { it.id == taskId } +
                    PlannerTask(id = taskId, prerequisiteIds = ids + candidate.id)) }.isSuccess }
                .sortedWith(Tasks.order)
        }
        PlannerDialog("Choose prerequisite", onDismissRequest = { choosing = false },
            dismiss = DialogAction("Cancel") { choosing = false }, scroll = null) {
                OutlinedTextField(query, { query = it.replace('\n', ' ') }, label = { Text("Find task") },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done))
                if (choices.isEmpty()) Text("No matching tasks. Tasks that would create a loop are excluded.")
                LazyScrollHints(Modifier.heightIn(max = 340.dp)) { hintState -> LazyColumn(Modifier.fillMaxWidth(), state = hintState) { items(choices, key = { it.id }) { candidate ->
                    TextButton(onClick = { onChange(ids + candidate.id); choosing = false }) {
                        Text(candidate.title + if (candidate.done) " · Completed" else "")
                    }
                } } }
        }
    }
    opening?.let { id ->
        val task = rememberEditedTask(id, tasks.find { it.id == id })
        if (task != null) PlanningOverlay({ opening = null }) { TaskEditor(task, false) { opening = null } }
        else LaunchedEffect(id) { opening = null }
    }
}

// Why shared text can't go to [value] ("event", "task" or "note") now, or null when it can. An event editor open in this
// or another Planner window (U3) holds the one event draft, so it is named rather than an unfinished draft to resume. A
// note opens on the Notes page, which would drop an event or task editor open here ([otherEditorOpen]).
internal fun sharedDraftBlock(value: String, draftExists: Boolean, eventEditorOpen: Boolean,
                              noteEditorOpen: Boolean = false, otherEditorOpen: Boolean = false): String? = when {
    value == "event" && eventEditorOpen -> "An event is open in Planner. Close this share, then save or close that event before sharing again."
    value == "note" && noteEditorOpen -> "A note is open in Planner. Close this share, then save or close that note before sharing again."
    value == "note" && otherEditorOpen -> "An event or task is open in Planner. Close this share, then save or close it before sharing again."
    draftExists -> "You have an unfinished $value. Close this share, then resume or discard that draft before sharing again."
    else -> null
}

// Returns the new event (or bill) its editor is open on, or null, so AppNav leaves that editor's draft to it (Q-2).
// A note goes to [onNote], which opens it on the Notes page. Each editor opens filled in from the share and counts it as
// unsaved, so Close asks before dropping it.
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SharedTextReview(text: String, subject: String?, onDismiss: () -> Unit, onNote: (PlannerNote) -> Unit = {}): ItineraryItem? {
    val app = LocalContext.current.applicationContext as ItineraryApp
    val content = remember(text, subject) { runCatching { SharedText.draft(text, subject) } }
    // The day the share came in, kept when the window is rebuilt on a later day so its event stays the one its draft
    // was made from (SH-11).
    val today = LocalDate.parse(rememberSaveable(text, subject) { LocalDate.now().toString() })
    // What the message says: the one day (and time) it names, and for a bill one amount. Read away from the screen's
    // thread, as a long message takes a moment (SH-5); the choices wait for it.
    val read by produceState<Pair<SharedWhen, BillSuggestion>?>(null, content) {
        value = content.getOrNull()?.let { shared -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            val now = java.time.LocalTime.now().takeIf { LocalDate.now() == today }
            val found = SharedDates.find(shared.body, today, now)
            found to BillSuggestions.parseMessage(shared.body.take(SharedDates.MAX_READ), found.date, today)
        } }
    }
    val found = read?.first
    val bill = read?.second
    val ready = content.isFailure || read != null
    var destination by rememberSaveable(text, subject) { mutableStateOf("") }
    // What the editor opens with (date, time, amount, currency), fixed when it is chosen: rebuilt after Android closed
    // Planner, the event must be the one its draft was made from at once, before the text is read again (Q-2, SH-11).
    var chosen by rememberSaveable(text, subject) { mutableStateOf("{}") }
    // "10:30": morning or evening, picked here; null leaves the time to the editor.
    var pickedTime by rememberSaveable(text, subject) { mutableStateOf<String?>(null) }
    val id = rememberSaveable(text, subject) { java.util.UUID.randomUUID().toString() }
    var error by remember { mutableStateOf<String?>(null) }
    fun choose(value: String) {
        val editor = if (value == "bill") "event" else value
        val check = runCatching { when (editor) {
            "event" -> EditorDraftStore(app).read() != null
            "note" -> NoteDraftStore(app).read() != null
            else -> TaskDraftStore(app).read("new") != null
        } }
        if (check.isFailure) { error = "Couldn't check your unfinished draft. Close this share and try again."; return }
        val blocked = sharedDraftBlock(editor, check.getOrThrow(), EditorDraftStore.openEditors.value > 0,
            NoteDraftStore.openEditors.value > 0, EditorDraftStore.openEditors.value + TaskDraftStore.openEditors.value > 0)
        if (blocked != null) error = blocked
        else if (value == "note") content.getOrNull()?.let { onNote(PlannerNote(title = it.title, content = it.notes)); onDismiss() }
        else {
            val forBill = value == "bill"
            chosen = org.json.JSONObject()
                .put("date", ((if (forBill) bill?.date else null) ?: found?.date)?.toString())
                .put("time", if (forBill) null else (found?.time?.toString() ?: pickedTime))
                .put("amount", if (forBill) bill?.amount else null)
                .put("currency", if (forBill) bill?.currency else null)
                .put("checkCurrency", forBill && bill?.amount != null && bill.currency == null).toString()
            destination = value
        }
    }
    val dateFormat = LocalDateFormat.current
    val is24Hour = LocalTimeFormat.current.is24Hour(LocalContext.current)
    if (destination.isEmpty()) PlannerDialog("Add to Planner", onDismissRequest = onDismiss,
        primary = DialogAction("Add task", enabled = content.isSuccess && ready) { choose("task") },
        dismiss = DialogAction("Cancel", onClick = onDismiss),
        extra = listOf(DialogAction("Add event", enabled = content.isSuccess && ready) { choose("event") },
            DialogAction("Add bill", enabled = content.isSuccess && ready) { choose("bill") },
            DialogAction("Add note", enabled = content.isSuccess && ready) { choose("note") })) {
            Text(content.getOrNull()?.title ?: content.exceptionOrNull()?.message.orEmpty())
            if (!ready) Text("Reading the text…")
            found?.date?.let { date ->
                Text("Date in the text: " + date.dayLabel(dateFormat) + (found.time?.let { ", " + it.label(is24Hour) }.orEmpty()))
                if (found.timeChoices.isNotEmpty()) {
                    Text("Which time? Pick one, or set it in the editor.")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        found.timeChoices.forEach { time ->
                            FilterChip(selected = pickedTime == time.toString(),
                                onClick = { pickedTime = if (pickedTime == time.toString()) null else time.toString() },
                                label = { Text(time.label(is24Hour)) })
                        }
                    }
                }
            }
            if (found != null && found.dates.size > 1)
                Text("Several dates in the text (" + found.dates.joinToString(", ") { it.dayLabel(dateFormat) } + "). Set the date in the editor.")
            bill?.amount?.let { amount -> Text("Amount for a bill: " +
                (bill.currency?.let { Bills.format(amount, it) } ?: (Bills.input(amount) + " (check the currency)"))) }
            // A bill's due date from its own label, when that isn't the date above (SH-7).
            bill?.date?.takeIf { it != found?.date }?.let { Text("Due date for a bill: " + it.dayLabel(dateFormat)) }
            bill?.warnings?.get("amount")?.let { Text("$it in the text. Enter the bill's amount in the editor.") }
            Text("Choose where to put this text, then review and save.")
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
    val shared = content.getOrNull() ?: return null
    val fields = remember(chosen) { org.json.JSONObject(chosen) }
    fun field(name: String) = fields.optString(name).takeIf { fields.has(name) && !fields.isNull(name) && it.isNotEmpty() }
    val date = field("date")?.let(LocalDate::parse)
    if (destination == "task") PlanningOverlay(onDismiss) {
        TaskEditor(PlannerTask(id = id, title = shared.title, notes = shared.notes, dueDate = date), true, prefilled = true, onDismiss = onDismiss)
    }
    if (destination != "event" && destination != "bill") return null
    val event = remember(destination, chosen) {
        if (destination == "bill") ItineraryItem(tripId = 0, date = date ?: today, startTime = null, title = shared.title,
            notes = shared.notes, category = "Bills", billAmountMinor = field("amount")?.toLong())
            .let { b -> field("currency")?.let { b.copy(billCurrency = it) } ?: b }
        else ItineraryItem(tripId = 0, date = date ?: today, startTime = date?.let { field("time")?.let(java.time.LocalTime::parse) },
            title = shared.title, notes = shared.notes)
    }
    NewPlanningEventEditor(event, prefilled = true, checkCurrency = fields.optBoolean("checkCurrency"), onDismiss = onDismiss)
    return event
}
