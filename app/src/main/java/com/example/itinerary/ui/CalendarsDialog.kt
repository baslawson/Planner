package com.example.itinerary.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.itinerary.ItineraryApp
import com.example.itinerary.data.CalendarSource
import com.example.itinerary.data.OutsideCalendars
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

// Settings → Calendars: tick which Nextcloud calendars to show. Uses the Nextcloud backup login; without one it offers
// to connect. Opening it refreshes the list of calendars (and downloads any ticked one that changed). Work runs in the
// app's scope, so closing the pop-up doesn't stop a sync halfway.
// [nextcloudOpen]: the Nextcloud login pop-up is showing on top; the login is checked again when it closes.
@Composable
fun CalendarsDialog(nextcloudOpen: Boolean, onConnect: () -> Unit, onDismiss: () -> Unit) {
    val app = LocalContext.current.applicationContext as ItineraryApp
    val sync = app.calendarSync
    val sources by sync.sources.collectAsStateWithLifecycle(initialValue = emptyList())
    val state by sync.state.collectAsStateWithLifecycle()
    var connected by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(nextcloudOpen) {
        if (nextcloudOpen) return@LaunchedEffect
        connected = sync.hasAccount()
        if (connected == true) app.appScope.launch { sync.sync() }
    }
    PlannerDialog("Calendars",
        onDismissRequest = onDismiss,
        primary = if (connected == true) DialogAction(if (state.running) "Syncing…" else "Sync now", enabled = !state.running) {
            app.appScope.launch { sync.sync() }
        } else null,
        dismiss = DialogAction("Close", onClick = onDismiss),
    ) {
        Text("Show events from your Nextcloud calendars beside your own. Planner only reads them: nothing on Nextcloud is changed, and they can't be edited here.")
        when (connected) {
            null -> Text("Checking the Nextcloud connection…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            false -> {
                Text("Connect to Nextcloud first. Calendars use the same login as Nextcloud backup.")
                StackedButton("Connect Nextcloud", onConnect)
            }
            true -> {
                if (sources.isEmpty() && state.running) Text("Looking for calendars…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                sources.forEach { source ->
                    CalendarRow(source, enabled = !state.running) { ticked ->
                        app.appScope.launch {
                            sync.setEnabled(source.id, ticked)
                            if (ticked) sync.sync()
                        }
                    }
                }
            }
        }
        state.message?.let {
            Text(it, color = if (state.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (connected == true) Text("Syncs when Planner opens (at most every 15 minutes) and when you tap Sync now, " +
            "covering 3 months back to 12 months ahead. Downloaded events aren't included in backups.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CalendarRow(source: CalendarSource, enabled: Boolean, onTick: (Boolean) -> Unit) {
    val context = LocalContext.current
    val format = LocalTimeFormat.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .toggleable(value = source.enabled, enabled = enabled, role = Role.Checkbox, onValueChange = onTick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Checkbox(checked = source.enabled, onCheckedChange = null, enabled = enabled)
        Box(Modifier.size(12.dp).clip(CircleShape).background(Color(source.color ?: OutsideCalendars.DEFAULT_COLOR)))
        Column(Modifier.weight(1f)) {
            Text(source.name)
            val detail = when {
                !source.enabled -> null
                source.lastError != null -> source.lastError
                source.lastSynced != null -> Instant.ofEpochMilli(source.lastSynced).atZone(ZoneId.systemDefault()).let {
                    "Synced ${it.toLocalDate().shortLabel()}, ${it.toLocalTime().label(format, context)}"
                }
                else -> "Not downloaded yet"
            }
            detail?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                    color = if (source.lastError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
