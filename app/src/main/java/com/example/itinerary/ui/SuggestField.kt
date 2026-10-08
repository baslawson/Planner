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
import androidx.compose.ui.focus.focusRequester
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
    // False for a title that may wrap onto more lines as it grows (Done still closes the keyboard).
    singleLine: Boolean = true,
) {
    var open by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    val expanded = open && focused && enabled && suggestions.isNotEmpty()
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    // Hunt 21 S3: the text with its cursor. Typing keeps the cursor where it is; a new value from outside (a suggestion
    // picked) puts it at the end, so typing on adds to the end, not into the middle of the picked words.
    var field by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(value, androidx.compose.ui.text.TextRange(value.length))) }
    // Hunt 23: what was just typed, so a value the screen changed from it (a line break taken out, the length cap) keeps the
    // cursor where it was, shifted by what went, rather than jumping it to the end.
    var sent by remember { mutableStateOf<String?>(null) }
    if (field.text != value) {
        val cursor = if (sent != null && field.text == sent) (field.selection.end - (field.text.length - value.length)).coerceIn(0, value.length) else value.length
        field = androidx.compose.ui.text.input.TextFieldValue(value, androidx.compose.ui.text.TextRange(cursor))
    }
    // S7: Done closes the list too, then does what it did (the caller's action, else closing the keyboard).
    val actions = KeyboardActions(onDone = { open = false; keyboardActions.onDone?.invoke(this) ?: defaultKeyboardAction(androidx.compose.ui.text.input.ImeAction.Done) },
        onGo = keyboardActions.onGo, onNext = keyboardActions.onNext, onPrevious = keyboardActions.onPrevious,
        onSearch = keyboardActions.onSearch, onSend = keyboardActions.onSend)
    // S1: the box's own tap (what TalkBack's double-tap reaches, as the drop-down anchor) also puts the cursor in it.
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { open = it; if (enabled) runCatching { focus.requestFocus() } }) {
        OutlinedTextField(
            value = field,
            onValueChange = { typed -> val changed = typed.text != field.text; field = typed; if (changed) { sent = typed.text; onValueChange(typed.text); open = true } },
            modifier = Modifier.fillMaxWidth().focusRequester(focus).menuAnchor(MenuAnchorType.PrimaryEditable, enabled)
                .onFocusChanged { focused = it.isFocused; if (it.isFocused) open = true },
            label = { Text(label) },
            singleLine = singleLine,
            enabled = enabled,
            keyboardOptions = keyboardOptions,
            keyboardActions = actions,
            trailingIcon = trailingIcon,
        )
        // Back to the top each time it opens rather than where it was left (ED-14); not as it closes, which would flick
        // the fading list (EU-5).
        val scroll = remember { androidx.compose.foundation.ScrollState(0) }
        androidx.compose.runtime.LaunchedEffect(expanded) { if (expanded) scroll.scrollTo(0) }
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { open = false },
            modifier = Modifier.heightIn(max = MENU_MAX_HEIGHT).scrollBar(scroll, inset = 8.dp), scrollState = scroll) {
            suggestions.forEach { name ->
                DropdownMenuItem(text = { Text(shown(name)) }, onClick = { sent = null; onPick(name); open = false })
            }
        }
    }
}
