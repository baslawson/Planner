package com.example.itinerary.ui
import com.example.itinerary.ui.MatrixOutlinedButton as OutlinedButton
import com.example.itinerary.ui.MatrixButton as Button

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.itinerary.data.Links

// Asks for a web address and an optional name. Confirms with (name, address); the address has been cleaned up
// (Links.normalize) and the name is never empty.
@Composable
fun AddLinkDialog(onDismiss: () -> Unit, onConfirm: (name: String, url: String) -> Unit) {
    var link by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    val url = Links.normalize(link)
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    fun confirm() {
        val address = url ?: return
        onConfirm(name.trim().ifEmpty { Links.defaultName(address) }, address)
    }

    PlannerDialog("Add a link",
        onDismissRequest = onDismiss,
        primary = DialogAction("Add", enabled = url != null, onClick = ::confirm),
        dismiss = DialogAction("Cancel", onClick = onDismiss),
    ) {
                OutlinedTextField(
                    value = link,
                    onValueChange = { link = it.replace('\n', ' ').take(Links.MAX_URL_LENGTH) },
                    label = { Text("Link") },
                    placeholder = { Text("example.com") },
                    // Only complain once something has been typed, and only if it can't be a web address.
                    isError = link.isNotBlank() && url == null,
                    supportingText = { if (link.isNotBlank() && url == null) Text("Enter a web address like example.com") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.replace('\n', ' ').take(Links.MAX_NAME_LENGTH) },
                    label = { Text("Name (optional)") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
    }
}
