package com.example.itinerary.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.key
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.itinerary.data.DayTimeline
import com.example.itinerary.data.ItineraryItem
import com.example.itinerary.data.OutsideInfo
import com.example.itinerary.data.PlannerTask
import java.time.LocalDate
import java.time.LocalTime

private val HOUR_HEIGHT = 56.dp
private val GUTTER = 56.dp
// Where a dragged event near the top or bottom of the grid starts scrolling it.
private val EDGE = 48.dp
// The bar at the bottom of an event, held and dragged to stretch it.
private val BAR = 14.dp

/**
 * The Calendar's day on an hour grid (user, 14 Oct), the other way to see the day's list: timed events as blocks from
 * their start to their end, side by side where they overlap (DayTimeline), so the free time between shows. All-day and
 * multi-day events and the day's tasks sit in a strip above. Today has a line at the current time. A tap on an event or
 * task opens it as in the list; a tap on an empty hour starts a new event at that hour. It opens scrolled to the current
 * hour today, else to the first event, else to 7 am.
 *
 * With several [days] (the week, user 15 Oct) each gets a column, headed by its weekday; a tap on the heading picks that day.
 *
 * Drags (user, 15 Oct): hold an event and drag it to another time (and, in the week, another day); drag the bar at its
 * bottom to make it longer or shorter. Both go in 15-minute steps, through [onMove], with a label of the new time while
 * dragging. Events from other calendars and on several days stay put. TalkBack gets the same as actions on the block.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DayHours(
    days: List<LocalDate>,
    selected: LocalDate,
    today: LocalDate,
    events: Map<LocalDate, List<ItineraryItem>>,
    tasks: Map<LocalDate, List<PlannerTask>>,
    outside: Map<Long, OutsideInfo>,
    onEvent: (ItineraryItem) -> Unit,
    onTask: (PlannerTask) -> Unit,
    onEmptyHour: (LocalDate, LocalTime) -> Unit,
    onMove: (ItineraryItem, LocalDate, LocalTime, Int?) -> Unit,
    modifier: Modifier = Modifier,
    onDay: (LocalDate) -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    val timeFormat = LocalTimeFormat.current
    val week = days.size > 1
    val blocks = remember(events, days) { days.map { day -> DayTimeline.blocks(events[day].orEmpty(), day) } }
    val allDay = remember(events, days) { days.map { day -> DayTimeline.allDay(events[day].orEmpty()) } }
    fun color(item: ItineraryItem) = outside[item.id]?.let { Color(it.color) } ?: item.accentColor()
    fun time(minute: Int) = LocalTime.of(minute / 60 % 24, minute % 60).label(timeFormat, context)

    Column(modifier) {
        if (week) WeekStrip(days, selected, today, allDay, tasks, ::color, onEvent, onTask, onDay)
        else {
            val dayTasks = tasks[days.first()].orEmpty()
            if (allDay.first().isNotEmpty() || dayTasks.isNotEmpty()) {
                FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    allDay.first().forEach { item ->
                        StripChip(item.title.ifBlank { "Untitled" }, color(item), task = false) { onEvent(item) }
                    }
                    dayTasks.forEach { task ->
                        StripChip(task.title.ifBlank { "Untitled task" }, colors.secondary, task = true, done = task.done) { onTask(task) }
                    }
                }
            }
        }
        // Bug hunt 28 P5: the newest, so a tap picks the new event's colour from the events as they are now.
        val currentOnEmptyHour by rememberUpdatedState(onEmptyHour)
        val currentOnMove by rememberUpdatedState(onMove)
        val haptics = LocalHapticFeedback.current
        val scroll = rememberScrollState()
        val density = LocalDensity.current
        val hourPx = with(density) { HOUR_HEIGHT.toPx() }
        val edgePx = with(density) { EDGE.toPx() }
        var viewport by remember { mutableIntStateOf(0) }
        // The event being dragged, and how far; its block is drawn where it would land.
        var drag by remember { mutableStateOf<GridDrag?>(null) }
        // -1 / 1 while a drag is held near the top / bottom edge, which scrolls the grid (and the drag with it).
        var edge by remember { mutableFloatStateOf(0f) }
        LaunchedEffect(days.first(), days.size) {
            val hour = when {
                today in days -> LocalTime.now().hour - 1
                blocks.any { it.isNotEmpty() } -> blocks.flatten().minOf { it.startMinute } / 60
                else -> 7
            }.coerceIn(0, 23)
            scroll.scrollTo((hour * hourPx).toInt())
        }
        LaunchedEffect(edge != 0f) {
            while (edge != 0f) {
                withFrameNanos { }
                val step = edge * hourPx / 12
                val moved = scroll.scrollBy(step)
                drag = drag?.let { it.copy(dy = it.dy + moved) }
            }
        }
        // Right to left, the columns run the other way: a drag rightwards goes back a day.
        val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
        fun columnsMoved(dx: Float, columnPx: Float) = Math.round((if (rtl) -dx else dx) / columnPx)
        fun dropped(current: GridDrag, columnPx: Float) {
            val item = current.block.item
            val minutes = current.dy * 60 / hourPx
            val columns = if (current.resize) 0 else columnsMoved(current.dx, columnPx)
            // Bug hunt 29 G5: less than half a step is no change (a hold let go, or a tiny stretch of an event with no length).
            if (Math.round(minutes / DayTimeline.STEP) == 0 && columns == 0) return
            if (current.resize) {
                val length = DayTimeline.resized(current.start, item.durationMinutes, minutes)
                if (length != item.durationMinutes) currentOnMove(item, item.date, item.startTime!!, length)
            } else {
                val start = DayTimeline.movedStart(current.start, minutes)
                val day = days[(current.dayIndex + columns).coerceIn(0, days.lastIndex)]
                val at = LocalTime.of(start / 60, start % 60)
                if (day != item.date || at != item.startTime) currentOnMove(item, day, at, item.durationMinutes)
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth().onSizeChanged { viewport = it.height }.verticalScroll(scroll)) {
            BoxWithConstraints(Modifier.fillMaxWidth().height(HOUR_HEIGHT * 24 + 144.dp)) {
                val gridWidth = maxWidth - GUTTER - 12.dp
                val columnWidth = gridWidth / days.size
                val columnPx = with(density) { columnWidth.toPx() }
                // The hours, and an empty spot to tap for a new event at that hour (on that day, in the week).
                // Bug hunt 29 G3: keyed on the column width too (a rotation or a resized window changes it).
                Column(Modifier.fillMaxWidth().pointerInput(days, columnPx, rtl) {
                    detectTapGestures { at ->
                        val x = if (rtl) size.width - at.x else at.x
                        val column = ((x - GUTTER.toPx()) / columnPx).toInt().coerceIn(0, days.lastIndex)
                        currentOnEmptyHour(days[column], LocalTime.of((at.y / hourPx).toInt().coerceIn(0, 23), 0))
                    }
                }) {
                    for (hour in 0 until 24) {
                        Row(Modifier.fillMaxWidth().height(HOUR_HEIGHT)) {
                            Text(time(hour * 60), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant,
                                modifier = Modifier.width(GUTTER).padding(start = 8.dp, top = 2.dp))
                            Column(Modifier.fillMaxHeight()) { HorizontalDivider(color = colors.outlineVariant) }
                        }
                    }
                }
                if (week) for (column in 1 until days.size) {
                    Box(Modifier.offset(x = GUTTER + columnWidth * column).width(1.dp).height(HOUR_HEIGHT * 24).background(colors.outlineVariant))
                }
                days.forEachIndexed { dayIndex, day ->
                    // Bug hunt 29 G4: each block keeps its own state (and gesture) when others are added before it.
                    blocks[dayIndex].forEach { block -> key(block.item.id, day) {
                        val item = block.item
                        val dragged = drag?.takeIf { it.block.item.id == item.id && it.dayIndex == dayIndex }
                        val movable = DayTimeline.movable(item, day)
                        // Bug hunt 29 G2: moves and stretches count from the event's own start, not where its block is
                        // drawn (one after 23:30 is drawn from 23:30, to leave room for its title).
                        val realStart = item.startTime?.let { it.hour * 60 + it.minute } ?: block.startMinute
                        // It has the bar to stretch it when it ends this day (or at its midnight, G7).
                        val stretchable = movable && item.date.atTime(item.startTime!!).plusMinutes((item.durationMinutes ?: 0).toLong()) <= day.plusDays(1).atStartOfDay()
                        val top = HOUR_HEIGHT * (block.startMinute / 60f)
                        val height = HOUR_HEIGHT * ((block.endMinute - block.startMinute) / 60f)
                        val width = columnWidth / block.columns
                        val accent = color(item)
                        val range = "${time(block.startMinute)} to ${time(block.endMinute)}"
                        fun nudge(minutes: Int, resize: Boolean) = run {
                            if (resize) currentOnMove(item, item.date, item.startTime!!, DayTimeline.resized(realStart, item.durationMinutes, minutes.toFloat()))
                            else DayTimeline.movedStart(realStart, minutes.toFloat()).let { start ->
                                currentOnMove(item, item.date, LocalTime.of(start / 60, start % 60), item.durationMinutes)
                            }
                            true
                        }
                        Box(
                            Modifier
                                .offset(x = GUTTER + columnWidth * dayIndex + width * block.column, y = top)
                                .width(width - 2.dp)
                                .height(height - 2.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(accent.copy(alpha = if (dragged != null) 0.1f else 0.25f))
                                .border(1.dp, accent.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                                .clickable(role = Role.Button) { onEvent(item) }
                                // One item for TalkBack, with what it says, the tap and the move actions together (they
                                // were two: the words on one, the actions on another; found by a device check, 15 Oct).
                                .clearAndSetSemantics {
                                    role = Role.Button
                                    onClick { onEvent(item); true }
                                    contentDescription = "$range, ${item.title}"
                                    if (movable) customActions = listOf(
                                        CustomAccessibilityAction("15 minutes later") { nudge(DayTimeline.STEP, false) },
                                        CustomAccessibilityAction("15 minutes earlier") { nudge(-DayTimeline.STEP, false) },
                                        CustomAccessibilityAction("15 minutes longer") { nudge(DayTimeline.STEP, true) },
                                        CustomAccessibilityAction("15 minutes shorter") { nudge(-DayTimeline.STEP, true) },
                                    )
                                }
                                // Bug hunt 29 G1: keyed on everything the gesture uses, so it never works from an
                                // event as it was (a stretch then a move wrote the old length back).
                                .then(if (movable) Modifier.pointerInput(block, day, days, columnPx, rtl) {
                                    // Bug hunt 29 (device test): a hold on the bar at the bottom is the bar's stretch,
                                    // not a move of the whole block (both would start, and the move's cancel ended the stretch).
                                    var onBar = false
                                    try { detectDragGesturesAfterLongPress(
                                        onDragStart = { at ->
                                            onBar = stretchable && at.y >= size.height - BAR.toPx()
                                            if (!onBar) {
                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                            drag = GridDrag(block, dayIndex, resize = false, start = realStart) }
                                        },
                                        onDrag = { change, amount -> if (!onBar) {
                                            change.consume()
                                            drag = drag?.let { it.copy(dx = it.dx + amount.x, dy = it.dy + amount.y) }
                                            val y = top.toPx() + change.position.y - scroll.value
                                            // Only once the finger has gone some way: an event picked up near the top
                                            // or bottom doesn't start the grid scrolling under it (found by a device test, 15 Oct).
                                            val travelled = drag?.let { kotlin.math.abs(it.dx) + kotlin.math.abs(it.dy) } ?: 0f
                                            edge = if (travelled < edgePx / 2) 0f
                                                else when { y < edgePx && amount.y < 0f -> -1f; y > viewport - edgePx && amount.y > 0f -> 1f; else -> if (y < edgePx || y > viewport - edgePx) edge else 0f }
                                        } },
                                        onDragEnd = { if (onBar) onBar = false else { edge = 0f; drag?.let { dropped(it, columnPx) }; drag = null } },
                                        onDragCancel = { if (onBar) onBar = false else { edge = 0f; drag = null } },
                                    ) } finally {
                                        // Restarted or gone mid-drag: no preview left behind.
                                        if (drag?.block?.item?.id == item.id) { edge = 0f; drag = null }
                                    }
                                } else Modifier)
                                .padding(horizontal = if (week) 3.dp else 6.dp, vertical = 2.dp),
                        ) {
                            Column {
                                Text(item.title.ifBlank { "Untitled" }, style = if (week) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelLarge,
                                    maxLines = if (height > 40.dp) (if (week) 3 else 2) else 1, overflow = TextOverflow.Ellipsis)
                                if (height > 44.dp && !week) Text(range, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, maxLines = 1)
                            }
                            // The bar to hold and drag for a longer or shorter event. Bug hunt 29: held first, like a
                            // move, so a scroll that starts on a short event still scrolls (G5); also on an event that
                            // ends at midnight (G7).
                            if (stretchable) Box(
                                Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(BAR)
                                    .clearAndSetSemantics { }
                                    .pointerInput(block, day, days, columnPx, rtl) {
                                        try { detectDragGesturesAfterLongPress(
                                            onDragStart = {
                                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                                drag = GridDrag(block, dayIndex, resize = true, start = realStart)
                                            },
                                            onDrag = { change, amount ->
                                                change.consume()
                                                drag = drag?.let { it.copy(dy = it.dy + amount.y) }
                                            },
                                            onDragEnd = { drag?.let { dropped(it, columnPx) }; drag = null },
                                            onDragCancel = { drag = null },
                                        ) } finally { if (drag?.block?.item?.id == item.id) drag = null }
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Box(Modifier.width(24.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(accent.copy(alpha = 0.8f)))
                            }
                        }
                    } }
                }
                // Where the dragged event would land, with its new times.
                drag?.let { current ->
                    val item = current.block.item
                    val minutes = current.dy * 60 / hourPx
                    val (start, length) = if (current.resize) current.start to DayTimeline.resized(current.start, item.durationMinutes, minutes)
                        else DayTimeline.movedStart(current.start, minutes) to (item.durationMinutes ?: DayTimeline.MIN_MINUTES)
                    val column = if (current.resize) current.dayIndex else (current.dayIndex + columnsMoved(current.dx, columnPx)).coerceIn(0, days.lastIndex)
                    val shown = minOf(length, 24 * 60 - start).coerceAtLeast(DayTimeline.STEP)
                    val accent = color(item)
                    Box(
                        Modifier
                            .offset(x = GUTTER + columnWidth * column, y = HOUR_HEIGHT * (start / 60f))
                            .width(columnWidth - 2.dp)
                            .height(HOUR_HEIGHT * (shown / 60f) - 2.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(accent.copy(alpha = 0.45f))
                            .border(2.dp, accent, RoundedCornerShape(6.dp))
                            .clearAndSetSemantics { }
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                    ) {
                        Text((if (week && !current.resize) days[column].format(patternFormatter("EEE")) + " " else "") +
                            "${time(start)} to ${time((start + length) % (24 * 60))}",
                            style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, maxLines = 2)
                    }
                }
                val nowColumn = days.indexOf(today)
                if (nowColumn >= 0) {
                    val now = LocalTime.now()
                    Box(Modifier.offset(x = GUTTER - 4.dp + columnWidth * nowColumn, y = HOUR_HEIGHT * ((now.hour * 60 + now.minute) / 60f))
                        .width(columnWidth + 4.dp).height(2.dp).background(colors.error))
                }
            }
        }
    }
}

// [start]: the event's own start (minutes into the day), which a move or stretch counts from.
private data class GridDrag(val block: DayTimeline.Block, val dayIndex: Int, val resize: Boolean, val start: Int, val dx: Float = 0f, val dy: Float = 0f)

// The week's heading row: each day's weekday and date (a tap picks it), then its all-day events and tasks, two at most.
@Composable
private fun WeekStrip(
    days: List<LocalDate>, selected: LocalDate, today: LocalDate, allDay: List<List<ItineraryItem>>,
    tasks: Map<LocalDate, List<PlannerTask>>, color: (ItineraryItem) -> Color,
    onEvent: (ItineraryItem) -> Unit, onTask: (PlannerTask) -> Unit, onDay: (LocalDate) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(end = 12.dp)) {
        Spacer(Modifier.width(GUTTER))
        days.forEachIndexed { index, day ->
            val entries = allDay[index].map { it to null } + tasks[day].orEmpty().map { null to it }
            Column(Modifier.weight(1f).padding(horizontal = 1.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                val picked = day == selected
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                        .background(if (picked) colors.primary.copy(alpha = 0.2f) else Color.Transparent)
                        // Its own description (with no year, unlike the week strip's days above): "Tuesday 15 April".
                        // One item for TalkBack: the day, as a heading, that a double tap picks.
                        .clearAndSetSemantics { heading(); contentDescription = day.format(patternFormatter("EEEE d MMMM")); role = Role.Tab
                            onClick(label = "Pick this day") { onDay(day); true } }
                        .clickable(role = Role.Tab, onClickLabel = "Pick this day") { onDay(day) }
                        .padding(vertical = 2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(day.format(patternFormatter("EEE")), style = MaterialTheme.typography.labelSmall,
                        color = if (day == today) colors.error else colors.onSurfaceVariant, maxLines = 1)
                    Text("${day.dayOfMonth}", style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (picked || day == today) FontWeight.Bold else FontWeight.Normal, maxLines = 1)
                }
                entries.take(2).forEach { (event, task) ->
                    val label = event?.title?.ifBlank { "Untitled" } ?: ((if (task!!.done) "☑ " else "☐ ") + task.title.ifBlank { "Untitled task" })
                    Box(
                        Modifier.padding(top = 2.dp).fillMaxWidth().clip(RoundedCornerShape(4.dp))
                            .then(if (event != null) Modifier.background(color(event).copy(alpha = 0.3f)) else Modifier.border(1.dp, colors.secondary, RoundedCornerShape(4.dp)))
                            .clickable(role = Role.Button) { if (event != null) onEvent(event) else onTask(task!!) }
                            .padding(horizontal = 2.dp, vertical = 1.dp),
                    ) { Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                if (entries.size > 2) Text("+${entries.size - 2}", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant,
                    modifier = Modifier.clickable(role = Role.Button, onClickLabel = "Open this day") { onDay(day) })
            }
        }
    }
}

@Composable
private fun StripChip(title: String, accent: Color, task: Boolean, done: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(6.dp)
    Box(
        Modifier
            .clip(shape)
            .then(if (task) Modifier.border(1.dp, accent, shape) else Modifier.background(accent.copy(alpha = 0.3f)))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text((if (task) (if (done) "☑ " else "☐ ") else "") + title, style = MaterialTheme.typography.labelLarge, maxLines = 1,
            overflow = TextOverflow.Ellipsis)
    }
}
