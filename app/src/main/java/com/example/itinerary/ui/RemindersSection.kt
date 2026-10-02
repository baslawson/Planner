package com.example.itinerary.ui
import com.example.itinerary.ui.MatrixIconButton as IconButton
import com.example.itinerary.ui.MatrixAssistChip as AssistChip
import com.example.itinerary.ui.MatrixFilterChip as FilterChip
import com.example.itinerary.ui.MatrixOutlinedButton as OutlinedButton

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

    val scheduler = (context.applicationContext as com.example.itinerary.ItineraryApp).reminderScheduler
    var exactAllowed by remember { mutableStateOf(scheduler.canScheduleExact()) }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) { exactAllowed = scheduler.canScheduleExact() }

    val hint = if (eventTime == null) "${if (billTask) "Without a due time, reminders count back from" else "All-day reminders count back from"} ${java.time.LocalTime.of(9, 0).label(LocalTimeFormat.current, context)}." else null
    ReminderSectionFrame(notificationsOn, onEnableNotifications, listOfNotNull(hint, exactAlarmHint(exactAllowed, reminders)),
        chips = PRESETS.map { preset -> preset.text to { onAdd(preset.amount, preset.unit) } } + ("Custom" to { customOpen = true })) {
        reminders.forEach { reminder ->
            val trigger = com.example.itinerary.data.reminderTrigger(eventDate, eventTime, reminder.offsetMinutes, zone)
            ReminderRow(reminder.label, "${trigger.toLocalDate().dayLabel(LocalDateFormat.current)} · ${trigger.toLocalTime().label(LocalTimeFormat.current, context)} · ${zone.id}",
                onRemove = { onRemove(reminder) }) {
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
    }

    if (customOpen) {
        CustomReminderDialog(
            onDismiss = { customOpen = false },
            onConfirm = { amount, unit -> onAdd(amount, unit); customOpen = false },
        )
    }
}

const val LATE_REMINDER_HINT = "Android may deliver this reminder late. Enable Alarms & reminders in app settings for precise timing."

/**
 * The warning under [reminders] while exact alarms are off (the default from Android 14): they may come late, and one set
 * to "Ring until I stop it" can't ring at all (Android starts the ringing alarm only from an exact alarm), so it says so.
 */
internal fun exactAlarmHint(exactAllowed: Boolean, reminders: List<Reminder>): String? = when {
    exactAllowed || reminders.isEmpty() -> null
    reminders.any { it.ringUntilDismissed } -> "Exact alarms are off: reminders may come late, and \"Ring until I stop it\" can't " +
        "ring, only notify. Enable Alarms & reminders in app settings so it can ring."
    else -> LATE_REMINDER_HINT
}

/**
 * The layout every reminders section in the app follows (events, bills, time blocks and tasks): a divider, the
 * "Reminders" heading, a warning with "Turn on" while notifications are off, hint lines, one row per reminder, and a
 * row of chips to add one.
 */
@Composable
fun ReminderSectionFrame(
    notificationsOn: Boolean,
    onEnableNotifications: () -> Unit,
    hints: List<String>,
    chips: List<Pair<String, () -> Unit>>,
    enabled: Boolean = true,
    rows: @Composable () -> Unit,
) {
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
        hints.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        rows()
        if (chips.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            chips.forEach { (text, onClick) -> AssistChip(onClick = onClick, enabled = enabled, label = { Text(text) }) }
        }
    }
}

/** One reminder in a [ReminderSectionFrame]: its label with a remove button, when it fires, and anything [extra]. */
@Composable
fun ReminderRow(label: String, detail: String?, onRemove: () -> Unit, enabled: Boolean = true, extra: @Composable () -> Unit = {}) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            IconButton(onClick = onRemove, enabled = enabled) {
                Icon(Icons.Filled.Close, contentDescription = "Remove reminder: $label")
            }
        }
        if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        extra()
    }
}

@Composable
private fun CustomReminderDialog(onDismiss: () -> Unit, onConfirm: (Int, ReminderUnit) -> Unit) {
    var amountText by remember { mutableStateOf("1") }
    var unit by remember { mutableStateOf(ReminderUnit.HOURS) }
    val amount = amountText.toIntOrNull()

    PlannerDialog("Custom reminder",
        onDismissRequest = onDismiss,
        primary = DialogAction("Add reminder", enabled = amount != null && amount >= 1) { onConfirm(amount!!, unit) },
        dismiss = DialogAction("Cancel", onClick = onDismiss),
    ) {
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
    }
}
