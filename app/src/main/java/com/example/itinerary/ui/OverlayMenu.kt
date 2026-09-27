package com.example.itinerary.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.itinerary.R
import kotlin.math.roundToInt

/**
 * A menu drawn in the screen's own window. The standard DropdownMenu opens a separate pop-up window and animates
 * in, which measured 200–300 ms on the emulator before it was fully shown; this one appears on the next frame.
 */
class OverlayMenuState {
    var open by mutableStateOf(false)
    // Plain fields, read only when the open menu is placed, so layout changes never cause extra recompositions.
    internal var anchor = Rect.Zero
    internal var host = Offset.Zero
}

/** Marks the button the menu drops down from. */
fun Modifier.overlayMenuAnchor(state: OverlayMenuState) = onGloballyPositioned { state.anchor = it.boundsInWindow() }

/**
 * Put this last in a Box that fills the screen, so the menu draws above everything else. It stays composed while
 * closed (an empty Box) so its position is already known on the frame the menu opens.
 */
@Composable
fun OverlayMenuHost(state: OverlayMenuState, title: String, content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize().onGloballyPositioned { state.host = it.positionInWindow() }) {
        if (!state.open) return@Box
        BackHandler { state.open = false }
        // A tap anywhere outside the menu closes it and does not reach the screen underneath.
        Box(Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() },
            indication = null, onClickLabel = "Close menu") { state.open = false })
        val margin = with(LocalDensity.current) { 8.dp.roundToPx() }
        Layout(content = {
            Surface(shape = MenuDefaults.shape, color = MenuDefaults.containerColor,
                tonalElevation = MenuDefaults.TonalElevation, shadowElevation = MenuDefaults.ShadowElevation,
                modifier = Modifier.semantics { paneTitle = title }) {
                Column(Modifier.width(IntrinsicSize.Max).padding(vertical = 8.dp), content = content)
            }
        }) { measurables, constraints ->
            val menu = measurables.single().measure(constraints.copy(minWidth = 0, minHeight = 0))
            layout(constraints.maxWidth, constraints.maxHeight) {
                // Right edge under the button's right edge, just below it, kept on screen like DropdownMenu.
                val right = (state.anchor.right - state.host.x).roundToInt()
                val top = (state.anchor.bottom - state.host.y).roundToInt()
                menu.place((right - menu.width).coerceIn(margin, (constraints.maxWidth - menu.width - margin).coerceAtLeast(margin)),
                    top.coerceIn(margin, (constraints.maxHeight - menu.height - margin).coerceAtLeast(margin)))
            }
        }
    }
}

/** The ⋮ button in the Agenda and plan top bars. */
@Composable
fun MoreOptionsButton(state: OverlayMenuState) {
    IconButton(onClick = { state.open = true }, modifier = Modifier.overlayMenuAnchor(state)) {
        Icon(Icons.Filled.MoreVert, contentDescription = "More options")
    }
}

/** The ⋮ menu shared by the Agenda and plan screens. */
@Composable
fun MoreOptionsMenu(state: OverlayMenuState, planningTools: PlanningToolsState, onThemes: () -> Unit, onSettings: () -> Unit) {
    OverlayMenuHost(state, title = "More options") {
        PlanningToolMenuItems(planningTools) { state.open = false }
        DropdownMenuItem(text = { Text("Themes") },
            leadingIcon = { Icon(painterResource(R.drawable.action_palette), contentDescription = null) },
            onClick = { state.open = false; onThemes() })
        DropdownMenuItem(text = { Text("Settings") },
            leadingIcon = { Icon(Icons.Filled.Settings, contentDescription = null) },
            onClick = { state.open = false; onSettings() })
    }
}
