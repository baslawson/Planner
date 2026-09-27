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
    content: @Composable RowScope.() -> Unit,
) {
    Surface(
        modifier = modifier
            .combinedClickable(onClick = onClick, onLongClick = onLongClick,
                onLongClickLabel = if (onLongClick != null) "Select event" else null, role = Role.Button)
            .semantics { if (selected != null) this.selected = selected },
        shape = RoundedCornerShape(12.dp),
        color = if (selected == true) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(com.example.itinerary.ui.theme.controlBorderWidth(), MaterialTheme.colorScheme.outlineVariant),
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
