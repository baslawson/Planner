package com.example.itinerary.ui
import com.example.itinerary.ui.MatrixTextButton as TextButton

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import java.time.LocalDate
import java.time.LocalTime

// Preserve imported and quick-entry dates outside Material's default 1900–2100 range.
internal fun pickerYears(vararg dates: LocalDate?): IntRange =
    minOf(1900, dates.filterNotNull().minOfOrNull { it.year } ?: 1900)..
        maxOf(2100, dates.filterNotNull().maxOfOrNull { it.year } ?: 2100)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateRangeDialog(
    // Null when no dates have been chosen yet (a new plan): the picker then opens with nothing selected.
    start: LocalDate?,
    end: LocalDate?,
    onDismiss: () -> Unit,
    onConfirm: (LocalDate, LocalDate) -> Unit,
) {
    val state = rememberDateRangePickerState(
        initialSelectedStartDateMillis = start?.toPickerMillis(),
        initialSelectedEndDateMillis = end?.toPickerMillis(),
        yearRange = pickerYears(start, end),
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = state.selectedStartDateMillis != null,
                onClick = {
                    val s = state.selectedStartDateMillis!!.toPickerDate()
                    val e = state.selectedEndDateMillis?.toPickerDate() ?: s
                    onConfirm(s, e)
                },
            ) { Text("Set dates") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) {
        DateRangePicker(
            state = state,
            modifier = Modifier.height(500.dp),
            // The built-in heading writes both dates out in full at a large size, which wraps badly in
            // monospace, so it is replaced with a compact one-line heading.
            title = { PickerTitle("Select dates") },
            headline = { DateHeadline(rangeHeadline(state.selectedStartDateMillis, state.selectedEndDateMillis)) },
            // The pencil that switches to typing dates would take space from the heading.
            showModeToggle = false,
        )
    }
}

@Composable
private fun PickerTitle(text: String) {
    Text(
        text,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 4.dp),
        style = MaterialTheme.typography.labelLarge,
    )
}

// The chosen date(s) as a saffron pill, matching the date in the event editor, so they stand out.
@Composable
private fun DateHeadline(text: String) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
        Text(
            text,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            maxLines = 2,
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.secondaryContainer)
                .padding(horizontal = 16.dp, vertical = 6.dp),
        )
    }
}

private fun rangeHeadline(startMillis: Long?, endMillis: Long?): String {
    val start = startMillis?.toPickerDate() ?: return "Pick your dates"
    val end = endMillis?.toPickerDate()
    return when {
        end == null || end == start -> start.fullLabel()
        start.year == end.year -> "${start.shortLabel()} – ${end.fullLabel()}"
        else -> "${start.fullLabel()} – ${end.fullLabel()}"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SingleDateDialog(
    initial: LocalDate,
    onDismiss: () -> Unit,
    onConfirm: (LocalDate) -> Unit,
) {
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initial.toPickerMillis(),
        yearRange = pickerYears(initial),
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = state.selectedDateMillis != null,
                onClick = { onConfirm(state.selectedDateMillis!!.toPickerDate()) },
            ) { Text("Set date") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) {
        DatePicker(
            state = state,
            title = { PickerTitle("Select date") },
            headline = { DateHeadline(state.selectedDateMillis?.toPickerDate()?.fullLabel() ?: "Pick a date") },
            showModeToggle = false,
        )
    }
}

// Material3 has no ready-made time picker dialog, and TimePicker is too wide for AlertDialog on narrow phones.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimePickerDialog(
    initial: LocalTime,
    onDismiss: () -> Unit,
    onConfirm: (LocalTime) -> Unit,
) {
    val is24Hour = LocalTimeFormat.current.is24Hour(LocalContext.current)
    val state = rememberTimePickerState(
        initialHour = initial.hour,
        initialMinute = initial.minute,
        is24Hour = is24Hour,
    )
    // Material3 remembers the initial mode. Keep an open picker in sync after returning
    // from system settings, without resetting the hour or minute the user has chosen.
    state.is24hour = is24Hour
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp) {
            Column(Modifier.padding(24.dp)) {
                Text("Choose a time", style = MaterialTheme.typography.titleLarge)
                Column(Modifier.padding(vertical = 20.dp)) {
                    key(is24Hour) { TimePicker(state = state) }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    TextButton(onClick = { onConfirm(LocalTime.of(state.hour, state.minute)) }) {
                        Text("Set time")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EndTimeDialog(start: LocalTime, initial: LocalTime, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    val is24Hour = LocalTimeFormat.current.is24Hour(LocalContext.current)
    val state = rememberTimePickerState(initialHour = initial.hour, initialMinute = initial.minute, is24Hour = is24Hour)
    state.is24hour = is24Hour
    val end = LocalTime.of(state.hour, state.minute)
    val minutes = com.example.itinerary.data.durationUntilEnd(start, end)
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("End time", style = MaterialTheme.typography.titleLarge)
                key(is24Hour) { TimePicker(state = state) }
                Text("${if (end <= start) "Ends next day" else "Ends same day"} · ${durationLabel(minutes)}")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    TextButton(onClick = { onConfirm(minutes) }) { Text("Set end time") }
                }
            }
        }
    }
}
