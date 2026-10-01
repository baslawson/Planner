package com.example.itinerary.ui
import com.example.itinerary.ui.MatrixOutlinedButton as OutlinedButton

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.LocalDate

data class SelectableEvent(val id: Long, val title: String, val date: LocalDate, val bill: Boolean = false)
data class SelectableTask(val id: String, val title: String, val date: LocalDate?)

// Long-pressed events, bills and tasks, deleted together from the bar below the list.
class EventSelection {
    var ids by mutableStateOf(emptyList<Long>())
    var taskIds by mutableStateOf(emptyList<String>())
    var busy by mutableStateOf(false)
    val active get() = ids.isNotEmpty() || taskIds.isNotEmpty()
    val size get() = ids.size + taskIds.size
    fun toggle(id: Long) {
        if (!busy) ids = if (id in ids) ids - id else ids + id
    }
    fun toggleTask(id: String) {
        if (!busy) taskIds = if (id in taskIds) taskIds - id else taskIds + id
    }
    fun clear() { if (!busy) { ids = emptyList(); taskIds = emptyList() } }
}

@Composable
fun rememberEventSelection(visible: List<SelectableEvent>, prune: Boolean = true,
                           visibleTasks: List<SelectableTask> = emptyList()): EventSelection {
    val selection = rememberSaveable(saver = androidx.compose.runtime.saveable.listSaver<EventSelection, ArrayList<*>>(
        save = { listOf(ArrayList(it.ids), ArrayList(it.taskIds)) },
        restore = { saved -> EventSelection().apply {
            ids = saved[0].filterIsInstance<Long>(); taskIds = saved.getOrNull(1).orEmpty().filterIsInstance<String>() } },
    )) { EventSelection() }
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    LaunchedEffect(selection.active) {
        if (selection.active) {
            focus.clearFocus()
            keyboard?.hide()
        }
    }
    val visibleIds = remember(visible) { visible.mapTo(hashSetOf()) { it.id } }
    LaunchedEffect(visibleIds, prune) { if (prune) selection.ids = selection.ids.filter { it in visibleIds } }
    val visibleTaskIds = remember(visibleTasks) { visibleTasks.mapTo(hashSetOf()) { it.id } }
    LaunchedEffect(visibleTaskIds, prune) { if (prune) selection.taskIds = selection.taskIds.filter { it in visibleTaskIds } }
    BackHandler(selection.active || selection.busy) { selection.clear() }
    return selection
}

@Composable
fun EventSelectionBar(selection: EventSelection, visible: List<SelectableEvent>, onDelete: suspend (Set<Long>, Set<String>) -> Unit,
                      visibleTasks: List<SelectableTask> = emptyList()) {
    var confirming by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val chosen = remember(visible, selection.ids) {
        val ids = selection.ids.toHashSet()
        visible.filter { it.id in ids }
    }
    val chosenTasks = remember(visibleTasks, selection.taskIds) {
        val ids = selection.taskIds.toHashSet()
        visibleTasks.filter { it.id in ids }
    }
    val count = chosen.size + chosenTasks.size
    if (selection.active || selection.busy) Surface(tonalElevation = 3.dp) {
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("${selection.size} selected", modifier = Modifier.weight(1f))
            OutlinedButton(onClick = selection::clear, enabled = !selection.busy) { Text("Cancel") }
            DangerButton(onClick = { confirming = true; error = null }, enabled = !selection.busy && count > 0) {
                Text(if (selection.busy) "Deleting…" else "Delete")
            }
        }
    }
    if (confirming && (count > 0 || selection.busy)) {
        val noun = when {
            chosen.isEmpty() -> "task"
            chosenTasks.isNotEmpty() -> "item"
            chosen.all { it.bill } -> "bill"
            chosen.any { it.bill } -> "item"
            else -> "event"
        }
        val label = if (count == 1) "1 $noun" else "$count ${noun}s"
        PlannerDialog("Delete $label?",
            onDismissRequest = { if (!selection.busy) confirming = false },
            primary = DialogAction(if (selection.busy) "Deleting…" else "Delete $label", enabled = !selection.busy, danger = true) {
                val ids = chosen.mapTo(hashSetOf()) { it.id }
                val taskIds = chosenTasks.mapTo(hashSetOf()) { it.id }
                selection.busy = true
                scope.launch {
                    try {
                        onDelete(ids, taskIds)
                        selection.ids = emptyList(); selection.taskIds = emptyList()
                        confirming = false
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { error = "Couldn't delete the selected ${noun}s. Please try again." }
                    finally { selection.busy = false }
                }
            },
            dismiss = DialogAction("Keep ${noun}s", enabled = !selection.busy) { confirming = false },
            // The list of chosen entries scrolls on its own.
            scroll = null,
        ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Delete the selected ${noun}s, including their reminders and attachments? For repeating entries, only the selected occurrences will be deleted.")
                    LazyScrollHints(Modifier.heightIn(max = 240.dp)) { hintState -> LazyColumn(Modifier.fillMaxWidth(), state = hintState) {
                        items(chosen, key = { it.id }) { event ->
                            Column(Modifier.padding(vertical = 4.dp)) {
                                Text(event.title, style = MaterialTheme.typography.titleSmall)
                                Text(event.date.dayLabel(LocalDateFormat.current), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        items(chosenTasks, key = { "task-${it.id}" }) { task ->
                            Column(Modifier.padding(vertical = 4.dp)) {
                                Text(task.title, style = MaterialTheme.typography.titleSmall)
                                Text("Task · " + (task.date?.dayLabel(LocalDateFormat.current) ?: "No due date"), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    } }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
        }
    } else if (!selection.active && !selection.busy) {
        LaunchedEffect(Unit) { confirming = false }
    }
}
