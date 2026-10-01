package com.example.itinerary.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
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
 * Menus drawn in the screen's own window. The standard DropdownMenu opens a separate pop-up window and animates
 * in, which measured 200–300 ms on the emulator before it was fully shown; these appear on the next frame.
 * One state per screen: the top-bar ⋮ and every card's ⋮ on that screen open their items through it.
 */
class OverlayMenuState {
    internal var items by mutableStateOf<(@Composable ColumnScope.() -> Unit)?>(null)
        private set
    // Plain fields, read only when the open menu is placed, so layout changes never cause extra recompositions.
    internal var title = ""
    internal var anchor = Rect.Zero
    internal var host = Offset.Zero
    val open get() = items != null
    fun show(anchor: Rect, title: String, items: @Composable ColumnScope.() -> Unit) {
        this.anchor = anchor; this.title = title; this.items = items
    }
    fun close() { items = null }
}

private val LocalOverlayMenu = staticCompositionLocalOf<OverlayMenuState?> { null }

/** A full-screen Box whose menus draw above everything in it. Use it as the screen's outermost layout. */
@Composable
fun OverlayMenuScreen(state: OverlayMenuState, modifier: Modifier = Modifier.fillMaxSize(), content: @Composable BoxScope.() -> Unit) {
    CompositionLocalProvider(LocalOverlayMenu provides state) {
        Box(modifier) {
            content()
            OverlayMenuHost(state)
        }
    }
}

// Stays composed while closed (an empty Box) so its position is already known on the frame a menu opens.
@Composable
private fun OverlayMenuHost(state: OverlayMenuState) {
    Box(Modifier.fillMaxSize().onGloballyPositioned { state.host = it.positionInWindow() }) {
        val items = state.items ?: return@Box
        BackHandler { state.close() }
        // A tap anywhere outside the menu closes it and does not reach the screen underneath.
        Box(Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() },
            indication = null, onClickLabel = "Close menu") { state.close() })
        val margin = with(LocalDensity.current) { 8.dp.roundToPx() }
        Layout(content = {
            Surface(shape = MenuDefaults.shape, color = MenuDefaults.containerColor,
                tonalElevation = MenuDefaults.TonalElevation, shadowElevation = MenuDefaults.ShadowElevation,
                modifier = Modifier.semantics { paneTitle = state.title }) {
                Column(Modifier.width(IntrinsicSize.Max).padding(vertical = 8.dp), content = items)
            }
        }) { measurables, constraints ->
            val menu = measurables.single().measure(constraints.copy(minWidth = 0, minHeight = 0))
            layout(constraints.maxWidth, constraints.maxHeight) {
                // Right edge under the button's right edge, just below it; above it if there is no room below.
                val right = (state.anchor.right - state.host.x).roundToInt()
                val below = (state.anchor.bottom - state.host.y).roundToInt()
                val above = (state.anchor.top - state.host.y).roundToInt() - menu.height
                val top = if (below + menu.height + margin <= constraints.maxHeight || above < margin) below else above
                menu.place((right - menu.width).coerceIn(margin, (constraints.maxWidth - menu.width - margin).coerceAtLeast(margin)),
                    top.coerceIn(margin, (constraints.maxHeight - menu.height - margin).coerceAtLeast(margin)))
            }
        }
    }
}

private class AnchorBounds { var rect = Rect.Zero }

/**
 * Wraps a button that opens a menu: [button] gets the open action, [items] get a close action. Inside an
 * [OverlayMenuScreen] the menu draws in-window; anywhere else it falls back to a standard DropdownMenu.
 */
@Composable
fun OverlayMenuAnchor(title: String, items: @Composable ColumnScope.(close: () -> Unit) -> Unit, button: @Composable (open: () -> Unit) -> Unit) {
    val state = LocalOverlayMenu.current
    val bounds = remember { AnchorBounds() }
    Box(Modifier.onGloballyPositioned { bounds.rect = it.boundsInWindow() }) {
        if (state != null) button { state.show(bounds.rect, title) { items { state.close() } } }
        else {
            var expanded by remember { mutableStateOf(false) }
            button { expanded = true }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) { items { expanded = false } }
        }
    }
}

/** The ⋮ menu in the Agenda and Calendar top bars. */
@Composable
fun MoreOptionsButton(planningTools: PlanningToolsState, onThemes: () -> Unit, onSettings: () -> Unit) {
    OverlayMenuAnchor(title = "More options", items = { close ->
        PlanningToolMenuItems(planningTools) { close() }
        DropdownMenuItem(text = { Text("Themes") },
            leadingIcon = { Icon(painterResource(R.drawable.action_palette), contentDescription = null) },
            onClick = { close(); onThemes() })
        DropdownMenuItem(text = { Text("Settings") },
            leadingIcon = { Icon(Icons.Filled.Settings, contentDescription = null) },
            onClick = { close(); onSettings() })
        HorizontalDivider()
        val context = androidx.compose.ui.platform.LocalContext.current
        DropdownMenuItem(text = { Text("Support Planner on Ko-fi") },
            leadingIcon = { Icon(Icons.Filled.Favorite, contentDescription = null) },
            onClick = {
                close()
                try { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(KOFI_URL))) }
                catch (_: android.content.ActivityNotFoundException) {
                    android.widget.Toast.makeText(context, "No app found to open this link", android.widget.Toast.LENGTH_SHORT).show()
                }
            })
        // The installed app's own version, so it never needs editing.
        val version = remember {
            runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()?.let {
                "Version ${it.versionName} (build ${androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(it)})"
            }
        }
        version?.let { Text(it, Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }) { open ->
        IconButton(onClick = open) { Icon(Icons.Filled.MoreVert, contentDescription = "More options") }
    }
}

/** Where "Support Planner on Ko-fi" goes (the same page as the README's badge). */
const val KOFI_URL = "https://ko-fi.com/baslawson"
