package com.example.itinerary.ui

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.itinerary.ItineraryApp
import com.example.itinerary.data.CalendarSource
import com.example.itinerary.data.OutsideCalendars

/** What the sync icon on Agenda and Calendar shows; null hides it (two-way Nextcloud sync is off). */
internal sealed interface SyncIndicatorState {
    val label: String
    // What a tap does: sync now while all is well, nothing while a sync runs, open Calendars when something needs a look.
    enum class Tap { SYNC, NOTHING, OPEN }
    val tap: Tap get() = when (this) { Synced -> Tap.SYNC; Syncing -> Tap.NOTHING; else -> Tap.OPEN }
    val tapLabel: String? get() = when (tap) { Tap.SYNC -> "Sync now"; Tap.OPEN -> "Open Calendars"; Tap.NOTHING -> null }
    data object Synced : SyncIndicatorState { override val label = "Sync: up to date" }
    data object Syncing : SyncIndicatorState { override val label = "Sync: syncing" }
    data object Failed : SyncIndicatorState { override val label = "Sync: problem, open Calendars" }
    data class Conflicts(val count: Int) : SyncIndicatorState {
        override val label = "Sync: $count conflict${if (count == 1) "" else "s"}, open Calendars"
    }

    companion object {
        // Conflicts and errors outrank a sync in progress: they need the user, and a retry may keep running. No Nextcloud
        // login (a restored backup brings the send-here calendar but not the login) is a problem: nothing can sync.
        // [loggedIn] null = not read yet: hidden rather than a guess. A failed download of the synced calendar is kept on
        // it (lastError) until one works: a send that works meanwhile doesn't make it "up to date".
        fun of(sources: List<CalendarSource>, loggedIn: Boolean?, running: Boolean, error: Boolean, conflicts: Int): SyncIndicatorState? {
            val target = sources.firstOrNull { it.kind == OutsideCalendars.KIND_NEXTCLOUD && it.sendHere }
            return when {
                target == null || loggedIn == null -> null
                conflicts > 0 -> Conflicts(conflicts)
                error || !loggedIn || target.lastError != null -> Failed
                running -> Syncing
                else -> Synced
            }
        }
    }
}

/**
 * Two-way Nextcloud sync at a glance, for a top bar: spins while syncing, warns on a problem. A tap syncs now while all is
 * well (and does nothing while a sync runs); with a problem or conflicts it opens Calendars, where they're shown. A long
 * press always opens Calendars.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun SyncIndicator(onOpenCalendars: () -> Unit) {
    val sync = (LocalContext.current.applicationContext as ItineraryApp).calendarSync
    val sources by sync.sources.collectAsStateWithLifecycle(initialValue = emptyList())
    val state by sync.state.collectAsStateWithLifecycle()
    val sendState by sync.sendState.collectAsStateWithLifecycle()
    val conflicts by sync.conflicts.collectAsStateWithLifecycle(initialValue = emptyList())
    // Read off the main thread, again whenever the calendars or a sync change (connecting, disconnecting and restoring all
    // change the calendar list or start a sync); keeps its last answer meanwhile, so the icon does not flicker.
    val loggedIn by produceState<Boolean?>(null, sources, state, sendState) { value = sync.hasAccount() }
    val shown = SyncIndicatorState.of(sources, loggedIn, state.running || sendState.running, state.error || sendState.error, conflicts.size) ?: return
    val app = LocalContext.current.applicationContext as ItineraryApp
    val onTap: () -> Unit = when (shown.tap) {
        SyncIndicatorState.Tap.SYNC -> { { app.appScope.launch { sync.syncNow() } } }
        SyncIndicatorState.Tap.NOTHING -> { {} }
        SyncIndicatorState.Tap.OPEN -> onOpenCalendars
    }
    Box(
        Modifier.size(48.dp).clip(CircleShape)
            .combinedClickable(onClick = onTap, onLongClick = onOpenCalendars, onLongClickLabel = "Open Calendars",
                onClickLabel = shown.tapLabel, role = Role.Button),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.primary) { when (shown) {
            is SyncIndicatorState.Conflicts -> BadgedBox(badge = { Badge { Text("${shown.count}") } }) {
                Icon(Icons.Filled.Warning, contentDescription = shown.label, tint = MaterialTheme.colorScheme.error)
            }
            SyncIndicatorState.Failed -> Icon(Icons.Filled.Warning, contentDescription = shown.label, tint = MaterialTheme.colorScheme.error)
            SyncIndicatorState.Syncing -> {
                val turn by rememberInfiniteTransition(label = "sync").animateFloat(0f, 360f,
                    infiniteRepeatable(tween(1000, easing = LinearEasing), RepeatMode.Restart), label = "turn")
                Icon(Icons.Filled.Refresh, contentDescription = shown.label, modifier = Modifier.rotate(turn))
            }
            SyncIndicatorState.Synced -> Icon(Icons.Filled.Refresh, contentDescription = shown.label)
        } }
    }
}
