package com.example.itinerary.ui
import androidx.compose.foundation.border

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.example.itinerary.R
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.itinerary.data.AddButton

private val AddButtonSize = 84.dp

// The colour of the big + button: teal, see-through by [seeThroughPercent] (0 = solid). The + stays solid.
@Composable
private fun addButtonFill(seeThroughPercent: Int): Color =
    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 1f - seeThroughPercent.coerceIn(0, 100) / 100f)

// The big + button on its own: in Settings as a live sample, on the agenda to add an event ([label] is what a screen
// reader says), and on Manage plans with a menu (see AddButtonWithMenu).
@Composable
fun AddButtonSample(
    seeThroughPercent: Int,
    open: Boolean = false,
    onClick: () -> Unit = {},
    modifier: Modifier = Modifier,
    label: String = "Add",
) {
    LargeFloatingActionButton(
        onClick = onClick,
        modifier = modifier.size(AddButtonSize).border(com.example.itinerary.ui.theme.controlBorderWidth(), MaterialTheme.colorScheme.primary, FloatingActionButtonDefaults.largeShape),
        containerColor = addButtonFill(seeThroughPercent),
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        // A shadow under a see-through button would look like a dirty patch, so it is dropped as it fades.
        elevation = if (seeThroughPercent > 10) {
            FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp)
        } else {
            FloatingActionButtonDefaults.elevation()
        },
    ) {
        Icon(
            if (open) Icons.Filled.Close else Icons.Filled.Add,
            // The label is only for screen readers; nothing is drawn.
            contentDescription = if (open) "Close" else label,
            modifier = Modifier.size(38.dp),
        )
    }
}

// The big + in the middle of the bottom edge and, when tapped, a card with the two things you can add. The card fades
// and grows out of the button, the background dims, and the + turns into a ×. Tap the × or the dimmed background
// (or the system Back key, handled by the caller) to close.
@Composable
fun AddButtonWithMenu(
    seeThroughPercent: Int,
    open: Boolean,
    onOpenChange: (Boolean) -> Unit,
    onNewPlan: () -> Unit,
    onQuickEvent: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        // Dims everything behind the card; tapping it closes the menu.
        AnimatedVisibility(visible = open, enter = fadeIn(tween(160)), exit = fadeOut(tween(120))) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onOpenChange(false) },
                    ),
            )
        }
        Column(
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AnimatedVisibility(
                visible = open,
                enter = fadeIn(tween(160)) + scaleIn(tween(200), initialScale = 0.8f, transformOrigin = TransformOrigin(0.5f, 1f)),
                exit = fadeOut(tween(120)) + scaleOut(tween(120), targetScale = 0.9f, transformOrigin = TransformOrigin(0.5f, 1f)),
            ) {
                AddMenuCard(
                    onNewPlan = { onOpenChange(false); onNewPlan() },
                    onQuickEvent = { onOpenChange(false); onQuickEvent() },
                )
            }
            Spacer(Modifier.height(16.dp))
            AddButtonSample(seeThroughPercent = if (open) 0 else seeThroughPercent, open = open, onClick = { onOpenChange(!open) })
        }
    }
}

@Composable
private fun AddMenuCard(onNewPlan: () -> Unit, onQuickEvent: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 6.dp,
        shadowElevation = 16.dp,
        border = BorderStroke(com.example.itinerary.ui.theme.controlBorderWidth(), MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
        modifier = Modifier.padding(horizontal = 24.dp).fillMaxWidth(),
    ) {
        Column(Modifier.padding(vertical = 8.dp)) {
            AddMenuRow(
                icon = Icons.Filled.DateRange,
                badge = MaterialTheme.colorScheme.primary,
                title = "Add new plan",
                subtitle = "Dates, colour and events for a trip",
                onClick = onNewPlan,
            )
            HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)
            AddMenuRow(
                icon = Icons.Filled.Star,
                badge = MaterialTheme.colorScheme.secondary,
                title = "Add new event",
                subtitle = "Creates a new plan with one event",
                onClick = onQuickEvent,
            )
        }
    }
}

@Composable
private fun AddMenuRow(icon: ImageVector, badge: Color, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(48.dp).clip(CircleShape).background(badge),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.surface, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.width(16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// The slider block for Settings.
@Composable
fun AddButtonSettingsSection(seeThroughPercent: Int, onChange: (Int) -> Unit) {
    SettingsHeading("Add button")
    Text(
        "How see-through the big + button on the agenda is, so what is behind it shows. It never disappears completely.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(12.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Slider(
                value = seeThroughPercent.toFloat(),
                onValueChange = { onChange(it.toInt()) },
                valueRange = AddButton.MIN_SEE_THROUGH.toFloat()..AddButton.MAX_SEE_THROUGH.toFloat(),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "See-through" },
            )
            Text(
                if (seeThroughPercent == 0) "Solid" else "$seeThroughPercent% see-through",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Spacer(Modifier.width(16.dp))
        // A live sample. A coloured stripe runs behind the button so the see-through effect can actually be seen.
        Box(
            Modifier
                .size(128.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.fillMaxWidth().height(28.dp).background(MaterialTheme.colorScheme.secondary))
            AddButtonSample(seeThroughPercent = seeThroughPercent)
        }
    }
}

// Compact action rows stacked above Agenda's +/close button.
@Composable
fun AgendaActionsButton(
    seeThroughPercent: Int,
    open: Boolean,
    onOpenChange: (Boolean) -> Unit,
    onAddEvent: () -> Unit,
    onQuickEntry: () -> Unit,
    onAddTask: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val configuration = LocalConfiguration.current
    val menuWidth = (configuration.screenWidthDp - 32).coerceIn(0, 320).dp
    val gap = with(LocalDensity.current) { 12.dp.roundToPx() }
    val position = remember(gap) {
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: androidx.compose.ui.unit.IntRect,
                windowSize: androidx.compose.ui.unit.IntSize, layoutDirection: androidx.compose.ui.unit.LayoutDirection,
                popupContentSize: androidx.compose.ui.unit.IntSize): IntOffset = IntOffset(
                (anchorBounds.left + (anchorBounds.width - popupContentSize.width) / 2)
                    .coerceIn(gap, (windowSize.width - popupContentSize.width - gap).coerceAtLeast(gap)),
                (anchorBounds.bottom - popupContentSize.height).coerceAtLeast(gap),
            )
        }
    }
    Box(modifier) {
        if (open) {
            // Keep the popup anchor in place; the popup draws the close button exactly once.
            Spacer(Modifier.size(AddButtonSize))
        } else {
            AddButtonSample(seeThroughPercent = seeThroughPercent, onClick = { onOpenChange(true) }, label = "Add menu")
        }
        if (open) Popup(popupPositionProvider = position, onDismissRequest = { onOpenChange(false) },
            properties = PopupProperties(focusable = true)) {
            Column(
                Modifier.width(menuWidth),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AgendaActionRow("Add task", R.drawable.action_task) { onOpenChange(false); onAddTask() }
                AgendaActionRow("Add event", R.drawable.shortcut_event) { onOpenChange(false); onAddEvent() }
                AgendaActionRow("Quick entry", R.drawable.action_quick_entry) { onOpenChange(false); onQuickEntry() }
                AddButtonSample(seeThroughPercent = seeThroughPercent, open = true,
                    onClick = { onOpenChange(false) }, modifier = Modifier.size(AddButtonSize))
            }
        }
    }
}

@Composable
private fun AgendaActionRow(label: String, icon: Int, onClick: () -> Unit) {
    val foreground = MaterialTheme.colorScheme.primary
    Surface(onClick = onClick, border = BorderStroke(com.example.itinerary.ui.theme.controlBorderWidth(), foreground),
        shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = foreground, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).background(foreground.copy(alpha = 0.12f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center) {
                Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(28.dp))
            }
            Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        }
    }
}
