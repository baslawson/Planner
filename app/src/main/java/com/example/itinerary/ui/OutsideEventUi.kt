package com.example.itinerary.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.itinerary.data.OutsideInfo
import com.example.itinerary.data.SyncMark
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import com.example.itinerary.data.timedEndAt

// Events from ticked Nextcloud calendars, by the (negative) id they are shown under. Provided by AppNav; rows use it to
// label an outside event and to leave out the actions that would change it.
val LocalOutsideEvents = compositionLocalOf<Map<Long, OutsideInfo>> { emptyMap() }

// Planner's events on Nextcloud (two-way sync), by event id. Provided by AppNav.
val LocalSyncMarks = compositionLocalOf<Map<Long, SyncMark>> { emptyMap() }

// "⟳ Nextcloud" under a Planner event that two-way sync keeps on Nextcloud; "⚠ Nextcloud: check Calendars" when it
// has something to settle there. Nothing for an event that isn't synced.
@Composable
fun SyncMarkLabel(id: Long) {
    val mark = LocalSyncMarks.current[id] ?: return
    val problem = mark == SyncMark.PROBLEM
    val color = if (problem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(if (problem) Icons.Filled.Warning else Icons.Filled.Refresh, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(if (problem) "Nextcloud: check Calendars" else "Nextcloud", style = MaterialTheme.typography.labelSmall, color = color)
    }
}

// "From Personal", plus the real times of a timed event longer than a day ("Starts 9:00 PM 3 Oct · ends 11:00 AM 5 Oct").
@Composable
fun OutsideEventLabel(info: OutsideInfo) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.DateRange, contentDescription = null, tint = Color(info.color), modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text("From ${info.calendar}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    longTimesLabel(info)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
}

@Composable
private fun longTimesLabel(info: OutsideInfo): String? {
    val start = info.event.timedStart ?: return null
    val end = info.event.timedEndAt() ?: return null
    val format = LocalTimeFormat.current
    val context = LocalContext.current
    return "Starts ${start.label(format, context)} ${info.event.date.shortLabel()} · ends " +
        "${end.toLocalTime().label(format, context)} ${end.toLocalDate().shortLabel()}"
}

// Tapping an outside event on the calendar: everything about it, read-only, with Copy to Planner (which opens the
// normal event editor with a new event filled in; nothing is saved until the user saves it) and Share.
@Composable
fun OutsideEventDialog(info: OutsideInfo, onDismiss: () -> Unit, onCopy: () -> Unit) {
    val event = info.event
    val context = LocalContext.current
    val format = LocalTimeFormat.current
    PlannerDialog(event.title,
        onDismissRequest = onDismiss,
        primary = DialogAction("Copy to Planner", onClick = onCopy),
        dismiss = DialogAction("Close", onClick = onDismiss),
        extra = listOf(DialogAction("Share") {
            shareEvent(context, event.title, event.date, event.startTime ?: event.timedStart,
                event.durationMinutes, event.location, format)
        }),
    ) {
        OutsideEventLabel(info)
        Text(event.date.dayLabel(LocalDateFormat.current), style = MaterialTheme.typography.titleSmall)
        when {
            event.startTime != null -> Text(event.startTime.label(format, context) +
                eventEndLabel(event.date, event.startTime, event.durationMinutes, format, context).let { if (it.isEmpty()) "" else " · $it" })
            event.timedStart == null -> Text("All day")
        }
        event.endDate?.let { Text(spanLabel(event.date, it)) }
        if (event.location.isNotBlank()) Text(event.location, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (event.notes.isNotBlank()) Text(event.notes, style = MaterialTheme.typography.bodyMedium)
        val home = when (info.kind) {
            com.example.itinerary.data.OutsideCalendars.KIND_PHONE -> "a calendar on your phone"
            com.example.itinerary.data.OutsideCalendars.KIND_LINK -> "a calendar you subscribed to"
            else -> "your Nextcloud calendar"
        }
        Text("This event belongs to $home, so it can't be changed here. " +
            "Copy it to make a Planner event of your own.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
