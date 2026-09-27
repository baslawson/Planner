package com.example.itinerary.ui
import com.example.itinerary.ui.MatrixIconButton as IconButton
import com.example.itinerary.ui.MatrixAssistChip as AssistChip
import com.example.itinerary.ui.MatrixFilterChip as FilterChip
import com.example.itinerary.ui.MatrixOutlinedButton as OutlinedButton
import com.example.itinerary.ui.MatrixButton as Button

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.itinerary.data.Reminder
import com.example.itinerary.data.ReminderUnit

private class Preset(val amount: Int, val unit: ReminderUnit, val text: String)

private val PRESETS = listOf(
    Preset(0, ReminderUnit.MINUTES, "At the time"),
    Preset(15, ReminderUnit.MINUTES, "15 min"),
    Preset(1, ReminderUnit.HOURS, "1 hour"),
    Preset(1, ReminderUnit.DAYS, "1 day"),
)

@Composable
fun RemindersSection(
    eventDate: java.time.LocalDate,
    eventTime: java.time.LocalTime?,
    reminders: List<Reminder>,
    notificationsOn: Boolean,
    onEnableNotifications: () -> Unit,
    onAdd: (Int, ReminderUnit) -> Unit,
    onRemove: (Reminder) -> Unit,
    onToggleRing: (Reminder, Boolean) -> Unit,
    billTask: Boolean = false,
) {
    var customOpen by remember { mutableStateOf(false) }
    val zone = rememberCurrentZoneId()
    val context = androidx.compose.ui.platform.LocalContext.current

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        HeadingText(
            "Reminders",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        if (!notificationsOn) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Notifications are off, so reminders won't show.",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                OutlinedButton(onClick = onEnableNotifications) { Text("Turn on") }
            }
        }
        if (eventTime == null) Text("${if (billTask) "Without a due time, reminders count back from" else "All-day reminders count back from"} ${java.time.LocalTime.of(9, 0).label(LocalTimeFormat.current, context)}.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        reminders.forEach { reminder ->
            Column(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(reminder.label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    IconButton(onClick = { onRemove(reminder) }) {
                        Icon(Icons.Filled.Close, contentDescription = "Remove reminder: ${reminder.label}")
                    }
                }
                val trigger = com.example.itinerary.data.reminderTrigger(eventDate, eventTime, reminder.offsetMinutes, zone)
                Text("${trigger.toLocalDate().dayLabel(LocalDateFormat.current)} · ${trigger.toLocalTime().label(LocalTimeFormat.current, context)} · ${zone.id}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Ring until I stop it",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Switch(
                        checked = reminder.ringUntilDismissed,
                        onCheckedChange = { onToggleRing(reminder, it) },
                    )
                }
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PRESETS.forEach { preset ->
                AssistChip(onClick = { onAdd(preset.amount, preset.unit) }, label = { Text(preset.text) })
            }
            AssistChip(onClick = { customOpen = true }, label = { Text("Custom") })
        }
    }

    if (customOpen) {
        CustomReminderDialog(
            onDismiss = { customOpen = false },
            onConfirm = { amount, unit -> onAdd(amount, unit); customOpen = false },
        )
    }
}

@Composable
private fun CustomReminderDialog(onDismiss: () -> Unit, onConfirm: (Int, ReminderUnit) -> Unit) {
    var amountText by remember { mutableStateOf("1") }
    var unit by remember { mutableStateOf(ReminderUnit.HOURS) }
    val amount = amountText.toIntOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { HeadingText("Custom reminder") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { input -> amountText = input.filter(Char::isDigit).take(4) },
                    label = { Text("How long before") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(160.dp),
                )
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReminderUnit.entries.forEach { option ->
                        FilterChip(
                            selected = option == unit,
                            onClick = { unit = option },
                            label = { Text(option.plural) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = amount != null && amount >= 1,
                onClick = { onConfirm(amount!!, unit) },
            ) { Text("Add reminder") }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
