package com.example.itinerary.ui
import com.example.itinerary.ui.MatrixOutlinedButton as OutlinedButton

import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.dp

// Shared Matrix controls style ordinary actions; destructive actions retain red.

@Composable
fun DangerButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError,
        ),
        content = content,
    )
}

// Delete beside other actions (an editor's bar, the selection bar, a template): shaped like them, in red. The solid
// DangerButton is kept for a dialog's one main action.
@Composable
fun DangerOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: androidx.compose.foundation.layout.PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit,
) {
    val red = MaterialTheme.colorScheme.error
    val muted = MaterialTheme.colorScheme.onSurface
    androidx.compose.material3.OutlinedButton(onClick, modifier, enabled,
        colors = ButtonDefaults.outlinedButtonColors(containerColor = red.copy(alpha = 0.08f), contentColor = red,
            disabledContainerColor = muted.copy(alpha = 0.04f),
            disabledContentColor = muted.copy(alpha = if (com.example.itinerary.ui.theme.LocalHighContrast.current) 0.6f else 0.38f)),
        border = BorderStroke(com.example.itinerary.ui.theme.controlBorderWidth(), if (enabled) red else muted.copy(alpha = 0.12f)),
        contentPadding = contentPadding, content = content)
}

// An editor's pinned actions: Delete on the left (when [onDelete] is given), Close and Save on the right, 8 dp apart.
// A quarter smaller than ordinary buttons (30 dp, text at 90 %, a 36 dp touch area), so they take less room over the
// keyboard; larger text still makes them taller.
// Wraps at large text: every button keeps its full size, and Save moves to its own line on the right rather than being
// squeezed ("Sa|ve") or pushed off the screen.
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun EditorActions(
    onDelete: (() -> Unit)?,
    onClose: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
    deleteEnabled: Boolean = true,
    closeEnabled: Boolean = true,
    saveEnabled: Boolean = true,
    saveLabel: @Composable RowScope.() -> Unit,
) {
    androidx.compose.foundation.layout.FlowRow(modifier.fillMaxWidth(),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp, Alignment.End),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
        val size = Modifier.heightIn(min = 30.dp)
        val padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 17.dp, vertical = 2.dp)
        val label = MaterialTheme.typography.labelLarge.let { it.copy(fontSize = it.fontSize * 0.9f, lineHeight = it.lineHeight * 0.9f) }
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalMinimumInteractiveComponentSize provides 36.dp) {
            if (onDelete != null) DangerOutlinedButton(enabled = deleteEnabled, onClick = onDelete, modifier = size, contentPadding = padding) {
                androidx.compose.material3.ProvideTextStyle(label) { Text("Delete") } }
            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            OutlinedButton(enabled = closeEnabled, onClick = onClose, modifier = size, contentPadding = padding) {
                androidx.compose.material3.ProvideTextStyle(label) { Text("Close") } }
            MatrixButton(enabled = saveEnabled, onClick = onSave, modifier = size, contentPadding = padding) {
                androidx.compose.material3.ProvideTextStyle(label) { saveLabel() } }
        }
    }
}

// A full-width outlined button, for a list of actions stacked in a dialog.
@Composable
fun StackedButton(text: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text(text) }
}

// A row that opens something when tapped, drawn as a bordered card. The arrow at the end says
// "opens"; leave it out when the row already has its own button at the end.
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun TappableRow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    arrow: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    selected: Boolean? = null,
    // An event from another calendar: the card takes a light wash and an outline of that calendar's colour.
    tint: androidx.compose.ui.graphics.Color? = null,
    content: @Composable RowScope.() -> Unit,
) {
    Surface(
        modifier = modifier
            .combinedClickable(onClick = onClick, onLongClick = onLongClick,
                onLongClickLabel = if (onLongClick != null) "Select event" else null, role = Role.Button)
            .semantics { if (selected != null) this.selected = selected },
        shape = RoundedCornerShape(12.dp),
        color = if (selected == true) MaterialTheme.colorScheme.secondaryContainer
            else tint?.copy(alpha = 0.14f)?.compositeOver(MaterialTheme.colorScheme.surfaceContainerLow) ?: MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(com.example.itinerary.ui.theme.controlBorderWidth(), tint?.copy(alpha = 0.8f) ?: MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            Modifier.padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = if (arrow) 6.dp else 4.dp)
                .height(IntrinsicSize.Min),
        ) {
            content()
            if (arrow) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
            }
        }
    }
}
