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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle as NameStyle
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * A plain month grid. The selected day gets a filled circle,
 * today a pulsing ring, and days that already have events a small dot.
 * Swipe up or down on the grid (or use the arrows) to move between months.
 * When [collapsed] it shrinks to the selected day's week, and the arrows move a week at a time.
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
) = CompositionLocalProvider(LocalCalendarRowHeight provides rowHeight) {
    val firstDow = remember { WeekFields.of(Locale.getDefault()).firstDayOfWeek }
    val today = rememberCurrentDate()

    // Seven fixed columns: past the limit, two-digit days and weekday names no longer fit.
    LimitTextScale { Column(modifier.padding(horizontal = 8.dp).animateContentSize()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = {
                    if (collapsed) onSelect(selected.minusWeeks(1)) else onMonthChange(month.minusMonths(1))
                },
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
            WeekRow(
                week = week,
                selected = selected,
                today = today,
                datesWithItems = datesWithItems,
                onSelect = onSelect,
            )
        } else {
            MonthPager(
                month = month,
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

// Months are pages of a vertical pager: swipe up for the next month, down for the previous one.
// Page numbers count months from January 1900, which is far more range than a trip planner needs.
private val PAGER_EPOCH: YearMonth = YearMonth.of(1900, 1)
private val LocalCalendarRowHeight = compositionLocalOf { 48.dp }
private const val PAGER_MONTHS = 12 * 400

private fun pageOf(month: YearMonth): Int = ChronoUnit.MONTHS.between(PAGER_EPOCH, month).toInt()

private fun monthOfPage(page: Int): YearMonth = PAGER_EPOCH.plusMonths(page.toLong())

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
) {
    val pagerState = rememberPagerState(initialPage = pageOf(month)) { PAGER_MONTHS }
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
    // ...and when the month is changed some other way (arrows, Today) the pager follows, and ends snapped on it.
    LaunchedEffect(month) {
        val target = pageOf(month)
        try {
            if (pagerState.currentPage != target || pagerState.currentPageOffsetFraction != 0f) {
                request.target = target
                pagerState.animateScrollToPage(target)
            }
        } finally {
            // Done, or stopped by a swipe (whose page then counts); left alone when a newer month has set its own target.
            if (request.target == target) request.target = null
        }
    }

    // The pager is as tall as the current month needs (4 to 6 weeks), so short months don't leave a gap.
    val height by animateDpAsState(LocalCalendarRowHeight.current * weeksIn(month, firstDow), label = "monthHeight")
    VerticalPager(
        state = pagerState,
        modifier = Modifier.fillMaxWidth().height(height),
        // One month per swipe, however hard the flick.
        flingBehavior = PagerDefaults.flingBehavior(state = pagerState, pagerSnapDistance = PagerSnapDistance.atMost(1)),
    ) { page ->
        val weeks = remember(page, firstDow) { monthCells(monthOfPage(page), firstDow).chunked(7) }
        Column {
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

// Sits on the line under the calendar: a labelled teal pill that collapses it to a week or opens the full month.
// The whole strip is tappable, which makes it an easy target. A saffron "Go to today" pill sits at the right end
// while there is somewhere to jump back from. The two pills are measured before they are placed, so large text
// cannot make them overlap: the teal pill stays centred while there is room, slides left to make room when there
// isn't, and if even that is not enough the "Go to today" pill drops to its own row below, still on the right.
@Composable
fun CalendarToggleBar(
    collapsed: Boolean,
    showToday: Boolean,
    onToday: () -> Unit,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Layout(
        modifier = modifier
            .fillMaxWidth()
            .clickable(
                role = Role.Button,
                onClickLabel = if (collapsed) "Show full month" else "Show week only",
                onClick = onToggle,
            )
            .padding(vertical = 8.dp),
        content = {
            HorizontalDivider(color = colors.outlineVariant)
            Row(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(colors.primaryContainer)
                    .padding(horizontal = 14.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (collapsed) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowUp,
                    contentDescription = null,
                    tint = colors.onPrimaryContainer,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    if (collapsed) "Full month" else "Collapse",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = colors.onPrimaryContainer,
                )
            }
            if (showToday) {
                Text(
                    "Go to today",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = colors.onSecondaryContainer,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(colors.secondaryContainer)
                        .clickable(role = Role.Button, onClickLabel = "Go to today", onClick = onToday)
                        .padding(horizontal = 12.dp, vertical = 4.dp),
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
            toggle.place(toggleX, (lineHeight - toggle.height) / 2)
            today?.place(todayX, if (wrapped) lineHeight + gap else (lineHeight - today.height) / 2)
        }
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

    Box(
        modifier
            .height(LocalCalendarRowHeight.current)
            .clickable(onClick = onClick)
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
