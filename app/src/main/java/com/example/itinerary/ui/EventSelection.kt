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

class EventSelection {
    var ids by mutableStateOf(emptyList<Long>())
    var busy by mutableStateOf(false)
    val active get() = ids.isNotEmpty()
    fun toggle(id: Long) {
        if (!busy) ids = if (id in ids) ids - id else ids + id
    }
    fun clear() { if (!busy) ids = emptyList() }
}

@Composable
fun rememberEventSelection(visible: List<SelectableEvent>, prune: Boolean = true): EventSelection {
    val selection = rememberSaveable(saver = androidx.compose.runtime.saveable.listSaver(
        save = { it.ids }, restore = { EventSelection().apply { ids = it } },
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
    BackHandler(selection.active || selection.busy) { selection.clear() }
    return selection
}

@Composable
fun EventSelectionBar(selection: EventSelection, visible: List<SelectableEvent>, onDelete: suspend (Set<Long>) -> Unit) {
    var confirming by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val chosen = remember(visible, selection.ids) {
        val ids = selection.ids.toHashSet()
        visible.filter { it.id in ids }
    }
    if (selection.active || selection.busy) Surface(tonalElevation = 3.dp) {
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("${selection.ids.size} selected", modifier = Modifier.weight(1f))
            OutlinedButton(onClick = selection::clear, enabled = !selection.busy) { Text("Cancel") }
            DangerButton(onClick = { confirming = true; error = null }, enabled = !selection.busy && chosen.isNotEmpty()) {
                Text(if (selection.busy) "Deleting…" else "Delete")
            }
        }
    }
    if (confirming && (chosen.isNotEmpty() || selection.busy)) {
        val noun = if (chosen.all { it.bill }) "bill" else if (chosen.any { it.bill }) "item" else "event"
        val label = if (chosen.size == 1) "1 $noun" else "${chosen.size} ${noun}s"
        PlannerDialog("Delete $label?",
            onDismissRequest = { if (!selection.busy) confirming = false },
            primary = DialogAction(if (selection.busy) "Deleting…" else "Delete $label", enabled = !selection.busy, danger = true) {
                val ids = chosen.mapTo(hashSetOf()) { it.id }
                selection.busy = true
                scope.launch {
                    try {
                        onDelete(ids)
                        selection.ids = emptyList()
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
                    } }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
        }
    } else if (!selection.active && !selection.busy) {
        LaunchedEffect(Unit) { confirming = false }
    }
}
