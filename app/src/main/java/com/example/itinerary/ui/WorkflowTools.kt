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
            OutlinedTextField(name, { name = it.take(80) }, label = { Text("Search name") }, singleLine = true)
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
                OutlinedTextField(query, { query = it }, label = { Text("Find task") }, singleLine = true)
                if (choices.isEmpty()) Text("No matching tasks. Tasks that would create a loop are excluded.")
                LazyScrollHints(Modifier.heightIn(max = 340.dp)) { hintState -> LazyColumn(Modifier.fillMaxWidth(), state = hintState) { items(choices, key = { it.id }) { candidate ->
                    TextButton(onClick = { onChange(ids + candidate.id); choosing = false }) {
                        Text(candidate.title + if (candidate.done) " · Completed" else "")
                    }
                } } }
        }
    }
    opening?.let { id ->
        val task = tasks.find { it.id == id }
        if (task != null) PlanningOverlay({ opening = null }) { TaskEditor(task, false) { opening = null } }
        else LaunchedEffect(id) { opening = null }
    }
}

@Composable
fun SharedTextReview(text: String, subject: String?, onDismiss: () -> Unit) {
    val app = LocalContext.current.applicationContext as ItineraryApp
    val content = remember(text, subject) { runCatching { SharedText.draft(text, subject) } }
    var destination by rememberSaveable(text, subject) { mutableStateOf("") }
    val id = rememberSaveable(text, subject) { java.util.UUID.randomUUID().toString() }
    var error by remember { mutableStateOf<String?>(null) }
    fun choose(value: String) {
        val check = runCatching { if (value == "event") EditorDraftStore(app).read() != null else TaskDraftStore(app).read("new") != null }
        if (check.isFailure) { error = "Couldn't check your unfinished draft. Close this share and try again."; return }
        if (check.getOrThrow()) error = "You have an unfinished $value. Close this share, then resume or discard that draft before sharing again."
        else destination = value
    }
    if (destination.isEmpty()) PlannerDialog("Add to Planner", onDismissRequest = onDismiss,
        primary = DialogAction("Add task", enabled = content.isSuccess) { choose("task") },
        dismiss = DialogAction("Cancel", onClick = onDismiss),
        extra = listOf(DialogAction("Add event", enabled = content.isSuccess) { choose("event") })) {
            Text(content.getOrNull()?.title ?: content.exceptionOrNull()?.message.orEmpty())
            Text("Choose where to put this text, then review and save.")
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
    content.getOrNull()?.let { shared ->
        if (destination == "task") PlanningOverlay(onDismiss) {
            TaskEditor(PlannerTask(id = id, title = shared.title, notes = shared.notes), true, onDismiss)
        }
        if (destination == "event") NewPlanningEventEditor(ItineraryItem(tripId = 0, date = LocalDate.now(), startTime = null,
                title = shared.title, notes = shared.notes), onDismiss)
    }
}
