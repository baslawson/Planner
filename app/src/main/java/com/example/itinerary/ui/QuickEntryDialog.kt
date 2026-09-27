package com.example.itinerary.ui

import com.example.itinerary.ui.MatrixTextButton as TextButton
import com.example.itinerary.ui.MatrixFilterChip as FilterChip
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QuickEntryEditor(
    today: LocalDate,
    onDismiss: () -> Unit,
    onAdd: suspend (QuickEntrySuggestion, Boolean, String) -> Unit,
    onReview: (QuickEntrySuggestion, Boolean) -> Unit,
    initial: QuickInput = QuickInput(baseDate = today),
    onInput: (QuickInput) -> Unit = {},
    batchRow: Boolean = false,
    onContinue: ((Boolean) -> Unit)? = null,
    onDiscard: (() -> Unit)? = null,
    inputBlocked: Boolean = false,
    modeControls: @Composable () -> Unit = {},
) {
    var field by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(initial.text, TextRange(initial.text.length))) }
    val text = field.text
    var aiJson by rememberSaveable { mutableStateOf(initial.ai?.json()?.toString()) }
    val ai = remember(aiJson) { aiJson?.let { QuickAiEntry.decodeDraft(org.json.JSONObject(it)) } }
    var aiBusy by remember { mutableStateOf(false) }
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
    var typeChosen by rememberSaveable { mutableStateOf(false) }
    var literalOffsets by rememberSaveable { mutableStateOf(initial.literals.flatMap { listOf(it.first, it.last + 1) }.toIntArray()) }
    val literalRanges = literalOffsets.asList().chunked(2).map { it[0] until it[1] }
    val literalKey = literalOffsets.contentHashCode()
    var dateOverride by rememberSaveable { mutableStateOf(initial.dateOverride) }
    var timeOverride by rememberSaveable { mutableStateOf(initial.timeOverride) }
    var countText by rememberSaveable { mutableStateOf(initial.countText) }
    var removeReminder by rememberSaveable { mutableStateOf(initial.removeReminder) }
    val saveToken = rememberSaveable(text, task, literalKey, dateOverride, timeOverride, countText, removeReminder, durationText, aiJson) { java.util.UUID.randomUUID().toString() }
    var pickingDate by rememberSaveable { mutableStateOf(false) }
    var pickingTime by rememberSaveable { mutableStateOf(false) }
    var showPhrases by rememberSaveable { mutableStateOf(false) }
    var selectedPhrase by remember { mutableStateOf<QuickEntryPhrase?>(null) }
    var busy by remember { mutableStateOf(false) }
    var saveError by remember(text, task) { mutableStateOf<String?>(null) }
    val parsed = remember(text, literalKey, today) { QuickEntry.parse(text, today, literalRanges) }
    val currentInput = QuickInput(text, task, literalRanges, dateOverride, timeOverride, countText, removeReminder, today, durationText, ai)
    SideEffect { onInput(currentInput) }
    val context = LocalContext.current
    val notifications = rememberNotificationState()
    val app = context.applicationContext as com.example.itinerary.ItineraryApp
    val aiEnabled by app.settings.aiFeaturesEnabled.collectAsState()
    var now by remember { mutableStateOf(ZonedDateTime.now()) }
    var exactAllowed by remember { mutableStateOf(app.reminderScheduler.canScheduleExact()) }
    LaunchedEffect(Unit) { while (true) { now = ZonedDateTime.now(); delay(30_000) } }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { now = ZonedDateTime.now(); exactAllowed = app.reminderScheduler.canScheduleExact() }
    val suggestion = remember(currentInput, now) { currentInput.suggestion(now) }
    val reminder = suggestion.reminderMinutes?.let { reminderTrigger(suggestion.date, if (task) null else suggestion.time, it.toLong(), now.zone) }
    val series = if (!task && suggestion.repeat != RepeatRule.NONE && suggestion.repeatCount in 2..365)
        runCatching { suggestion.repeat.dates(suggestion.date, suggestion.repeatCount).takeIf { dates -> dates.all { it.year in 1..9999 } } }.getOrNull() else null
    val problem = if (text.length > 500) "Use at most 500 characters per entry, or choose Paste multiple entries." else suggestion.quickProblem(task, now)
    val valid = text.isNotBlank() && problem == null && !busy && !aiBusy && !inputBlocked
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    var fieldReady by remember { mutableStateOf(false) }
    LaunchedEffect(fieldReady) { if (fieldReady) { focus.requestFocus(); keyboard?.show() } }
    fun editField(value: TextFieldValue) {
        if (value.text != text) {
            val updated = currentInput.edited(value.text)
            if (!typeChosen) QuickEntry.parse(value.text, today, updated.literals).takeIf { it.taskHint }?.let { task = !it.timed() }
            aiJson = null
            literalOffsets = updated.literals.flatMap { listOf(it.first, it.last + 1) }.toIntArray()
            dateOverride = updated.dateOverride; timeOverride = updated.timeOverride
            countText = updated.countText; removeReminder = updated.removeReminder; durationText = updated.durationText
            settled = false; attempted = false
        }
        field = value
    }
    fun edit(value: String) = editField(TextFieldValue(value, TextRange(value.length)))
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
        val restored = QuickEntry.parse(text, today, remaining).phrases.firstOrNull { it.start == removed.first }
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
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { saveError = "Couldn't save. Your entry is still here; try again." }
            finally { busy = false }
        }
    }
    val colors = mapOf(QuickPhraseKind.DATE to MaterialTheme.colorScheme.primary,
        QuickPhraseKind.TIME to MaterialTheme.colorScheme.tertiary, QuickPhraseKind.DURATION to MaterialTheme.colorScheme.secondary,
        QuickPhraseKind.LOCATION to MaterialTheme.colorScheme.secondary, QuickPhraseKind.REMINDER to MaterialTheme.colorScheme.tertiary,
        QuickPhraseKind.REPEAT to MaterialTheme.colorScheme.primary, QuickPhraseKind.UNSUPPORTED to if (showFeedback) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
    val highlight = VisualTransformation { original ->
        val result = AnnotatedString.Builder(original)
        (if (ai == null) parsed.phrases else emptyList()).forEach { phrase -> if (phrase.start >= 0 && phrase.end <= original.length)
            result.addStyle(SpanStyle(color = colors.getValue(phrase.kind), fontWeight = FontWeight.Bold), phrase.start, phrase.end) }
        TransformedText(result.toAnnotatedString(), OffsetMapping.Identity)
    }
    AlertDialog(containerColor = MaterialTheme.colorScheme.background, tonalElevation = 0.dp, 
        onDismissRequest = { if (!busy) onDismiss() },
        title = { if (batchRow) Text("Review entry") else HeadingText("Quick entry") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                modeControls()
                if (today != LocalDate.now()) Text("Dates based on ${today.fullLabel()}", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !task, enabled = !busy, onClick = { task = false; typeChosen = true }, label = { Text("Event") })
                    FilterChip(selected = task, enabled = !busy, onClick = { task = true; typeChosen = true }, label = { Text("Task") })
                }
                OutlinedTextField(field, ::editField, enabled = !busy,
                    label = { Text(if (task) "Task and optional due date" else "Event and when") },
                    placeholder = { Text(if (task) "Buy groceries tmr" else "Gym every Monday 6pm") },
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).onFocusChanged { fieldFocused = it.isFocused }.onGloballyPositioned { fieldReady = true }, visualTransformation = highlight,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { add() }), maxLines = 3)
                if (!batchRow && ai == null) QuickAiAction(currentInput, multiple = false, enabled = !busy && !inputBlocked,
                    onWorking = { aiBusy = it }, onResult = { entries -> keyboard?.hide(); aiJson = entries.single().json().toString(); literalOffsets = intArrayOf() })
                if (ai != null) {
                    Text(if (aiEnabled) "AI preview · check the details. Editing the original text returns to offline parsing." else "Check the details. Editing the original text creates a new preview.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(ai.title, { if (it.length <= 500) aiJson = ai.copy(title = it.filterNot { c -> c.isISOControl() }).json().toString() }, enabled = !busy,
                        label = { Text("Entry title") }, singleLine = true)
                    OutlinedTextField(ai.location, { if (it.length <= 500) aiJson = ai.copy(location = it.filterNot { c -> c.isISOControl() }).json().toString() }, enabled = !busy,
                        label = { Text("Location") }, singleLine = true)
                    if (aiEnabled) TextButton(enabled = !busy, onClick = { aiJson = null }) { Text("Use offline interpretation") }
                }
                if (completions.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    completions.forEach { completion -> TextButton(enabled = !busy, onClick = {
                        val (next, cursor) = completion.apply(text)
                        editField(TextFieldValue(next, TextRange(cursor)))
                        focus.requestFocus(); keyboard?.show()
                    }) { Text(completion.label) } }
                }
                if (text.isBlank()) {
                    Text("Try an example", style = MaterialTheme.typography.labelMedium)
                    val examples = if (task) listOf("Buy groceries", "Call plumber tmr") else listOf("Dentist Fri 3pm for 45 minutes", "Gym every Monday 6pm")
                    examples.forEach { example -> TextButton(onClick = { edit(example) }) { Text(example) } }
                } else {
                    if (ai == null) Text(suggestion.title, style = MaterialTheme.typography.titleMedium)
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
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(enabled = !busy, onClick = { keyboard?.hide(); pickingDate = true }) {
                            Text(if (suggestion.dateChoices.isNotEmpty()) "Choose date" else if (task && !suggestion.dateSpecified) "No due date" else if (showDetails) suggestion.date.dayLabel(LocalDateFormat.current) else when (suggestion.date) {
                                LocalDate.now() -> "Today"
                                LocalDate.now().plusDays(1) -> "Tomorrow"
                                else -> suggestion.date.fullLabel()
                            })
                        }
                        if (showDetails && task && suggestion.dateSpecified) TextButton(enabled = !busy, onClick = { dateOverride = "" }) { Text("Clear date") }
                        if (!task) {
                            TextButton(enabled = !busy, onClick = { keyboard?.hide(); pickingTime = true }) {
                                Text(suggestion.time?.label(LocalTimeFormat.current, context) ?: if (suggestion.ambiguousTime || suggestion.durationMinutes != null) "Choose time" else "All day · set time")
                            }
                            if (showDetails && suggestion.time != null && suggestion.durationMinutes == null) TextButton(enabled = !busy, onClick = { timeOverride = "" }) { Text("All day") }
                        }
                        if (!task && (suggestion.durationMinutes != null || showDetails && suggestion.time != null)) TextButton(enabled = !busy, onClick = { pickingDuration = true; keyboard?.hide() }) {
                            Text(suggestion.durationMinutes?.let { "$it min" } ?: "Duration")
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (ai == null && suggestion.location.isNotBlank()) TextButton(enabled = !busy, onClick = {
                            parsed.phrases.firstOrNull { it.kind == QuickPhraseKind.LOCATION }?.let { val prefix = Regex("^at\\s+", RegexOption.IGNORE_CASE).find(text.substring(it.start, it.end))?.value?.length ?: 0; field = field.copy(selection = TextRange(it.start + prefix, it.end)); focus.requestFocus(); keyboard?.show() }
                        }) { Text(suggestion.location) }
                        if (suggestion.repeat != RepeatRule.NONE) TextButton(enabled = !busy, onClick = { showDetails = !showDetails }) {
                            Text(if (task) suggestion.repeat.label else "${suggestion.repeat.label} · ${suggestion.repeatCount} events")
                        }
                        if (reminder != null) TextButton(enabled = !busy, onClick = { showDetails = !showDetails }) { Text("Reminder: ${suggestion.quickReminders().single().label}") }
                    }
                    TextButton(enabled = !busy, onClick = { showDetails = !showDetails }) { Text(if (showDetails) "Hide details" else "Details") }
                    if (showDetails) {
                        if (!task && suggestion.durationMinutes != null) {
                            val end = suggestion.time?.plusMinutes(suggestion.durationMinutes.toLong())
                            val nextDay = suggestion.time?.let { it.toSecondOfDay() / 60 + suggestion.durationMinutes >= 1440 } == true
                            Text("${suggestion.durationMinutes} minutes" + (end?.let { " · ends ${it.label(LocalTimeFormat.current, context)}${if (nextDay) " next day" else ""}" } ?: ""), style = MaterialTheme.typography.bodySmall)
                        }
                        if (ai == null && suggestion.location.isNotBlank()) Text("Location: ${suggestion.location}", style = MaterialTheme.typography.bodySmall)
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
                            if (!exactAllowed) Text("Android may deliver this reminder late. Enable Alarms & reminders in app settings for precise timing.", style = MaterialTheme.typography.bodySmall)
                        }
                        if (ai == null && parsed.phrases.isNotEmpty()) {
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
                    if (showFeedback && suggestion.dateChoices.isEmpty() && suggestion.isPast(now, task)) {
                        Text("This date or time is in the past.", color = MaterialTheme.colorScheme.error)
                        TextButton(enabled = !busy, onClick = { dateOverride = (if (suggestion.repeat != RepeatRule.NONE) PlannerTask(dueDate = suggestion.date, repeat = suggestion.repeat.name).nextOccurrence(now.toLocalDate())!!.dueDate!! else if (task) now.toLocalDate() else now.toLocalDate().plusDays(1)).toString() }) { Text(if (suggestion.repeat != RepeatRule.NONE) "Move to next occurrence" else if (task) "Move to today" else "Move to tomorrow") }
                    }
                    if (showFeedback && problem != null && problem != "Which date did you mean?") Text(problem, color = MaterialTheme.colorScheme.error)
                    if (!showFeedback && problem != null) Text("Keep typing, or choose a suggestion.", style = MaterialTheme.typography.bodySmall)
                    if (!batchRow && showDetails) TextButton(enabled = valid, onClick = { keyboard?.hide(); onReview(suggestion, task) }) { Text("More details") }
                    if (showDetails && onDiscard != null) TextButton(enabled = !busy, onClick = onDiscard) { Text("Discard draft") }
                }
                if (saveError != null) Text(saveError!!, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (!batchRow && onContinue != null) TextButton(enabled = text.isNotBlank() && !busy && !aiBusy && !inputBlocked && (valid || !showFeedback), onClick = { add(true) }) { Text("Add another") }
            TextButton(enabled = text.isNotBlank() && !busy && !aiBusy && !inputBlocked && (valid || !showFeedback), onClick = { add() }) { Text(if (busy) "Saving…" else if (batchRow) "Done" else if (task) "Add task" else if (suggestion.repeat != RepeatRule.NONE) "Add ${suggestion.repeatCount} events" else "Add event") }
        } },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(if (batchRow) "Back to list" else "Close") } },
    )
    selectedPhrase?.let { phrase ->
        AlertDialog(containerColor = MaterialTheme.colorScheme.background, tonalElevation = 0.dp, onDismissRequest = { selectedPhrase = null }, title = { Text(text.substring(phrase.start, phrase.end).trim()) },
            text = { Text("Keep these words in the title instead of using them as ${phrase.kind.label.lowercase()} details.") },
            confirmButton = { TextButton(onClick = { clearCorrection(phrase.kind); literalOffsets += intArrayOf(phrase.start, phrase.end); selectedPhrase = null }) { Text("Keep in title") } },
            dismissButton = { TextButton(onClick = { selectedPhrase = null }) { Text("Cancel") } })
    }
    if (pickingDuration) {
        var minutes by rememberSaveable { mutableStateOf((suggestion.durationMinutes ?: 30).toString()) }
        AlertDialog(containerColor = MaterialTheme.colorScheme.background, tonalElevation = 0.dp,
            onDismissRequest = { pickingDuration = false }, title = { Text("Duration") },
            text = { Column {
                OutlinedTextField(minutes, { minutes = it.filter(Char::isDigit).take(4) }, label = { Text("Minutes (1–1440)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                FlowRow { listOf(30, 45, 60).forEach { n -> TextButton(onClick = { minutes = n.toString() }) { Text("$n min") } } }
                TextButton(onClick = { durationText = ""; pickingDuration = false }) { Text("No duration") }
            } },
            confirmButton = { TextButton(enabled = minutes.toIntOrNull() in 1..1440, onClick = { durationText = minutes; pickingDuration = false }) { Text("Set duration") } },
            dismissButton = { TextButton(onClick = { pickingDuration = false }) { Text("Cancel") } })
    }
    if (pickingDate) SingleDateDialog(suggestion.date, onDismiss = { pickingDate = false }, onConfirm = { dateOverride = it.toString(); pickingDate = false })
    if (pickingTime) TimePickerDialog(suggestion.time ?: LocalTime.of(9, 0), onDismiss = { pickingTime = false }, onConfirm = { timeOverride = it.toString(); pickingTime = false })
}
