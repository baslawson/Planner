package com.example.itinerary.ui
import com.example.itinerary.ui.MatrixIconButton as IconButton
import com.example.itinerary.ui.MatrixFilterChip as FilterChip

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Checkbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import com.example.itinerary.data.PlanEvent
import com.example.itinerary.data.PlanSummary
import com.example.itinerary.data.Search
import com.example.itinerary.data.Trip
import com.example.itinerary.data.UpcomingPlans
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import java.time.LocalDate

enum class PlanFilter(val label: String) {
    ALL("All"),
    NOW("Now"),
    UPCOMING("Upcoming"),
    PAST("Past"),
}

// The plans the filter bar leaves showing. The typed text is stripped of capitals and accents once
// here, for the whole list; doing it inside the per-plan test meant normalising the query again for
// every plan on every keystroke.
fun List<Trip>.matching(chip: PlanFilter, text: String, today: LocalDate): List<Trip> {
    val needle = Search.normalize(text.trim())
    return filter { it.matches(chip, needle, today) }
}

// [needle] is the filter text already normalised by [matching]; an empty one matches every plan.
private fun Trip.matches(chip: PlanFilter, needle: String, today: LocalDate): Boolean {
    val statusOk = when (chip) {
        PlanFilter.ALL -> true
        PlanFilter.NOW -> !today.isBefore(startDate) && !today.isAfter(endDate)
        PlanFilter.UPCOMING -> startDate.isAfter(today)
        PlanFilter.PAST -> endDate.isBefore(today)
    }
    if (!statusOk) return false
    // Looks in both the name and the description.
    return needle.isEmpty() || Search.normalize("$name $destination").contains(needle)
}

@Composable
fun FilterBar(text: String, onText: (String) -> Unit, selected: PlanFilter, onSelect: (PlanFilter) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { onText(it.replace('\n', ' ')) },
            label = { Text("Filter plans") },
            singleLine = true,
            trailingIcon = {
                if (text.isNotEmpty()) {
                    IconButton(onClick = { onText("") }) {
                        Icon(Icons.Filled.Close, contentDescription = "Clear filter")
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PlanFilter.entries.forEach { option ->
                FilterChip(selected = option == selected, onClick = { onSelect(option) }, label = { Text(option.label) })
            }
        }
    }
}

// The plan list. Press and hold a plan, then drag it to reorder. While a drag is under way the list
// is a local copy that is saved once, when the plan is dropped. Automatic ordering resumes four seconds later,
// allowing every plan to be dragged even while upcoming plans are normally shown first.
@Composable
fun TripList(
    trips: List<Trip>,
    // Each plan's events, in date then time order; the card is made of them (see TripRow).
    eventsByPlan: Map<Long, List<PlanEvent>>,
    pinnedIds: Set<Long>,
    autoArrangeEnabled: Boolean,
    today: LocalDate,
    canDrag: Boolean,
    onOpen: (Long, LocalDate?) -> Unit,
    onEdit: (Trip) -> Unit,
    onDelete: (Trip) -> Unit,
    onReorder: (List<Long>) -> Unit,
    selectionMode: Boolean = false,
    selectedIds: Set<Long> = emptySet(),
    onToggleSelection: (Long) -> Unit = {},
) {
    val listState = rememberLazyListState()
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val saved by rememberUpdatedState(trips)
    val automatic by rememberUpdatedState(autoArrangeEnabled)
    val saveOrder by rememberUpdatedState(onReorder)
    val order = remember { mutableStateListOf<Trip>().apply { addAll(trips) } }
    var draggedId by remember { mutableStateOf<Long?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var awaitingAutomaticOrder by remember { mutableStateOf(false) }
    var settleJob by remember { mutableStateOf<Job?>(null) }
    var reorderVersion by remember { mutableIntStateOf(0) }

    fun saveManualOrder() {
        awaitingAutomaticOrder = automatic
        reorderVersion++
        saveOrder(order.map { it.id })
    }

    // A new drag cancels the countdown. Start again after its drop animation finishes.
    LaunchedEffect(awaitingAutomaticOrder, draggedId, autoArrangeEnabled, reorderVersion, selectionMode) {
        if (awaitingAutomaticOrder && draggedId == null && !selectionMode) {
            if (autoArrangeEnabled) delay(4_000)
            awaitingAutomaticOrder = false
        }
    }

    // A filter change or removal must take effect immediately, including during the grace period.
    // The set is the effect's key, so it is built once per list rather than on every recomposition.
    val tripIds = remember(trips) { trips.mapTo(HashSet()) { it.id } }
    LaunchedEffect(canDrag, tripIds) {
        settleJob?.cancel()
        draggedId = null
        awaitingAutomaticOrder = false
        if (selectionMode) {
            val latest = trips.associateBy { it.id }
            val existingIds = order.mapTo(HashSet()) { it.id }
            val refreshed = order.mapNotNull { latest[it.id] } + trips.filter { it.id !in existingIds }
            order.clear()
            order.addAll(refreshed)
        }
    }

    // Include drag/delay state so database updates received during a drag are applied afterwards.
    LaunchedEffect(trips, draggedId, awaitingAutomaticOrder, selectionMode) {
        if (draggedId == null && !awaitingAutomaticOrder && !selectionMode) {
            order.clear()
            order.addAll(trips)
        }
    }

    // Room at the end so the big + button never covers the last plan.
    Box(Modifier.fillMaxSize()) {
    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 144.dp)) {
        items(order, key = { it.id }) { trip ->
            val dragging = trip.id == draggedId
            Column(
                Modifier
                    .then(
                        if (dragging) {
                            Modifier
                                .zIndex(1f)
                                .graphicsLayer { translationY = dragOffset }
                                .shadow(8.dp)
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        } else {
                            Modifier.animateItem()
                        },
                    )
                    .pointerInput(canDrag, trip.id, selectionMode) {
                        if (!canDrag || selectionMode) return@pointerInput
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                settleJob?.cancel()
                                draggedId = trip.id
                                dragOffset = 0f
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                dragOffset += amount.y
                                val visible = listState.layoutInfo.visibleItemsInfo
                                val current = visible.firstOrNull { it.key == draggedId }
                                    ?: return@detectDragGesturesAfterLongPress
                                // Wait for the layout to catch up with the last swap before deciding on another.
                                if (current.index != order.indexOfFirst { it.id == draggedId }) {
                                    return@detectDragGesturesAfterLongPress
                                }
                                val top = current.offset + dragOffset
                                val bottom = top + current.size
                                val next = visible.firstOrNull { it.index == current.index + 1 }
                                val previous = visible.firstOrNull { it.index == current.index - 1 }
                                when {
                                    next != null && bottom > next.offset + next.size / 2f -> {
                                        order.add(current.index + 1, order.removeAt(current.index))
                                        dragOffset -= next.size
                                    }
                                    previous != null &&
                                        top < previous.offset + previous.size / 2f -> {
                                        order.add(current.index - 1, order.removeAt(current.index))
                                        dragOffset += previous.size
                                    }
                                }
                            },
                            onDragEnd = {
                                if (order.map { it.id } != saved.map { it.id }) saveManualOrder()
                                // Settle into the slot, then go back to following the saved list.
                                settleJob = scope.launch {
                                    animate(dragOffset, 0f, animationSpec = tween(150)) { value, _ -> dragOffset = value }
                                    draggedId = null
                                }
                            },
                            onDragCancel = {
                                // Restarting an idle recognizer (e.g. entering selection) is not a cancelled drag.
                                if (draggedId != trip.id) return@detectDragGesturesAfterLongPress
                                settleJob?.cancel()
                                awaitingAutomaticOrder = false
                                draggedId = null
                                dragOffset = 0f
                                order.clear()
                                order.addAll(saved)
                            },
                        )
                    },
            ) {
                val floated = trip.id in pinnedIds
                TripRow(
                    trip = trip,
                    events = eventsByPlan[trip.id].orEmpty(),
                    today = today,
                    soonCaption = if (floated) UpcomingPlans.caption(trip, today) else null,
                    isFirst = selectionMode || !canDrag || order.firstOrNull()?.id == trip.id,
                    isLast = selectionMode || !canDrag || order.lastOrNull()?.id == trip.id,
                    selectionMode = selectionMode,
                    selected = trip.id in selectedIds,
                    onToggleSelection = { onToggleSelection(trip.id) },
                    onOpen = { date -> if (selectionMode) onToggleSelection(trip.id) else onOpen(trip.id, date) },
                    onEdit = { onEdit(trip) },
                    onDelete = { onDelete(trip) },
                    onMoveToTop = {
                        order.remove(trip)
                        order.add(0, trip)
                        saveManualOrder()
                    },
                    onMoveToBottom = {
                        order.remove(trip)
                        order.add(trip)
                        saveManualOrder()
                    },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
    if (!selectionMode && autoArrangeEnabled && (awaitingAutomaticOrder || draggedId != null)) {
        Snackbar(Modifier.align(Alignment.BottomCenter).padding(start = 16.dp, end = 16.dp, bottom = 152.dp)
            .semantics { liveRegion = LiveRegionMode.Polite }) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "Settings > Upcoming plans on top",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.inversePrimary,
                )
                Text(if (draggedId != null) {
                    "Plans will rearrange automatically a few seconds after you finish dragging."
                } else {
                    "Plans will rearrange automatically in a few seconds."
                })
                Text("Turn this off in Settings to keep your order.")
            }
        }
    }
    }
}

@Composable
private fun TripRow(
    trip: Trip,
    events: List<PlanEvent>, // this plan's events, in date then time order
    today: LocalDate,
    soonCaption: String?, // why the plan is floated to the top ("Starts in 3 days"), or null
    isFirst: Boolean,
    isLast: Boolean,
    selectionMode: Boolean,
    selected: Boolean,
    onToggleSelection: () -> Unit,
    // The day to open the plan on, or null for the plan's own starting day.
    onOpen: (LocalDate?) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onMoveToTop: () -> Unit,
    onMoveToBottom: () -> Unit,
) {
    var suppressHoldClick by remember { mutableStateOf(false) }
    // Each plan has its own colour: a bar down the left edge (drawn in the row's left padding, so the text
    // stays where it was) and a soft tint behind the row.
    val accent = trip.accentColor()
    Row(
        Modifier
            .fillMaxWidth()
            .background(accent.copy(alpha = 0.14f))
            .drawBehind { drawRect(accent, size = Size(6.dp.toPx(), size.height)) }
            .pointerInput(Unit) {
                // Observe the release before clickable handles it, without consuming
                // events needed by the parent's long-press drag recognizer.
                try {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val released = event.changes.all { !it.pressed }
                            if (released) {
                                suppressHoldClick = event.changes.any {
                                    it.uptimeMillis - down.uptimeMillis >= viewConfiguration.longPressTimeoutMillis
                                }
                            }
                        } while (!released)
                        // Clickable runs in Main. Clear in Final so accessibility
                        // clicks outside a touch gesture remain available.
                        awaitPointerEvent(PointerEventPass.Final)
                        suppressHoldClick = false
                    }
                } finally {
                    suppressHoldClick = false
                }
            }
            .clickable(onClick = { if (!suppressHoldClick) onOpen(null) })
            .padding(start = 20.dp, top = 16.dp, bottom = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(trip.name, style = MaterialTheme.typography.titleLarge)
            if (soonCaption != null) {
                Text(
                    soonCaption,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            // The body of the card is the plan's events rather than its dates; an empty plan falls back to
            // its date range, which is otherwise carried by the day headings above the events themselves.
            PlanEventSummary(
                trip = trip,
                events = events,
                today = today,
                // A tap that was really a long-held touch belongs to the drag, exactly as for the whole row.
                onOpenDay = { date -> if (!suppressHoldClick) onOpen(date) },
            )
        }
        if (selectionMode) {
            Checkbox(
                checked = selected,
                onCheckedChange = { onToggleSelection() },
                modifier = Modifier.semantics { contentDescription = "Select ${trip.name}" },
            )
        }
        OverlayMenuAnchor(title = "Plan options", items = { close ->
            DropdownMenuItem(text = { Text("Edit plan") }, enabled = !selectionMode,
                onClick = { close(); onEdit() })
            DropdownMenuItem(
                text = { Text("Move to top") },
                enabled = !isFirst,
                onClick = { close(); onMoveToTop() },
            )
            DropdownMenuItem(
                text = { Text("Move to bottom") },
                enabled = !isLast,
                onClick = { close(); onMoveToBottom() },
            )
            DropdownMenuItem(text = { Text("Delete plan") }, onClick = { close(); onDelete() })
        }) { open ->
            IconButton(onClick = open) {
                Icon(Icons.Filled.MoreVert, contentDescription = "Plan options")
            }
        }
    }
}

// What a plan card is made of: the plan's own events, grouped under their day, in place of the date range the
// card used to show. Which few are drawn is PlanSummary's decision. A plan with no events keeps its date range,
// since with no events there is nothing else to say when it is.
@Composable
private fun PlanEventSummary(
    trip: Trip,
    events: List<PlanEvent>,
    today: LocalDate,
    onOpenDay: (LocalDate) -> Unit,
) {
    if (events.isEmpty()) {
        Text(
            trip.rangeLabel(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "No events yet",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    // Worked out once per list of events, not on every recomposition of every row.
    val shown = remember(events, today) { PlanSummary.visible(events, today) }
    val lines = remember(shown) { PlanSummary.withDayBreaks(shown) }
    Column(Modifier.padding(top = 4.dp)) {
        lines.forEachIndexed { index, (heading, event) ->
            if (heading != null) {
                Text(
                    heading.cardDayLabel(),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = if (index == 0) 0.dp else 6.dp, bottom = 2.dp),
                )
            }
            EventLine(event) { onOpenDay(event.date) }
        }
        val hidden = events.size - shown.size
        if (hidden > 0) {
            Text(
                "+$hidden more",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

// One event on a plan card, laid out like the event rows on the plan screen (time, the event's own colour, title)
// but without their border, so the card reads as one thing. Tapping it opens the plan on that event's day.
@Composable
private fun EventLine(event: PlanEvent, onClick: () -> Unit) {
    val accent = event.accentColor()
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            event.startTime?.label(LocalTimeFormat.current, LocalContext.current) ?: "All day",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.width(76.dp),
        )
        Box(
            Modifier
                .width(4.dp)
                .height(18.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(accent),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            event.title,
            style = MaterialTheme.typography.bodyLarge,
            color = readableOnSurface(accent),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
