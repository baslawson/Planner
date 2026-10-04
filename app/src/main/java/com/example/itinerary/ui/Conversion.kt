package com.example.itinerary.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.itinerary.ItineraryApp
import com.example.itinerary.data.*
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * Make a task into an event or an event into a task (wish list #1). A card's ⋮ or an editor asks with [convert]; AppNav's
 * [ConversionHost] then opens the other kind's editor filled in, with what won't carry over above it. Only its Save makes
 * the new one, and the original then goes to Recently deleted with an Undo that takes both back.
 */
class Conversions {
    // "task:<id>" or "event:<id>:<whole series>", kept when the window is rebuilt.
    var request by mutableStateOf<String?>(null)
    // The request the window was rebuilt with: its editor's draft is its own, not one in the way.
    internal var restored: String? = null
    fun taskToEvent(taskId: String) { request = "task:$taskId" }
    fun eventToTask(eventId: Long, wholeSeries: Boolean) { request = "event:$eventId:$wholeSeries" }
}

val LocalConversions = staticCompositionLocalOf<Conversions?> { null }

@Composable
fun rememberConversions(): Conversions {
    var saved by rememberSaveable { mutableStateOf<String?>(null) }
    val conversions = remember { Conversions().also { it.request = saved; it.restored = saved } }
    LaunchedEffect(conversions.request) { saved = conversions.request }
    return conversions
}

/** What a conversion leaves behind, above the new editor's form. */
@Composable
fun ConversionNotice(text: String) {
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = RoundedCornerShape(12.dp)) {
        Text(text, Modifier.fillMaxWidth().padding(12.dp), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer)
    }
}

internal fun conversionNotice(kind: String, dropped: List<String>): String =
    "Saving makes this $kind and moves the original to Recently deleted (Undo takes both back)." +
        if (dropped.isEmpty()) "" else "\nWon't carry over:\n" + dropped.joinToString("\n") { "• $it" }

// Why a conversion can't open its editor now, or null when it can: the editor it needs holds one unfinished draft.
internal fun conversionBlock(toEvent: Boolean, eventDraft: Boolean, eventEditorOpen: Boolean, taskDraft: Boolean): String? = when {
    toEvent && eventEditorOpen -> "Close the event that's open first, then try again."
    toEvent && eventDraft -> "You have an unfinished event. Resume or discard it first, then try again."
    !toEvent && taskDraft -> "You have an unfinished new task. Resume or discard it first, then try again."
    else -> null
}

private sealed interface Prepared {
    data class ToEvent(val converted: Converted<ItineraryItem>, val files: List<Attachment>) : Prepared
    data class ToTask(val converted: Converted<PlannerTask>, val ids: Set<Long>) : Prepared
}

@Composable
fun ConversionHost(conversions: Conversions) {
    val request = conversions.request ?: return
    val app = LocalContext.current.applicationContext as ItineraryApp
    val scope = rememberCoroutineScope()
    val done = { conversions.request = null }
    // The original is replaced once, at the new one's first Save.
    var replaced by rememberSaveable(request) { mutableStateOf(false) }
    val today = LocalDate.parse(rememberSaveable(request) { LocalDate.now().toString() })
    val parts = request.split(':')
    val toEvent = parts[0] == "task"
    // Read once: what the original is now, made into the other kind; or why it can't be.
    val prepared by produceState<Result<Prepared>?>(null, request) {
        value = runCatching {
            // An editor this one came from (with nothing unsaved) may still be closing: give it a moment.
            if (request != conversions.restored) kotlinx.coroutines.delay(300)
            if (request != conversions.restored) conversionBlock(toEvent, runCatching { EditorDraftStore(app).read() }.getOrNull() != null,
                EditorDraftStore.openEditors.value > 0, runCatching { TaskDraftStore(app).read("new") }.getOrNull() != null)
                ?.let { error(it) }
            if (toEvent) {
                val task = app.repository.task(parts[1]) ?: error("This task no longer exists.")
                Prepared.ToEvent(TaskEventConversion.toEvent(task, today), task.attachments)
            } else {
                val id = parts[1].toLong()
                val whole = parts[2].toBoolean()
                val (event, attachments, reminders) = app.repository.eventDetails(id) ?: error("This event no longer exists.")
                Prepared.ToTask(TaskEventConversion.toTask(event, reminders, attachments, whole), if (whole) app.repository.seriesIds(id) else setOf(id))
            }
        }
    }
    val result = prepared ?: return
    result.exceptionOrNull()?.let { e ->
        PlannerDialog("Can't convert", onDismissRequest = done, dismiss = DialogAction("Close", onClick = done)) {
            Text(e.message ?: "Couldn't read it. Please try again.")
        }
        return
    }
    fun replace(block: suspend () -> Unit) {
        if (replaced) return
        replaced = true
        scope.launch {
            withContext(NonCancellable) {
                try { block() } catch (e: Exception) {
                    android.widget.Toast.makeText(app, "Saved, but the original couldn't be moved to Recently deleted. Delete it yourself.",
                        android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    when (val ready = result.getOrThrow()) {
      is Prepared.ToEvent -> {
        val (converted, files) = ready
        val event = remember(request) { converted.result }
        NewPlanningEventEditor(event, onSaved = { id -> replace { app.repository.replaceTaskWithEvent(parts[1], id) } }, prefilled = true,
            initialAddedReminders = converted.reminders, initialAddedAttachments = files.map { it.copy(id = 0, itemId = 0) },
            notice = conversionNotice("event", converted.dropped), onDismiss = done)
      }
      is Prepared.ToTask -> {
        val (converted, ids) = ready
        val task = remember(request) { converted.result }
        PlanningOverlay(done) {
            TaskEditor(task, true, prefilled = true, notice = conversionNotice("task", converted.dropped),
                afterSave = { id -> replace { app.repository.replaceEventsWithTask(ids, id) } }, onDismiss = done)
        }
      }
    }
}

/** A repeating event made into a task: just this occurrence, or the whole series as one repeating task. */
@Composable
fun MakeTaskSeriesChoice(onChoose: (wholeSeries: Boolean) -> Unit, onDismiss: () -> Unit) {
    PlannerDialog("Make it a task", onDismissRequest = onDismiss,
        primary = DialogAction("This event") { onChoose(false) },
        dismiss = DialogAction("Cancel", onClick = onDismiss),
        extra = listOf(DialogAction("Whole series") { onChoose(true) })) {
        Text("This event becomes a task and the rest of the series stays. Or the whole series becomes one repeating task.")
    }
}
