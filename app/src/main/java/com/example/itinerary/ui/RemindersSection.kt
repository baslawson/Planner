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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
    onChange: (Reminder, Int, ReminderUnit) -> Unit,
    billTask: Boolean = false,
) {
    // Bug hunt 19: kept through a rotation, so a date picked survives while the clock is open.
    var customOpen by rememberSaveable { mutableStateOf(false) }
    var pickingDate by rememberSaveable { mutableStateOf(false) }
    var pickingTime by rememberSaveable { mutableStateOf(false) }
    var pickedDay by rememberSaveable { mutableStateOf<String?>(null) }
    val pickedDate = pickedDay?.let(java.time.LocalDate::parse)
    val context = androidx.compose.ui.platform.LocalContext.current
    // The reminder being changed (its "Change ▾"), or null while adding one.
    var changingKey by rememberSaveable { mutableStateOf<String?>(null) }
    val changing = changingKey?.let { key -> reminders.firstOrNull { it.changeKey == key } }
    // Hunt 22 P4: a reminder gone meanwhile (deleted or changed elsewhere while its dialog was open) isn't added instead.
    fun choose(amount: Int, unit: ReminderUnit) {
        if (changingKey == null) onAdd(amount, unit)
        else changing?.let { onChange(it, amount, unit) }
            ?: android.widget.Toast.makeText(context, "That reminder was changed meanwhile.", android.widget.Toast.LENGTH_SHORT).show()
        changingKey = null
    }
    // The ways to add a reminder, or to change [reminder] to another time.
    fun choices(reminder: Reminder?): List<Pair<String, () -> Unit>> {
        val key = reminder?.changeKey
        return PRESETS.map { preset -> preset.text to {
                if (reminder != null) onChange(reminder, preset.amount, preset.unit) else onAdd(preset.amount, preset.unit) } } +
            ("Custom…" to { changingKey = key; customOpen = true }) +
            ("Pick date and time…" to { changingKey = key; pickedDay = null; pickingDate = true })
    }
    val zone = rememberCurrentZoneId()

    val scheduler = (context.applicationContext as com.example.itinerary.ItineraryApp).reminderScheduler
    val exactAllowed by rememberExactAlarmsAllowed(scheduler)
    val defaultSound = rememberDefaultReminderSound()

    val hint = if (eventTime == null) "${if (billTask) "Without a due time, reminders count back from" else "All-day reminders count back from"} ${java.time.LocalTime.of(9, 0).label(LocalTimeFormat.current, context)}." else null
    ReminderSectionFrame(notificationsOn, onEnableNotifications, listOfNotNull(hint, exactAlarmHint(exactAllowed, reminders, defaultSound)),
        chips = choices(null)) {
        reminders.forEach { reminder ->
            val trigger = com.example.itinerary.data.reminderTrigger(eventDate, eventTime, reminder, zone)
            ReminderRow(reminder.label, "${trigger.toLocalDate().dayLabel(LocalDateFormat.current)} · ${trigger.toLocalTime().label(LocalTimeFormat.current, context)}",
                onRemove = { onRemove(reminder) }, changes = choices(reminder)) {
                ReminderSoundChoice(reminder.sound, defaultSound) { onSound(reminder, it) }
            }
        }
    }

    if (customOpen) {
        CustomReminderDialog(
            initialAmount = changing?.amount?.takeIf { it >= 1 } ?: 1, initialUnit = changing?.unit ?: ReminderUnit.HOURS,
            confirm = if (changingKey != null) "Change reminder" else "Add reminder",
            onDismiss = { customOpen = false; changingKey = null },
            onConfirm = { amount, unit -> choose(amount, unit); customOpen = false },
        )
    }
    // Bug notes 1: a reminder at a date and time, from the calendar then the clock (as tasks and notes set theirs). It's kept
    // as the time before the event (reminderAt), so it moves with the event. Changing one starts from when it fires now.
    val changingAt = changing?.let { com.example.itinerary.data.reminderTrigger(eventDate, eventTime, it, zone).toLocalDateTime() }
    val eventStartTime = changingAt?.toLocalTime() ?: eventTime ?: java.time.LocalTime.of(9, 0)
    if (pickingDate) SingleDateDialog(pickedDate ?: changingAt?.toLocalDate() ?: eventDate, onDismiss = { pickingDate = false; changingKey = null },
        onConfirm = { pickedDay = it.toString(); pickingDate = false; pickingTime = true })
    if (pickingTime) TimePickerDialog(eventStartTime, onDismiss = { pickingTime = false; changingKey = null }, onConfirm = { time ->
        pickingTime = false
        val offset = com.example.itinerary.data.reminderAt(eventDate, eventTime, pickedDate!!.atTime(time), zone)
        if (offset == null) { android.widget.Toast.makeText(context, "Choose a time before the event starts.", android.widget.Toast.LENGTH_LONG).show(); changingKey = null }
        else choose(offset.first, offset.second)
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
 * A reminder's sound (bugnotes 7 Oct, in place of the "Ring until I stop it" switch): "🔔 Default (10 s) ▾" (read out as
 * "Sound: …"), whose list is Default, the notification sound only, 10 s, 30 s, 1 min or until stopped ([ReminderSound]).
 */
@Composable
fun ReminderSoundChoice(sound: ReminderSound, default: ReminderSound, enabled: Boolean = true, onChange: (ReminderSound) -> Unit) {
    DropdownChoice(sound.choiceLabel(default), ReminderSound.entries.map { choice ->
        choice.choiceLabel(default) to { onChange(choice) } }, enabled, quiet = true, icon = Icons.Filled.Notifications,
        description = "Sound: ${sound.choiceLabel(default)}")
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
        // Bug notes 2: the ways to add one are a dropdown list, not a row of chips that scrolls sideways. A full-width
        // outlined button like the other actions in the editors (user, 10 Oct: as plain text it was small and easy to miss).
        if (chips.isNotEmpty()) DropdownChoice("Add reminder", chips, enabled, icon = Icons.Filled.Notifications, arrow = false, fill = true)
    }
}

/**
 * An outlined "[label] ▾" button whose list runs one of [choices] (its own text and action). [quiet]: a plain text button
 * instead (inside a reminder's card), with [icon] before the label, no ▾ without [arrow], and [description] for TalkBack.
 * [fill]: the button takes the full width.
 */
@Composable
internal fun DropdownChoice(label: String, choices: List<Pair<String, () -> Unit>>, enabled: Boolean = true, quiet: Boolean = false,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null, arrow: Boolean = true, description: String? = null, fill: Boolean = false) {
    var open by remember { mutableStateOf(false) }
    androidx.compose.foundation.layout.Box(if (fill) Modifier.fillMaxWidth() else Modifier) {
        val described = (if (fill) Modifier.fillMaxWidth() else Modifier)
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier)
        val content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
            if (icon != null) { Icon(icon, contentDescription = null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)) }
            Text(label)
            if (arrow) Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        if (quiet) MatrixQuietButton(onClick = { open = true }, modifier = described, enabled = enabled, content = content)
        else OutlinedButton(onClick = { open = true }, modifier = described, enabled = enabled, content = content)
        androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            choices.forEach { (text, onClick) ->
                androidx.compose.material3.DropdownMenuItem(text = { Text(text) }, onClick = { open = false; onClick() })
            }
        }
    }
}

// Which reminder "Change ▾" was opened for, kept through a rotation (one not yet saved has no id).
private val Reminder.changeKey get() = "$amount ${unit.name}"

/**
 * One reminder in a [ReminderSectionFrame], as a card (user, 7 Oct: the buttons looked messy): its label, which opens
 * [changes] ("Change reminder: …", the same choices as "Add reminder", so its time changes without removing it and
 * losing its sound), a remove button, when it fires, and anything [extra] (its sound).
 */
@Composable
fun ReminderRow(label: String, detail: String?, onRemove: () -> Unit, enabled: Boolean = true,
    changes: List<Pair<String, () -> Unit>> = emptyList(), extra: @Composable () -> Unit = {}) {
    androidx.compose.material3.Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        // The text lines up with the text buttons' (12 dp in).
        Column(Modifier.padding(start = 4.dp, top = 2.dp, bottom = 4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.foundation.layout.Box(Modifier.weight(1f)) {
                    if (changes.isNotEmpty()) DropdownChoice(label, changes, enabled, quiet = true, description = "Change reminder: $label")
                    else Text(label, Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodyMedium)
                }
                IconButton(onClick = onRemove, enabled = enabled) {
                    Icon(Icons.Filled.Close, contentDescription = "Remove reminder: $label")
                }
            }
            if (detail != null) Text(detail, Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            extra()
        }
    }
}

@Composable
private fun CustomReminderDialog(initialAmount: Int, initialUnit: ReminderUnit, confirm: String, onDismiss: () -> Unit, onConfirm: (Int, ReminderUnit) -> Unit) {
    // Hunt 22 P5: kept through a rotation, as the dialog is (customOpen).
    var amountText by rememberSaveable { mutableStateOf(initialAmount.toString()) }
    var unit by rememberSaveable { mutableStateOf(initialUnit) }
    val amount = amountText.toIntOrNull()

    PlannerDialog("Custom reminder",
        onDismissRequest = onDismiss,
        primary = DialogAction(confirm, enabled = amount != null && amount >= 1) { onConfirm(amount!!, unit) },
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
