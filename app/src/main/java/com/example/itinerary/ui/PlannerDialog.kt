package com.example.itinerary.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

// One button in a PlannerDialog. [danger]: the main action deletes or replaces something, so it is red, not green.
class DialogAction(val label: String, val enabled: Boolean = true, val danger: Boolean = false, val onClick: () -> Unit)

// Every pop-up in the app is laid out like Quick entry:
// - a flat card in the page's background colour, with the title top left in the heading colour;
// - anything in [header] (such as the Event/Task switch) fixed under the title;
// - the content in the middle, scrolling with the scroll bar when it does not fit (unless [scroll] is null, for a
//   dialog that brings its own list), while the title and buttons stay put;
// - quiet buttons in a row: [dismiss] (Close / Cancel) on the left, [extra] on the right;
// - under them, [primary], the one main action, full width: solid green, or red when it is a danger action.
// [properties]: for a dialog that must, for example, keep a password out of screenshots.
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlannerDialog(
    title: String?,
    onDismissRequest: () -> Unit,
    primary: DialogAction? = null,
    dismiss: DialogAction? = null,
    extra: List<DialogAction> = emptyList(),
    header: (@Composable ColumnScope.() -> Unit)? = null,
    scroll: ScrollState? = rememberScrollState(),
    properties: DialogProperties = DialogProperties(),
    content: @Composable ColumnScope.() -> Unit,
) {
    AlertDialog(
        containerColor = MaterialTheme.colorScheme.background,
        tonalElevation = 0.dp,
        onDismissRequest = onDismissRequest,
        properties = properties,
        title = if (title == null && header == null) null else {
            {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (title != null) HeadingText(title)
                    header?.invoke(this)
                }
            }
        },
        text = {
            if (scroll != null) ScrollHints(scroll, Modifier.fillMaxWidth(), fitContent = true) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
            } else Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
        },
        // The footer is one column: AlertDialog's own button row would squeeze three buttons at phone width.
        confirmButton = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (dismiss != null || extra.isNotEmpty()) FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    if (dismiss != null) QuietAction(dismiss)
                    if (extra.isNotEmpty()) FlowRow { extra.forEach { QuietAction(it) } }
                }
                if (primary != null) {
                    if (primary.danger) DangerButton(onClick = primary.onClick, modifier = Modifier.fillMaxWidth(), enabled = primary.enabled) { Text(primary.label) }
                    else MatrixPrimaryButton(onClick = primary.onClick, modifier = Modifier.fillMaxWidth(), enabled = primary.enabled) { Text(primary.label) }
                }
            }
        },
    )
}

@Composable
private fun QuietAction(action: DialogAction) {
    MatrixQuietButton(onClick = action.onClick, enabled = action.enabled) {
        Text(action.label, color = if (action.danger && action.enabled) MaterialTheme.colorScheme.error else androidx.compose.ui.graphics.Color.Unspecified)
    }
}
