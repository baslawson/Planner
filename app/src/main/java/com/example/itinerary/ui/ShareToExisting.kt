package com.example.itinerary.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.itinerary.ItineraryApp
import com.example.itinerary.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * "Add to existing…" in a share's Add to Planner: a search and the events, bills, tasks and notes it can go to
 * (ShareTargets). Picking one adds the shared [files] to it and [text] after its notes; [onAdded] gets it and what to
 * say. An item open in an editor is left alone (its editor would save over what's added), with a word why.
 */
@Composable
fun ShareToExisting(files: List<Attachment>, text: String?, onBack: () -> Unit, onAdded: (ShareTargets.Target, String) -> Unit) {
    val app = LocalContext.current.applicationContext as ItineraryApp
    val dateFormat = LocalDateFormat.current
    var query by rememberSaveable { mutableStateOf("") }
    val data by produceState<Triple<List<ItineraryItem>, List<PlannerTask>, List<PlannerNote>>?>(null) {
        value = withContext(Dispatchers.IO) { runCatching { app.repository.shareCandidates() }.getOrNull() }
    }
    val targets = remember(data, query) {
        data?.let { (items, tasks, notes) -> ShareTargets.targets(items, tasks, notes, LocalDate.now(), query) { it.dayLabel(dateFormat) } }
    }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun pick(target: ShareTargets.Target) {
        if (busy) return
        val open = when (target.kind) {
            ShareTargets.Kind.EVENT, ShareTargets.Kind.BILL -> EditorDraftStore.openEditors.value > 0
            ShareTargets.Kind.TASK -> TaskDraftStore.isOpen(target.id)
            ShareTargets.Kind.NOTE -> NoteDraftStore.isOpen(target.id)
        }
        if (open) {
            error = if (target.kind == ShareTargets.Kind.EVENT || target.kind == ShareTargets.Kind.BILL)
                "An event is open in its editor. Save or close it first, then add these."
            else "\"${target.title}\" is open in its editor. Save or close it first, then add these."
            return
        }
        busy = true; error = null
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { app.repository.addShareTo(target, files, text) } }
                .onSuccess { title -> onAdded(target, addedMessage(files, text, title)) }
                .onFailure { error = it.message ?: "Couldn't add them there. Try again." }
            busy = false
        }
    }
    PlannerDialog("Add to existing", onDismissRequest = onBack, dismiss = DialogAction("Back", onClick = onBack), scroll = null) {
        Text(if (files.isEmpty()) "Pick where the text goes: it's added after the notes."
            else "Pick where ${if (files.size == 1) "it goes" else "they go"}: " + SharedFiles.summary(files) +
                (if (!text.isNullOrBlank()) ". The text that came with ${if (files.size == 1) "it" else "them"} goes after the notes." else "."))
        OutlinedTextField(query, { query = it }, label = { Text("Search") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        val list = targets
        when {
            list == null -> Text("Loading…")
            list.isEmpty() -> Text(if (query.isBlank()) "Nothing to add to yet." else "Nothing matches \"$query\".")
            else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp)) {
                ShareTargets.Kind.entries.forEach { kind ->
                    val rows = list.filter { it.kind == kind }
                    if (rows.isNotEmpty()) {
                        item(key = "h-" + kind.name) {
                            Text(kind.heading, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 10.dp, bottom = 2.dp).semantics { heading() })
                        }
                        items(rows, key = { kind.name + "-" + it.id }) { target ->
                            Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = !busy) { pick(target) }.padding(vertical = 8.dp)) {
                                Text(target.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(target.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
        if (busy) Text("Adding…")
    }
}

/** "Added 2 photos to Dentist." / "Added the text to Dentist." */
fun addedMessage(files: List<Attachment>, text: String?, title: String): String {
    val photos = files.isNotEmpty() && files.all { it.mimeType.startsWith("image/") }
    val what = when {
        files.isEmpty() -> "the text"
        files.size == 1 -> if (photos) "the photo" else "the file"
        else -> "${files.size} " + if (photos) "photos" else "files"
    }
    return "Added $what to ${title.ifBlank { "it" }}."
}
