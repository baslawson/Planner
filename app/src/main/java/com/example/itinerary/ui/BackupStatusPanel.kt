package com.example.itinerary.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.itinerary.ItineraryApp
import java.time.Instant
import java.time.ZoneId

@Composable
fun BackupStatusPanel() {
    val context = LocalContext.current
    val dateFormat = LocalDateFormat.current
    val timeFormat = LocalTimeFormat.current
    val app = context.applicationContext as ItineraryApp
    val status by app.backup.status.state.collectAsStateWithLifecycle()
    val reminder by app.backup.status.reminder.collectAsStateWithLifecycle()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val time = status.lastSuccess?.let { runCatching {
            Instant.parse(it).atZone(ZoneId.systemDefault()).let { value ->
                "${value.toLocalDate().dayLabel(dateFormat)}, ${value.toLocalTime().label(timeFormat, context)}"
            }
        }.getOrNull() }
        Text(if (time == null) "No successful backup recorded on this device." else "Last successful backup: $time · ${status.destination}",
            style = MaterialTheme.typography.bodyMedium)
        if (status.outcome == "RUNNING") Text("Backup in progress…")
        if (status.failed) Text(if (status.outcome == "INTERRUPTED") "The last ${status.attemptDestination.orEmpty()} backup was interrupted. Please retry."
            else "The last ${status.attemptDestination.orEmpty()} backup failed. Please retry.", color = MaterialTheme.colorScheme.error)
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("Weekly backup reminders", Modifier.weight(1f))
            Switch(checked = reminder.enabled, onCheckedChange = app.backup.status::setRemindersEnabled)
        }
        Text("A prompt appears in Agenda when your last backup is at least 7 days old, or you have never backed up.", style = MaterialTheme.typography.bodySmall)
        Text("Backups run when you choose Export or Nextcloud upload.", style = MaterialTheme.typography.bodySmall)
    }
}
