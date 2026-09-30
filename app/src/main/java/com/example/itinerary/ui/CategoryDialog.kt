package com.example.itinerary.ui
import com.example.itinerary.ui.MatrixOutlinedButton as OutlinedButton
import com.example.itinerary.ui.MatrixButton as Button

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
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
import com.example.itinerary.data.Categories

// Lists the categories with a tick box each, and removes the ones ticked. [options] are names with how many
// events use them; those events become Other, so if any are affected it asks first.
@Composable
fun RemoveCategoriesDialog(options: List<Pair<String, Int>>, onDismiss: () -> Unit, onRemove: (Set<String>) -> Unit) {
    var selected by rememberSaveable { mutableStateOf(emptySet<String>()) }
    var confirming by rememberSaveable { mutableStateOf(false) }
    val affected = options.filter { it.first in selected }.sumOf { it.second }

    if (confirming) {
        PlannerDialog(if (selected.size == 1) "Remove ${selected.first()}?" else "Remove ${selected.size} categories?",
            onDismissRequest = { confirming = false },
            primary = DialogAction("Remove", danger = true) { onRemove(selected) },
            dismiss = DialogAction("Keep") { confirming = false },
        ) {
            Text("$affected ${if (affected == 1) "event" else "events"} will become Other. This can't be undone.")
        }
    } else {
        PlannerDialog("Remove categories",
            onDismissRequest = onDismiss,
            primary = DialogAction("Remove", enabled = selected.isNotEmpty(), danger = true) {
                if (affected > 0) confirming = true else onRemove(selected)
            },
            dismiss = DialogAction("Cancel", onClick = onDismiss),
        ) {
                    Text(
                        "Tick the categories to remove. Events using them become Other.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    options.forEach { (name, count) ->
                        val checked = name in selected
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .toggleable(
                                    value = checked,
                                    role = Role.Checkbox,
                                    onValueChange = { selected = if (it) selected + name else selected - name },
                                )
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = checked, onCheckedChange = null)
                            Spacer(Modifier.width(12.dp))
                            Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                if (count == 0) "not used" else "$count ${if (count == 1) "event" else "events"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
        }
    }
}

// Asks for the name of a category the user is making up. [inUse] are the categories already on offer,
// so typing "food" picks Food instead of making a second one. "Bills" is refused (a bill is made with its own button).
// Confirms with the name to store.
@Composable
fun CustomCategoryDialog(inUse: List<String>, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val name = Categories.ownCategory(text, inUse)
    val bills = Categories.isBillsName(text)
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    PlannerDialog("Your own category",
        onDismissRequest = onDismiss,
        primary = DialogAction("Add", enabled = name != null) { onConfirm(name!!) },
        dismiss = DialogAction("Cancel", onClick = onDismiss),
    ) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.replace('\n', ' ').take(Categories.MAX_LENGTH) },
                label = { Text("Category name") },
                supportingText = if (bills) { { Text(Categories.BILLS_BY_NAME_MESSAGE) } } else null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { name?.let(onConfirm) }),
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
            )
    }
}
