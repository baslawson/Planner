package com.example.itinerary.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Every shared button and chip: a rounded rectangle, not a pill (user, 10 Oct: "cleaner and appealing"). */
val ControlShape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp)

/** Shared action styling keeps dialogs, editors and secondary screens consistent. */
@Composable
private fun matrixButtonColors() = ButtonDefaults.buttonColors(
    containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
    contentColor = MaterialTheme.colorScheme.primary,
    disabledContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f),
    disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = if (com.example.itinerary.ui.theme.LocalHighContrast.current) 0.6f else 0.38f),
)

@Composable
private fun matrixBorder(enabled: Boolean) = BorderStroke(com.example.itinerary.ui.theme.controlBorderWidth(),
    if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))

@Composable
fun MatrixButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding, content: @Composable RowScope.() -> Unit) {
    Button(onClick, modifier, enabled, shape = ControlShape, colors = matrixButtonColors(), border = matrixBorder(enabled), contentPadding = contentPadding, content = content)
}

@Composable
fun MatrixOutlinedButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding, content: @Composable RowScope.() -> Unit) {
    OutlinedButton(onClick, modifier, enabled, shape = ControlShape, colors = matrixButtonColors(), border = matrixBorder(enabled), contentPadding = contentPadding, content = content)
}

@Composable
fun MatrixTextButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit) {
    TextButton(onClick, modifier, enabled, shape = ControlShape, colors = matrixButtonColors(), border = matrixBorder(enabled), content = content)
}

/** The one main action in a dialog: filled, so it stands out from the outlined controls around it. */
@Composable
fun MatrixPrimaryButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit) {
    Button(onClick, modifier, enabled, shape = ControlShape, colors = ButtonDefaults.buttonColors(
        containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary,
        disabledContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
        disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = if (com.example.itinerary.ui.theme.LocalHighContrast.current) 0.6f else 0.38f)),
        content = content)
}

/** Low-emphasis actions beside a primary one, or links that open more options: no border or fill. */
@Composable
fun MatrixQuietButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    contentPadding: PaddingValues = ButtonDefaults.TextButtonContentPadding, content: @Composable RowScope.() -> Unit) {
    TextButton(onClick, modifier, enabled, shape = ControlShape, contentPadding = contentPadding, colors = ButtonDefaults.textButtonColors(
        contentColor = MaterialTheme.colorScheme.primary,
        disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = if (com.example.itinerary.ui.theme.LocalHighContrast.current) 0.6f else 0.38f)),
        content = content)
}

@Composable
fun MatrixIconButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    content: @Composable () -> Unit) {
    // Greyed out as the other shared buttons are, stronger in high contrast (Undo and Redo were outlined buttons until 10 Oct).
    IconButton(onClick, modifier, enabled, colors = IconButtonDefaults.iconButtonColors(
        contentColor = MaterialTheme.colorScheme.primary,
        disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = if (com.example.itinerary.ui.theme.LocalHighContrast.current) 0.6f else 0.38f)),
        content = content)
}

@Composable
fun MatrixFilterChip(selected: Boolean, onClick: () -> Unit, label: @Composable () -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true, leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null) {
    val green = MaterialTheme.colorScheme.primary
    FilterChip(selected, onClick, label, modifier, enabled, leadingIcon, trailingIcon, shape = ControlShape,
        colors = FilterChipDefaults.filterChipColors(
            containerColor = Color.Transparent,
            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            selectedContainerColor = green.copy(alpha = 0.08f), selectedLabelColor = green,
            selectedLeadingIconColor = green, selectedTrailingIconColor = green),
        border = FilterChipDefaults.filterChipBorder(enabled, selected,
            selectedBorderColor = green,
            borderWidth = com.example.itinerary.ui.theme.controlBorderWidth(),
            selectedBorderWidth = com.example.itinerary.ui.theme.controlBorderWidth()))
}

@Composable
fun MatrixAssistChip(onClick: () -> Unit, label: @Composable () -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true, leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null) {
    val green = MaterialTheme.colorScheme.primary
    AssistChip(onClick, label, modifier, enabled, leadingIcon, trailingIcon, shape = ControlShape,
        colors = AssistChipDefaults.assistChipColors(containerColor = green.copy(alpha = 0.08f),
            labelColor = green, leadingIconContentColor = green, trailingIconContentColor = green),
        border = matrixBorder(enabled))
}
