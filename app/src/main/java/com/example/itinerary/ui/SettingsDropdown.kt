package com.example.itinerary.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
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

// A one-line dropdown for a Settings choice: a read-only field showing [current] with an arrow, and a list of [options]
// that opens under it. Tapping an option chooses it and closes the list. [entry] draws one option in the list, so each
// setting decides how its choices look (in their own font, with an example under them, and so on).
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> SettingsDropdown(
    label: String,
    current: String,
    options: List<T>,
    onSelect: (T) -> Unit,
    entry: @Composable (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }) {
        OutlinedTextField(
            value = current,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        val scroll = rememberScrollState()
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false },
            modifier = Modifier.heightIn(max = MENU_MAX_HEIGHT).scrollBar(scroll), scrollState = scroll) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { entry(option) },
                    onClick = { onSelect(option); open = false },
                )
            }
        }
    }
}
