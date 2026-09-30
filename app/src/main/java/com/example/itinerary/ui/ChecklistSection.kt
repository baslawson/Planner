package com.example.itinerary.ui
import com.example.itinerary.ui.MatrixIconButton as IconButton
import com.example.itinerary.ui.MatrixOutlinedButton as OutlinedButton

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.itinerary.data.ChecklistEntry

@Composable
fun ChecklistSection(entries: List<ChecklistEntry>, onChange: (List<ChecklistEntry>) -> Unit) {
    HorizontalDivider()
    HeadingText("Checklist", style = MaterialTheme.typography.titleMedium)
    entries.forEachIndexed { index, task ->
        key(task.id) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = task.done, onCheckedChange = { done ->
                    onChange(entries.map { if (it.id == task.id) it.copy(done = done) else it })
                }, modifier = Modifier.semantics { contentDescription = "Complete task ${index + 1}" })
                OutlinedTextField(value = task.text, onValueChange = { value ->
                    onChange(entries.map { if (it.id == task.id) it.copy(text = value.replace('\n', ' ')) else it })
                }, label = { Text("Task ${index + 1}") }, modifier = Modifier.weight(1f),
                    isError = task.text.isBlank(), keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done))
                IconButton(onClick = { onChange(entries.filterNot { it.id == task.id }) }) {
                    Icon(Icons.Default.Close, contentDescription = "Remove task ${index + 1}")
                }
            }
        }
    }
    if (entries.any { it.text.isBlank() }) Text("Enter a task or remove the empty row before saving.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    OutlinedButton(enabled = entries.size < 100, onClick = { onChange(entries + ChecklistEntry(text = "")) }) {
        Text("Add task")
    }
}

fun checklistProgress(entries: List<ChecklistEntry>): String = "${entries.count { it.done }}/${entries.size} tasks done"
