package com.example.itinerary.ui
import com.example.itinerary.ui.MatrixIconButton as IconButton
import com.example.itinerary.ui.MatrixFilterChip as FilterChip
import com.example.itinerary.ui.MatrixOutlinedButton as OutlinedButton

import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.Surface
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import com.example.itinerary.data.OutsideCalendars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.itinerary.data.Agenda
import com.example.itinerary.data.AgendaEntry
import com.example.itinerary.data.AgendaRange
import com.example.itinerary.data.plannerCopy
import com.example.itinerary.data.AgendaType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import java.time.LocalDate

// Events grouped by day. Opening an event shows the shared calendar on its date.
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun AgendaScreen(
    vm: TripsViewModel,
    onOpenEvent: (LocalDate) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenCalendar: () -> Unit,
) {
    // Wait for the database before showing an empty state.
    val loadedEvents by vm.agendaEvents.collectAsStateWithLifecycle()
    val events = loadedEvents.orEmpty()
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    var showCompleted by rememberSaveable { mutableStateOf(false) }
    var anytimeExpanded by rememberSaveable { mutableStateOf(true) }
    var editingTaskId by rememberSaveable { mutableStateOf<String?>(null) }
    var editingBillId by rememberSaveable { mutableStateOf<Long?>(null) }
    val backupStatus by vm.backupStatus.collectAsStateWithLifecycle()
    val backupStore = (LocalContext.current.applicationContext as com.example.itinerary.ItineraryApp).backup.status
    val backupReminder by backupStore.reminder.collectAsStateWithLifecycle()
    val categoryCounts by vm.categoryCounts.collectAsStateWithLifecycle()
    val hiddenCategories by vm.hiddenCategories.collectAsStateWithLifecycle()
    val addButtonSeeThrough by vm.addButtonSeeThrough.collectAsStateWithLifecycle()

    // Kept across rotation and while another screen is on top, so coming back finds the agenda as it was left.
    var showSettings by rememberSaveable { mutableStateOf(value = false) }
    var showCalendars by rememberSaveable { mutableStateOf(value = false) }
    val overlayMenu = remember { OverlayMenuState() }
    var showThemes by remember { mutableStateOf(false) }
    val planningTools = remember { PlanningToolsState() }
    val showBillsSummary by vm.showBillsSummary.collectAsStateWithLifecycle()
    val range by vm.agendaRange.collectAsStateWithLifecycle()
    val settings = (LocalContext.current.applicationContext as com.example.itinerary.ItineraryApp).settings
    val types by settings.agendaTypes.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    // A new event being added with the + button; existing events are edited from the calendar.
    val newEvent = remember { NewEventState() }

    // Re-read when the app comes back to the front, so an agenda left open overnight moves on to the new day.
    val today = rememberCurrentDate()

    val days = remember(events, range, today, types) {
        if (AgendaType.EVENTS !in types) emptyList() else Agenda.days(events, range, "", today)
    }

    val shownTasks = remember(tasks, range, today, showCompleted, types) {
        if (AgendaType.TASKS !in types) emptyList() else com.example.itinerary.data.Tasks.visible(tasks, range, "", today, showCompleted)
    }
    val shownBills = remember(events, range, today, showCompleted, types) {
        if (AgendaType.BILLS !in types) emptyList() else com.example.itinerary.data.BillTasks.visible(events, range, "", today, showCompleted)
    }
    val billsByDate = remember(shownBills) { shownBills.groupBy { it.date } }
    val datedTasks = remember(shownTasks) { shownTasks.filter { it.dueDate != null }.groupBy { it.dueDate!! } }
    val anytimeTasks = remember(shownTasks) { shownTasks.filter { it.dueDate == null } }
    val eventsByDate = remember(days) { days.associateBy { it.date } }
    val dates = remember(eventsByDate, datedTasks, billsByDate) { (eventsByDate.keys + datedTasks.keys + billsByDate.keys).sorted() }

    val selectable = remember(days, shownBills) {
        // Outside events can't be deleted from Planner, so they can't be selected either.
        days.flatMap { it.entries }.distinctBy { it.event.id }.filterNot { OutsideCalendars.isOutside(it.event.id) }
            .map { SelectableEvent(it.event.id, it.event.title, it.event.date) } +
            shownBills.map { SelectableEvent(it.id, it.title, it.date, bill = true) }
    }
    val selectableTasks = remember(shownTasks) { shownTasks.map { SelectableTask(it.id, it.title, it.dueDate) } }
    val selection = rememberEventSelection(selectable, visibleTasks = selectableTasks)

    // The rows above the days, in the list's order (the list below uses the same flags).
    val showBackupFailed = backupStatus.failed
    val showBackupReminder = !backupStatus.failed && backupStatus.outcome != "RUNNING" && backupReminder.due(backupStatus.lastSuccess, today)
    val showBillSummary = AgendaType.BILLS in types && showBillsSummary && events.any { it.category == "Bills" }
    val showTaskOptions = (AgendaType.TASKS in types && tasks.isNotEmpty()) || (AgendaType.BILLS in types && events.any { it.category == "Bills" })
    val showEmpty = dates.isEmpty() && anytimeTasks.isEmpty()
    // Where the list shows what Quick entry added (its day's heading, or Anytime tasks), or null while it doesn't (not
    // saved yet, or outside this range or these types).
    fun rowOf(added: QuickAdded): Int? {
        var index = listOf(showBackupFailed, showBackupReminder, showBillSummary, showTaskOptions).count { it }
        if (added.date == null) return index.takeIf { anytimeTasks.any { it.title == added.title } }
        if (anytimeTasks.isNotEmpty()) index += 1 + if (anytimeExpanded) anytimeTasks.size else 0
        if (showEmpty) index++
        val shown = if (added.task) datedTasks[added.date].orEmpty().any { it.title == added.title }
            else eventsByDate[added.date]?.entries.orEmpty().any { it.event.title == added.title }
        if (!shown) return null
        for (date in dates) {
            if (date == added.date) return index
            val taskCount = datedTasks[date].orEmpty().size + billsByDate[date].orEmpty().size
            index += 1 + (if (taskCount > 0) 1 else 0) + taskCount + eventsByDate[date]?.entries.orEmpty().size
        }
        return null
    }
    // Quick entry's "Add": the list scrolls to it once it's there; if this view doesn't show it, the bar says it.
    var following by remember { mutableStateOf<QuickAdded?>(null) }
    val addedBar = rememberAddedBar()
    LaunchedEffect(following, dates, anytimeTasks, anytimeExpanded) {
        val added = following ?: return@LaunchedEffect
        rowOf(added)?.let { listState.animateScrollToItem(it); following = null }
    }
    LaunchedEffect(following) {
        val added = following ?: return@LaunchedEffect
        kotlinx.coroutines.delay(1500)
        if (following == added) { following = null; addedBar(added) }
    }

    if (showThemes) ThemesDialog(onDismiss = { showThemes = false })
    PlanningToolDialogs(planningTools, onEvent = { newEvent.start(it) })

    OverlayMenuScreen(overlayMenu) {
        Scaffold(
            bottomBar = { EventSelectionBar(selection, selectable, vm::deleteEvents, selectableTasks) },
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        Box(Modifier.padding(start = 12.dp, end = 8.dp)) {
                            ViewModeToggle(agendaSelected = true, onSwitch = onOpenCalendar)
                        }
                    },
                    title = {
                        HeadingText(
                            "AGENDA",
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.Bold,
                            shrinkToFit = true,
                        )
                    },
                    actions = {
                        SyncIndicator(onOpenCalendars = { showCalendars = true })
                        IconButton(onClick = onOpenSearch) {
                            Icon(Icons.Filled.Search, contentDescription = "Search")
                        }
                        MoreOptionsButton(planningTools, onThemes = { showThemes = true }, onSettings = { showSettings = true })
                    },
                )
            },
        ) { inner ->
            when {
                loadedEvents == null -> Box(Modifier.fillMaxSize().padding(inner))
                else -> Column(Modifier.fillMaxSize().padding(inner)) {
                    // The range chips and type tabs stay put; only what is below them scrolls.
                    AgendaFilterBar(
                        range = range,
                        onRange = vm::setAgendaRange,
                    )
                    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AgendaType.entries.forEach { option ->
                            val selected = option in types
                            val green = MaterialTheme.colorScheme.primary
                            Row(Modifier.weight(1f)
                                .semantics {
                                    contentDescription = "Show ${option.label.lowercase()}"
                                    toggleableState = ToggleableState(selected)
                                }
                                .combinedClickable(role = Role.Checkbox,
                                    enabled = !selection.active,
                                    onClick = { settings.toggleAgendaType(option) },
                                    onLongClickLabel = "Show only ${option.label.lowercase()}",
                                    onLongClick = { settings.setAgendaTypes(setOf(option)) })
                                .heightIn(min = 48.dp).padding(horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center) {
                                Text(
                                    text = when (option) {
                                        AgendaType.TASKS -> "✅"
                                        AgendaType.BILLS -> "🧾"
                                        AgendaType.EVENTS -> "🗓️"
                                    },
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.alpha(if (selected) 1f else 0.4f).clearAndSetSemantics {},
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(option.label, style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.drawBehind {
                                        if (selected) {
                                            val y = size.height + 3.dp.toPx()
                                            drawLine(green, start = Offset(0f, y), end = Offset(size.width, y),
                                                strokeWidth = 1.dp.toPx())
                                        }
                                    },
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    color = if (selected) green else MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                    textDecoration = if (selected) TextDecoration.None else TextDecoration.LineThrough)
                            }
                        }
                    }
                    LazyScrollHints(listState, Modifier.fillMaxSize()) { LazyColumn(
                        Modifier.fillMaxSize(), state = listState,
                        contentPadding = PaddingValues(bottom = 144.dp),
                    ) {
                        if (showBackupFailed) item(key = "backup-failed") {
                            com.example.itinerary.ui.MatrixTextButton(onClick = { showSettings = true }) {
                                Text("Last backup failed or was interrupted · View backup status", color = MaterialTheme.colorScheme.error)
                            }
                        }
                        if (showBackupReminder) item(key = "backup-reminder") {
                            androidx.compose.material3.OutlinedCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                                Column(Modifier.padding(12.dp)) {
                                    Text(if (backupStatus.lastSuccess == null) "Keep your events and tasks safe with a backup" else "It's time to back up your events and tasks")
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        com.example.itinerary.ui.MatrixTextButton(onClick = { showSettings = true }) { Text("Backup settings") }
                                        com.example.itinerary.ui.MatrixTextButton(onClick = { backupStore.remindNextWeek(today) }) { Text("Remind me in a week") }
                                    }
                                }
                            }
                        }
                        if (showBillSummary) item(key = "bill-summary") { MonthlyBills(events, today) }
                        if (showTaskOptions) item(key = "task-options") {
                            FilterChip(selected = showCompleted, enabled = !selection.active,
                                onClick = { showCompleted = !showCompleted }, label = { Text("Show completed tasks") },
                                modifier = Modifier.padding(horizontal = 16.dp))
                        }
                        if (anytimeTasks.isNotEmpty()) {
                            item(key = "anytime-heading") {
                                com.example.itinerary.ui.MatrixTextButton(onClick = { anytimeExpanded = !anytimeExpanded },
                                    modifier = Modifier.fillMaxWidth()) {
                                    Text("Anytime tasks (${anytimeTasks.size}) · ${if (anytimeExpanded) "Collapse" else "Expand"}")
                                }
                            }
                            if (anytimeExpanded) items(anytimeTasks, key = { "task-${it.id}" }) { task ->
                                TaskCard(task, today, selection = selection) { editingTaskId = task.id }
                            }
                        }
                        if (showEmpty) item(key = "empty") {
                            if (types.isEmpty()) Text("Select a type to show items.",
                                Modifier.fillMaxWidth().padding(20.dp),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            else NothingToShow(events.isNotEmpty() || tasks.isNotEmpty(), range, types.size != AgendaType.entries.size,
                                onShowAll = { vm.setAgendaRange(AgendaRange.ALL); settings.setAgendaTypes(AgendaType.entries.toSet()) })
                        }
                        dates.forEach { date ->
                            stickyHeader(key = "day-$date") { AgendaDayHeading(date, today) }
                            if (datedTasks[date].orEmpty().isNotEmpty() || billsByDate[date].orEmpty().isNotEmpty()) item(key = "tasks-heading-$date") {
                                Text("Tasks", Modifier.padding(horizontal = 20.dp, vertical = 4.dp), style = MaterialTheme.typography.labelLarge)
                            }
                            items(billsByDate[date].orEmpty(), key = { "bill-${it.id}" }, contentType = { "bill-task" }) { bill ->
                                BillTaskCard(bill, today, selection) { editingBillId = bill.id }
                            }
                            items(datedTasks[date].orEmpty(), key = { "task-${it.id}" }, contentType = { "task" }) { task ->
                                TaskCard(task, today, selection = selection) { editingTaskId = task.id }
                            }
                            // An event under way (a trip, or one from yesterday past midnight) can appear twice (its first
                            // day and Today), so its Today card needs its own key.
                            items(eventsByDate[date]?.entries.orEmpty(), key = { if (it.continuing) "under-way-${it.event.id}" else it.event.id },
                                contentType = { "event" }) { entry ->
                                AgendaEventCard(entry, today, selection,
                                    onMove = { vm.moveToTomorrow(entry.event.id) },
                                    onCopy = { newEvent.start(it.event.plannerCopy()) }) {
                                    // One under way shown under Today opens the calendar on today, the day it was shown under.
                                    if (selection.active) { if (!OutsideCalendars.isOutside(entry.event.id)) selection.toggle(entry.event.id) }
                                    else onOpenEvent(if (entry.continuing) today else entry.event.date)
                                }
                            }
                        }
                    } }
                }
            }
        }

        // Keep creation and bill tools together under the big +. A new event starts today.
        AddMenuHost(
            state = newEvent,
            showButton = loadedEvents != null && !selection.active && !selection.busy,
            day = today,
            today = today,
            usedColors = { date -> events.filter { it.date == date && it.customColor == null }.map { it.colorIndex } },
            seeThroughPercent = addButtonSeeThrough,
            categoryCounts = categoryCounts,
            hiddenCategories = hiddenCategories,
            onRemoveCategories = vm::removeCategories,
            onShowCategory = vm::showCategory,
            planningTools = planningTools,
            saveEvent = vm::saveEvent,
            onQuickAdded = { following = it },
        )
        editingBillId?.let { id -> BillTaskEditor(id) { editingBillId = null } }
        editingTaskId?.let { id ->
            val task = tasks.find { it.id == id }
            if (task != null) androidx.compose.runtime.key(id) { TaskEditor(task, false) { editingTaskId = null } }
        }
        SettingsHost(vm = vm, show = showSettings, onDismiss = { showSettings = false }, showCalendars = showCalendars, onShowCalendars = { showCalendars = it })
    }

}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun AgendaFilterBar(range: AgendaRange, onRange: (AgendaRange) -> Unit) {
    val green = MaterialTheme.colorScheme.primary
    // Equal shares of the width when they fit; at large text whole chips move to a second line instead of
    // splitting words ("Upcom|ing").
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AgendaRange.entries.forEach { option ->
            val selected = option == range
            Surface(selected = selected, onClick = { onRange(option) },
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                shape = RoundedCornerShape(14.dp),
                color = if (selected) green.copy(alpha = 0.08f) else androidx.compose.ui.graphics.Color.Transparent,
                contentColor = if (selected) green else MaterialTheme.colorScheme.onSurfaceVariant,
                border = BorderStroke(com.example.itinerary.ui.theme.controlBorderWidth(), if (selected) green else MaterialTheme.colorScheme.outline)) {
                Box(Modifier.padding(horizontal = 4.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
                    Text(option.label, style = MaterialTheme.typography.labelLarge,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
                }
            }
        }
    }
}

// "Today · Thursday 24 September 2026": the relative word where there is one, and always the real date, written the
// way the user chose in Settings.
@Composable
private fun AgendaDayHeading(date: LocalDate, today: LocalDate) {
    val label = date.dayLabel(LocalDateFormat.current)
    val relative = when (date) {
        today -> "Today"
        today.plusDays(1) -> "Tomorrow"
        today.minusDays(1) -> "Yesterday"
        else -> null
    }
    HeadingText(
        if (relative != null) "$relative · $label" else label,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp)
            .semantics { heading() },
    )
}

// One event: time, colour and title.
@Composable
private fun AgendaEventCard(entry: AgendaEntry, today: LocalDate, selection: EventSelection, onMove: suspend () -> Unit,
                            onCopy: (com.example.itinerary.data.OutsideInfo) -> Unit, onClick: () -> Unit) {
    val event = entry.event
    val accent = event.accentColor()
    val outside = LocalOutsideEvents.current[event.id]
    TappableRow(onClick = onClick, onLongClick = if (outside != null) null else ({ selection.toggle(event.id) }),
        selected = (event.id in selection.ids).takeIf { selection.active }, arrow = false, tint = outside?.let { Color(it.color) }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        LimitTextScale { // the time column has a fixed width
            Text(
                event.startTime?.label(LocalTimeFormat.current, LocalContext.current) ?: "All day",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.width(76.dp),
            )
        }
        Box(
            Modifier
                .width(4.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(2.dp))
                .background(accent),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            EventTitle(androidx.compose.ui.text.AnnotatedString(event.title), readableOnSurface(accent), event.category == "Bills")
            if (outside != null) OutsideEventLabel(outside) else SyncMarkLabel(event.id)
            if (event.skipped) Text("Skipped · reminders paused", style = MaterialTheme.typography.labelMedium)
            if (event.category == "Bills" && event.billAmountMinor != null) Text(com.example.itinerary.data.Bills.format(event.billAmountMinor, event.billCurrency), style = MaterialTheme.typography.bodyMedium)
            if (event.category == "Bills") {
                BillStatus(event.paid)
                BillBalance(event.billAmountMinor, event.billCurrency, event.paid, event.payments)
                OverdueBill(event.date, event.paid, event.skipped, today)
            }
            if (event.linkedTaskId != null) Text("Task time block", style = MaterialTheme.typography.labelSmall)
            if (event.checklist.isNotEmpty()) Text(checklistProgress(event.checklist), style = MaterialTheme.typography.bodySmall)
            if (event.durationMinutes != null && event.startTime != null) Text(
                eventEndLabel(event.date, event.startTime, event.durationMinutes, LocalTimeFormat.current, LocalContext.current,
                    if (entry.continuing) today else event.date),
                style = MaterialTheme.typography.bodySmall,
            )
            event.endDate?.let { end -> Text(spanLabel(event.date, end, if (entry.continuing) today else event.date),
                style = MaterialTheme.typography.bodySmall) }
        }
        val context = LocalContext.current
        val format = LocalTimeFormat.current
        // An outside event has nothing to select, and only actions that leave its calendar as it is.
        if (outside != null) { if (!selection.active) OutsideActionsMenu(outside) { onCopy(outside) } }
        else if (selection.active) androidx.compose.material3.Checkbox(checked = event.id in selection.ids, onCheckedChange = null)
        else EventActionsMenu(event.id, event.title, event.date, today, onMove,
            billId = event.id.takeIf { event.category == "Bills" }, paid = event.paid,
            repeatId = event.id.takeIf { event.seriesId != null || event.skipped }, skipped = event.skipped,
            onShare = { shareEvent(context, event.title, event.date, event.startTime, event.durationMinutes, event.location, format) })
    }
}

@Composable
private fun NothingToShow(
    anyEvents: Boolean,
    range: AgendaRange,
    filtering: Boolean,
    onShowAll: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(20.dp)) {
        val message = when {
            !anyEvents -> "No events or tasks yet. Tap the + button to add one."
            filtering -> "No events or tasks match this filter."
            range == AgendaRange.TODAY -> "Nothing today."
            range == AgendaRange.THIS_WEEK -> "Nothing from today through Sunday."
            range == AgendaRange.UPCOMING -> "Nothing coming up. Earlier entries are under All."
            else -> "No events or tasks match this filter."
        }
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        if (anyEvents) {
            OutlinedButton(onClick = onShowAll) { Text("Show all events and tasks") }
        }
    }
}
