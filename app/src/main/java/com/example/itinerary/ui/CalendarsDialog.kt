package com.example.itinerary.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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

// Settings → Calendars: tick which calendars to show, in two parts. Nextcloud uses the backup login (without one it offers
// to connect); "On this phone" lists the phone's visible calendars once the user allows Planner to read them (asked only
// when they tap "Show phone calendars"). Opening it refreshes both lists (and downloads any ticked Nextcloud calendar that
// changed). Work runs in the app's scope, so closing the pop-up doesn't stop a sync halfway.
// [nextcloudOpen]: the Nextcloud login pop-up is showing on top; the login is checked again when it closes.
@Composable
fun CalendarsDialog(nextcloudOpen: Boolean, onConnect: () -> Unit, onDismiss: () -> Unit) {
    val app = LocalContext.current.applicationContext as ItineraryApp
    val sync = app.calendarSync
    val all by sync.sources.collectAsStateWithLifecycle(initialValue = emptyList())
    val sources = all.filter { it.kind == OutsideCalendars.KIND_NEXTCLOUD }
    val phoneSources = all.filter { it.kind == OutsideCalendars.KIND_PHONE }
    val linkSources = all.filter { it.kind == OutsideCalendars.KIND_LINK }
    val linkState by sync.linkState.collectAsStateWithLifecycle()
    val sendState by sync.sendState.collectAsStateWithLifecycle()
    val sendRows by sync.sent.collectAsStateWithLifecycle(initialValue = emptyList())
    val conflicts by sync.conflicts.collectAsStateWithLifecycle(initialValue = emptyList())
    var reviewing by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<CalendarSource?>(null) }
    val state by sync.state.collectAsStateWithLifecycle()
    val phoneState by sync.phoneState.collectAsStateWithLifecycle()
    var connected by remember { mutableStateOf<Boolean?>(null) }
    var phoneAllowed by remember { mutableStateOf(sync.phonePermitted()) }
    var phoneRefused by remember { mutableStateOf(false) }
    val askPhone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        phoneAllowed = granted
        phoneRefused = !granted
        app.appScope.launch { sync.refreshPhone() }
    }
    // Permission may be switched off in Android settings while this is open behind them.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val now = sync.phonePermitted()
        if (now != phoneAllowed) { phoneAllowed = now; app.appScope.launch { sync.refreshPhone() } }
    }
    LaunchedEffect(nextcloudOpen) {
        if (nextcloudOpen) return@LaunchedEffect
        connected = sync.hasAccount()
        if (connected == true) app.appScope.launch { sync.sync() }
    }
    LaunchedEffect(Unit) { app.appScope.launch { sync.refreshPhone() } }
    val running = state.running || phoneState.running || linkState.running || sendState.running
    if (adding) AddLinkDialog(onDismiss = { adding = false })
    if (reviewing) ConflictsDialog(conflicts, onDismiss = { reviewing = false })
    removing?.let { source ->
        PlannerDialog("Remove ${source.name}?", onDismissRequest = { removing = null },
            primary = DialogAction("Remove", danger = true) { removing = null; app.appScope.launch { sync.removeLink(source.id) } },
            dismiss = DialogAction("Cancel") { removing = null }) {
            Text("Its events leave Planner. To see them again, add the link again.")
        }
    }
    PlannerDialog("Calendars",
        onDismissRequest = onDismiss,
        primary = if (connected == true || phoneAllowed || linkSources.isNotEmpty()) DialogAction(if (running) "Syncing…" else "Sync now", enabled = !running) {
            app.appScope.launch { sync.refreshPhone(); sync.refreshLinks(); if (connected == true) { sync.sync(); sync.send() } }
        } else null,
        dismiss = DialogAction("Close", onClick = onDismiss),
    ) {
      // Room on the right for the scroll bar, which this longer pop-up usually shows.
      Column(Modifier.padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Show events from your other calendars beside your own. Only a Nextcloud calendar can sync both ways (choose it under " +
            "Nextcloud → Keep in sync with). All other calendars here, including phone calendars and links, are read-only: " +
            "Planner doesn't change them and their events can't be edited here.")
        SettingsHeading("Nextcloud")
        when (connected) {
            null -> Text("Checking the Nextcloud connection…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            false -> {
                Text("Connect to Nextcloud first. Calendars use the same login as Nextcloud backup.")
                StackedButton("Connect Nextcloud", onConnect)
            }
            true -> {
                if (sources.isEmpty() && state.running) Text("Looking for calendars…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                // The calendar kept in sync both ways isn't a read-only one; it's chosen under "Keep in sync with".
                sources.filterNot { it.sendHere }.forEach { source ->
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
        if (connected == true && sources.isNotEmpty()) SendChoice(sources, sendRows, sendState, enabled = !running) { id ->
            app.appScope.launch { sync.setSendTarget(id) }
        }
        if (conflicts.isNotEmpty()) StackedButton("Review ${conflicts.size} conflict${if (conflicts.size == 1) "" else "s"}") { reviewing = true }

        SettingsHeading("On this phone")
        if (!phoneAllowed) {
            Text("Calendars already on this phone: Google, Samsung, Outlook, DAVx⁵ and others. Read-only: Planner asks to read them; it can't change them.")
            if (phoneRefused) {
                Text("Planner wasn't allowed to read calendars. Allow Calendars in Planner's app settings.", color = MaterialTheme.colorScheme.error)
                StackedButton("Open app settings") { com.example.itinerary.reminders.openAppSettings(app) }
            } else StackedButton("Show phone calendars") { askPhone.launch(Manifest.permission.READ_CALENDAR) }
        } else {
            phoneSources.forEach { source ->
                CalendarRow(source, enabled = !phoneState.running) { ticked ->
                    app.appScope.launch { sync.setPhoneEnabled(source.id, ticked) }
                }
            }
            phoneState.message?.let {
                Text(it, color = if (phoneState.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("Read-only. Updates when Planner opens, when you tap Sync now and whenever these calendars change while Planner is open. " +
                "Only calendars shown in your phone's calendar app are listed, and they aren't included in backups.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        SettingsHeading("Subscribed by link")
        linkSources.forEach { source ->
            CalendarRow(source, enabled = !linkState.running, onRemove = { removing = source }) { ticked ->
                app.appScope.launch { sync.setLinkEnabled(source.id, ticked) }
            }
        }
        linkState.message?.let {
            Text(it, color = if (linkState.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        StackedButton("Add link") { adding = true }
        Text("Read-only. A calendar's https or webcal link, such as public holidays, a club's fixtures or a calendar's secret address. " +
            "Updates when Planner opens (at most once an hour) and when you tap Sync now. Links are included in backups.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
    }
}

@Composable
private fun CalendarRow(source: CalendarSource, enabled: Boolean, onRemove: (() -> Unit)? = null, onTick: (Boolean) -> Unit) {
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
            source.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            val detail = when {
                source.lastError != null -> source.lastError
                !source.enabled -> null
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
        if (onRemove != null) MatrixTextButton(onClick = onRemove, enabled = enabled) { Text("Remove") }
    }
}

// Add link: the calendar's https or webcal address and, if wanted, a name of its own. It's downloaded once to check it
// before it's added. The link can hold a private token, so it isn't kept in saved screen state.
@Composable
private fun AddLinkDialog(onDismiss: () -> Unit) {
    val app = LocalContext.current.applicationContext as ItineraryApp
    val scope = rememberCoroutineScope()
    var link by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    PlannerDialog("Add calendar link", onDismissRequest = { if (!busy) onDismiss() },
        primary = DialogAction(if (busy) "Checking…" else "Subscribe", enabled = !busy && link.isNotBlank()) {
            busy = true; error = null
            scope.launch {
                try { app.calendarSync.addLink(link, name); onDismiss() }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) { error = (e as? com.example.itinerary.data.BackupException)?.message ?: (e as? IllegalArgumentException)?.message ?: "Couldn't add this calendar." }
                finally { busy = false }
            }
        },
        dismiss = DialogAction("Cancel", enabled = !busy, onClick = onDismiss),
        properties = androidx.compose.ui.window.DialogProperties(securePolicy = androidx.compose.ui.window.SecureFlagPolicy.SecureOn)) {
        androidx.compose.material3.OutlinedTextField(link, { link = it; error = null }, label = { Text("Link (https:// or webcal://)") },
            singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri))
        androidx.compose.material3.OutlinedTextField(name, { name = it }, label = { Text("Name (optional)") },
            singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
        Text("Planner only reads it. A private link (such as a calendar's secret address) stays on this phone and in your backups.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

// Steps 5–6: "Keep in sync with" one writable Nextcloud calendar, or Off. Shows how many events are kept in sync and
// anything waiting.
@Composable
private fun SendChoice(sources: List<CalendarSource>, rows: List<com.example.itinerary.data.SentEvent>,
                       state: com.example.itinerary.data.CalendarSync.State, enabled: Boolean, onChoose: (Long?) -> Unit) {
    val target = sources.firstOrNull { it.sendHere }
    Text("Keep in sync with", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
    Column(Modifier.selectableGroup()) {
        (listOf<CalendarSource?>(null) + sources.filter { it.writable }).forEach { option ->
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .selectable(selected = option?.id == target?.id, enabled = enabled, role = Role.RadioButton) { onChoose(option?.id) },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                androidx.compose.material3.RadioButton(selected = option?.id == target?.id, onClick = null, enabled = enabled)
                Text(option?.name ?: "Off (don't sync)")
            }
        }
    }
    if (target != null) {
        val here = rows.filter { it.calendar == target.href }
        val count = here.count { it.uid != null && it.problem == null }
        Text("$count event${if (count == 1) " is" else "s are"} kept in sync with ${target.name}. Changes in Planner go there a few " +
            "seconds after you save; changes there come into Planner when calendars sync. Its events become Planner events, " +
            "except repeating ones and timed ones longer than a day, which stay read-only. Events deleted there go to " +
            "Recently deleted. Past Planner events go there only once you edit them.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Text("Title, time, place and notes are synced; anything else in its events (attendees, alarms…) is kept. Bills, payments, " +
        "checklists, reminders and attachments stay in Planner. Turning this off leaves the events in both places.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    state.message?.let { Text(it, color = if (state.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
}

// Step 6: events changed in both places. For each, what Planner has and what Nextcloud has (or that it was deleted
// there), and the choice. Nothing is written for an event until it's settled.
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ConflictsDialog(conflicts: List<com.example.itinerary.data.SentEvent>, onDismiss: () -> Unit) {
    val app = LocalContext.current.applicationContext as ItineraryApp
    val items by app.repository.allItems.collectAsStateWithLifecycle(initialValue = emptyList())
    val byId = remember(items) { items.associateBy { it.id } }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val zone = java.time.ZoneId.systemDefault()
    LaunchedEffect(conflicts.isEmpty()) { if (conflicts.isEmpty()) onDismiss() }
    PlannerDialog("Changed in both places", onDismissRequest = { if (!busy) onDismiss() },
        dismiss = DialogAction("Close", enabled = !busy, onClick = onDismiss)) {
        Text("These events were changed in Planner and on Nextcloud since they were last synced. Choose which version to keep.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        conflicts.forEach { row ->
            val mine = byId[row.itemId]
            val theirs = row.conflict?.takeIf { it.isNotEmpty() }?.let { com.example.itinerary.data.ServerEvents.parse(it, zone).item }
            SettingsHeading(mine?.title ?: theirs?.title ?: "Event")
            Text("In Planner: " + (mine?.let { describe(it) } ?: "deleted"))
            Text("On Nextcloud: " + when {
                row.conflict == "" -> "deleted"
                theirs != null -> describe(theirs)
                else -> "changed"
            })
            fun choose(choice: com.example.itinerary.data.CalendarSync.Resolution) {
                busy = true; error = null
                app.appScope.launch {
                    try { app.calendarSync.resolve(row.id, choice) }
                    catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (e: Exception) { error = (e as? com.example.itinerary.data.BackupException)?.message ?: e.message ?: "Couldn't settle this. Try again." }
                    finally { busy = false }
                }
            }
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MatrixTextButton(onClick = { choose(com.example.itinerary.data.CalendarSync.Resolution.PLANNER) }, enabled = !busy) { Text("Keep Planner's") }
                MatrixTextButton(onClick = { choose(com.example.itinerary.data.CalendarSync.Resolution.NEXTCLOUD) }, enabled = !busy) { Text("Keep Nextcloud's") }
                if (mine != null && theirs != null)
                    MatrixTextButton(onClick = { choose(com.example.itinerary.data.CalendarSync.Resolution.BOTH) }, enabled = !busy) { Text("Keep both") }
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

// "Mon 5 Oct · 09:00–10:00 · Dentist · Room 1"
@Composable
private fun describe(item: com.example.itinerary.data.ItineraryItem): String {
    val context = LocalContext.current
    val format = LocalTimeFormat.current
    val time = item.startTime?.let { start ->
        start.label(format, context) + (item.durationMinutes?.let { "–" + start.plusMinutes(it.toLong()).label(format, context) } ?: "")
    } ?: item.endDate?.let { "until ${it.shortLabel()}" } ?: "all day"
    return listOf(item.date.dayLabel(LocalDateFormat.current), time, item.title, item.location).filter { it.isNotBlank() }.joinToString(" · ")
}
