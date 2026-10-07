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
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.itinerary.data.Reminder
import com.example.itinerary.data.ReminderSound
import com.example.itinerary.data.ReminderUnit
import com.example.itinerary.data.sound

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
    onSound: (Reminder, com.example.itinerary.data.ReminderSound) -> Unit,
    billTask: Boolean = false,
) {
    // Bug hunt 19: kept through a rotation, so a date picked survives while the clock is open.
    var customOpen by rememberSaveable { mutableStateOf(false) }
    var pickingDate by rememberSaveable { mutableStateOf(false) }
    var pickingTime by rememberSaveable { mutableStateOf(false) }
    var pickedDay by rememberSaveable { mutableStateOf<String?>(null) }
    val pickedDate = pickedDay?.let(java.time.LocalDate::parse)
    val zone = rememberCurrentZoneId()
    val context = androidx.compose.ui.platform.LocalContext.current

    val scheduler = (context.applicationContext as com.example.itinerary.ItineraryApp).reminderScheduler
    val exactAllowed by rememberExactAlarmsAllowed(scheduler)
    val defaultSound = rememberDefaultReminderSound()

    val hint = if (eventTime == null) "${if (billTask) "Without a due time, reminders count back from" else "All-day reminders count back from"} ${java.time.LocalTime.of(9, 0).label(LocalTimeFormat.current, context)}." else null
    ReminderSectionFrame(notificationsOn, onEnableNotifications, listOfNotNull(hint, exactAlarmHint(exactAllowed, reminders, defaultSound)),
        chips = PRESETS.map { preset -> preset.text to { onAdd(preset.amount, preset.unit) } } + ("Custom…" to { customOpen = true }) +
            ("Pick date and time…" to { pickedDay = null; pickingDate = true })) {
        reminders.forEach { reminder ->
            val trigger = com.example.itinerary.data.reminderTrigger(eventDate, eventTime, reminder, zone)
            ReminderRow(reminder.label, "${trigger.toLocalDate().dayLabel(LocalDateFormat.current)} · ${trigger.toLocalTime().label(LocalTimeFormat.current, context)} · ${zone.id}",
                onRemove = { onRemove(reminder) }) {
                ReminderSoundChoice(reminder.sound, defaultSound) { onSound(reminder, it) }
            }
        }
    }

    if (customOpen) {
        CustomReminderDialog(
            onDismiss = { customOpen = false },
            onConfirm = { amount, unit -> onAdd(amount, unit); customOpen = false },
        )
    }
    // Bug notes 1: a reminder at a date and time, from the calendar then the clock (as tasks and notes set theirs). It's kept
    // as the time before the event (reminderAt), so it moves with the event.
    val eventStartTime = eventTime ?: java.time.LocalTime.of(9, 0)
    if (pickingDate) SingleDateDialog(pickedDate ?: eventDate, onDismiss = { pickingDate = false },
        onConfirm = { pickedDay = it.toString(); pickingDate = false; pickingTime = true })
    if (pickingTime) TimePickerDialog(eventStartTime, onDismiss = { pickingTime = false }, onConfirm = { time ->
        pickingTime = false
        val offset = com.example.itinerary.data.reminderAt(eventDate, eventTime, pickedDate!!.atTime(time), zone)
        if (offset == null) android.widget.Toast.makeText(context, "Choose a time before the event starts.", android.widget.Toast.LENGTH_LONG).show()
        else onAdd(offset.first, offset.second)
    })
}

// Whether Android lets Planner set exact alarms. Checked again on resume, because the user grants this in system settings,
// outside the app.
@Composable
internal fun rememberExactAlarmsAllowed(scheduler: com.example.itinerary.reminders.ReminderScheduler): State<Boolean> {
    val allowed = remember { mutableStateOf(scheduler.canScheduleExact()) }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) { allowed.value = scheduler.canScheduleExact() }
    return allowed
}

const val LATE_REMINDER_HINT = "Android may deliver this reminder late. Enable Alarms & reminders in app settings for precise timing."

/**
 * The warning under [reminders] while exact alarms are off (the default from Android 14): they may come late, and one set
 * to ring (for a few seconds or until stopped, [default] for those left at Default) can't ring at all (Android starts the
 * ringing alarm only from an exact alarm), so it says so.
 */
internal fun exactAlarmHint(exactAllowed: Boolean, reminders: List<Reminder>, default: ReminderSound): String? =
    reminderAlarmHint(exactAllowed, reminders.isNotEmpty(), reminders.any { rings(it.ringUntilDismissed, it.ringSeconds, default) })

/** Whether a reminder with this choice rings (through AlarmService) rather than only notifying. */
internal fun rings(ring: Boolean, seconds: Int, default: ReminderSound): Boolean =
    ReminderSound.resolve(ring, seconds, default).alarmSeconds != null

internal fun reminderAlarmHint(exactAllowed: Boolean, hasReminder: Boolean, ring: Boolean): String? = when {
    exactAllowed || !hasReminder -> null
    ring -> "Exact alarms are off: reminders may come late, and can't ring, only notify. " +
        "Enable Alarms & reminders in app settings so they can ring."
    else -> LATE_REMINDER_HINT
}

/** Settings › Notifications › "Reminder sound", what a reminder left at Default does. */
@Composable
internal fun rememberDefaultReminderSound(): ReminderSound {
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as com.example.itinerary.ItineraryApp
    val sound by app.settings.reminderSound.collectAsStateWithLifecycle()
    return sound
}

/**
 * A reminder's sound (bugnotes 7 Oct, in place of the "Ring until I stop it" switch): "Sound: Default (10 s) ▾", whose
 * list is Default, the notification sound only, 10 s, 30 s, 1 min or until stopped ([ReminderSound]).
 */
@Composable
fun ReminderSoundChoice(sound: ReminderSound, default: ReminderSound, enabled: Boolean = true, onChange: (ReminderSound) -> Unit) {
    DropdownChoice("Sound: ${sound.choiceLabel(default)}", ReminderSound.entries.map { choice ->
        choice.choiceLabel(default) to { onChange(choice) } }, enabled)
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
        // Bug notes 2: the ways to add one are a dropdown list, not a row of chips that scrolls sideways.
        if (chips.isNotEmpty()) DropdownChoice("Add reminder", chips, enabled)
    }
}

/** An outlined "[label] ▾" button whose list runs one of [choices] (its own text and action). */
@Composable
internal fun DropdownChoice(label: String, choices: List<Pair<String, () -> Unit>>, enabled: Boolean = true) {
    var open by remember { mutableStateOf(false) }
    androidx.compose.foundation.layout.Box {
        OutlinedButton(onClick = { open = true }, enabled = enabled) {
            Text(label)
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            choices.forEach { (text, onClick) ->
                androidx.compose.material3.DropdownMenuItem(text = { Text(text) }, onClick = { open = false; onClick() })
            }
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
                DropdownChoice(unit.plural.replaceFirstChar { it.uppercase() }, ReminderUnit.entries.map { option ->
                    option.plural.replaceFirstChar { it.uppercase() } to { unit = option } })
            }
    }
}
