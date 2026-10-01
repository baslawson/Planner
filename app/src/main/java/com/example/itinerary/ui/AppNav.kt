package com.example.itinerary.ui

import androidx.compose.runtime.*
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.itinerary.ItineraryApp
import java.time.LocalDate

// Goes back from [entry], but only while it is the screen being shown. A second quick tap on a back arrow lands on
// the screen that is already animating away; without this it would pop the screen underneath as well and leave the
// app blank. The first screen is never popped.
private fun NavController.popFrom(entry: NavBackStackEntry) {
    if (entry.lifecycle.currentState == Lifecycle.State.RESUMED && previousBackStackEntry != null) popBackStack()
}

// Goes to [route] from [entry], but only while it is the screen being shown. A second quick tap on an event row (or on
// the search icon) lands on the screen that is already animating away; without this it would push a second copy of the
// destination, so one tap on back would appear to do nothing. launchSingleTop is a second guard, for when both taps
// aim at the same destination.
private fun NavController.navigateFrom(entry: NavBackStackEntry, route: String) {
    if (entry.lifecycle.currentState == Lifecycle.State.RESUMED) {
        navigate(route) { launchSingleTop = true }
    }
}

private const val CALENDAR_ROUTE = "calendar?date={date}"

private fun AnimatedContentTransitionScope<NavBackStackEntry>.isViewSwitch(): Boolean {
    val from = initialState.destination.route
    val to = targetState.destination.route
    return (from == "agenda" && to == CALENDAR_ROUTE) || (from == CALENDAR_ROUTE && to == "agenda")
}

private fun NavController.openCalendar(entry: NavBackStackEntry, date: LocalDate? = null) {
    if (entry.lifecycle.currentState != Lifecycle.State.RESUMED) return
    // Explicit event dates must never restore an older cached Calendar destination.
    if (date != null) clearBackStack(CALENDAR_ROUTE)
    navigate(if (date == null) "calendar" else "calendar?date=$date") {
        launchSingleTop = true
        restoreState = date == null
    }
}

@Composable
fun AppNav(sharedText: String? = null, sharedSubject: String? = null, onSharedOpened: () -> Unit = {}, widgetDate: LocalDate? = null, onWidgetOpened: () -> Unit = {}, entryAction: String? = null, onEntryOpened: () -> Unit = {}, calendarUri: android.net.Uri? = null, onCalendarOpened: () -> Unit = {}, widgetTaskId: String? = null, onWidgetTaskOpened: () -> Unit = {}) {
    val nav = rememberNavController()
    val app = LocalContext.current.applicationContext as ItineraryApp
    if (sharedText != null) key(sharedText, sharedSubject) { SharedTextReview(sharedText, sharedSubject, onSharedOpened) }
    // The task open from the widget. A second widget task (a new [widgetTaskId]) waits until this one's editor has
    // closed the usual way, "Save changes?" included; Keep editing there drops it (E4: the editor used to be reused).
    var widgetTaskOpen by remember { mutableStateOf<String?>(null) }
    // Read when the editor closes, which can be after a save that began before another task was tapped.
    val widgetTaskWanted by rememberUpdatedState(widgetTaskId)
    LaunchedEffect(widgetTaskId, widgetTaskOpen) { if (widgetTaskOpen == null && widgetTaskId != null) widgetTaskOpen = widgetTaskId }
    widgetTaskOpen?.let { openId ->
        val switching = widgetTaskId != null && widgetTaskId != openId
        val closeWidgetTask = {
            widgetTaskOpen = null
            // With another task waiting it opens next; otherwise the request is done.
            if (widgetTaskWanted == null || widgetTaskWanted == openId) onWidgetTaskOpened()
        }
        val widgetTasks by app.repository.tasks.collectAsStateWithLifecycle(initialValue = null)
        val task = rememberEditedTask(openId, widgetTasks?.find { it.id == openId })
        key(openId) {
            if (task != null) PlanningOverlay(closeWidgetTask) {
                TaskEditor(task, false, closeRequested = switching, onCloseCancelled = { if (widgetTaskWanted != null && widgetTaskWanted != openId) onWidgetTaskOpened() }, onDismiss = closeWidgetTask)
            } else if (widgetTasks != null) {
                if (switching) LaunchedEffect(Unit) { closeWidgetTask() }
                PlannerDialog("Task unavailable", onDismissRequest = closeWidgetTask,
                    dismiss = DialogAction("Close", onClick = closeWidgetTask)) { Text("This task may have been deleted.") }
            }
        }
    }
    var viewRestored by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(nav) {
        nav.currentBackStackEntryFlow.collect { entry ->
            val route = entry.destination.route
            if (!viewRestored) {
                viewRestored = true
                // Keep Agenda underneath Calendar so Back and the toggle still work.
                // Explicit widget dates take priority over the saved view/date.
                if (route == "agenda" && app.settings.lastViewCalendar && widgetDate == null) {
                    nav.navigate("calendar") { launchSingleTop = true }
                    return@collect
                }
            }
            when (route) {
                "agenda" -> app.settings.lastViewCalendar = false
                "calendar?date={date}" -> app.settings.lastViewCalendar = true
            }
        }
    }
    var recovered by remember { mutableStateOf(runCatching {
        draftToRecover(com.example.itinerary.data.EditorDraftStore.openEditors.value) { com.example.itinerary.data.EditorDraftStore(app).read() }
    }.getOrNull()) }

    var shortcutItem by remember { mutableStateOf<com.example.itinerary.data.ItineraryItem?>(null) }
    var shortcutScan by remember { mutableStateOf(false) }
    if (calendarUri != null) CalendarImportDialog(initialUri = calendarUri, onDismiss = onCalendarOpened)
    var draftChecked by remember { mutableStateOf(recovered == null) }
    // U2: the saved event the recovery editor reopens, claimed while it's up (a new event's draft has no other editor).
    // Claimed before any restored bill editor looks (BillTaskEditor checks once composed, in its LaunchedEffect).
    val recoveringId = remember(recovered) {
        recovered?.let { runCatching { com.example.itinerary.data.DraftCodec.item(it.getJSONObject("initial")).id }.getOrNull() }?.takeIf { it != 0L }
    }
    if (recoveringId != null) DisposableEffect(recoveringId) {
        com.example.itinerary.data.EditorDraftStore.recoveryOpened(recoveringId)
        onDispose { com.example.itinerary.data.EditorDraftStore.recoveryClosed(recoveringId) }
    }
    LaunchedEffect(Unit) {
        val token = recovered?.optString("token")?.takeIf { it.isNotEmpty() }
        if (token != null && app.repository.snapshot().items.any { it.draftToken == token }) {
            // The transaction committed before the old process could remove its draft journal.
            com.example.itinerary.data.EditorDraftStore(app).clear()
            java.io.File(app.filesDir, "draft-scan").deleteRecursively()
            recovered = null
        }
        draftChecked = true
    }

    // The agenda owns event, backup and settings state.
    val tripsFactory = viewModelFactory {
        initializer { TripsViewModel(app.repository, app.settings, app.backup, app.nextcloudBackups, app.calendarSync) }
    }

    val pending by app.repository.pendingDeletions.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val next = pending.firstOrNull()
    LaunchedEffect(next?.token) {
        while (next != null && app.repository.pendingDeletions.value.any { it.token == next.token }) {
            try {
                val result = snackbar.showSnackbar(
                    message = deletedMessage(next.items.size, next.tasks.size),
                    actionLabel = "Undo",
                    withDismissAction = true,
                    duration = SnackbarDuration.Long,
                )
                withContext(NonCancellable) {
                    if (result == SnackbarResult.ActionPerformed) app.repository.undoDeletion(next.token)
                    else app.repository.finishDeletion(next.token)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                Toast.makeText(app, "Couldn't undo deletion. Try Undo again.", Toast.LENGTH_LONG).show()
                // Offer the same Undo again if storage temporarily failed.
            }
        }
    }
    val moves by app.repository.pendingMoves.collectAsStateWithLifecycle()
    val nextMove = moves.firstOrNull()
    // Deletion Undo gets priority. Cancelling this effect leaves a move available until it can be shown.
    LaunchedEffect(nextMove?.token, pending.isNotEmpty()) {
        if (pending.isEmpty() && nextMove != null) {
            while (app.repository.pendingMoves.value.any { it.token == nextMove.token }) {
                try {
                    val result = snackbar.showSnackbar("Moved ${nextMove.title} to tomorrow", actionLabel = "Undo",
                        withDismissAction = true, duration = SnackbarDuration.Long)
                    withContext(NonCancellable) {
                        if (result == SnackbarResult.ActionPerformed) {
                            if (!app.repository.undoMove(nextMove.token))
                                Toast.makeText(app, "The ${if (nextMove.task != null) "task" else "event"} was changed or deleted, so its date wasn't undone.", Toast.LENGTH_LONG).show()
                        } else app.repository.finishMove(nextMove.token)
                    }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { Toast.makeText(app, "Couldn't undo the move. Try Undo again.", Toast.LENGTH_LONG).show() }
            }
        }
    }
    val payments by app.repository.pendingPayments.collectAsStateWithLifecycle()
    val payment = payments.firstOrNull()
    LaunchedEffect(payment?.token, pending.isNotEmpty(), moves.isNotEmpty()) {
        if (payment != null && pending.isEmpty() && moves.isEmpty()) {
            val result = snackbar.showSnackbar(
                "${payment.before.title} marked ${if (payment.paid) "paid" else "unpaid"}",
                actionLabel = "Undo", withDismissAction = true, duration = SnackbarDuration.Long)
            withContext(NonCancellable) {
                try {
                    if (result == SnackbarResult.ActionPerformed) {
                        if (!app.repository.undoPayment(payment.token))
                            Toast.makeText(app, "This bill changed; payment was not undone.", Toast.LENGTH_LONG).show()
                    } else app.repository.finishPayment(payment.token)
                } catch (_: Exception) {
                    app.repository.finishPayment(payment.token)
                    Toast.makeText(app, "Couldn't undo payment. Please check the bill.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    val maintenanceIssues by app.repository.maintenanceIssues.collectAsStateWithLifecycle()
    LaunchedEffect(maintenanceIssues) {
        if (maintenanceIssues.isNotEmpty()) {
            do {
                val description = app.repository.maintenanceIssues.value.map {
                    when (it) { "reminders" -> "reminders"; "widget" -> "the home-screen widget"; else -> "unused attachment cleanup" }
                }.joinToString(" and ")
                val result = snackbar.showSnackbar(
                    "Your data is saved. Couldn't update $description.",
                    actionLabel = "Retry", withDismissAction = true, duration = SnackbarDuration.Long)
                if (result != SnackbarResult.ActionPerformed) break
                app.repository.retryMaintenance()
            } while (app.repository.maintenanceIssues.value.isNotEmpty())
        }
    }
    val outsideEvents by app.calendarSync.shown.collectAsStateWithLifecycle(initialValue = emptyMap())
    val syncMarks by app.calendarSync.syncMarks.collectAsStateWithLifecycle(initialValue = emptyMap())
    CompositionLocalProvider(LocalOutsideEvents provides outsideEvents, LocalAppSnackbar provides snackbar, LocalSyncMarks provides syncMarks) {
    Box(Modifier.fillMaxSize()) {
        NavHost(navController = nav, startDestination = "agenda") {
            composable(
                "agenda",
                enterTransition = { if (isViewSwitch()) EnterTransition.None else null },
                exitTransition = { if (isViewSwitch()) ExitTransition.None else null },
                popEnterTransition = { if (isViewSwitch()) EnterTransition.None else null },
                popExitTransition = { if (isViewSwitch()) ExitTransition.None else null },
            ) { entry ->
                val vm: TripsViewModel = viewModel(factory = tripsFactory)
                AgendaScreen(
                    vm = vm,
                    // Opens the shared calendar on the event date.
                    onOpenEvent = { date -> nav.openCalendar(entry, date) },
                    onOpenSearch = { nav.navigateFrom(entry, "search") },
                    onOpenCalendar = { nav.openCalendar(entry) },
                )
            }
            composable("search") { entry ->
                val vm: SearchViewModel = viewModel(
                    factory = viewModelFactory { initializer { SearchViewModel(app.repository, app.settings, app.calendarSync.shown) } },
                )
                SearchScreen(
                    vm = vm,
                    onBack = { nav.popFrom(entry) },
                    onOpenResult = { date -> nav.openCalendar(entry, date) },
                )
            }
            composable(
                route = CALENDAR_ROUTE,
                enterTransition = { if (isViewSwitch()) EnterTransition.None else null },
                exitTransition = { if (isViewSwitch()) ExitTransition.None else null },
                popEnterTransition = { if (isViewSwitch()) EnterTransition.None else null },
                popExitTransition = { if (isViewSwitch()) ExitTransition.None else null },
                arguments = listOf(
                    navArgument("date") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) { entry ->
                val startDate = entry.arguments?.getString("date")?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                val vm: ItineraryViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer { ItineraryViewModel(app.repository, app.settings, startDate, createSavedStateHandle(), app.calendarSync.shown) }
                    },
                )
                var showSettings by rememberSaveable { mutableStateOf(false) }
                var settingsOpened by rememberSaveable { mutableStateOf(false) }
                var showCalendars by rememberSaveable { mutableStateOf(false) }
                Box(Modifier.fillMaxSize()) {
                    ItineraryScreen(
                        vm = vm,
                        onOpenSettings = { settingsOpened = true; showSettings = true },
                        onOpenCalendars = { settingsOpened = true; showCalendars = true },
                        onOpenSearch = { nav.navigateFrom(entry, "search") },
                        onAgenda = {
                            if (entry.lifecycle.currentState == Lifecycle.State.RESUMED) {
                                // Save just Calendar, not a Search screen that may sit underneath it.
                                nav.popBackStack(CALENDAR_ROUTE, inclusive = true, saveState = true)
                                nav.popBackStack("agenda", inclusive = false)
                            }
                        },
                    )
                    // Once opened, keep the host for outstanding picker/backup results even after closing it.
                    if (settingsOpened) {
                        val settingsVm: TripsViewModel = viewModel(factory = tripsFactory)
                        SettingsHost(vm = settingsVm, show = showSettings, onDismiss = { showSettings = false },
                            showCalendars = showCalendars, onShowCalendars = { showCalendars = it })
                    }
                }
            }
        }
        // A widget tap while an event or task editor is open goes back to that editor; the widget's day opens once it is
        // saved or discarded (D10, U5). Moving to the day at once would drop the editor with its draft.
        // Not lifecycle-bound: the tap that brings Planner back must see the current count at once.
        val openEventEditors by com.example.itinerary.data.EditorDraftStore.openEditors.collectAsState()
        val openTaskEditors by com.example.itinerary.data.TaskDraftStore.openEditors.collectAsState()
        val openEditors = openEventEditors + openTaskEditors
        val waitingForEditor = openEditors > 0
        LaunchedEffect(widgetDate) {
            if (widgetDateStep(widgetDate, openEditors) == WidgetDateStep.WAIT)
                Toast.makeText(app, widgetWaitMessage(openEventEditors, openTaskEditors), Toast.LENGTH_LONG).show()
        }
        LaunchedEffect(widgetDate, waitingForEditor) {
            if (widgetDateStep(widgetDate, openEditors) == WidgetDateStep.OPEN) {
                nav.clearBackStack(CALENDAR_ROUTE)
                nav.navigate("calendar?date=$widgetDate") {
                    popUpTo("agenda")
                }
                onWidgetOpened()
            }
        }
        LaunchedEffect(entryAction, draftChecked) {
            if (entryAction != null && draftChecked) {
                val existingDraft = runCatching { com.example.itinerary.data.EditorDraftStore(app).read() }
                if (existingDraft.isFailure || existingDraft.getOrNull() != null || recovered != null || shortcutItem != null ||
                    com.example.itinerary.data.EditorDraftStore.openEditors.value > 0) {
                    Toast.makeText(app, "Finish or discard your current draft before using a shortcut.", Toast.LENGTH_LONG).show()
                } else if (com.example.itinerary.EntryShortcuts.accepts(entryAction)) {
                    shortcutScan = entryAction == com.example.itinerary.EntryShortcuts.SCAN
                    shortcutItem = com.example.itinerary.data.ItineraryItem(tripId = 0, date = LocalDate.now(), startTime = null,
                        title = "", category = if (entryAction == com.example.itinerary.EntryShortcuts.ADD_BILL) "Bills" else "Other")
                }
                onEntryOpened()
            }
        }
        shortcutItem?.let { item ->
            val vm: TripsViewModel = viewModel(key = "shortcut-editor", factory = tripsFactory)
            val counts by vm.categoryCounts.collectAsStateWithLifecycle()
            val hidden by vm.hiddenCategories.collectAsStateWithLifecycle()
            ItemEditorSheet(initial = item, existingAttachments = emptyList(), existingReminders = emptyList(),
                categoryCounts = counts, hiddenCategories = hidden, onRemoveCategories = vm::removeCategories,
                onShowCategory = vm::showCategory, onDismiss = { shortcutItem = null }, startWithScan = shortcutScan,
                onSave = { event, added, removed, ar, rr, options -> vm.saveEvent(event, added, removed, ar, rr, options) },
                onDelete = { event, series -> app.repository.deleteWithUndo(event, series) })
        }
        if (draftChecked) recovered?.let { draft ->
            val vm: TripsViewModel = viewModel(key = "draft-recovery", factory = tripsFactory)
            val counts by vm.categoryCounts.collectAsStateWithLifecycle()
            val hidden by vm.hiddenCategories.collectAsStateWithLifecycle()
            ItemEditorSheet(
                initial = com.example.itinerary.data.DraftCodec.item(draft.getJSONObject("initial")),
                existingAttachments = com.example.itinerary.data.DraftCodec.attachments(draft.optJSONArray("existingAttachments")),
                existingReminders = com.example.itinerary.data.DraftCodec.reminders(draft.optJSONArray("existingReminders")),
                categoryCounts = counts, hiddenCategories = hidden, onRemoveCategories = vm::removeCategories,
                onShowCategory = vm::showCategory, onDismiss = { recovered = null },
                onSave = { item, added, removed, ar, rr, options -> vm.saveEvent(item, added, removed, ar, rr, options) },
                onDelete = { item, series -> app.repository.deleteWithUndo(item, series) })
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(8.dp))
    }
    val openEvents by com.example.itinerary.data.EditorDraftStore.openEditors.collectAsStateWithLifecycle()
    val openTasks by com.example.itinerary.data.TaskDraftStore.openEditors.collectAsStateWithLifecycle()
    SupportPromptHost(blocked = openEvents > 0 || openTasks > 0 || recovered != null)
    }

}

// What the widget's day waits for, named by what is open.
internal fun widgetWaitMessage(eventEditors: Int, taskEditors: Int): String = when {
    taskEditors == 0 -> "Close this event first. Then the widget's day opens."
    eventEditors == 0 -> "Close this task first. Then the widget's day opens."
    else -> "Close the open event and task first. Then the widget's day opens."
}

internal enum class WidgetDateStep { NOTHING, WAIT, OPEN }

// U3: the event draft on disk is recovered only while no event editor is open in this process. One open in another
// Planner window (a share, an .ics file or a shortcut can open a second one) is still writing that draft.
internal fun <T> draftToRecover(openEditors: Int, read: () -> T?): T? = if (openEditors > 0) null else read()

// What a widget tap does now: nothing to open, wait for the open editor(s) to be closed, or open the day.
internal fun widgetDateStep(widgetDate: LocalDate?, openEditors: Int): WidgetDateStep = when {
    widgetDate == null -> WidgetDateStep.NOTHING
    openEditors > 0 -> WidgetDateStep.WAIT
    else -> WidgetDateStep.OPEN
}

// The Undo bar after a deletion: "Event deleted", "3 tasks deleted", "4 items deleted" (events and tasks together).
internal fun deletedMessage(events: Int, tasks: Int): String {
    val count = events + tasks
    val noun = if (tasks == 0) "event" else if (events == 0) "task" else "item"
    return if (count == 1) "${noun.replaceFirstChar { it.uppercase() }} deleted" else "$count ${noun}s deleted"
}
