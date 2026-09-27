package com.example.itinerary.ui
import com.example.itinerary.ui.MatrixOutlinedButton as OutlinedButton

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.itinerary.data.StagedBackup

// Shown while a backup is being written or read; there is nothing to tap until it finishes.
@Composable
fun BusyDialog(message: String) {
    AlertDialog(
        onDismissRequest = {},
        confirmButton = {},
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(28.dp))
                Spacer(Modifier.width(16.dp))
                Text(message)
            }
        },
    )
}

@Composable
fun ImportConfirmDialog(staged: StagedBackup, onConfirm: () -> Unit, onCancel: () -> Unit) {
    fun count(n: Int, one: String, many: String = one + "s") = "$n ${if (n == 1) one else many}"
    val dateText = staged.exportedOn?.fullLabel()?.let { "Backup from $it" }
        ?: "This backup"
    AlertDialog(
        onDismissRequest = onCancel,
        title = { HeadingText("Replace everything?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "$dateText has ${count(staged.events, "event")}, ${count(staged.tasks, "task")}, " +
                        "${count(staged.reminders, "reminder")} and ${count(staged.attachments, "attachment")}, " +
                        "${count(staged.templates, "template")}, plus payment history and your settings.",
                )
                if (staged.deletedGroups > 0) Text("Also includes ${count(staged.deletedGroups, "group")} in Recently deleted.")
                Spacer(Modifier.height(8.dp))
                Text(
                    "This permanently replaces all events, tasks, reminders, attachments, Recently deleted and settings " +
                        "currently in the app.",
                    color = MaterialTheme.colorScheme.error,
                )
                if (staged.missingFiles > 0) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "${count(staged.missingFiles, "attachment")} listed in the backup could not be found " +
                            "inside it and will be left out.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = { DangerButton(onClick = onConfirm) { Text("Replace everything") } },
        dismissButton = { OutlinedButton(onClick = onCancel) { Text("Cancel") } },
    )
}
