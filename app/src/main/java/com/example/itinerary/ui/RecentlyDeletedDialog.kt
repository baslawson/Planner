package com.example.itinerary.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.triStateToggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.itinerary.data.*
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

// Pick entries (a tap on the row, or Select all), then Restore or Delete forever for all of them at once.
@Composable
fun RecentlyDeletedDialog(repo: Repository, onDismiss: () -> Unit) {
    val entries by repo.recentlyDeleted.collectAsStateWithLifecycle(emptyList())
    val today = rememberCurrentDate()
    val shown = remember(entries, today) { entries.filter { it.deletedAt + TRASH_RETENTION_MS > System.currentTimeMillis() } }
    var picked by rememberSaveable { mutableStateOf(setOf<String>()) }
    // Only what is still listed counts: an entry restored, deleted or expired meanwhile drops out of the selection.
    val chosen = remember(shown, picked) { shown.filter { it.id in picked }.map { it.id } }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirming by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun run(verb: String, block: suspend (List<String>) -> Int) {
        val ids = chosen
        busy = true; error = null
        scope.launch {
            try {
                val failed = block(ids)
                picked = emptySet()
                if (failed > 0) error = "Couldn't $verb ${count(failed)}. Please try again."
            } catch (_: Exception) { error = "Couldn't complete that action. Please try again." }
            finally { busy = false }
        }
    }
    PlannerDialog("Recently deleted", { if (!busy) onDismiss() },
        primary = if (shown.isEmpty()) null else DialogAction(if (busy) "Working…" else "Restore" + countSuffix(chosen.size),
            enabled = !busy && chosen.isNotEmpty()) { run("restore") { repo.restoreDeleted(it) } },
        dismiss = DialogAction("Close", enabled = !busy, onClick = onDismiss),
        // Deleting for good is the bigger step, so it is the quieter (red text) button beside Close.
        extra = if (shown.isEmpty()) emptyList() else listOf(DialogAction("Delete forever" + countSuffix(chosen.size),
            enabled = !busy && chosen.isNotEmpty(), danger = true) { confirming = true }),
        scroll = null) {
            Text("Tasks, events and their attachments are kept for 30 days, then deleted for good.")
            if (shown.isEmpty()) Text("No recently deleted events or tasks.")
            else {
                val all = when (chosen.size) { 0 -> ToggleableState.Off; shown.size -> ToggleableState.On; else -> ToggleableState.Indeterminate }
                Row(Modifier.fillMaxWidth()
                    .triStateToggleable(state = all, enabled = !busy, role = Role.Checkbox,
                        onClick = { picked = if (all == ToggleableState.On) emptySet() else shown.mapTo(hashSetOf()) { it.id } })
                    .padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    TriStateCheckbox(state = all, onClick = null, enabled = !busy)
                    Spacer(Modifier.width(12.dp))
                    Text("Select all", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Text(count(shown.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider()
            }
            LazyScrollHints(Modifier.heightIn(max = 460.dp)) { hintState -> LazyColumn(Modifier.fillMaxWidth(), state = hintState) {
                items(shown, key = { it.id }) { entry ->
                    val checked = entry.id in picked
                    Row(Modifier.fillMaxWidth()
                        .toggleable(value = checked, enabled = !busy, role = Role.Checkbox,
                            onValueChange = { picked = if (it) picked + entry.id else picked - entry.id })
                        .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = checked, onCheckedChange = null, enabled = !busy)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(entry.label, style = MaterialTheme.typography.titleSmall)
                            val zone = ZoneId.systemDefault()
                            val deleted = Instant.ofEpochMilli(entry.deletedAt).atZone(zone).toLocalDate()
                            val until = Instant.ofEpochMilli(entry.deletedAt + TRASH_RETENTION_MS).atZone(zone).toLocalDate()
                            Text("Deleted ${deleted.shortLabel()} · restore before ${until.shortLabel()}",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            } }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
    if (confirming) PlannerDialog("Delete ${count(chosen.size)} forever?", { confirming = false },
        primary = DialogAction("Delete forever", danger = true, enabled = chosen.isNotEmpty()) {
            confirming = false; run("delete") { repo.permanentlyDelete(it) } },
        dismiss = DialogAction("Cancel") { confirming = false }) {
        Text(if (chosen.size == 1) "${shown.firstOrNull { it.id == chosen.single() }?.label ?: "It"} can't be restored afterwards."
            else "They can't be restored afterwards.")
    }
}

private fun count(n: Int) = if (n == 1) "1 entry" else "$n entries"
private fun countSuffix(n: Int) = if (n == 0) "" else " ($n)"
