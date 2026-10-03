package com.example.itinerary.ui

import com.example.itinerary.ui.MatrixTextButton as TextButton
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.example.itinerary.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

/** What Quick entry just added: its title, its date (none for an anytime task) and whether it's a task. */
data class QuickAdded(val title: String, val date: LocalDate?, val task: Boolean) {
    fun message(format: DateFormatChoice): String = "Added: $title" + (date?.let { ", " + it.dayLabel(format) } ?: "")
}

/** Says what Quick entry added in the app's bar at the bottom, for a screen that can't show it. */
@Composable
fun rememberAddedBar(): (QuickAdded) -> Unit {
    val bar = LocalAppSnackbar.current
    val format = LocalDateFormat.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    return { added -> bar?.let { scope.launch { it.showSnackbar(added.message(format), withDismissAction = true) } } }
}

/** The app's bar at the bottom (the one with Undo), for a screen that has something short to say. */
val LocalAppSnackbar = androidx.compose.runtime.staticCompositionLocalOf<androidx.compose.material3.SnackbarHostState?> { null }

/** Quick entry with its private draft: kept across closing, cleared once the entry is added. Nothing pops up once it
 *  is: "Add another" says so in the dialog, and "Add" hands [onAddedAndClosed] what was added for the screen to show. */
@Composable
fun QuickEntryDialog(
    today: LocalDate, onDismiss: () -> Unit,
    onAdd: suspend (QuickEntrySuggestion, Boolean, String) -> Unit,
    onReview: (QuickEntrySuggestion, Boolean) -> Unit,
    onAddedAndClosed: (QuickAdded) -> Unit = {},
) {
    val context = LocalContext.current
    val repository = (context.applicationContext as com.example.itinerary.ItineraryApp).repository
    val store = remember { QuickDraftStore(context) }
    val loaded = remember { runCatching { store.read() } }
    var draft by remember { mutableStateOf(loaded.getOrNull() ?: QuickDraft(QuickInput(baseDate = today))) }
    var error by remember { mutableStateOf(if (loaded.isFailure) "Couldn't read the previous draft. Close and retry, or discard it to start again." else null) }
    var loadFailed by remember { mutableStateOf(loaded.isFailure) }
    var generation by rememberSaveable { mutableStateOf(0) }
    var discard by remember { mutableStateOf(false) }
    var warnings by remember { mutableStateOf<List<String>>(emptyList()) }
    var permission by remember { mutableStateOf<CompletableDeferred<Boolean>?>(null) }
    var handover by remember { mutableStateOf<String?>(null) }
    // The entry just added (until the dialog goes on or closes), and the one "Add another" says it added.
    var justAdded by remember { mutableStateOf<QuickAdded?>(null) }
    var shownAdded by remember { mutableStateOf<QuickAdded?>(null) }
    fun update(next: QuickDraft) {
        if (next == draft || loadFailed) return
        try { store.write(next); draft = next; error = null }
        catch (_: Exception) { error = "Couldn't keep this draft. Free some storage and try again." }
    }
    suspend fun confirm(candidates: List<QuickCandidate>): Boolean {
        val snapshot = repository.snapshot()
        val found = withContext(Dispatchers.Default) { quickConflicts(candidates, snapshot.items, snapshot.tasks) }
        if (found.isEmpty()) return true
        warnings = found
        val answer = CompletableDeferred<Boolean>()
        permission = answer
        return try { answer.await() } finally { permission = null; warnings = emptyList() }
    }
    if (loadFailed) {
        PlannerDialog("Quick entry draft", onDismiss, primary = DialogAction("Discard draft", danger = true) { discard = true },
            dismiss = DialogAction("Close", onClick = onDismiss)) { Text(error.orEmpty()) }
    } else key(generation) {
        QuickEntryEditor(draft.single.baseDate, onDismiss = { justAdded?.let(onAddedAndClosed); onDismiss() },
            onAdd = { suggestion, task, token ->
                if (!confirm(suggestion.eachTime().mapIndexed { i, s -> QuickCandidate(quickToken(token, i), s, task) })) throw QuickSaveCancelled()
                // Checked again: a reminder can pass while "Check before adding" waits.
                suggestion.quickProblem(task, ZonedDateTime.now())?.let { throw QuickSaveProblem(it) }
                onAdd(suggestion, task, token)
                store.clear()
                justAdded = QuickAdded(suggestion.title, if (task) suggestion.quickTask().dueDate else suggestion.date, task)
            },
            onReview = { s, t ->
                // The full editor would offer to resume an older unfinished draft and replace this entry with it.
                val older = runCatching { if (t) TaskDraftStore(context).read("new") else EditorDraftStore(context).read() }
                val kind = if (t) "task" else "event"
                when {
                    older.isFailure -> handover = "Couldn't check for an unfinished $kind. Your Quick entry is still here; try again."
                    older.getOrNull() != null -> handover = "You have an unfinished $kind ‘${older.getOrNull()?.optString("title").orEmpty().ifBlank { "untitled" }}’. " +
                        "Finish or discard it first (Add ${if (t) "task" else "event"}), then open this Quick entry in the full editor. Your Quick entry is kept."
                    else -> { store.clear(); onReview(s, t) }
                }
            },
            onContinue = { task ->
                shownAdded = justAdded; justAdded = null
                val next = QuickDraft(QuickInput(task = task, baseDate = LocalDate.now()))
                store.write(next); draft = next
                generation++
            },
            onDiscard = { discard = true },
            initial = draft.single, inputBlocked = error != null, onInput = { input -> update(QuickDraft(input)) },
            modeControls = {
                if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error)
                else shownAdded?.let { added ->
                    Text(added.message(LocalDateFormat.current),
                        Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = MaterialTheme.colorScheme.primary)
                }
            })
    }
    if (permission != null) PlannerDialog("Check before adding", { permission?.complete(false) },
        primary = DialogAction("Add anyway") { permission?.complete(true) },
        dismiss = DialogAction("Go back") { permission?.complete(false) }) {
        Text("This entry may duplicate or overlap your plans. You can go back to edit it.")
        warnings.forEach { Text(it) }
    }
    handover?.let { message ->
        PlannerDialog("Unfinished draft", { handover = null }, primary = DialogAction("OK") { handover = null }) { Text(message) }
    }
    if (discard) PlannerDialog("Discard quick entry draft?", { discard = false },
        primary = DialogAction("Discard", danger = true) {
            try { store.clear(); generation++; draft = QuickDraft(QuickInput(baseDate = today)); loadFailed = false; error = null; discard = false }
            catch (_: Exception) { error = "Couldn't discard the draft. Try again."; discard = false }
        },
        dismiss = DialogAction("Keep draft") { discard = false }) {
        Text("Unfinished text will be removed. Entries already saved stay in your planner.")
    }
}

internal class QuickSaveCancelled : Exception()
internal class QuickSaveProblem(message: String) : Exception(message)

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun QuickEntryEditor(
    today: LocalDate,
    onDismiss: () -> Unit,
    onAdd: suspend (QuickEntrySuggestion, Boolean, String) -> Unit,
    onReview: (QuickEntrySuggestion, Boolean) -> Unit,
    initial: QuickInput = QuickInput(baseDate = today),
    onInput: (QuickInput) -> Unit = {},
    onContinue: ((Boolean) -> Unit)? = null,
    onDiscard: (() -> Unit)? = null,
    inputBlocked: Boolean = false,
    modeControls: @Composable () -> Unit = {},
) {
    var field by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(initial.text, TextRange(initial.text.length))) }
    val text = field.text
    // Optional title box: its words are always the title. Empty, the when box works as the single entry box.
    var titleField by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(initial.title, TextRange(initial.title.length))) }
    val title = titleField.text
    var fieldFocused by remember { mutableStateOf(false) }
    var settled by remember { mutableStateOf(true) }
    var attempted by remember { mutableStateOf(false) }
    var showDetails by rememberSaveable { mutableStateOf(false) }
    var pickingDuration by rememberSaveable { mutableStateOf(false) }
    var durationText by rememberSaveable { mutableStateOf(initial.durationText) }
    LaunchedEffect(text) { delay(650); settled = true }
    val showFeedback = settled || attempted || !fieldFocused
    var task by rememberSaveable { mutableStateOf(initial.task) }
    // "Remind me to …" picks Task or Event until the person chooses a type themselves.
    var typeChosen by rememberSaveable { mutableStateOf(initial.typeChosen) }
    var literalOffsets by rememberSaveable { mutableStateOf(initial.literals.flatMap { listOf(it.first, it.last + 1) }.toIntArray()) }
    val literalRanges = literalOffsets.asList().chunked(2).map { it[0] until it[1] }
    val literalKey = literalOffsets.contentHashCode()
    var dateOverride by rememberSaveable { mutableStateOf(initial.dateOverride) }
    var timeOverride by rememberSaveable { mutableStateOf(initial.timeOverride) }
    var countText by rememberSaveable { mutableStateOf(initial.countText) }
    var removeReminder by rememberSaveable { mutableStateOf(initial.removeReminder) }
    val saveToken = rememberSaveable(text, title, task, literalKey, dateOverride, timeOverride, countText, removeReminder, durationText) { java.util.UUID.randomUUID().toString() }
    var pickingDate by rememberSaveable { mutableStateOf(false) }
    var pickingTime by rememberSaveable { mutableStateOf(false) }
    var showPhrases by rememberSaveable { mutableStateOf(false) }
    var selectedPhrase by remember { mutableStateOf<QuickEntryPhrase?>(null) }
    var busy by remember { mutableStateOf(false) }
    var saveError by remember(text, title, task) { mutableStateOf<String?>(null) }
    // A draft keeps the day it was started until the person edits it again.
    var baseDate by rememberSaveable { mutableStateOf(today) }
    val parsed = remember(text, title, literalKey, baseDate) { QuickInput(text, literals = literalRanges, baseDate = baseDate, title = title).parse() }
    val currentInput = QuickInput(text, task, literalRanges, dateOverride, timeOverride, countText, removeReminder, baseDate, durationText, title, typeChosen)
    SideEffect { onInput(currentInput) }
    val context = LocalContext.current
    val notifications = rememberNotificationState()
    val app = context.applicationContext as com.example.itinerary.ItineraryApp
    var now by remember { mutableStateOf(ZonedDateTime.now()) }
    val exactAllowed by rememberExactAlarmsAllowed(app.reminderScheduler)
    LaunchedEffect(Unit) { while (true) { now = ZonedDateTime.now(); delay(30_000) } }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { now = ZonedDateTime.now() }
    val suggestion = remember(currentInput, now) { currentInput.suggestion(now) }
    val reminder = suggestion.reminderMinutes?.let { reminderTrigger(suggestion.date, if (task) null else suggestion.time, it.toLong(), now.zone) }
    val series = if (!task && suggestion.repeat != RepeatRule.NONE && suggestion.repeatCount in 2..365)
        runCatching { suggestion.repeat.dates(suggestion.date, suggestion.repeatCount).takeIf { dates -> dates.all { it.year in 1..9999 } } }.getOrNull() else null
    val problem = if (currentInput.length > 500) "Use at most 500 characters per entry." else suggestion.quickProblem(task, now)
    val valid = !currentInput.empty && problem == null && !busy && !inputBlocked
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    var fieldReady by remember { mutableStateOf(false) }
    LaunchedEffect(fieldReady) { if (fieldReady) { focus.requestFocus(); keyboard?.show() } }
    // Tasks have no time or duration: a time or duration picked for an event would block the task with nothing to remove.
    fun switchType(toTask: Boolean) {
        if (toTask && !task) {
            if (!timeOverride.isNullOrEmpty()) timeOverride = null
            if (!durationText.isNullOrEmpty()) durationText = null
        }
        task = toTask
    }
    fun editField(value: TextFieldValue) {
        if (value.text != text) {
            val updated = currentInput.rebased(LocalDate.now()).edited(value.text)
            baseDate = updated.baseDate
            if (!typeChosen) updated.parse().takeIf { it.taskHint }?.let { switchType(!it.timed()) }
            literalOffsets = updated.literals.flatMap { listOf(it.first, it.last + 1) }.toIntArray()
            dateOverride = updated.dateOverride; timeOverride = updated.timeOverride
            countText = updated.countText; removeReminder = updated.removeReminder; durationText = updated.durationText
            settled = false; attempted = false
        }
        field = value
    }
    fun edit(value: String) = editField(TextFieldValue(value, TextRange(value.length)))
    fun editTitle(value: TextFieldValue) {
        if (value.text != title) { settled = false; attempted = false; baseDate = LocalDate.now() }
        titleField = value.copy(text = value.text.replace('\n', ' '))
    }
    val completions = if (fieldFocused && field.selection.collapsed && !busy)
        quickCompletions(text, field.selection.start, literalRanges, task) else emptyList()
    fun clearCorrection(kind: QuickPhraseKind?) {
        if (kind == QuickPhraseKind.DATE) dateOverride = null
        if (kind == QuickPhraseKind.TIME) { timeOverride = null; durationText = null }
        if (kind == QuickPhraseKind.DURATION) durationText = null
        if (kind == QuickPhraseKind.REPEAT) countText = null
        if (kind == QuickPhraseKind.REMINDER) removeReminder = false
    }
    fun undoLiteral() {
        val removed = literalRanges.last()
        val remaining = literalRanges.dropLast(1)
        val restored = currentInput.copy(literals = remaining).parse().phrases.firstOrNull { it.start == removed.first }
        clearCorrection(restored?.kind)
        literalOffsets = literalOffsets.dropLast(2).toIntArray()
    }
    fun add(another: Boolean = false) {
        attempted = true
        if (!valid || busy) return
        if (reminder != null && reminder.toInstant() <= java.time.Instant.now()) { now = ZonedDateTime.now(); return }
        busy = true; saveError = null; if (!another) keyboard?.hide()
        scope.launch {
            try { onAdd(suggestion, task, saveToken); if (another && onContinue != null) onContinue(task) else onDismiss() }
            catch (_: QuickSaveCancelled) { /* Return to the unchanged entry. */ }
            catch (problem: QuickSaveProblem) { now = ZonedDateTime.now(); saveError = problem.message }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { saveError = "Couldn't save. Your entry is still here; try again." }
            finally { busy = false }
        }
    }
    val colors = mapOf(QuickPhraseKind.DATE to MaterialTheme.colorScheme.primary,
        QuickPhraseKind.TIME to MaterialTheme.colorScheme.tertiary, QuickPhraseKind.DURATION to MaterialTheme.colorScheme.secondary,
        QuickPhraseKind.LOCATION to MaterialTheme.colorScheme.secondary, QuickPhraseKind.REMINDER to MaterialTheme.colorScheme.tertiary,
        QuickPhraseKind.REPEAT to MaterialTheme.colorScheme.primary, QuickPhraseKind.UNSUPPORTED to if (showFeedback) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
    // "In 2 hours" in an old draft can't be read, so it isn't shown as understood.
    val staleRelative = suggestion.error == QuickEntry.STALE_RELATIVE
    val errorColor = MaterialTheme.colorScheme.error
    val highlight = VisualTransformation { original ->
        val result = AnnotatedString.Builder(original)
        parsed.phrases.forEach { phrase -> if (phrase.start >= 0 && phrase.end <= original.length)
            result.addStyle(SpanStyle(color = if (staleRelative && phrase.kind == QuickPhraseKind.TIME) errorColor else colors.getValue(phrase.kind),
                fontWeight = FontWeight.Bold), phrase.start, phrase.end) }
        TransformedText(result.toAnnotatedString(), OffsetMapping.Identity)
    }
    val canAdd = !currentInput.empty && !busy && !inputBlocked && (valid || !showFeedback)
    PlannerDialog(
        title = "Quick entry",
        onDismissRequest = { if (!busy) onDismiss() },
        primary = DialogAction(if (busy) "Saving…" else if (task) "Add task" else (suggestion.extraTimes.size + 1).let { times ->
                if (suggestion.repeat != RepeatRule.NONE) "Add ${suggestion.repeatCount * times} events" else if (times > 1) "Add $times events" else "Add event" },
            enabled = canAdd) { add() },
        dismiss = DialogAction("Close", enabled = !busy, onClick = onDismiss),
        extra = if (onContinue != null) listOf(DialogAction("Add another", enabled = canAdd) { add(true) }) else emptyList(),
        // The Event/Task switch sits with the heading, outside the scrolling part: in landscape the content opens
        // scrolled to the focused field, which hid the switch above it.
        header = {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    val green = MaterialTheme.colorScheme.primary
                    listOf(false to "Event", true to "Task").forEachIndexed { index, (isTask, label) ->
                        SegmentedButton(selected = task == isTask, enabled = !busy, onClick = { switchType(isTask); typeChosen = true },
                            shape = SegmentedButtonDefaults.itemShape(index, 2),
                            colors = SegmentedButtonDefaults.colors(activeContainerColor = green.copy(alpha = 0.16f), activeContentColor = green,
                                activeBorderColor = green, inactiveContainerColor = Color.Transparent,
                                inactiveContentColor = MaterialTheme.colorScheme.onSurfaceVariant, inactiveBorderColor = green.copy(alpha = 0.5f))) { Text(label) }
                    }
                }
        },
    ) {
                modeControls()
                if (baseDate != LocalDate.now()) Text("Dates based on ${baseDate.fullLabel()}", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(titleField, ::editTitle, enabled = !busy,
                    label = { Text("Title") }, placeholder = { Text(if (task) "Buy groceries" else "Gym") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { focus.requestFocus() }))
                OutlinedTextField(field, ::editField, enabled = !busy,
                    label = { Text(if (title.isNotBlank()) (if (task) "Due" else "When") else if (task) "Task and due date" else "Event and when") },
                    placeholder = { Text(if (title.isNotBlank()) (if (task) "tmr" else "every Monday 6pm") else if (task) "Buy groceries tmr" else "Gym every Monday 6pm") },
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).onFocusChanged { fieldFocused = it.isFocused }.onGloballyPositioned { fieldReady = true }, visualTransformation = highlight,
                    keyboardOptions = KeyboardOptions(capitalization = if (title.isNotBlank()) KeyboardCapitalization.None else KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { add() }))
                if (completions.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    completions.forEach { completion -> TextButton(enabled = !busy, onClick = {
                        val (next, cursor) = completion.apply(text)
                        editField(TextFieldValue(next, TextRange(cursor)))
                        focus.requestFocus(); keyboard?.show()
                    }) { Text(completion.label) } }
                }
                if (currentInput.empty) {
                    Text("Try an example", style = MaterialTheme.typography.labelMedium)
                    val examples = if (task) listOf("Buy groceries" to "", "Call plumber" to "tmr") else listOf("Dentist" to "Fri 3pm for 45 minutes", "Gym" to "every Monday 6pm")
                    examples.forEach { (exampleTitle, exampleWhen) -> TextButton(onClick = {
                        editTitle(TextFieldValue(exampleTitle, TextRange(exampleTitle.length))); edit(exampleWhen)
                    }) { Text("$exampleTitle $exampleWhen".trim()) } }
                } else {
                    if (showFeedback && suggestion.dateChoices.isNotEmpty()) {
                        Text("Which date did you mean?")
                        suggestion.dateChoices.forEach { choice -> TextButton(enabled = !busy, onClick = { dateOverride = choice.toString() }) { Text(choice.fullLabel()) } }
                    }
                    if (showFeedback && !task && suggestion.ambiguousTime && suggestion.dateChoices.isEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            suggestion.timeChoices.forEach { choice -> TextButton(enabled = !busy, onClick = { timeOverride = choice.toString() }) {
                                val minutes = if (choice.minute == 0) "" else ":%02d".format(choice.minute)
                                Text("${if (choice.hour % 12 == 0) 12 else choice.hour % 12}$minutes ${if (choice.hour < 12) "AM" else "PM"}")
                            } }
                        }
                    }
                    // What will be saved, as one summary; the date and time open their pickers.
                    Text("Will add", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Surface(shape = RoundedCornerShape(12.dp), color = Color.Transparent,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                            Text(suggestion.title.ifBlank { "Add a title" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                                color = if (suggestion.title.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                            FlowRow {
                                SummaryButton({ Icon(Icons.Filled.DateRange, contentDescription = null, Modifier.size(18.dp)) }, kind = if (task) "Due date" else "Date", enabled = !busy, onClick = { keyboard?.hide(); pickingDate = true },
                                    label = if (suggestion.endDate != null && !task) spanLabel(suggestion.date, suggestion.endDate) else if (suggestion.dateChoices.isNotEmpty()) "Choose date" else if (task && !suggestion.dateSpecified) "No due date" else if (showDetails) suggestion.date.dayLabel(LocalDateFormat.current) else when (suggestion.date) {
                                        LocalDate.now() -> "Today"
                                        LocalDate.now().plusDays(1) -> "Tomorrow"
                                        else -> suggestion.date.fullLabel()
                                    })
                                if (showDetails && task && suggestion.dateSpecified) MatrixQuietButton(enabled = !busy, onClick = { dateOverride = "" }) { Text("Clear date") }
                                if (!task) {
                                    SummaryButton({ Icon(painterResource(com.example.itinerary.R.drawable.action_clock), contentDescription = null, Modifier.size(18.dp)) }, kind = "Time", enabled = !busy, onClick = { keyboard?.hide(); pickingTime = true },
                                        label = suggestion.time?.let { first -> val format = LocalTimeFormat.current; (listOf(first) + suggestion.extraTimes).joinToString(" and ") { it.label(format, context) } } ?: if (suggestion.ambiguousTime || suggestion.durationMinutes != null) "Choose time" else "All day · set time")
                                    if (showDetails && suggestion.time != null && suggestion.durationMinutes == null) MatrixQuietButton(enabled = !busy, onClick = { timeOverride = "" }) { Text("All day") }
                                }
                                if (!task && (suggestion.durationMinutes != null || showDetails && suggestion.time != null)) SummaryButton(null, kind = "Duration", enabled = !busy, onClick = { pickingDuration = true; keyboard?.hide() },
                                    label = suggestion.durationMinutes?.let { "for $it min" } ?: "Duration")
                                if (suggestion.location.isNotBlank()) SummaryButton({ Icon(Icons.Filled.Place, contentDescription = null, Modifier.size(18.dp)) }, kind = "Place", enabled = !busy, label = suggestion.location, onClick = {
                                    parsed.phrases.firstOrNull { it.kind == QuickPhraseKind.LOCATION }?.let { val prefix = Regex("^at\\s+", RegexOption.IGNORE_CASE).find(text.substring(it.start, it.end))?.value?.length ?: 0; field = field.copy(selection = TextRange(it.start + prefix, it.end)); focus.requestFocus(); keyboard?.show() }
                                })
                                if (suggestion.repeat != RepeatRule.NONE) SummaryButton({ Icon(Icons.Filled.Refresh, contentDescription = null, Modifier.size(18.dp)) }, kind = "Repeat", enabled = !busy, onClick = { showDetails = !showDetails },
                                    label = if (task) suggestion.repeat.label else "${suggestion.repeat.label} · ${suggestion.repeatCount} events")
                                if (reminder != null) SummaryButton({ Icon(Icons.Filled.Notifications, contentDescription = null, Modifier.size(18.dp)) }, kind = "Reminder", enabled = !busy, onClick = { showDetails = !showDetails },
                                    label = suggestion.quickReminders().single().label)
                            }
                        }
                    }
                    MatrixQuietButton(enabled = !busy, onClick = { showDetails = !showDetails }) {
                        Text(if (showDetails) "Fewer options" else "More options")
                        Icon(if (showDetails) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, contentDescription = null)
                    }
                    if (showDetails) {
                        if (!task && suggestion.durationMinutes != null) {
                            val end = suggestion.time?.plusMinutes(suggestion.durationMinutes.toLong())
                            val nextDay = suggestion.time?.let { it.toSecondOfDay() / 60 + suggestion.durationMinutes >= 1440 } == true
                            Text("${suggestion.durationMinutes} minutes" + (end?.let { " · ends ${it.label(LocalTimeFormat.current, context)}${if (nextDay) " next day" else ""}" } ?: ""), style = MaterialTheme.typography.bodySmall)
                        }
                        if (suggestion.location.isNotBlank()) Text("Location: ${suggestion.location}", style = MaterialTheme.typography.bodySmall)
                        if (suggestion.repeat != RepeatRule.NONE) {
                            if (task) Text("${suggestion.repeat.label} · the next task is created when you complete this one.", style = MaterialTheme.typography.bodySmall)
                            else {
                                OutlinedTextField(countText ?: suggestion.repeatCount.toString(), { countText = it.filter(Char::isDigit).take(3) }, enabled = !busy,
                                    label = { Text("Occurrences") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
                                if (series != null) Text("${suggestion.repeat.label} · ${series.size} events\nFirst: ${series.first().fullLabel()}\nLast: ${series.last().fullLabel()}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        if (reminder != null) {
                            Text("Reminder: ${suggestion.quickReminders().single().label}", style = MaterialTheme.typography.bodySmall)
                            Text("${reminder.toLocalDate().fullLabel()} · ${reminder.toLocalTime().label(LocalTimeFormat.current, context)} · ${now.zone.id}", style = MaterialTheme.typography.bodySmall)
                            if (task || suggestion.time == null) Text("Counts back from ${LocalTime.of(9, 0).label(LocalTimeFormat.current, context)} on ${if (task) "the due date" else "an all-day event"}.", style = MaterialTheme.typography.bodySmall)
                            TextButton(enabled = !busy, onClick = { removeReminder = true }) { Text("Remove reminder") }
                            if (!exactAllowed) Text(LATE_REMINDER_HINT, style = MaterialTheme.typography.bodySmall)
                        }
                        if (parsed.phrases.isNotEmpty()) {
                            TextButton(enabled = !busy, onClick = { showPhrases = !showPhrases }) { Text(if (showPhrases) "Hide recognised phrases" else "Adjust recognised text") }
                            if (showPhrases) {
                                Text("Tap a phrase to keep it in the title.", style = MaterialTheme.typography.bodySmall)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    parsed.phrases.forEach { phrase -> TextButton(enabled = !busy, onClick = { selectedPhrase = phrase; keyboard?.hide() }) {
                                        Text("${phrase.kind.label}: ${text.substring(phrase.start, phrase.end).trim()}", color = colors.getValue(phrase.kind))
                                    } }
                                }
                            }
                        }
                        if (literalRanges.isNotEmpty()) TextButton(enabled = !busy, onClick = { undoLiteral() }) { Text("Undo keep in title") }
                    }
                    if (reminder != null && !notifications.enabled) {
                        Text("Notifications are off, so reminders won't show.", color = MaterialTheme.colorScheme.error)
                        TextButton(enabled = !busy, onClick = notifications.enable) { Text("Enable notifications") }
                    }
                    // Typed on purpose ("yesterday"): a plain note rather than a warning to move it.
                    if (showFeedback && suggestion.dateChoices.isEmpty() && suggestion.pastDate && suggestion.isPast(now, task))
                        Text("Adds to a past date.", style = MaterialTheme.typography.bodySmall)
                    else if (showFeedback && suggestion.dateChoices.isEmpty() && suggestion.isPast(now, task)) {
                        Text("This date or time is in the past.", color = MaterialTheme.colorScheme.error)
                        TextButton(enabled = !busy, onClick = { dateOverride = (if (suggestion.repeat != RepeatRule.NONE) suggestion.nextRepeatDate(task, now) else if (task) now.toLocalDate() else now.toLocalDate().plusDays(1)).toString() }) { Text(if (suggestion.repeat != RepeatRule.NONE) "Move to next occurrence" else if (task) "Move to today" else "Move to tomorrow") }
                    }
                    if (showFeedback && problem != null && problem != "Which date did you mean?") Text(problem, color = MaterialTheme.colorScheme.error)
                    if (!showFeedback && problem != null) Text("Keep typing, or choose a suggestion.", style = MaterialTheme.typography.bodySmall)
                    // The full editor holds one event, so an entry with several times is added from here.
                    if (showDetails && !task && suggestion.extraTimes.isNotEmpty()) Text("Adds an event at each time. The full editor holds one event: choose one time to open it there.", style = MaterialTheme.typography.bodySmall)
                    else if (showDetails) TextButton(enabled = valid, onClick = { keyboard?.hide(); onReview(suggestion, task) }) { Text("Open in full editor") }
                    if (showDetails && onDiscard != null) TextButton(enabled = !busy, onClick = onDiscard) { Text("Discard draft") }
                }
                if (saveError != null) Text(saveError!!, color = MaterialTheme.colorScheme.error)
    }
    selectedPhrase?.let { phrase ->
        PlannerDialog(text.substring(phrase.start, phrase.end).trim(), { selectedPhrase = null },
            primary = DialogAction("Keep in title") { clearCorrection(phrase.kind); literalOffsets += intArrayOf(phrase.start, phrase.end); selectedPhrase = null },
            dismiss = DialogAction("Cancel") { selectedPhrase = null }) {
            Text("Keep these words in the title instead of using them as ${phrase.kind.label.lowercase()} details.")
        }
    }
    if (pickingDuration) {
        var minutes by rememberSaveable { mutableStateOf((suggestion.durationMinutes ?: 30).toString()) }
        PlannerDialog("Duration", { pickingDuration = false },
            primary = DialogAction("Set duration", enabled = minutes.toIntOrNull() in 1..1440) { durationText = minutes; pickingDuration = false },
            dismiss = DialogAction("Cancel") { pickingDuration = false }) {
                OutlinedTextField(minutes, { minutes = it.filter(Char::isDigit).take(4) }, label = { Text("Minutes (1–1440)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                FlowRow { listOf(30, 45, 60).forEach { n -> TextButton(onClick = { minutes = n.toString() }) { Text("$n min") } } }
                TextButton(onClick = { durationText = ""; pickingDuration = false }) { Text("No duration") }
        }
    }
    if (pickingDate) SingleDateDialog(suggestion.date, onDismiss = { pickingDate = false }, onConfirm = { dateOverride = it.toString(); pickingDate = false })
    if (pickingTime) TimePickerDialog(suggestion.time ?: LocalTime.of(9, 0), onDismiss = { pickingTime = false }, onConfirm = { timeOverride = it.toString(); pickingTime = false })
}

/** A tappable line in the Will add summary: icon, value, and a small pencil showing it can be changed. */
@Composable
private fun SummaryButton(icon: (@Composable () -> Unit)?, kind: String, label: String, enabled: Boolean, onClick: () -> Unit) {
    // Screen readers get the meaning the icon shows on screen: "Time, 18:00".
    MatrixQuietButton(enabled = enabled, onClick = onClick, contentPadding = PaddingValues(horizontal = 4.dp),
        modifier = Modifier.semantics { contentDescription = "$kind, $label" }) {
        if (icon != null) { icon(); Spacer(Modifier.width(6.dp)) }
        Text(label)
        Spacer(Modifier.width(4.dp))
        Icon(Icons.Filled.Edit, contentDescription = null, Modifier.size(14.dp))
    }
}
