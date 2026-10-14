package com.example.itinerary.ui
import com.example.itinerary.ui.MatrixIconButton as IconButton

import com.example.itinerary.data.billTaskSummary

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.Alignment
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.itinerary.data.ItineraryItem
import com.example.itinerary.data.OutsideCalendars
import com.example.itinerary.data.plannerCopy
import java.time.LocalDate
import kotlinx.coroutines.launch
import java.time.YearMonth

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItineraryScreen(vm: ItineraryViewModel, onAgenda: () -> Unit, onOpenSearch: () -> Unit, onOpenSettings: () -> Unit, onOpenCalendars: () -> Unit,
                    onOpenNotes: () -> Unit, onAddNote: () -> Unit = {}) {
    val today = rememberCurrentDate()
    val loadedItems by vm.items.collectAsStateWithLifecycle()
    val allItems = loadedItems.orEmpty()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val month by vm.month.collectAsStateWithLifecycle()
    val calendarCollapsed by vm.calendarCollapsed.collectAsStateWithLifecycle()
    val calendarMaximised by vm.calendarMaximised.collectAsStateWithLifecycle()
    // Maximised: the month fills the screen and each day shows its entries; the day's list is hidden.
    val maximised = calendarMaximised && !calendarCollapsed
    val loadedTasks by vm.tasks.collectAsStateWithLifecycle()
    val allTasks = loadedTasks.orEmpty()
    var editingTaskId by rememberSaveable { mutableStateOf<String?>(null) }
    val allAttachments by vm.attachments.collectAsStateWithLifecycle()
    val allReminders by vm.reminders.collectAsStateWithLifecycle()
    val categoryCounts by vm.categoryCounts.collectAsStateWithLifecycle()
    val hiddenCategories by vm.hiddenCategories.collectAsStateWithLifecycle()
    val addButtonSeeThrough by vm.addButtonSeeThrough.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<ItineraryItem?>(null) }
    // An event from a Nextcloud calendar, shown read-only (see OutsideEventDialog).
    var viewingOutside by remember { mutableStateOf<com.example.itinerary.data.OutsideInfo?>(null) }
    val outsideEvents = LocalOutsideEvents.current
    // A new event from the big +; it starts on the selected day.
    val newEvent = remember { NewEventState() }
    val overlayMenu = remember { OverlayMenuState() }
    var showThemes by remember { mutableStateOf(false) }
    val planningTools = remember { PlanningToolsState() }

    val attachmentsByItem = remember(allAttachments) { allAttachments.groupBy { it.itemId } }
    val remindersByItem = remember(allReminders) { allReminders.groupBy { it.itemId } }
    val dayItems = remember(allItems, selected) { com.example.itinerary.data.eventsOnDay(allItems, selected) }
    // The tasks due that day, under its events (done ones too, crossed out, so ticking one doesn't make it vanish).
    val dayTasks = remember(allTasks, selected) { com.example.itinerary.data.CalendarChips.tasksOnDay(allTasks, selected) }
    val dayHoursSettings = (LocalContext.current.applicationContext as com.example.itinerary.ItineraryApp).settings
    val dayHours by dayHoursSettings.dayHours.collectAsStateWithLifecycle()
    // On the hour grid with the calendar at Week size, the whole week shows side by side (user, 15 Oct).
    val locales = androidx.compose.ui.platform.LocalConfiguration.current.locales
    val firstDow = remember(locales) { java.time.temporal.WeekFields.of(java.util.Locale.getDefault()).firstDayOfWeek }
    val hourDays = remember(selected, calendarCollapsed, dayHours, firstDow) {
        if (calendarCollapsed && dayHours) selected.with(java.time.temporal.TemporalAdjusters.previousOrSame(firstDow)).let { start -> (0L until 7L).map(start::plusDays) }
        else listOf(selected)
    }
    val hourEvents = remember(allItems, hourDays, dayHours) { if (dayHours) hourDays.associateWith { com.example.itinerary.data.eventsOnDay(allItems, it) } else emptyMap() }
    val hourTasks = remember(allTasks, hourDays, dayHours) { if (dayHours) hourDays.associateWith { com.example.itinerary.data.CalendarChips.tasksOnDay(allTasks, it) } else emptyMap() }
    val blockerCounts = remember(allTasks) { taskBlockerCounts(allTasks) }
    val streaks = remember(allTasks) { com.example.itinerary.data.TaskStreaks.streaks(allTasks) }
    // Outside events can't be deleted from Planner, so they can't be selected either.
    val selectable = remember(dayItems) { dayItems.filterNot { OutsideCalendars.isOutside(it.id) }.map { SelectableEvent(it.id, it.title, it.date, bill = it.category == "Bills") } }
    val selectableTasks = remember(dayTasks) { dayTasks.map { SelectableTask(it.id, it.title, it.dueDate) } }
    // Not before the lists it prunes against are read (L5-2, A6-5).
    val selection = rememberEventSelection(selectable, prune = loadedItems != null && loadedTasks != null, visibleTasks = selectableTasks)
    // Full size hides the day's list (Hunt 27): a selection made there would act on rows that can't be seen.
    LaunchedEffect(maximised) { if (maximised) selection.clear() }
    val datesWithItems = remember(allItems, allTasks) {
        buildSet {
            // A task due that day gets the dot too, while it isn't done.
            allTasks.forEach { task -> if (!task.done) task.dueDate?.let(::add) }
            allItems.forEach { event ->
                add(event.date)
                // Every day of a multi-day event gets a dot.
                event.endDate?.let { end -> generateSequence(event.date.plusDays(1)) { it.plusDays(1) }.takeWhile { it <= end }.forEach(::add) }
                if (event.category != "Bills" && event.startTime != null && event.durationMinutes != null &&
                    event.date.atTime(event.startTime).plusMinutes(event.durationMinutes.toLong()) > event.date.plusDays(1).atStartOfDay()) add(event.date.plusDays(1))
            }
        }
    }
    // Worked out only while maximised.
    val chipsByDate = remember(allItems, allTasks, maximised) {
        if (maximised) com.example.itinerary.data.CalendarChips.byDate(allItems, allTasks) else emptyMap()
    }
    // An entry dragged to another day of the full-screen month, moved with the same Undo bar as "Move to tomorrow".
    val moveScope = androidx.compose.runtime.rememberCoroutineScope()
    val moveContext = LocalContext.current
    val moveEntry: (com.example.itinerary.data.DayChip, Long) -> Unit = { chip, days ->
        moveScope.launch {
            try {
                if (chip.task) vm.moveTaskByDays(chip.key.removePrefix("task:"), days)
                else vm.moveByDays(chip.key.removePrefix("event:").toLong(), days)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { android.widget.Toast.makeText(moveContext, e.message ?: "Couldn't move it.", android.widget.Toast.LENGTH_LONG).show() }
        }
    }


    // If a saved event no longer shows on this day, follow it so it doesn't seem to have vanished.
    // A later day of a trip or the morning after an overnight event still shows it, so the view stays.
    val addedBar = rememberAddedBar()
    fun follow(item: ItineraryItem) {
        EditorRules.followDate(item, selected)?.let { day ->
            vm.select(day)
            vm.showMonth(YearMonth.from(day))
        }
    }

    if (showThemes) ThemesDialog(onDismiss = { showThemes = false })
    PlanningToolDialogs(planningTools, onEvent = { editing = it })

    OverlayMenuScreen(overlayMenu) {
        Scaffold(
            bottomBar = { EventSelectionBar(selection, selectable, vm::deleteEvents, selectableTasks) },
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        Box(Modifier.padding(start = 12.dp, end = 8.dp)) {
                            ViewModeToggle(agendaSelected = false, onSwitch = onAgenda)
                        }
                    },
                    title = {
                        // The sync cloud sits right after the heading, as on Notes.
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            HeadingText(
                                "CALENDAR",
                                modifier = Modifier.weight(1f, fill = false),
                                style = MaterialTheme.typography.headlineLarge,
                                fontWeight = FontWeight.Bold,
                                shrinkToFit = true,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            SyncIndicator(onOpenCalendars = onOpenCalendars)
                        }
                    },
                    actions = {
                        IconButton(onClick = onOpenSearch) {
                            Icon(Icons.Filled.Search, contentDescription = "Search")
                        }
                        MoreOptionsButton(planningTools, onThemes = { showThemes = true }, onSettings = onOpenSettings, onNotes = onOpenNotes)
                    },
                )
            },
        ) { inner ->
            // The calendar and day header stay put; only the event list scrolls. On a screen wider than it is tall
            // (landscape) the month sits beside the day's events: stacked, the grid filled the screen and the events
            // could not be reached below it.
            BoxWithConstraints(Modifier.fillMaxSize().padding(inner)) {
                val sideBySide = maxWidth > maxHeight
                // Rows short enough for a whole month beside the list (title, weekdays and the toggle take ~150dp).
                val rowHeight = if (sideBySide) ((maxHeight - 150.dp) / 6).coerceIn(36.dp, 48.dp) else 48.dp
                // Offer the shortcut when the day (or, with the full month open, the month) isn't today's.
                val showToday = (selected != today) || (!calendarCollapsed && month != YearMonth.from(today))
                val sizeBar: @Composable () -> Unit = {
                    CalendarSizeBar(
                        size = when { calendarCollapsed -> CalendarSize.WEEK; maximised -> CalendarSize.FULL; else -> CalendarSize.MONTH },
                        onSize = { size ->
                            // Opening the month from the week should land on the month of the day being viewed.
                            if (calendarCollapsed && size != CalendarSize.WEEK) vm.showMonth(YearMonth.from(selected))
                            vm.setCalendarCollapsed(size == CalendarSize.WEEK)
                            vm.setCalendarMaximised(size == CalendarSize.FULL)
                        },
                        showToday = showToday,
                        onToday = {
                            vm.select(LocalDate.now())
                            vm.showMonth(YearMonth.now())
                        },
                    )
                }
                val calendar: @Composable ColumnScope.() -> Unit = {
                    // Full screen, the bar sits at the top, where the + button (bottom centre unless moved) can't cover it.
                    if (maximised) sizeBar()
                    MonthCalendar(
                        month = month,
                        selected = selected,
                        datesWithItems = datesWithItems,
                        collapsed = calendarCollapsed,
                        onSelect = { day ->
                            vm.select(day)
                            // Maximised, a tap opens that day: back to the month with the day's list under it.
                            if (maximised) vm.setCalendarMaximised(false)
                        },
                        onMonthChange = vm::showMonth,
                        modifier = if (maximised) Modifier.weight(1f) else Modifier,
                        rowHeight = rowHeight,
                        chips = chipsByDate.takeIf { maximised },
                        onMoveEntry = moveEntry,
                    )
                    if (!maximised) sizeBar()
                }
                val dayList: @Composable ColumnScope.() -> Unit = {
                    DayHeader(date = selected, hours = dayHours, onHours = dayHoursSettings::setDayHours)
                    // The day on an hour grid (DayHours) unless events are being selected, which only the list shows.
                    if (dayHours && !selection.active) DayHours(
                        days = hourDays, selected = selected, today = today, events = hourEvents, tasks = hourTasks, outside = outsideEvents,
                        onEvent = { item -> outsideEvents[item.id]?.let { viewingOutside = it } ?: run { editing = item } },
                        onTask = { editingTaskId = it.id },
                        onEmptyHour = { day, at ->
                            if (newEvent.adding == null) newEvent.start(ItineraryItem(tripId = 0L, date = day, startTime = at, durationMinutes = 60, title = "",
                                colorIndex = com.example.itinerary.data.PlanColors.next(com.example.itinerary.data.eventsOnDay(allItems, day)
                                    .filter { it.customColor == null }.map { it.colorIndex }, com.example.itinerary.data.PlanColors.EVENT_COUNT)))
                        },
                        onMove = { item, day, at, length ->
                            moveScope.launch {
                                try { vm.moveEventTo(item.id, day, at, length) }
                                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                                catch (e: Exception) { android.widget.Toast.makeText(moveContext, e.message ?: "Couldn't move it.", android.widget.Toast.LENGTH_LONG).show() }
                            }
                        },
                        onDay = vm::select,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                    else LazyScrollHints(Modifier.weight(1f).fillMaxWidth()) { state -> LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = PaddingValues(bottom = 144.dp)) {
                        if (dayItems.isEmpty() && dayTasks.isEmpty()) {
                            item {
                                Text(
                                    "Nothing planned for this day. Tap + to add something.",
                                    modifier = Modifier.padding(horizontal = 20.dp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        items(dayItems, key = { it.id }) { item ->
                            if (item.category == "Bills") BillTaskCard(item.billTaskSummary(), today, selection) { editing = item }
                            else ItemRow(
                                item = item,
                                selection = selection,
                                attachmentCount = attachmentsByItem[item.id]?.size ?: 0,
                                today = today,
                                displayedDate = selected,
                                onMove = { vm.moveToTomorrow(item.id) },
                                reminderCount = remindersByItem[item.id]?.size ?: 0,
                                outside = outsideEvents[item.id],
                                onCopy = { outsideEvents[item.id]?.let { editing = it.event.plannerCopy() } },
                            ) {
                                val outside = outsideEvents[item.id]
                                when {
                                    outside != null -> if (!selection.active) viewingOutside = outside
                                    selection.active -> selection.toggle(item.id)
                                    else -> editing = item
                                }
                            }
                        }
                        items(dayTasks, key = { "task:${it.id}" }) { task ->
                            TaskCard(task, today, blockerCounts[task.id] ?: 0, selection = selection, streak = streaks[task.id] ?: 0) { editingTaskId = task.id }
                        }
                    } }
                }
                if (maximised) Column(Modifier.fillMaxSize()) {
                    // The whole height: the + button can be dragged off whatever it covers.
                    calendar()
                } else if (sideBySide) Row(Modifier.fillMaxSize()) {
                    ScrollHints(rememberScrollState(), Modifier.weight(1f).fillMaxHeight()) { calendar() }
                    Column(Modifier.weight(1f).fillMaxHeight()) { dayList() }
                } else Column(Modifier.fillMaxSize()) {
                    calendar()
                    dayList()
                }
            }
        }
        AddMenuHost(
            state = newEvent,
            showButton = !selection.active && !selection.busy,
            day = selected,
            today = today,
            usedColors = { date -> com.example.itinerary.data.eventsOnDay(allItems, date).filter { it.customColor == null }.map { it.colorIndex } },
            seeThroughPercent = addButtonSeeThrough,
            categoryCounts = categoryCounts,
            hiddenCategories = hiddenCategories,
            onRemoveCategories = vm::removeCategories,
            onShowCategory = vm::showCategory,
            planningTools = planningTools,
            saveEvent = vm::saveItem,
            onEventSaved = ::follow,
            // An event is shown by following it to its day (above); a task, which may be due another day or none, is said below.
            onQuickAdded = { if (it.task) addedBar(it) },
            onAddNote = onAddNote,
        )
    }

    editingTaskId?.let { id ->
        val task = rememberEditedTask(id, allTasks.find { it.id == id })
        if (task != null) androidx.compose.runtime.key(id) { TaskEditor(task, false) { editingTaskId = null } }
    }

    viewingOutside?.let { info ->
        OutsideEventDialog(info, onDismiss = { viewingOutside = null }, onCopy = {
            viewingOutside = null
            editing = info.event.plannerCopy()
        })
    }

    editing?.let { current ->
        ItemEditorSheet(
            initial = current,
            existingAttachments = attachmentsByItem[current.id].orEmpty(),
            existingReminders = remindersByItem[current.id].orEmpty(),
            categoryCounts = categoryCounts,
            hiddenCategories = hiddenCategories,
            onRemoveCategories = vm::removeCategories,
            onShowCategory = vm::showCategory,
            onDismiss = { editing = null; planningTools.eventEditorDismissed() },
            onSave = { item, added, removed, addedReminders, removedReminders, options ->
                val id = vm.saveItem(item, added, removed, addedReminders, removedReminders, options)
                planningTools.eventSaved()
                follow(item)
                id
            },
            onDelete = vm::deleteItem,
        )
    }
}

@Composable
// The day's heading, with List | Hours to see the day as a list or on an hour grid (DayHours) when [hours] is given.
private fun DayHeader(date: LocalDate, hours: Boolean? = null, onHours: (Boolean) -> Unit = {}) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        HeadingText(
            date.dayLabel(LocalDateFormat.current),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.weight(1f).padding(horizontal = 20.dp, vertical = 16.dp),
        )
        if (hours != null) Row(Modifier.padding(end = 12.dp).selectableGroup()) {
            listOf(false to "List", true to "Hours").forEach { (value, label) ->
                androidx.compose.material3.FilterChip(selected = hours == value, onClick = { onHours(value) }, label = { Text(label) },
                    modifier = Modifier.padding(start = 4.dp))
            }
        }
    }
}

@Composable
private fun ItemRow(item: ItineraryItem, selection: EventSelection, attachmentCount: Int, reminderCount: Int, today: LocalDate, displayedDate: LocalDate, onMove: suspend () -> Unit, outside: com.example.itinerary.data.OutsideInfo? = null, onCopy: () -> Unit = {}, onClick: () -> Unit) {
    // The event's own colour: the bar as it is, the title in a shade that reads well on the current theme.
    val accent = item.accentColor()
    TappableRow(onClick = onClick, onLongClick = if (outside != null) null else ({ selection.toggle(item.id) }),
        selected = selection.isSelected(item.id).takeIf { selection.active }, arrow = false, tint = outside?.let { androidx.compose.ui.graphics.Color(it.color) }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        LimitTextScale { // the time column has a fixed width
            Text(
                item.startTime?.label(LocalTimeFormat.current, LocalContext.current) ?: "All day",
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
            EventTitle(androidx.compose.ui.text.AnnotatedString(item.title), readableOnSurface(accent), item.category == "Bills")
            if (outside != null) OutsideEventLabel(outside) else SyncMarkLabel(item.id)
            if (item.skipped) Text("Skipped · reminders paused", style = MaterialTheme.typography.labelMedium)
            if (item.category == "Bills" && item.billAmountMinor != null) Text(com.example.itinerary.data.Bills.format(item.billAmountMinor, item.billCurrency), style = MaterialTheme.typography.bodyMedium)
            if (item.category == "Bills") {
                BillStatus(item.paid)
                BillBalance(item.billAmountMinor, item.billCurrency, item.paid, item.payments)
                OverdueBill(item.date, item.paid, item.skipped, today)
            }
            if (item.linkedTaskId != null) Text("Task time block", style = MaterialTheme.typography.labelSmall)
            if (item.checklist.isNotEmpty()) Text(checklistProgress(item.checklist), style = MaterialTheme.typography.bodySmall)
            if (item.durationMinutes != null && item.startTime != null) Text(
                eventEndLabel(item.date, item.startTime, item.durationMinutes, LocalTimeFormat.current, LocalContext.current, displayedDate),
                style = MaterialTheme.typography.bodySmall,
            )
            item.endDate?.let { end -> Text(spanLabel(item.date, end, displayedDate), style = MaterialTheme.typography.bodySmall) }
            if (item.location.isNotBlank()) {
                Text(
                    item.location,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (item.notes.isNotBlank()) {
                Text(
                    item.notes,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (attachmentCount > 0 || reminderCount > 0) {
                val badges = buildList {
                    if (attachmentCount > 0) add("📎 $attachmentCount")
                    if (reminderCount > 0) add("⏰ $reminderCount")
                }
                Text(
                    badges.joinToString("  "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        val context = LocalContext.current
        val format = LocalTimeFormat.current
        // An outside event has nothing to select, and only actions that leave its calendar as it is.
        if (outside != null) { if (!selection.active) OutsideActionsMenu(outside, onCopy) }
        else if (selection.active) androidx.compose.material3.Checkbox(checked = selection.isSelected(item.id), onCheckedChange = null)
        else EventActionsMenu(item.id, item.title, item.date, today, onMove,
            billId = item.id.takeIf { item.category == "Bills" }, paid = item.paid,
            repeatId = item.id.takeIf { item.seriesId != null || item.skipped }, skipped = item.skipped, repeating = item.seriesId != null,
            onShare = { shareEvent(context, item.title, item.date, item.startTime, item.durationMinutes, item.location, format) })
    }
}

