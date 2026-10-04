package com.example.itinerary.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged

// A text box that offers existing names as you type (a note's notebook, its tags): while it has focus, [suggestions]
// open in a list under it, and tapping one calls [onPick]. Typing something new works as in a plain box.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SuggestField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    suggestions: List<String>,
    onPick: (String) -> Unit,
    enabled: Boolean = true,
    shown: (String) -> String = { it },
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    var open by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    val expanded = open && focused && enabled && suggestions.isNotEmpty()
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { open = it }) {
        OutlinedTextField(
            value = value,
            onValueChange = { onValueChange(it); open = true },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryEditable, enabled)
                .onFocusChanged { focused = it.isFocused; if (it.isFocused) open = true },
            label = { Text(label) },
            singleLine = true,
            enabled = enabled,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            trailingIcon = trailingIcon,
        )
        // A fresh one each time it opens, so it starts at the top rather than where it was left (ED-14).
        val scroll = remember(expanded) { androidx.compose.foundation.ScrollState(0) }
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { open = false },
            modifier = Modifier.heightIn(max = MENU_MAX_HEIGHT).scrollBar(scroll, inset = 8.dp), scrollState = scroll) {
            suggestions.forEach { name ->
                DropdownMenuItem(text = { Text(shown(name)) }, onClick = { onPick(name); open = false })
            }
        }
    }
}
