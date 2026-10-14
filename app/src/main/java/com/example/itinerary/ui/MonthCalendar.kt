package com.example.itinerary.ui
import com.example.itinerary.ui.MatrixIconButton as IconButton

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerSnapDistance
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import com.example.itinerary.data.CalendarChips
import com.example.itinerary.data.DayChip
import com.example.itinerary.data.WeekLines
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import java.time.temporal.ChronoUnit
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle as NameStyle
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * A plain month grid. The selected day gets a filled circle,
 * today a pulsing ring, and days that already have events a small dot.
 * Swipe sideways on the grid (or use the arrows) to move between months: a finger moving left goes to the next month,
 * moving right to the previous one, as in other Android calendars (user, 14 Oct; it was the other way round before). The week strip moves a week the same way.
 * When [collapsed] it shrinks to the selected day's week, and the arrows move a week at a time.
 * Given [chips] (maximised) the month fills the height it is given, and each day lists its entries in small coloured
 * lines instead of the dot, an event on more than one day as one bar across them.
 */
@Composable
fun MonthCalendar(
    month: YearMonth,
    selected: LocalDate,
    datesWithItems: Set<LocalDate>,
    collapsed: Boolean,
    onSelect: (LocalDate) -> Unit,
    onMonthChange: (YearMonth) -> Unit,
    modifier: Modifier = Modifier,
    // Shorter rows let the whole month fit beside the day's events in landscape.
    rowHeight: Dp = 48.dp,
    chips: Map<LocalDate, List<DayChip>>? = null,
    // Maximised: an entry dragged to another day, and by how many days (see MaximisedMonthPage).
    onMoveEntry: ((DayChip, Long) -> Unit)? = null,
) = CompositionLocalProvider(LocalCalendarRowHeight provides rowHeight) {
    // R18-U2: read again when the language or region changes (the activity handles that itself, so it isn't recreated).
    val locales = androidx.compose.ui.platform.LocalConfiguration.current.locales
    val firstDow = remember(locales) { WeekFields.of(Locale.getDefault()).firstDayOfWeek }
    val today = rememberCurrentDate()
    // The arrows stop at the ends of the Calendar's range (January 1 and December 9999).
    val previous = if (collapsed) YearMonth.from(selected.minusWeeks(1)) else month.minusMonths(1)
    val next = if (collapsed) YearMonth.from(selected.plusWeeks(1)) else month.plusMonths(1)

    // Seven fixed columns: past the limit, two-digit days and weekday names no longer fit.
    LimitTextScale { Column(modifier.padding(horizontal = 8.dp).animateContentSize()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = {
                    if (collapsed) onSelect(selected.minusWeeks(1)) else onMonthChange(month.minusMonths(1))
                },
                enabled = CalendarMonths.inRange(previous),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    contentDescription = if (collapsed) "Previous week" else "Previous month",
                )
            }
            Text(
                (if (collapsed) YearMonth.from(selected) else month).label(),
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleLarge,
            )
            IconButton(
                onClick = {
                    if (collapsed) onSelect(selected.plusWeeks(1)) else onMonthChange(month.plusMonths(1))
                },
                enabled = CalendarMonths.inRange(next),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = if (collapsed) "Next week" else "Next month",
                )
            }
        }

        Row(Modifier.fillMaxWidth()) {
            for (i in 0 until 7) {
                val dow = firstDow.plus(i.toLong())
                Text(
                    dow.getDisplayName(NameStyle.SHORT, Locale.getDefault()),
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (collapsed) {
            // Just the selected day's week, which can straddle two months.
            val weekStart = selected.with(TemporalAdjusters.previousOrSame(firstDow))
            val week = remember(weekStart) { List(7) { weekStart.plusDays(it.toLong()) } }
            // A sideways swipe moves a week, as the arrows do: a finger moving left to the next one.
            val swipe = with(androidx.compose.ui.platform.LocalDensity.current) { 48.dp.toPx() }
            val currentSelected by rememberUpdatedState(selected)
            val currentOnSelect by rememberUpdatedState(onSelect)
            // Right-to-left (Hunt 27): the month pager turns round there, so the week strip does too.
            val rtl = androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
            Box(Modifier.pointerInput(swipe, rtl) {
                var travelled = 0f
                detectHorizontalDragGestures(
                    onDragStart = { travelled = 0f },
                    onHorizontalDrag = { change, amount -> change.consume(); travelled += if (rtl) -amount else amount },
                    onDragEnd = {
                        val day = currentSelected
                        val target = when {
                            travelled < -swipe -> day.plusWeeks(1)
                            travelled > swipe -> day.minusWeeks(1)
                            else -> null
                        }
                        if (target != null && CalendarMonths.inRange(YearMonth.from(target))) currentOnSelect(target)
                    },
                )
            }) {
                WeekRow(
                    week = week,
                    selected = selected,
                    today = today,
                    datesWithItems = datesWithItems,
                    onSelect = onSelect,
                )
            }
        } else if (chips != null) {
            // Maximised: the weeks share whatever height is left under the weekday names.
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                MonthPager(
                    month = CalendarMonths.clamp(month),
                    firstDow = firstDow,
                    selected = selected,
                    today = today,
                    datesWithItems = datesWithItems,
                    onSelect = onSelect,
                    onMonthChange = onMonthChange,
                    fillHeight = maxHeight,
                    chips = chips,
                    onMoveEntry = onMoveEntry,
                )
            }
        } else {
            MonthPager(
                month = CalendarMonths.clamp(month),
                firstDow = firstDow,
                selected = selected,
                today = today,
                datesWithItems = datesWithItems,
                onSelect = onSelect,
                onMonthChange = onMonthChange,
            )
        }
    } }
}

// Months are pages of a sideways pager, so a finger moving left brings the next month in (mirrored in right-to-left layouts).
// Page numbers count months from January of the year 1 (see CalendarMonths).
private val LocalCalendarRowHeight = compositionLocalOf { 48.dp }

private fun pageOf(month: YearMonth): Int = CalendarMonths.pageOf(month)

private fun monthOfPage(page: Int): YearMonth = CalendarMonths.monthOfPage(page)

private fun monthCells(month: YearMonth, firstDow: DayOfWeek): List<LocalDate?> {
    val leadingBlanks = (month.atDay(1).dayOfWeek.value - firstDow.value + 7) % 7
    return List(leadingBlanks) { null } + (1..month.lengthOfMonth()).map { month.atDay(it) }
}

private fun weeksIn(month: YearMonth, firstDow: DayOfWeek): Int {
    val leadingBlanks = (month.atDay(1).dayOfWeek.value - firstDow.value + 7) % 7
    return (leadingBlanks + month.lengthOfMonth() + 6) / 7
}

// Plain fields, not Compose state: they are read by the settled-page collector, not by the layout.
private class ScrollRequest(var month: YearMonth) { var target: Int? = null }

@Composable
private fun MonthPager(
    month: YearMonth,
    firstDow: DayOfWeek,
    selected: LocalDate,
    today: LocalDate,
    datesWithItems: Set<LocalDate>,
    onSelect: (LocalDate) -> Unit,
    onMonthChange: (YearMonth) -> Unit,
    // Maximised: the pager is this tall whatever the month, and each month's weeks share it.
    fillHeight: Dp? = null,
    chips: Map<LocalDate, List<DayChip>>? = null,
    onMoveEntry: ((DayChip, Long) -> Unit)? = null,
) {
    val pagerState = rememberPagerState(initialPage = pageOf(month)) { CalendarMonths.COUNT }
    val currentMonth by rememberUpdatedState(month)
    val currentOnMonthChange by rememberUpdatedState(onMonthChange)

    // The page the pager is being moved to for a month set some other way (arrows, Today), until it gets there. Set
    // as the new month is applied, before any settled page from the interrupted animation can be reported (D5).
    val request = remember { ScrollRequest(month) }
    SideEffect {
        if (request.month != month) { request.month = month; request.target = pageOf(month) }
    }

    // Swiping to a new month reports it once the page has settled. A page passed on the way to a requested month
    // is not a swipe: e.g. a second arrow tap stops the first animation between two months and starts another.
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            val landed = monthOfPage(page)
            if (request.target == null && landed != currentMonth) currentOnMonthChange(landed)
        }
    }
    // The pager is as tall as the current month needs (4 to 6 weeks), so short months don't leave a gap. It follows the
    // settled page, so it changes once a move has landed, not during it: pages that change size under an animation
    // make it stop short (between a 5-week and a 6-week month the grid stayed ~10 dp too high or too low).
    val fullHeight = fillHeight ?: (LocalCalendarRowHeight.current * weeksIn(month, firstDow))
    val monthHeight by animateDpAsState(LocalCalendarRowHeight.current * weeksIn(monthOfPage(pagerState.settledPage), firstDow), label = "monthHeight")
    val height = fillHeight ?: monthHeight
    val currentHeight by rememberUpdatedState(height)

    // ...and when the month is changed some other way (arrows, Today) the pager follows, and ends snapped on it.
    // (Keyed on the height too, so a new row height — landscape — can't leave it waiting for one it never reaches.)
    LaunchedEffect(month, fullHeight) {
        val target = pageOf(month)
        try {
            if (pagerState.currentPage != target || pagerState.currentPageOffsetFraction != 0f) {
                request.target = target
                pagerState.animateScrollToPage(target)
                // A tap during the last move's height change still moves pages that change size: once the height
                // has settled, finish the move. AG-1: a swipe meanwhile is the user's and ends this move (its page then
                // counts, as for a swipe during the animation): waiting on would never end when the swipe heads for a
                // month of another height, and finishing would pull the grid back. A time limit guards the wait too.
                val swiped = withTimeoutOrNull(2_000) {
                    snapshotFlow { pagerState.isScrollInProgress to (currentHeight == fullHeight) }.first { (scrolling, settled) -> scrolling || settled }.first
                } ?: false
                if (!swiped && (pagerState.currentPage != target || pagerState.currentPageOffsetFraction != 0f))
                    pagerState.animateScrollToPage(target)
            }
        } finally {
            // Done, or stopped by a swipe (whose page then counts); left alone when a newer month has set its own target.
            if (request.target == target) request.target = null
        }
    }

    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxWidth().height(height),
        // One month per swipe, however hard the flick.
        flingBehavior = PagerDefaults.flingBehavior(state = pagerState, pagerSnapDistance = PagerSnapDistance.atMost(1)),
    ) { page ->
        val weeks = remember(page, firstDow) { monthCells(monthOfPage(page), firstDow).chunked(7) }
        val pageRowHeight = fillHeight?.let { it / weeks.size } ?: LocalCalendarRowHeight.current
        CompositionLocalProvider(LocalCalendarRowHeight provides pageRowHeight) {
            if (chips != null) MaximisedMonthPage(weeks, selected, today, onSelect, chips, onMoveEntry)
            else Column {
                weeks.forEach { week ->
                    WeekRow(
                        week = week,
                        selected = selected,
                        today = today,
                        datesWithItems = datesWithItems,
                        onSelect = onSelect,
                    )
                }
            }
        }
    }
}

@Composable
private fun WeekRow(
    week: List<LocalDate?>,
    selected: LocalDate,
    today: LocalDate,
    datesWithItems: Set<LocalDate>,
    onSelect: (LocalDate) -> Unit,
) {
    Row(Modifier.fillMaxWidth()) {
        for (i in 0 until 7) {
            val date = week.getOrNull(i)
            if (date == null) {
                Spacer(Modifier.weight(1f).height(LocalCalendarRowHeight.current))
            } else {
                DayCell(
                    date = date,
                    isSelected = date == selected,
                    isToday = date == today,
                    hasItems = date in datesWithItems,
                    onClick = { onSelect(date) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** How much of the Calendar screen the calendar takes: one week, the month above the day's list, or the whole screen. */
enum class CalendarSize(val label: String, val description: String) {
    WEEK("Week", "Calendar: week"),
    MONTH("Month", "Calendar: month"),
    FULL("Full", "Calendar: full screen");

    val smaller: CalendarSize get() = entries[(ordinal - 1).coerceAtLeast(0)]
    val bigger: CalendarSize get() = entries[(ordinal + 1).coerceAtMost(entries.lastIndex)]
}

// Sits on the line under the calendar: a Week | Month | Full switch, the current size filled in, and a "Today" pill at
// the right end while there is somewhere to jump back from. A swipe down on the bar makes the calendar one size
// bigger, a swipe up one size smaller. The two parts are measured before they are placed, so large text cannot make
// them overlap: the switch stays centred while there is room, slides left to make room when there isn't, and if even
// that is not enough "Today" drops to its own row below, still on the right.
@Composable
fun CalendarSizeBar(
    size: CalendarSize,
    onSize: (CalendarSize) -> Unit,
    showToday: Boolean,
    onToday: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val swipe = with(LocalDensity.current) { 24.dp.toPx() }
    val currentSize by rememberUpdatedState(size)
    val currentOnSize by rememberUpdatedState(onSize)
    Layout(
        modifier = modifier
            .fillMaxWidth()
            .pointerInput(swipe) {
                var travelled = 0f
                detectVerticalDragGestures(
                    onDragStart = { travelled = 0f },
                    onVerticalDrag = { change, amount -> change.consume(); travelled += amount },
                    onDragEnd = {
                        when {
                            travelled > swipe -> currentOnSize(currentSize.bigger)
                            travelled < -swipe -> currentOnSize(currentSize.smaller)
                        }
                    },
                )
            }
            .padding(vertical = 8.dp),
        content = {
            HorizontalDivider(color = colors.outlineVariant)
            Row(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .border(1.dp, colors.primary, RoundedCornerShape(50))
                    .background(colors.primaryContainer)
                    .selectableGroup(),
            ) {
                CalendarSize.entries.forEach { option ->
                    val chosen = option == size
                    Text(
                        option.label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = if (chosen) colors.onPrimary else colors.onPrimaryContainer,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(if (chosen) colors.primary else Color.Transparent)
                            .selectable(selected = chosen, role = Role.Tab, onClick = { onSize(option) })
                            .semantics {
                                contentDescription = option.description
                                // On each tab (Hunt 27): the bar itself is never focused by TalkBack.
                                customActions = listOf(
                                    CustomAccessibilityAction("Bigger calendar") { currentOnSize(currentSize.bigger); true },
                                    CustomAccessibilityAction("Smaller calendar") { currentOnSize(currentSize.smaller); true },
                                )
                            }
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                    )
                }
            }
            if (showToday) {
                Text(
                    "Today",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = colors.onSecondaryContainer,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(colors.secondaryContainer)
                        .clickable(role = Role.Button, onClickLabel = "Go to today", onClick = onToday)
                        .semantics { contentDescription = "Go to today" }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val toggle = measurables[1].measure(loose)
        val today = measurables.getOrNull(2)?.measure(loose)
        val edge = 16.dp.roundToPx()
        val gap = 8.dp.roundToPx()

        var toggleX = (width - toggle.width) / 2
        var todayX = 0
        var wrapped = false
        if (today != null) {
            todayX = width - edge - today.width
            if (toggleX + toggle.width + gap > todayX) {
                toggleX = todayX - gap - toggle.width
                if (toggleX < edge) {
                    wrapped = true
                    toggleX = (width - toggle.width) / 2
                    todayX = todayX.coerceAtLeast(0)
                }
            }
        }
        val lineHeight = maxOf(toggle.height, if (today != null && !wrapped) today.height else 0)
        val height = if (today != null && wrapped) lineHeight + gap + today.height else lineHeight
        val divider = measurables[0].measure(Constraints.fixedWidth(width))

        layout(width, height) {
            divider.place(0, (lineHeight - divider.height) / 2)
            toggle.place(toggleX.coerceAtLeast(0), (lineHeight - toggle.height) / 2)
            today?.place(todayX, if (wrapped) lineHeight + gap else (lineHeight - today.height) / 2)
        }
    }
}

private val CHIP_GAP = 2.dp
private val MAX_NUMBER_SIZE = 26.dp

// The sizes of a maximised day: its row, the number at the top, and the entry lines under it ([slots] of them).
private data class ChipMetrics(val rowHeight: Dp, val numberSize: Dp, val chipHeight: Dp, val slots: Int) {
    // Where entry line [line] starts, from the top of the row.
    fun lineTop(line: Int): Dp = 2.dp + numberSize + CHIP_GAP + (chipHeight + CHIP_GAP) * line
}

@Composable
private fun chipMetrics(): ChipMetrics {
    val rowHeight = LocalCalendarRowHeight.current
    val chipHeight = with(LocalDensity.current) { 12.sp.toDp() } + 2.dp
    val numberSize = minOf(MAX_NUMBER_SIZE, rowHeight - 4.dp)
    val slots = ((rowHeight - 4.dp - numberSize - CHIP_GAP) / (chipHeight + CHIP_GAP)).toInt()
    return ChipMetrics(rowHeight, numberSize, chipHeight, slots)
}

@Composable
private fun chipStyle() = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.Normal)

// An entry being dragged: picked up on day [from], the finger now [at] (in the page), over day [target] (null: no day).
private data class ChipDrag(val chip: DayChip, val from: LocalDate, val at: Offset, val target: LocalDate?)

// A month of the full-screen calendar. Hold an entry to pick it up, drag it to another day and let go to move it there
// (user, 14 Oct). A multi-day event keeps its length and moves by as many days as the finger did. While dragging, the
// entry follows the finger and the day under it is outlined; only the month's own days take a drop. A tap still opens
// the day, and a sideways swipe that starts before the hold still changes the month.
@Composable
private fun MaximisedMonthPage(
    weeks: List<List<LocalDate?>>,
    selected: LocalDate,
    today: LocalDate,
    onSelect: (LocalDate) -> Unit,
    chips: Map<LocalDate, List<DayChip>>,
    onMoveEntry: ((DayChip, Long) -> Unit)?,
) {
    val metrics = chipMetrics()
    val lines = remember(weeks, chips, metrics.slots) { weeks.map { CalendarChips.weekLines(it, chips, metrics.slots) } }
    var drag by remember { mutableStateOf<ChipDrag?>(null) }
    val haptics = LocalHapticFeedback.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val currentOnMove by rememberUpdatedState(onMoveEntry)
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .then(if (onMoveEntry == null) Modifier else Modifier.pointerInput(weeks, lines, metrics, rtl) {
                val rowPx = metrics.rowHeight.toPx()
                // The day cell under a point, as (row, column in reading order).
                fun cellAt(p: Offset): Pair<Int, Int>? {
                    val row = (p.y / rowPx).toInt()
                    val col = (p.x / (size.width / 7f)).toInt().let { if (rtl) 6 - it else it }
                    return if (p.x < 0 || p.y < 0 || row !in weeks.indices || col !in 0..6) null else row to col
                }
                fun dayAt(p: Offset) = cellAt(p)?.let { (row, col) -> weeks[row].getOrNull(col) }
                fun entryAt(p: Offset): DayChip? {
                    val (row, col) = cellAt(p) ?: return null
                    val y = p.y - row * rowPx - metrics.lineTop(0).toPx()
                    val step = (metrics.chipHeight + CHIP_GAP).toPx()
                    val line = if (y < 0) return null else (y / step).toInt()
                    if (y - line * step > metrics.chipHeight.toPx()) return null
                    // Bug hunt 28 P3: only what Planner can move; other calendars' events (birthdays too) are read-only.
                    return lines[row].pieces.firstOrNull { it.line == line && col in it.start..it.end }?.chip?.takeIf { it.movable }
                }
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val chip = entryAt(down.position) ?: return@awaitEachGesture
                    val from = dayAt(down.position) ?: return@awaitEachGesture
                    // A hold: no lift, no move past the touch slop and nothing else (the pager) taking the touch first.
                    var stopped = false
                    val held = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        while (!stopped) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                            stopped = change == null || !change.pressed || change.isConsumed ||
                                (change.position - down.position).getDistance() > viewConfiguration.touchSlop
                        }
                    } == null && !stopped
                    if (!held) return@awaitEachGesture
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    drag = ChipDrag(chip, from, down.position, from)
                    try {
                        // Taken before the day cells and the pager see it, so the lift isn't a tap and nothing scrolls.
                        while (true) {
                            val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                            change.consume()
                            if (!change.pressed) break
                            drag = drag?.copy(at = change.position, target = dayAt(change.position))
                        }
                        val done = drag
                        if (done?.target != null && done.target != done.from)
                            currentOnMove?.invoke(done.chip, ChronoUnit.DAYS.between(done.from, done.target))
                    } finally {
                        drag = null
                    }
                }
            }),
    ) {
        Column {
            weeks.forEachIndexed { i, week ->
                MaximisedWeekRow(week, lines[i], metrics, selected, today, onSelect, chips, lifted = drag?.chip?.key)
            }
        }
        drag?.let { d ->
            val cell = maxWidth / 7
            // The day it would go to, outlined.
            weeks.indexOfFirst { d.target in it }.takeIf { d.target != null && it >= 0 }?.let { row ->
                val col = weeks[row].indexOf(d.target).let { if (rtl) 6 - it else it }
                Box(
                    Modifier
                        .absoluteOffset(x = cell * col, y = metrics.rowHeight * row)
                        .size(cell, metrics.rowHeight)
                        .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp)),
                )
            }
            // The entry, just above the finger so it stays in sight.
            val density = LocalDensity.current
            val width = cell * 1.5f
            Box(
                Modifier
                    .absoluteOffset {
                        // Bug hunt 28 P10: kept inside the page, which clips; under the finger when there's no room above it.
                        val w = with(density) { width.toPx() }; val h = with(density) { metrics.chipHeight.toPx() }
                        val above = d.at.y - h * 3
                        IntOffset((d.at.x - w / 2).coerceIn(0f, maxOf(0f, constraints.maxWidth - w)).roundToInt(),
                            (if (above >= 0) above else d.at.y + h * 2).roundToInt())
                    }
                    .size(width, metrics.chipHeight)
                    .shadow(4.dp, RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.surface),
                propagateMinConstraints = true,
            ) {
                DayChipLine(d.chip, opens = true, closes = true, style = chipStyle())
            }
        }
    }
}

// A week of the maximised month: the seven day cells, and over them each day's entries, one line each, as
// CalendarChips.weekLines laid them out ([lines]). An event on more than one day is one bar across its days (user,
// 14 Oct), cut at the end of the row and carried on in the next. The entries are hidden from TalkBack, which reads them
// from each day's cell, and a tap on one goes through to the day under it. Too short for even one line (landscape),
// each day falls back to the dot. The entry being dragged ([lifted]) is faint where it was.
@Composable
private fun MaximisedWeekRow(
    week: List<LocalDate?>,
    lines: WeekLines,
    metrics: ChipMetrics,
    selected: LocalDate,
    today: LocalDate,
    onSelect: (LocalDate) -> Unit,
    chips: Map<LocalDate, List<DayChip>>,
    lifted: String?,
) {
    val colors = MaterialTheme.colorScheme
    val chipStyle = chipStyle()
    val (rowHeight, numberSize, chipHeight, slots) = metrics
    Box(Modifier.fillMaxWidth().height(rowHeight)) {
        Row(Modifier.fillMaxWidth()) {
            for (i in 0 until 7) {
                val date = week.getOrNull(i)
                if (date == null) {
                    Spacer(Modifier.weight(1f).height(rowHeight))
                } else {
                    MaximisedDayCell(
                        date = date,
                        isSelected = date == selected,
                        isToday = date == today,
                        chips = chips[date].orEmpty(),
                        dot = slots <= 0,
                        numberSize = numberSize,
                        onClick = { onSelect(date) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        val moreCols = lines.more.indices.filter { lines.more[it] > 0 }
        Layout(
            content = {
                lines.pieces.forEach { DayChipLine(it.chip, it.opens, it.closes, chipStyle, lifted = it.chip.key == lifted) }
                moreCols.forEach { col ->
                    Text("+${lines.more[col]}", style = chipStyle, color = colors.onSurfaceVariant, maxLines = 1, textAlign = TextAlign.Center)
                }
            },
            modifier = Modifier.matchParentSize().clearAndSetSemantics { },
        ) { measurables, constraints ->
            val cell = constraints.maxWidth / 7f
            val inset = 1.dp.roundToPx()
            val height = chipHeight.roundToPx()
            fun lineTop(line: Int) = metrics.lineTop(line).roundToPx()
            // A piece reaches the edge of its cell where the entry carries on, so the bar runs on without a gap.
            val placed = measurables.mapIndexed { i, m ->
                val piece = lines.pieces.getOrNull(i)
                val (start, end, line) = if (piece != null) Triple(piece.start, piece.end, piece.line)
                    else moreCols[i - lines.pieces.size].let { Triple(it, it, lines.moreLine) }
                val left = (start * cell).roundToInt() + if (piece?.opens == false) 0 else inset
                val right = ((end + 1) * cell).roundToInt() - if (piece?.closes == false) 0 else inset
                Triple(m.measure(Constraints.fixed((right - left).coerceAtLeast(0), height)), left, lineTop(line))
            }
            layout(constraints.maxWidth, constraints.maxHeight) {
                placed.forEach { (p, x, y) -> p.placeRelative(x, y) }
            }
        }
    }
}

// A day of the maximised month: its number at the top (the entries are drawn over the week by MaximisedWeekRow), or the
// number with a [dot] when there's no room for entries.
@Composable
private fun MaximisedDayCell(
    date: LocalDate,
    isSelected: Boolean,
    isToday: Boolean,
    chips: List<DayChip>,
    dot: Boolean,
    numberSize: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val rowHeight = LocalCalendarRowHeight.current
    Column(
        modifier
            .height(rowHeight)
            .semantics {
                contentDescription = dayCellDescription(date, isToday, chips.isNotEmpty(), locale, chips.map { it.title })
                selected = isSelected
            }
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 1.dp, vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(numberSize)
                .clip(CircleShape)
                .background(if (isSelected) colors.primary else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            if (isToday && !isSelected) TodayRing(colors.primary, Modifier.matchParentSize())
            Text("${date.dayOfMonth}", style = MaterialTheme.typography.bodyMedium, color = if (isSelected) colors.onPrimary else colors.onSurface)
            if (dot && chips.isNotEmpty()) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 1.dp)
                        .size(4.dp)
                        .background(if (isSelected) colors.onPrimary else colors.primary, CircleShape),
                )
            }
        }
    }
}

// One entry in a maximised week (sized by MaximisedWeekRow): an event or bill tinted with its colour, a task outlined
// with a box in front. A bar has rounded ends only where the entry [opens] and [closes], square where it carries on;
// faint while it is being dragged elsewhere ([lifted]).
@Composable
private fun DayChipLine(chip: DayChip, opens: Boolean, closes: Boolean, style: TextStyle, lifted: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    val start = if (opens) 3.dp else 0.dp
    val end = if (closes) 3.dp else 0.dp
    val shape = RoundedCornerShape(topStart = start, bottomStart = start, topEnd = end, bottomEnd = end)
    val accent = if (chip.task) colors.secondary else chip.customColor?.let { Color(it) } ?: planColor(chip.colorIndex).color
    Box(
        Modifier
            .alpha((if (chip.faded) 0.45f else 1f) * (if (lifted) 0.3f else 1f))
            .clip(shape)
            .then(if (chip.task) Modifier.border(1.dp, accent, shape) else Modifier.background(accent.copy(alpha = 0.3f)))
            .padding(horizontal = 2.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            (if (chip.task) "☐ " else "") + chip.title,
            style = style,
            color = colors.onSurface,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
        )
    }
}

@Composable
private fun DayCell(
    date: LocalDate,
    isSelected: Boolean,
    isToday: Boolean,
    hasItems: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val textColor = when {
        isSelected -> colors.onPrimary
        else -> colors.onSurface
    }

    // AG-3: the number alone tells TalkBack nothing; it reads the full date, today and the dot, and the selection.
    // Worked out only when the semantics are read (TalkBack, tests), not on every composition of the cell (UI-7), in the
    // phone's language now (Planner handles a language change itself, so the cell redraws with it — L5-3).
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    Box(
        modifier
            .height(LocalCalendarRowHeight.current)
            .semantics { contentDescription = dayCellDescription(date, isToday, hasItems, locale); selected = isSelected }
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(if (isSelected) colors.primary else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            if (isToday && !isSelected) TodayRing(colors.primary, Modifier.matchParentSize())
            Text("${date.dayOfMonth}", style = MaterialTheme.typography.bodyLarge, color = textColor)
            if (hasItems) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 2.dp)
                        .size(4.dp)
                        .background(if (isSelected) colors.onPrimary else colors.primary, CircleShape),
                )
            }
        }
    }
}

// What TalkBack reads for a day of the month grid, e.g. "Monday 5 October 2026, today, has entries" (the dot is events,
// bills or tasks alike). Maximised, the day names its [entries]: "..., 2 entries: Dentist, Pay rent".
internal fun dayCellDescription(date: LocalDate, isToday: Boolean, hasItems: Boolean, locale: Locale = Locale.getDefault(),
                                entries: List<String>? = null): String =
    listOfNotNull(date.format(patternFormatter("EEEE d MMMM yyyy", locale)),
        "today".takeIf { isToday },
        if (!entries.isNullOrEmpty()) "${entries.size} ${if (entries.size == 1) "entry" else "entries"}: ${entries.joinToString(", ")}"
        else "has entries".takeIf { hasItems }).joinToString(", ")

// Today's ring fades between full strength and faint so it catches the eye. The alpha is read only while drawing,
// so the pulse redraws this ring each frame without recomposing the calendar. It runs faint -> full because, with
// system animations switched off, Compose parks the animation on its target value: the ring then stays solid.
@Composable
private fun TodayRing(color: Color, modifier: Modifier = Modifier) {
    val alpha by rememberInfiniteTransition(label = "todayPulse").animateFloat(
        initialValue = 0.2f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 1000), RepeatMode.Reverse),
        label = "todayRingAlpha",
    )
    Box(
        modifier.drawBehind {
            val width = 2.dp.toPx()
            drawCircle(color.copy(alpha = alpha), radius = (size.minDimension - width) / 2, style = Stroke(width))
        },
    )
}
