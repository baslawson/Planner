package com.example.itinerary.ui
import com.example.itinerary.ui.MatrixOutlinedButton as OutlinedButton
import com.example.itinerary.ui.MatrixButton as Button

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.example.itinerary.data.PlanColors
import com.example.itinerary.data.Trip

// The New plan / Edit plan dialog; also opened from the pencil button on the plan screen.
@Composable
fun TripEditDialog(initial: Trip?, usedColors: List<Int>, onDismiss: () -> Unit, onSave: (Trip) -> Unit) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    // A new plan starts on the colour the fewest plans use; the user can pick another.
    var colorIndex by remember { mutableStateOf(initial?.colorIndex ?: PlanColors.next(usedColors)) }
    // A colour picked freely instead of one of the palette swatches (null = use the palette one).
    var customColor by remember { mutableStateOf(initial?.customColor) }
    var pickingColor by remember { mutableStateOf(false) }
    var destination by remember { mutableStateOf(initial?.destination ?: "") }
    // A new plan has no dates until the user picks them (the button says "Pick dates" and Save waits for them).
    var start by remember { mutableStateOf(initial?.startDate) }
    var end by remember { mutableStateOf(initial?.endDate) }
    var pickingDates by remember { mutableStateOf(false) }

    // Darker than the standard dialog colour on the dark theme, with a thin outline so its edges still show against
    // the dimmed screen behind it. The light theme keeps the standard look.
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = if (dark) MaterialTheme.colorScheme.surfaceContainerLow else AlertDialogDefaults.containerColor,
        modifier = if (dark) {
            Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, AlertDialogDefaults.shape)
        } else {
            Modifier
        },
        title = { HeadingText(if (initial == null) "New plan" else "Edit plan") },
        text = {
            // Scrolls so a long, wrapped description can't push the buttons off a small screen.
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Both boxes grow as the text wraps. Done closes the keyboard instead of adding a line break.
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.replace('\n', ' ') },
                    label = { Text("Plan name") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = destination,
                    onValueChange = { destination = it.replace('\n', ' ') },
                    label = { Text("Description") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(onClick = { pickingDates = true }, modifier = Modifier.fillMaxWidth()) {
                    val s = start
                    val e = end
                    Text(if (s != null && e != null) "${s.shortLabel()} to ${e.shortLabel()}" else "Pick dates")
                }
                HeadingText("Colour", style = MaterialTheme.typography.titleSmall)
                PlanColorPicker(
                    selected = colorIndex,
                    onSelect = { colorIndex = it; customColor = null },
                    custom = customColor?.let { Color(it) },
                    onPickCustom = { pickingColor = true },
                )
            }
        },
        confirmButton = {
            Button(
                enabled = name.isNotBlank() && start != null && end != null,
                onClick = {
                    val s = start
                    val e = end
                    if (s != null && e != null) {
                        val base = initial ?: Trip(name = "", destination = "", startDate = s, endDate = e)
                        onSave(
                            base.copy(
                                name = name.trim(),
                                destination = destination.trim(),
                                startDate = s,
                                endDate = e,
                                colorIndex = colorIndex,
                                customColor = customColor,
                            ),
                        )
                    }
                },
            ) { Text("Save plan") }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("Cancel") } },
    )

    if (pickingColor) {
        CustomColorDialog(
            // Starts from what the plan is showing now.
            initial = customColor?.let { Color(it) } ?: planColor(colorIndex).color,
            onDismiss = { pickingColor = false },
            onConfirm = { customColor = it.toArgb(); pickingColor = false },
        )
    }
    if (pickingDates) {
        DateRangeDialog(
            start = start,
            end = end,
            onDismiss = { pickingDates = false },
            onConfirm = { s, e -> start = s; end = e; pickingDates = false },
        )
    }
}
