package com.example.itinerary.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.ui.res.painterResource
import com.example.itinerary.R
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import kotlin.math.roundToInt
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.itinerary.ItineraryApp
import com.example.itinerary.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.time.*
import com.example.itinerary.ui.MatrixTextButton as TextButton
import com.example.itinerary.ui.MatrixButton as Button
import com.example.itinerary.ui.MatrixOutlinedButton as OutlinedButton

val LocalEditingTaskId = compositionLocalOf<String?> { null }

class PlanningToolsState {
    var freeTime by mutableStateOf(false)
    var importing by mutableStateOf(false)
    var editingFreeTimeEvent by mutableStateOf(false)
        private set

    fun editFreeTimeEvent() { editingFreeTimeEvent = true; freeTime = false }
    fun eventEditorDismissed() {
        if (editingFreeTimeEvent) { editingFreeTimeEvent = false; freeTime = true }
    }
    fun eventSaved() { editingFreeTimeEvent = false }
}

@Composable
fun PlanningToolMenuItems(state: PlanningToolsState, closeMenu: () -> Unit) {
    DropdownMenuItem(text = { Text("Find free time") },
        leadingIcon = { Icon(painterResource(R.drawable.action_clock), contentDescription = null) }, onClick = { closeMenu(); state.freeTime = true })
    DropdownMenuItem(text = { Text("Import calendar invitation") },
        leadingIcon = { Icon(Icons.Filled.DateRange, contentDescription = null) }, onClick = { closeMenu(); state.importing = true })
}

@Composable
fun PlanningToolDialogs(state: PlanningToolsState, onEvent: (ItineraryItem) -> Unit) {
    val app = LocalContext.current.applicationContext as ItineraryApp
    val events by app.repository.allItems.collectAsStateWithLifecycle(initialValue = null)
    val searchState = rememberSaveableStateHolder()
    // Temporarily remove the full-screen search while editing, keeping its fields/results/scroll.
    // A normal close or successful save finishes the search session.
    LaunchedEffect(state.freeTime, state.editingFreeTimeEvent) {
        if (!state.freeTime && !state.editingFreeTimeEvent) searchState.removeState("free-time")
    }
    if (state.freeTime && events != null) searchState.SaveableStateProvider("free-time") {
        FreeTimeDialog(events.orEmpty(), onDismiss = { state.freeTime = false }, onChoose = {
            state.editFreeTimeEvent(); onEvent(it)
        })
    }
    if (state.importing) CalendarImportDialog(onDismiss = { state.importing = false })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FreeTimeDialog(events: List<ItineraryItem>, onDismiss: () -> Unit, onChoose: (ItineraryItem) -> Unit) {
    val context = LocalContext.current
    val timeFormat = LocalTimeFormat.current
    var from by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var through by rememberSaveable { mutableStateOf(LocalDate.now().plusDays(6).toString()) }
    var start by rememberSaveable { mutableStateOf("09:00") }
    var end by rememberSaveable { mutableStateOf("17:00") }
    var minutes by rememberSaveable { mutableStateOf("60") }
    var unknown by rememberSaveable { mutableStateOf("60") }
    var weekdays by rememberSaveable { mutableStateOf(true) }
    var allDay by rememberSaveable { mutableStateOf(true) }
    var picker by remember { mutableStateOf<String?>(null) }
    var chosen by remember { mutableStateOf<FreeWindow?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var searched by rememberSaveable { mutableStateOf(false) }
    val scroll = rememberScrollState()
    // Capture before opening the picker: its focus changes can scroll the search underneath it.
    var returnScroll by rememberSaveable { mutableStateOf<Int?>(null) }
    var restoringScroll by remember { mutableStateOf(returnScroll) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var searchRun by remember { mutableIntStateOf(0) }
    var scrolledRun by remember { mutableIntStateOf(0) }
    var resultsTop by remember { mutableStateOf<Int?>(null) }
    val hintClearance = with(LocalDensity.current) { 64.dp.roundToPx() }
    LaunchedEffect(resultsTop, restoringScroll) {
        val position = restoringScroll
        if (resultsTop != null && position != null) {
            withFrameNanos { }
            scroll.scrollTo(position)
            restoringScroll = null; returnScroll = null
        }
    }
    LaunchedEffect(searchRun, resultsTop, searched) {
        val top = resultsTop
        if (searched && top != null && searchRun > scrolledRun) {
            // Results must be measured before scrolling; leave space for the More above hint.
            withFrameNanos { }
            scroll.animateScrollTo((top - hintClearance).coerceAtLeast(0))
            scrolledRun = searchRun
        }
    }
    val now = LocalDateTime.now()
    val valid = minutes.toIntOrNull() in 1..1440 && unknown.toIntOrNull() in 1..1440 &&
        LocalTime.parse(end) > LocalTime.parse(start) && LocalDate.parse(through) >= LocalDate.parse(from) &&
        java.time.temporal.ChronoUnit.DAYS.between(LocalDate.parse(from), LocalDate.parse(through)) <= 366
    val windows = if (valid && searched) FreeTime.find(events, LocalDate.parse(from), LocalDate.parse(through),
        LocalTime.parse(start), LocalTime.parse(end), minutes.toInt(), weekdays, allDay, unknown.toInt(), now) else emptyList()
    PlanningOverlay(onDismiss) {
        val view = LocalView.current
        val window = (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window
        val lightBars = MaterialTheme.colorScheme.background.luminance() > 0.5f
        SideEffect {
            window?.let {
                androidx.core.view.WindowCompat.getInsetsController(it, view).apply {
                    isAppearanceLightStatusBars = lightBars
                    isAppearanceLightNavigationBars = lightBars
                }
            }
        }
        Scaffold(modifier = Modifier.fillMaxSize(), containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(title = { HeadingText("Find free time") }, navigationIcon = {
                    MatrixIconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                })
            }) { inner ->
            ScrollHints(scroll, Modifier.fillMaxSize().padding(inner).consumeWindowInsets(inner).imePadding()) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 8.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Uses recorded events only. Tasks and bill due dates do not block time.")
                    TextButton(onClick = { picker = "from" }) { Text("From: ${LocalDate.parse(from).dayLabel(LocalDateFormat.current)}") }
                    TextButton(onClick = { picker = "through" }) { Text("Through: ${LocalDate.parse(through).dayLabel(LocalDateFormat.current)}") }
                    TextButton(onClick = { picker = "start" }) { Text("Start: ${LocalTime.parse(start).label(timeFormat, context)}") }
                    TextButton(onClick = { picker = "end" }) { Text("End: ${LocalTime.parse(end).label(timeFormat, context)}") }
                    OutlinedTextField(minutes, { minutes = it; searched = false }, label = { Text("Minutes needed (1–1440)") }, singleLine = true)
                    Row { Checkbox(weekdays, { weekdays = it; searched = false }); Text("Weekdays only", Modifier.padding(top = 12.dp)) }
                    Row { Checkbox(allDay, { allDay = it; searched = false }); Text("All-day events block the day", Modifier.weight(1f).padding(top = 12.dp)) }
                    Text("Saved travel and preparation buffers also block time.", style = MaterialTheme.typography.bodySmall)
                    // A heading rather than a floating label: once a floating label wraps at large text it rises into the note above.
                    Text("Minutes for untimed events", style = MaterialTheme.typography.labelLarge)
                    OutlinedTextField(unknown, { unknown = it; searched = false },
                        modifier = Modifier.semantics { contentDescription = "Minutes for untimed events" },
                        supportingText = { Text("How long to count an event that has no end time.") }, singleLine = true)
                    if (!valid) Text("Choose valid hours, durations and a range of up to one year.", color = MaterialTheme.colorScheme.error)
                    Button(enabled = valid, onClick = {
                        focus.clearFocus(); keyboard?.hide()
                        searched = true; error = null; searchRun++
                    }) { Text("Find gaps") }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (searched && valid) {
                        Column(Modifier.fillMaxWidth().onGloballyPositioned { resultsTop = it.positionInParent().y.roundToInt() },
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(if (windows.isEmpty()) "No available gaps" else "${windows.size} available ${if (windows.size == 1) "gap" else "gaps"}",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.semantics { heading(); liveRegion = LiveRegionMode.Polite })
                            Text(if (windows.isEmpty()) "Try fewer minutes, wider daily hours, or different dates. Check whether all-day events block those days."
                                else "Choose a gap, then pick a start time. Nothing is added until you save.", style = MaterialTheme.typography.bodySmall)
                            windows.take(60).forEach { gap ->
                                OutlinedButton(onClick = { returnScroll = scroll.value; chosen = gap }) {
                                    Text("${gap.start.toLocalDate().dayLabel(LocalDateFormat.current)} · ${gap.start.toLocalTime().label(timeFormat, context)}–${gap.end.toLocalTime().label(timeFormat, context)}")
                                }
                            }
                            if (windows.size > 60) Text("Showing the first 60 gaps. Narrow the dates for more detail.")
                        }
                    }
                }
            }
        }
    }
    if (picker == "from" || picker == "through") SingleDateDialog(LocalDate.parse(if (picker == "from") from else through),
        onDismiss = { picker = null }, onConfirm = { if (picker == "from") from = it.toString() else through = it.toString(); picker = null; searched = false })
    if (picker == "start" || picker == "end") TimePickerDialog(LocalTime.parse(if (picker == "start") start else end),
        onDismiss = { picker = null }, onConfirm = { if (picker == "start") start = it.toString() else end = it.toString(); picker = null; searched = false })
    chosen?.let { gap -> TimePickerDialog(gap.start.toLocalTime(), onDismiss = { chosen = null; returnScroll = null }, onConfirm = { time ->
        val begin = gap.start.toLocalDate().atTime(time)
        if (begin < gap.start || begin.plusMinutes(minutes.toLong()) > gap.end || begin < LocalDateTime.now()) {
            error = "Choose a start that fits the selected gap and is not in the past."; chosen = null; returnScroll = null
        } else {
            onChoose(ItineraryItem(tripId = 0, date = begin.toLocalDate(), startTime = time, durationMinutes = minutes.toInt(), title = ""))
        }
    }) }
}

@Composable
fun CalendarImportDialog(onDismiss: () -> Unit, initialUri: Uri? = null) {
    val context = LocalContext.current
    val repo = (context.applicationContext as ItineraryApp).repository
    val existing by repo.allItems.collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf<ItineraryItem?>(null) }
    var invitations by remember { mutableStateOf<List<CalendarImport.Invitation>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    fun read(uri: Uri) {
        busy = true; error = null
        scope.launch {
            try {
                invitations = withContext(Dispatchers.IO) {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                        val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                        while (true) {
                            val size = input.read(buffer); if (size < 0) break
                            require(out.size() + size <= CalendarImport.MAX_BYTES) { "Calendar file is too large (maximum 1 MB)." }
                            out.write(buffer, 0, size)
                        }; out.toByteArray()
                    } ?: error("Could not open this file.")
                    CalendarImport.parse(bytes.toString(Charsets.UTF_8))
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "Could not read this calendar invitation."; invitations = emptyList() }
            finally { busy = false }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(::read) }
    LaunchedEffect(initialUri) { if (initialUri != null) read(initialUri) }
    if (selected == null) AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text("Import calendar invitation") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Choose an .ics file, then review each appointment in the event editor. Nothing is added until you save.")
            Button(enabled = !busy, onClick = { picker.launch(arrayOf("*/*")) }) { Text("Choose calendar file") }
            if (busy) CircularProgressIndicator()
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            invitations.forEach { invitation ->
                val item = invitation.item
                HorizontalDivider()
                Text(item.title, style = MaterialTheme.typography.titleMedium)
                Text("${item.date.dayLabel(LocalDateFormat.current)} · ${item.startTime?.label(LocalTimeFormat.current, context) ?: "All day"}")
                invitation.warnings.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (existing.any { it.title == item.title && it.date == item.date && it.startTime == item.startTime })
                    Text("A matching appointment already exists. Review before adding another.", color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = {
                    if (runCatching { EditorDraftStore(context).read() == null }.getOrDefault(false)) selected = item
                    else error = "Finish or discard your current event draft before importing another appointment."
                }) { Text("Review ${item.title}") }
            }
        }
    }, confirmButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Close") } })
    selected?.let { NewPlanningEventEditor(it) { selected = null } }
}

@Composable
fun NewPlanningEventEditor(item: ItineraryItem, onDismiss: () -> Unit) {
    val app = LocalContext.current.applicationContext as ItineraryApp
    val scope = rememberCoroutineScope()
    val categories = remember(app) { CategoryState(app.repository, app.settings, scope) }
    val counts by categories.counts.collectAsStateWithLifecycle()
    val hidden by categories.hidden.collectAsStateWithLifecycle()
    PlanningOverlay(onDismiss) {
    ItemEditorSheet(item, emptyList(), emptyList(), counts, hidden, categories::remove, categories::show, onDismiss,
        onSave = { event, added, removed, reminders, removedReminders, options -> app.repository.saveItem(event, added, removed, reminders, removedReminders, options) },
        onDelete = { _, _ -> })
    }
}

@Composable
fun ScheduleTaskDialog(task: PlannerTask, onDismiss: () -> Unit) {
    var date by rememberSaveable { mutableStateOf(maxOf(task.dueDate ?: LocalDate.now(), LocalDate.now()).toString()) }
    var time by rememberSaveable { mutableStateOf("09:00") }
    var minutes by rememberSaveable { mutableStateOf("60") }
    var picker by remember { mutableStateOf<String?>(null) }
    var event by remember { mutableStateOf<ItineraryItem?>(null) }
    if (event == null) AlertDialog(onDismissRequest = onDismiss, title = { Text("Schedule time for ${task.title}") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Creates a calendar block linked to this saved task. The task’s due date and completion stay separate.")
            TextButton(onClick = { picker = "date" }) { Text(LocalDate.parse(date).dayLabel(LocalDateFormat.current)) }
            TextButton(onClick = { picker = "time" }) { Text(LocalTime.parse(time).label(LocalTimeFormat.current, LocalContext.current)) }
            OutlinedTextField(minutes, { minutes = it }, label = { Text("Duration in minutes (1–1440)") }, singleLine = true)
        }
    }, confirmButton = { Button(enabled = minutes.toIntOrNull() in 1..1440, onClick = {
        event = ItineraryItem(tripId = 0, date = LocalDate.parse(date), startTime = LocalTime.parse(time), title = task.title,
            durationMinutes = minutes.toInt(), linkedTaskId = task.id)
    }) { Text("Review time block") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
    if (picker == "date") SingleDateDialog(LocalDate.parse(date), { picker = null }, { date = it.toString(); picker = null })
    if (picker == "time") TimePickerDialog(LocalTime.parse(time), { picker = null }, { time = it.toString(); picker = null })
    event?.let { NewPlanningEventEditor(it, onDismiss = { event = null; onDismiss() }) }
}

@Composable
fun LinkedTaskSection(id: String) {
    val repo = (LocalContext.current.applicationContext as ItineraryApp).repository
    val tasks by repo.tasks.collectAsStateWithLifecycle(emptyList())
    val task = tasks.find { it.id == id }
    var open by remember { mutableStateOf(false) }
    Text(if (task == null) "Linked task unavailable (it may be in Recently deleted)." else
        "Linked task: ${task.title} · ${if (task.done) "Completed" else "Not completed"}")
    if (LocalEditingTaskId.current == id) Text("Close this block to return to the task.", style = MaterialTheme.typography.bodySmall)
    else if (task != null) TextButton(onClick = { open = true }) { Text("Open linked task") }
    if (open && task != null) PlanningOverlay({ open = false }) { TaskEditor(task, false) { open = false } }
}

@Composable
fun TaskTimeBlocks(taskId: String) {
    val repo = (LocalContext.current.applicationContext as ItineraryApp).repository
    val events by repo.allItems.collectAsStateWithLifecycle(emptyList())
    val blocks = events.filter { it.linkedTaskId == taskId }.sortedWith(compareBy({ it.date }, { it.startTime }))
    var editing by remember { mutableStateOf<ItineraryItem?>(null) }
    blocks.forEach { block ->
        val context = LocalContext.current
        TextButton(onClick = {
            if (runCatching { EditorDraftStore(context).read() }.getOrNull() != null)
                android.widget.Toast.makeText(context, "Close your current event editor before opening another time block.", android.widget.Toast.LENGTH_LONG).show()
            else editing = block
        }) {
            Text("Time block: ${block.date.dayLabel(LocalDateFormat.current)} · ${block.startTime?.label(LocalTimeFormat.current, LocalContext.current) ?: "All day"}")
        }
    }
    editing?.let { item ->
        val attachments by repo.allAttachments.collectAsStateWithLifecycle(initialValue = null)
        val reminders by repo.allReminders.collectAsStateWithLifecycle(initialValue = null)
        val app = LocalContext.current.applicationContext as ItineraryApp
        val scope = rememberCoroutineScope()
        val categories = remember(app) { CategoryState(repo, app.settings, scope) }
        val counts by categories.counts.collectAsStateWithLifecycle()
        val hidden by categories.hidden.collectAsStateWithLifecycle()
        if (attachments != null && reminders != null) PlanningOverlay({ editing = null }) { ItemEditorSheet(item, attachments.orEmpty().filter { it.itemId == item.id }, reminders.orEmpty().filter { it.itemId == item.id },
            counts, hidden, categories::remove, categories::show, { editing = null },
            onSave = { event, added, removed, addedReminders, removedReminders, options ->
                repo.saveItem(event, added, removed, addedReminders, removedReminders, options)
            }, onDelete = { event, series -> repo.deleteWithUndo(event, series) }) }
    }
}

@Composable
fun PlanningOverlay(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val view = androidx.compose.ui.platform.LocalView.current
        val window = (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window
        SideEffect {
            window?.let {
                androidx.core.view.WindowCompat.setDecorFitsSystemWindows(it, false)
                it.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
                it.setSoftInputMode(if (android.os.Build.VERSION.SDK_INT >= 31)
                    android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING else android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                it.attributes = it.attributes.apply {
                    // Compose 1.7 measures against full display bounds; keep the native frame consistent.
                    if (android.os.Build.VERSION.SDK_INT >= 30) setFitInsetsTypes(0)
                    if (android.os.Build.VERSION.SDK_INT >= 28)
                        layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
        }
        Box(Modifier.fillMaxSize()) { content() }
    }
}
