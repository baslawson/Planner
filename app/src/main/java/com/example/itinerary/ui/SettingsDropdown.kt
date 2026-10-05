package com.example.itinerary.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.unit.dp
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

// A dropdown for a Settings choice: a read-only field showing [current] with an arrow, and a list of [options]
// that opens under it. Tapping an option chooses it and closes the list. [entry] draws one option in the list, so each
// setting decides how its choices look (in their own font, with an example under them, and so on).
// Most settings use one line; long selections can wrap by setting [singleLine] to false.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> SettingsDropdown(
    label: String,
    current: String,
    options: List<T>,
    onSelect: (T) -> Unit,
    singleLine: Boolean = true,
    entry: @Composable (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }) {
        OutlinedTextField(
            value = current,
            onValueChange = {},
            readOnly = true,
            singleLine = singleLine,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        // Back to the top each time it opens rather than where it was left (ED-14); not as it closes, which would flick
        // the fading list (EU-5).
        val scroll = remember { androidx.compose.foundation.ScrollState(0) }
        androidx.compose.runtime.LaunchedEffect(open) { if (open) scroll.scrollTo(0) }
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false },
            modifier = Modifier.heightIn(max = MENU_MAX_HEIGHT).scrollBar(scroll, inset = 8.dp), scrollState = scroll) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { entry(option) },
                    onClick = { onSelect(option); open = false },
                )
            }
        }
    }
}
