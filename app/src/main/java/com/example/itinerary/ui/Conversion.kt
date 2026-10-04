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
    // The request whose new item has been saved (its original replaced): rebuilt after that, nothing is left to convert,
    // and the new item's editor draft is recovered the usual way (TE-5).
    var replacedFor by mutableStateOf<String?>(null)
    // What replaced it: the new event's id, or "<new task id>|<event ids>" — so a window rebuilt after Android ended
    // Planner between the Save and the original's move can still move it (CV-4).
    var replacedWith by mutableStateOf<String?>(null)
    // The request the window was rebuilt with, until its editor opens: its draft is its own, not one in the way (TE-6).
    internal var restored: String? = null
    fun taskToEvent(taskId: String) { replacedFor = null; replacedWith = null; request = "task:$taskId" }
    fun eventToTask(eventId: Long, wholeSeries: Boolean) { replacedFor = null; replacedWith = null; request = "event:$eventId:$wholeSeries" }
}

val LocalConversions = staticCompositionLocalOf<Conversions?> { null }

@Composable
fun rememberConversions(): Conversions {
    var saved by rememberSaveable { mutableStateOf<String?>(null) }
    var savedReplaced by rememberSaveable { mutableStateOf<String?>(null) }
    var savedWith by rememberSaveable { mutableStateOf<String?>(null) }
    val conversions = remember { Conversions().also { it.request = saved; it.restored = saved; it.replacedFor = savedReplaced; it.replacedWith = savedWith } }
    LaunchedEffect(conversions.request) { saved = conversions.request }
    LaunchedEffect(conversions.replacedFor) { savedReplaced = conversions.replacedFor }
    LaunchedEffect(conversions.replacedWith) { savedWith = conversions.replacedWith }
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
    // Only this request's own close ends it: "Make it a task" in this editor asks for the next one first (TE-3).
    val done = { if (conversions.request == request) conversions.request = null }
    // Rebuilt after its Save, the original is gone: the new item stays, with its draft recovered as any other (TE-5).
    val restored = request == conversions.restored
    val parts = request.split(':')
    val toEvent = parts[0] == "task"
    if (restored && conversions.replacedFor == request) {
        LaunchedEffect(request) {
            // Ended between the Save and the original's move (CV-4): moved now, while both are there (an Undo since took
            // the new one back, and then nothing is moved). Already moved, the calls find nothing to do.
            conversions.replacedWith?.let { with -> runCatching { withContext(NonCancellable) {
                val repo = app.repository
                if (toEvent) with.toLong().let { id -> if (repo.eventDetails(id) != null) repo.replaceTaskWithEvent(parts[1], id) }
                else {
                    val (taskId, ids) = with.split('|', limit = 2)
                    if (repo.task(taskId) != null) repo.replaceEventsWithTask(ids.split(',').mapNotNull { it.toLongOrNull() }.toSet(), taskId)
                }
            } } }
            done()
        }
        return
    }
    val today = LocalDate.parse(rememberSaveable(request) { LocalDate.now().toString() })
    // The new task's id, kept with the request so a rebuilt window knows the new-task draft as its own (TE-6).
    val taskSeed = rememberSaveable(request) { java.util.UUID.randomUUID().toString() }
    // Read once: what the original is now, made into the other kind; or why it can't be.
    val prepared by produceState<Result<Prepared>?>(null, request) {
        value = runCatching {
            val ready = if (toEvent) {
                val task = app.repository.task(parts[1]) ?: error("This task no longer exists.")
                Prepared.ToEvent(TaskEventConversion.toEvent(task, today, hasTimeBlocks = app.repository.hasTimeBlocks(task.id)), task.attachments)
            } else {
                val id = parts[1].toLong()
                val whole = parts[2].toBoolean()
                val (event, attachments, reminders) = app.repository.eventDetails(id) ?: error("This event no longer exists.")
                val series = if (whole) app.repository.seriesEvents(id) else listOf(event)
                // A whole series is due on its next occurrence (its last, when all have passed), not the one tapped; its
                // reminder goes with it (CV-2).
                val occurrence = if (!whole) event else series.filter { !(it.endDate ?: it.date).isBefore(today) }.minByOrNull { it.date }
                    ?: series.maxBy { it.date }
                Prepared.ToTask(TaskEventConversion.toTask(event, reminders, attachments, whole, seriesCount = series.size, occurrence = occurrence,
                    idSeed = taskSeed), series.mapTo(hashSetOf()) { it.id })
            }
            val eventDraft = runCatching { EditorDraftStore(app).read() }.getOrNull()
            val taskDraft = runCatching { TaskDraftStore(app).read("new") }.getOrNull()
            // Rebuilt with this request, a draft is in the way only when it isn't this conversion's own.
            val ownDraft = restored && when (ready) {
                is Prepared.ToEvent -> eventDraft != null && runCatching { DraftCodec.item(eventDraft.getJSONObject("initial")) == ready.converted.result }.getOrDefault(false)
                is Prepared.ToTask -> taskDraft?.optString("id") == ready.converted.result.id
            }
            if (!ownDraft) conversionBlock(toEvent, eventDraft != null, EditorDraftStore.openEditors.value > 0, taskDraft != null)?.let { error(it) }
            ready
        }
        conversions.restored = null
    }
    val result = prepared ?: return
    result.exceptionOrNull()?.let { e ->
        PlannerDialog("Can't convert", onDismissRequest = done, dismiss = DialogAction("Close", onClick = done)) {
            Text(e.message ?: "Couldn't read it. Please try again.")
        }
        return
    }
    // The original is replaced once, at the new one's first Save, in the app's own scope: closing the editor straight
    // after ("Save changes?" → Save) mustn't cancel it and leave both (TE-8).
    fun replace(with: String, block: suspend () -> Unit) {
        if (conversions.replacedFor == request) return
        conversions.replacedFor = request
        conversions.replacedWith = with
        app.appScope.launch {
            withContext(NonCancellable) {
                try { block() } catch (e: Exception) {
                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        android.widget.Toast.makeText(app, "Saved, but the original couldn't be moved to Recently deleted. Delete it yourself.",
                            android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }
    when (val ready = result.getOrThrow()) {
      is Prepared.ToEvent -> {
        val (converted, files) = ready
        val event = remember(request) { converted.result }
        NewPlanningEventEditor(event, onSaved = { id -> replace(id.toString()) { app.repository.replaceTaskWithEvent(parts[1], id) } }, prefilled = true,
            initialAddedReminders = converted.reminders, initialAddedAttachments = files.map { it.copy(id = 0, itemId = 0) },
            notice = conversionNotice("event", converted.dropped), onDismiss = done)
      }
      is Prepared.ToTask -> {
        val (converted, ids) = ready
        val task = remember(request) { converted.result }
        PlanningOverlay(done) {
            TaskEditor(task, true, prefilled = true, notice = conversionNotice("task", converted.dropped),
                afterSave = { id -> replace("$id|" + ids.joinToString(",")) { app.repository.replaceEventsWithTask(ids, id) } }, onDismiss = done)
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
