package com.example.itinerary.ui
import com.example.itinerary.ui.MatrixTextButton as TextButton

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.itinerary.data.*
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

@Composable
fun RecentlyDeletedDialog(repo: Repository, onDismiss: () -> Unit) {
    val entries by repo.recentlyDeleted.collectAsStateWithLifecycle(emptyList())
    val today = rememberCurrentDate()
    val shown = remember(entries, today) { entries.filter { it.deletedAt + TRASH_RETENTION_MS > System.currentTimeMillis() } }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<DeletedEntry?>(null) }
    val scope = rememberCoroutineScope()
    fun action(block: suspend () -> Unit) {
        busy = true; error = null
        scope.launch { try { block() } catch (_: Exception) { error = "Couldn't complete that action. Please try again." } finally { busy = false } }
    }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text("Recently deleted") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Tasks, events and their attachments can be restored for 30 days after deletion.")
            if (shown.isEmpty()) Text("No recently deleted events or tasks.")
            LazyScrollHints(Modifier.heightIn(max = 380.dp)) { hintState -> LazyColumn(Modifier.fillMaxWidth(), state = hintState) {
                items(shown, key = { it.id }) { entry ->
                    Column(Modifier.padding(vertical = 8.dp)) {
                        Text(entry.label, style = MaterialTheme.typography.titleSmall)
                        val until = Instant.ofEpochMilli(entry.deletedAt + TRASH_RETENTION_MS).atZone(ZoneId.systemDefault()).toLocalDate()
                        Text("Restore before ${until.dayLabel(LocalDateFormat.current)}", style = MaterialTheme.typography.bodySmall)
                        Row {
                            TextButton(enabled = !busy, onClick = { action { repo.restoreDeleted(entry.id) } }) { Text("Restore") }
                            DangerButton(enabled = !busy, onClick = { deleting = entry }) { Text("Delete forever") }
                        }
                    }
                }
            } }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Close") } })
    deleting?.let { entry -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Delete forever?") },
        text = { Text("${entry.label} cannot be restored afterward.") },
        confirmButton = { DangerButton(onClick = { deleting = null; action { repo.permanentlyDelete(entry.id) } }) { Text("Delete forever") } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } }) }
}
