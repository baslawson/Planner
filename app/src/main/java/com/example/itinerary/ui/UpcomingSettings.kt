package com.example.itinerary.ui
import com.example.itinerary.ui.MatrixFilterChip as FilterChip
import com.example.itinerary.ui.MatrixOutlinedButton as OutlinedButton
import com.example.itinerary.ui.MatrixButton as Button

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.itinerary.data.UpcomingPlans

private fun presetLabel(days: Int): String = when (days) {
    7 -> "1 week"
    14 -> "2 weeks"
    else -> "$days days"
}

// The "Upcoming plans on top" part of the Settings dialog: a switch, and (while it is on) the time frame.
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UpcomingSettingsSection(enabled: Boolean, onEnabled: (Boolean) -> Unit, days: Int, onDays: (Int) -> Unit) {
    var customOpen by remember { mutableStateOf(false) }

    SettingsHeading("Upcoming plans on top")
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = enabled, role = Role.Switch, onValueChange = onEnabled)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "On Manage plans, show plans that start soon, or are already under way, first. The agenda always stays in date order.",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.width(12.dp))
        Switch(checked = enabled, onCheckedChange = null)
    }
    if (enabled) {
        Text(
            "You can still drag plans into any order. Automatic ordering resumes 4 seconds after you drop a plan.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Time frame: plans starting within the next $days ${if (days == 1) "day" else "days"}.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            UpcomingPlans.PRESET_DAYS.forEach { preset ->
                FilterChip(selected = days == preset, onClick = { onDays(preset) }, label = { Text(presetLabel(preset)) })
            }
            val custom = days !in UpcomingPlans.PRESET_DAYS
            FilterChip(
                selected = custom,
                onClick = { customOpen = true },
                label = { Text(if (custom) "Custom: $days ${if (days == 1) "day" else "days"}" else "Custom…") },
            )
        }
    }

    if (customOpen) {
        DaysDialog(initial = days, onDismiss = { customOpen = false }, onConfirm = { onDays(it); customOpen = false })
    }
}

// Asks for a number of days from MIN_DAYS to MAX_DAYS.
@Composable
private fun DaysDialog(initial: Int, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial.toString()) }
    val value = text.toIntOrNull()
    val valid = value != null && value in UpcomingPlans.MIN_DAYS..UpcomingPlans.MAX_DAYS
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { HeadingText("Time frame") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { input -> text = input.filter(Char::isDigit).take(3) },
                label = { Text("Number of days") },
                singleLine = true,
                isError = text.isNotEmpty() && !valid,
                supportingText = { Text("From ${UpcomingPlans.MIN_DAYS} to ${UpcomingPlans.MAX_DAYS}") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
            )
        },
        confirmButton = { Button(enabled = valid, onClick = { onConfirm(value!!) }) { Text("Save") } },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
