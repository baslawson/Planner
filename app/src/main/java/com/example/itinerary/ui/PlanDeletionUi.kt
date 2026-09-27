package com.example.itinerary.ui
import com.example.itinerary.ui.MatrixOutlinedButton as OutlinedButton

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import com.example.itinerary.data.Trip

@Composable
fun PlanSelectionBar(
    count: Int,
    visibleCount: Int,
    busy: Boolean,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onSelectAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier.fillMaxWidth(), tonalElevation = 3.dp) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text("$count selected", style = MaterialTheme.typography.titleSmall)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onCancel, enabled = !busy) { Text("Cancel") }
                Spacer(Modifier.width(8.dp))
                DangerButton(onClick = onDelete, enabled = count > 0 && !busy) {
                    Text(if (busy) "Deleting…" else "Delete ($count)")
                }
                Spacer(Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    TriStateCheckbox(
                        state = when {
                            count == 0 -> ToggleableState.Off
                            count == visibleCount -> ToggleableState.On
                            else -> ToggleableState.Indeterminate
                        },
                        onClick = onSelectAll,
                        enabled = visibleCount > 0 && !busy,
                        modifier = Modifier.semantics { contentDescription = "Select all visible plans" },
                    )
                    Text("All", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
fun ConfirmPlanDeletion(plans: List<Trip>, busy: Boolean, error: String?, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val label = if (plans.size == 1) "1 plan" else "${plans.size} plans"
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { HeadingText("Delete $label?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("This permanently deletes the selected plans and all their events, reminders and attachments. This cannot be undone.")
                LazyColumn(Modifier.heightIn(max = 240.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(plans, key = { it.id }) { plan ->
                        Column {
                            Text(plan.name, style = MaterialTheme.typography.titleSmall)
                            Text(plan.rangeLabel(), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            DangerButton(onClick = onConfirm, enabled = !busy) {
                Text(if (busy) "Deleting…" else "Delete $label")
            }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss, enabled = !busy) { Text("Keep plans") } },
    )
}
