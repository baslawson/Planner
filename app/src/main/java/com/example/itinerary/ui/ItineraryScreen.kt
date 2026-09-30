package com.example.itinerary.ui
import com.example.itinerary.ui.MatrixIconButton as IconButton

import com.example.itinerary.data.billTaskSummary

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import java.time.YearMonth

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItineraryScreen(vm: ItineraryViewModel, onAgenda: () -> Unit, onOpenSearch: () -> Unit, onOpenSettings: () -> Unit, onOpenCalendars: () -> Unit) {
    val today = rememberCurrentDate()
    val allItems by vm.items.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val month by vm.month.collectAsStateWithLifecycle()
    val calendarCollapsed by vm.calendarCollapsed.collectAsStateWithLifecycle()
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
    // Outside events can't be deleted from Planner, so they can't be selected either.
    val selectable = remember(dayItems) { dayItems.filterNot { OutsideCalendars.isOutside(it.id) }.map { SelectableEvent(it.id, it.title, it.date, bill = it.category == "Bills") } }
    val selection = rememberEventSelection(selectable)
    val datesWithItems = remember(allItems) {
        buildSet {
            allItems.forEach { event ->
                add(event.date)
                // Every day of a multi-day event gets a dot.
                event.endDate?.let { end -> generateSequence(event.date.plusDays(1)) { it.plusDays(1) }.takeWhile { it <= end }.forEach(::add) }
                if (event.category != "Bills" && event.startTime != null && event.durationMinutes != null &&
                    event.date.atTime(event.startTime).plusMinutes(event.durationMinutes.toLong()) > event.date.plusDays(1).atStartOfDay()) add(event.date.plusDays(1))
            }
        }
    }


    // If a saved event no longer shows on this day, follow it so it doesn't seem to have vanished.
    // A later day of a trip or the morning after an overnight event still shows it, so the view stays.
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
            bottomBar = { EventSelectionBar(selection, selectable, vm::deleteEvents) },
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        Box(Modifier.padding(start = 12.dp, end = 8.dp)) {
                            ViewModeToggle(agendaSelected = false, onSwitch = onAgenda)
                        }
                    },
                    title = {
                        HeadingText(
                            "CALENDAR",
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.Bold,
                            shrinkToFit = true,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    actions = {
                        SyncIndicator(onOpenCalendars = onOpenCalendars)
                        IconButton(onClick = onOpenSearch) {
                            Icon(Icons.Filled.Search, contentDescription = "Search")
                        }
                        MoreOptionsButton(planningTools, onThemes = { showThemes = true }, onSettings = onOpenSettings)
                    },
                )
            },
        ) { inner ->
            // The calendar and day header stay put; only the event list scrolls. On a screen wider than it is tall
            // (landscape) the month sits beside the day's events: stacked, the grid filled the screen and the events
            // could not be reached, since swipes over the grid change the month.
            BoxWithConstraints(Modifier.fillMaxSize().padding(inner)) {
                val sideBySide = maxWidth > maxHeight
                // Rows short enough for a whole month beside the list (title, weekdays and the toggle take ~150dp).
                val rowHeight = if (sideBySide) ((maxHeight - 150.dp) / 6).coerceIn(36.dp, 48.dp) else 48.dp
                val calendar: @Composable () -> Unit = {
                    MonthCalendar(
                        month = month,
                        selected = selected,
                        datesWithItems = datesWithItems,
                        collapsed = calendarCollapsed,
                        onSelect = vm::select,
                        onMonthChange = vm::showMonth,
                        rowHeight = rowHeight,
                    )
                    // Offer the shortcut when the day (or, with the full month open, the month) isn't today's.
                    val showToday = (selected != today) || (!calendarCollapsed && month != YearMonth.from(today))
                    CalendarToggleBar(
                        collapsed = calendarCollapsed,
                        showToday = showToday,
                        onToday = {
                            vm.select(LocalDate.now())
                            vm.showMonth(YearMonth.now())
                        },
                        onToggle = {
                            // Opening the full month should land on the month of the day being viewed.
                            if (calendarCollapsed) vm.showMonth(YearMonth.from(selected))
                            vm.setCalendarCollapsed(!calendarCollapsed)
                        },
                    )
                }
                val dayList: @Composable ColumnScope.() -> Unit = {
                    DayHeader(date = selected)
                    LazyScrollHints(Modifier.weight(1f).fillMaxWidth()) { state -> LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = PaddingValues(bottom = 144.dp)) {
                        if (dayItems.isEmpty()) {
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
                            ) {
                                val outside = outsideEvents[item.id]
                                when {
                                    outside != null -> if (!selection.active) viewingOutside = outside
                                    selection.active -> selection.toggle(item.id)
                                    else -> editing = item
                                }
                            }
                        }
                    } }
                }
                if (sideBySide) Row(Modifier.fillMaxSize()) {
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
        )
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
                vm.saveItem(item, added, removed, addedReminders, removedReminders, options)
                planningTools.eventSaved()
                follow(item)
            },
            onDelete = vm::deleteItem,
        )
    }
}

@Composable
private fun DayHeader(date: LocalDate) {
    HeadingText(
        date.dayLabel(LocalDateFormat.current),
        style = MaterialTheme.typography.headlineSmall,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
    )
}

@Composable
private fun ItemRow(item: ItineraryItem, selection: EventSelection, attachmentCount: Int, reminderCount: Int, today: LocalDate, displayedDate: LocalDate, onMove: suspend () -> Unit, outside: com.example.itinerary.data.OutsideInfo? = null, onClick: () -> Unit) {
    // The event's own colour: the bar as it is, the title in a shade that reads well on the current theme.
    val accent = item.accentColor()
    TappableRow(onClick = onClick, onLongClick = if (outside != null) null else ({ selection.toggle(item.id) }),
        selected = (item.id in selection.ids).takeIf { selection.active }, arrow = false, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
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
            outside?.let { OutsideEventLabel(it) }
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
        // An outside event has nothing to select and none of these actions: they would change it.
        if (outside != null) Unit
        else if (selection.active) androidx.compose.material3.Checkbox(checked = item.id in selection.ids, onCheckedChange = null)
        else EventActionsMenu(item.id, item.title, item.date, today, onMove,
            billId = item.id.takeIf { item.category == "Bills" }, paid = item.paid,
            repeatId = item.id.takeIf { item.seriesId != null || item.skipped }, skipped = item.skipped,
            onShare = { shareEvent(context, item.title, item.date, item.startTime, item.durationMinutes, item.location, format) })
    }
}
