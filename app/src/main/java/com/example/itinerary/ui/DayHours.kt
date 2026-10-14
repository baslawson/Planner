package com.example.itinerary.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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

/**
 * The Calendar's day on an hour grid (user, 14 Oct), the other way to see the day's list: timed events as blocks from
 * their start to their end, side by side where they overlap (DayTimeline), so the free time between shows. All-day and
 * multi-day events and the day's tasks sit in a strip above. Today has a line at the current time. A tap on an event or
 * task opens it as in the list; a tap on an empty hour starts a new event at that hour. It opens scrolled to the current
 * hour today, else to the first event, else to 7 am.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DayHours(
    day: LocalDate,
    today: LocalDate,
    events: List<ItineraryItem>,
    tasks: List<PlannerTask>,
    outside: Map<Long, OutsideInfo>,
    onEvent: (ItineraryItem) -> Unit,
    onTask: (PlannerTask) -> Unit,
    onEmptyHour: (LocalTime) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    val timeFormat = LocalTimeFormat.current
    val blocks = remember(events, day) { DayTimeline.blocks(events, day) }
    val allDay = remember(events) { DayTimeline.allDay(events) }
    fun color(item: ItineraryItem) = outside[item.id]?.let { Color(it.color) } ?: item.accentColor()
    fun time(minute: Int) = LocalTime.of(minute / 60 % 24, minute % 60).label(timeFormat, context)

    Column(modifier) {
        if (allDay.isNotEmpty() || tasks.isNotEmpty()) {
            FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                allDay.forEach { item ->
                    StripChip(item.title.ifBlank { "Untitled" }, color(item), task = false) { onEvent(item) }
                }
                tasks.forEach { task ->
                    StripChip(task.title.ifBlank { "Untitled task" }, colors.secondary, task = true, done = task.done) { onTask(task) }
                }
            }
        }
        // Bug hunt 28 P5: the newest, so a tap picks the new event's colour from the events as they are now.
        val currentOnEmptyHour by rememberUpdatedState(onEmptyHour)
        val scroll = rememberScrollState()
        val hourPx = with(LocalDensity.current) { HOUR_HEIGHT.toPx() }
        LaunchedEffect(day) {
            val hour = when {
                day == today -> LocalTime.now().hour - 1
                blocks.isNotEmpty() -> blocks.minOf { it.startMinute } / 60
                else -> 7
            }.coerceIn(0, 23)
            scroll.scrollTo((hour * hourPx).toInt())
        }
        Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll)) {
            BoxWithConstraints(Modifier.fillMaxWidth().height(HOUR_HEIGHT * 24 + 144.dp)) {
                val gridWidth = maxWidth - GUTTER - 12.dp
                // The hours, and an empty spot to tap for a new event at that hour.
                Column(Modifier.fillMaxWidth().pointerInput(day) {
                    detectTapGestures { at -> currentOnEmptyHour(LocalTime.of((at.y / hourPx).toInt().coerceIn(0, 23), 0)) }
                }) {
                    for (hour in 0 until 24) {
                        Row(Modifier.fillMaxWidth().height(HOUR_HEIGHT)) {
                            Text(time(hour * 60), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant,
                                modifier = Modifier.width(GUTTER).padding(start = 8.dp, top = 2.dp))
                            Column(Modifier.fillMaxHeight()) { HorizontalDivider(color = colors.outlineVariant) }
                        }
                    }
                }
                blocks.forEach { block ->
                    val top = HOUR_HEIGHT * (block.startMinute / 60f)
                    val height = HOUR_HEIGHT * ((block.endMinute - block.startMinute) / 60f)
                    val width = gridWidth / block.columns
                    val accent = color(block.item)
                    val range = "${time(block.startMinute)} to ${time(block.endMinute)}"
                    Box(
                        Modifier
                            .offset(x = GUTTER + width * block.column, y = top)
                            .width(width - 2.dp)
                            .height(height - 2.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(accent.copy(alpha = 0.25f))
                            .border(1.dp, accent.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                            .semantics { contentDescription = "$range, ${block.item.title}" }
                            .clickable(role = Role.Button) { onEvent(block.item) }
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Column {
                            Text(block.item.title.ifBlank { "Untitled" }, style = MaterialTheme.typography.labelLarge,
                                maxLines = if (height > 40.dp) 2 else 1, overflow = TextOverflow.Ellipsis)
                            if (height > 44.dp) Text(range, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, maxLines = 1)
                        }
                    }
                }
                if (day == today) {
                    val now = LocalTime.now()
                    Box(Modifier.offset(x = GUTTER - 4.dp, y = HOUR_HEIGHT * ((now.hour * 60 + now.minute) / 60f)).width(gridWidth + 4.dp).height(2.dp)
                        .background(colors.error))
                }
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
