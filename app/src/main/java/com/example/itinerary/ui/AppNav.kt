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
import kotlinx.coroutines.flow.first
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
import com.example.itinerary.data.page
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
fun AppNav(sharedText: String? = null, sharedSubject: String? = null, onSharedOpened: () -> Unit = {}, widgetDate: LocalDate? = null, onWidgetOpened: () -> Unit = {}, entryAction: String? = null, onEntryOpened: () -> Unit = {}, calendarUri: android.net.Uri? = null, onCalendarOpened: () -> Unit = {}, widgetTaskId: String? = null, onWidgetTaskOpened: () -> Unit = {}, noteId: String? = null, onNoteOpened: () -> Unit = {}) {
    // Its id is kept with the window's saved state: a note draft names the window it was written in (NW-5).
    // NT-2: and whether it came back from that saved state (not started afresh), which decides whose old drafts it takes.
    val fresh = remember { booleanArrayOf(false) }
    val windowId = rememberSaveable { fresh[0] = true; java.util.UUID.randomUUID().toString() }
    val windowEditors = remember { WindowEditors(windowId, restored = !fresh[0]) }
    DisposableEffect(windowId) {
        com.example.itinerary.data.NoteDraftStore.windowOpened(windowId)
        onDispose { com.example.itinerary.data.NoteDraftStore.windowClosed(windowId) }
    }
    // Task ↔ event conversions asked for from a card's ⋮ or an editor (wish list #1), opened by ConversionHost below.
    val conversions = rememberConversions()
    CompositionLocalProvider(LocalWindowEditors provides windowEditors, LocalConversions provides conversions) {
    val nav = rememberNavController()
    val app = LocalContext.current.applicationContext as ItineraryApp
    // A share made into a note, handed to the Notes page, which opens it in a new note's editor. Kept with its words
    // until then, as the share itself is already cleared (SH-12).
    var sharedNote by rememberSaveable(stateSaver = androidx.compose.runtime.saveable.Saver<com.example.itinerary.data.PlannerNote?, String>(
        save = { it?.let { note -> com.example.itinerary.data.NoteCodec.encode(listOf(note)).toString() } ?: "" },
        restore = { text -> text.takeIf { it.isNotEmpty() }?.let { runCatching { com.example.itinerary.data.NoteCodec.decodeLenient(org.json.JSONArray(it)).firstOrNull() }.getOrNull() } },
    )) { mutableStateOf<com.example.itinerary.data.PlannerNote?>(null) }
    // SH-10: the share on screen. A second share reaching this window meanwhile waits until this one is closed the usual
    // way, "Save changes?" included, as a second widget task does: it used to replace it at once, dropping its editor
    // and leaving that editor's draft to block the new share. Saved, as the window keeps only the newest share: rebuilt
    // after Android closed Planner, the open one comes back and the other still waits (SQ-6).
    var shareOpen by rememberSaveable(stateSaver = androidx.compose.runtime.saveable.Saver<Pair<String, String?>?, ArrayList<String?>>(
        save = { open -> open?.let { arrayListOf(it.first, it.second) } },
        restore = { (it[0] ?: return@Saver null) to it[1] },
    )) { mutableStateOf(sharedText?.let { it to sharedSubject }) }
    val shareWanted by rememberUpdatedState(sharedText?.let { it to sharedSubject })
    LaunchedEffect(sharedText, sharedSubject, shareOpen) {
        val wanted = sharedText?.let { it to sharedSubject } ?: return@LaunchedEffect
        if (shareOpen == null) shareOpen = wanted
        else if (shareOpen != wanted) Toast.makeText(app, "Close this share first. Then the new one opens.", Toast.LENGTH_LONG).show()
    }
    val sharedEvent = shareOpen?.let { open -> key(open) {
        // With another share waiting it opens next; otherwise the share is done.
        SharedTextReview(open.first, open.second, onDismiss = {
            shareOpen = null
            if (shareWanted == null || shareWanted == open) onSharedOpened()
        }, onNote = { note ->
            sharedNote = note
            if (nav.currentDestination?.route != "notes") nav.navigate("notes") { launchSingleTop = true }
        })
    } }
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
    // U-13: the note a reminder's tap asked for, handed to the Notes page (see below).
    var noteToOpen by rememberSaveable { mutableStateOf<String?>(null) }
    var viewRestored by rememberSaveable { mutableStateOf(false) }
    // The intent that opened this window, before anything replaced it: a notification tap is not a plain start (SR-1).
    val launchIntent = LocalContext.current.findActivity()?.intent
    val launchedPlain = remember { com.example.itinerary.opensOnStartScreen(launchIntent?.action,
        launchIntent?.getBooleanExtra(com.example.itinerary.reminders.EXTRA_FROM_NOTIFICATION, false) == true) }
    LaunchedEffect(nav) {
        nav.currentBackStackEntryFlow.collect { entry ->
            val route = entry.destination.route
            if (!viewRestored) {
                viewRestored = true
                // Keep Agenda underneath Calendar so Back and the toggle still work.
                // Explicit widget dates take priority over the saved view/date.
                // Settings → Open Planner on (wish list #10). A widget day, shortcut, share, calendar file or reminder opens
                // what it is for instead, over Agenda as before.
                val page = app.settings.startScreen.value.page(app.settings.lastViewCalendar)
                val asked = widgetDate != null || noteId != null || sharedText != null || entryAction != null || calendarUri != null || widgetTaskId != null ||
                    !launchedPlain
                if (route == "agenda" && page == com.example.itinerary.data.StartScreen.NOTES && !asked) {
                    nav.navigate("notes") { launchSingleTop = true }
                    return@collect
                }
                if (route == "agenda" && page == com.example.itinerary.data.StartScreen.CALENDAR && widgetDate == null) {
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
        // Q-2: a share restored as a new event (after process death) reopens its own editor on its draft: not here too.
        draftToRecover(com.example.itinerary.data.EditorDraftStore.openEditors.value,
            // A task being made into an event after Android closed Planner reopens its own editor on its draft too.
            ownedElsewhere = { conversions.request?.startsWith("task:") == true && conversions.replacedFor != conversions.request ||
                sharedEvent != null && runCatching { com.example.itinerary.data.DraftCodec.item(it.getJSONObject("initial")) == sharedEvent }.getOrDefault(false) }) {
            com.example.itinerary.data.EditorDraftStore(app).read()
        }
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
                // A conversion's Undo waits until its editor closes: shown under that window, it would run out unseen (TE-4).
                if (next.madeInto != null) snapshotFlow { conversions.request }.first { it == null }
                val result = snackbar.showSnackbar(
                    message = next.madeInto?.let { if (it.taskId != null) "Made into a task" else "Made into an event" }
                        ?: deletedMessage(next.items.size, next.tasks.size, next.notes.size),
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
                            Toast.makeText(app, "Undo has expired or this bill changed; the payment stays.", Toast.LENGTH_LONG).show()
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
                    onOpenNotes = { nav.navigateFrom(entry, "notes") },
                    onOpenCalendar = { nav.openCalendar(entry) },
                )
            }
            composable("notes") { entry -> NotesScreen(onBack = { nav.popFrom(entry) }, openNoteId = noteToOpen, onNoteOpened = { noteToOpen = null },
                newNote = sharedNote, onNewNoteOpened = { sharedNote = null }) }
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
                        onOpenNotes = { nav.navigateFrom(entry, "notes") },
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
        // Only the editors in this window (U-N5): one in another Planner window (a share opened in the browser's task)
        // stays where it is. Snapshot state: the tap that brings Planner back sees the current count at once.
        val openEventEditors = windowEditors.events
        val openTaskEditors = windowEditors.tasks
        // A note being written here counts too (Q-1): moving to the day would close the Notes page with it (N6-6: one in
        // another window doesn't).
        val openNoteEditors = windowEditors.notes
        val openEditors = openEventEditors + openTaskEditors + openNoteEditors
        val waitingForEditor = openEditors > 0
        LaunchedEffect(widgetDate) {
            if (widgetDateStep(widgetDate, openEditors) == WidgetDateStep.WAIT)
                Toast.makeText(app, widgetWaitMessage(openEventEditors, openTaskEditors, openNoteEditors), Toast.LENGTH_LONG).show()
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
        // U-13: a note reminder's tap opens the Notes page with that note. An event or task editor open here is closed
        // first, as for the widget's day: moving to Notes would drop it with its draft.
        val noteWaits = openEventEditors + openTaskEditors > 0
        LaunchedEffect(noteId) { if (noteId != null && noteWaits) Toast.makeText(app, noteWaitMessage(openEventEditors, openTaskEditors), Toast.LENGTH_LONG).show() }
        LaunchedEffect(noteId, noteWaits) {
            if (noteId != null && !noteWaits) {
                noteToOpen = noteId
                if (nav.currentDestination?.route != "notes") nav.navigate("notes") { launchSingleTop = true }
                onNoteOpened()
            }
        }
        LaunchedEffect(entryAction, draftChecked) {
            if (entryAction != null && draftChecked) {
                val existingDraft = runCatching { com.example.itinerary.data.EditorDraftStore(app).read() }
                if (existingDraft.isFailure || existingDraft.getOrNull() != null || recovered != null || shortcutItem != null ||
                    com.example.itinerary.data.EditorDraftStore.openEditors.value > 0) {
                    Toast.makeText(app, shortcutBlockedMessage(windowEditors.events, com.example.itinerary.data.EditorDraftStore.openEditors.value), Toast.LENGTH_LONG).show()
                } else if (com.example.itinerary.EntryShortcuts.accepts(entryAction)) {
                    shortcutScan = entryAction == com.example.itinerary.EntryShortcuts.SCAN
                    shortcutItem = com.example.itinerary.data.ItineraryItem(tripId = 0, date = LocalDate.now(), startTime = null,
                        title = "", category = if (entryAction == com.example.itinerary.EntryShortcuts.ADD_BILL) "Bills" else "Other")
                }
                onEntryOpened()
            }
        }
        ConversionHost(conversions)
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
    val openNotes by com.example.itinerary.data.NoteDraftStore.openEditors.collectAsStateWithLifecycle()
    // Not over the update pop-up either (it carries the same support line): one ask at a time.
    val updateState by app.updates.state.collectAsStateWithLifecycle()
    val updateDismissed by app.updates.dismissed.collectAsStateWithLifecycle()
    val updateShown = app.updates.supported && !updateDismissed && updateState.offered() != null
    SupportPromptHost(blocked = openEvents > 0 || openTasks > 0 || openNotes > 0 || recovered != null || updateShown)
    }
    }
}

// What the widget's day waits for, named by what is open.
internal fun widgetWaitMessage(eventEditors: Int, taskEditors: Int, noteEditors: Int = 0): String = when {
    noteEditors > 0 && eventEditors == 0 && taskEditors == 0 -> "Close this note first. Then the widget's day opens."
    noteEditors > 0 -> "Close the open editors first. Then the widget's day opens."
    taskEditors == 0 -> "Close this event first. Then the widget's day opens."
    eventEditors == 0 -> "Close this task first. Then the widget's day opens."
    else -> "Close the open event and task first. Then the widget's day opens."
}

// What a note reminder's tap waits for (U-13), named by what is open.
internal fun noteWaitMessage(eventEditors: Int, taskEditors: Int): String = when {
    taskEditors == 0 -> "Close this event first. Then the note opens."
    eventEditors == 0 -> "Close this task first. Then the note opens."
    else -> "Close the open event and task first. Then the note opens."
}

// A shortcut waits for the one event draft Planner keeps, wherever its editor is open (U-N5: in another Planner
// window, such as a share opened in the browser's task, the user wouldn't see it from here).
internal fun shortcutBlockedMessage(windowEventEditors: Int, allEventEditors: Int): String =
    if (windowEventEditors == 0 && allEventEditors > 0) "Finish or discard the event open in another Planner window before using a shortcut."
    else "Finish or discard your current draft before using a shortcut."

/** The editors open in one Planner window (one AppNav), for what waits for them there (U-N5). */
internal class WindowEditors(val id: String = java.util.UUID.randomUUID().toString(), val restored: Boolean = false) {
    var events by mutableIntStateOf(0)
    var tasks by mutableIntStateOf(0)
    var notes by mutableIntStateOf(0)
}
internal val LocalWindowEditors = compositionLocalOf<WindowEditors?> { null }

internal enum class WidgetDateStep { NOTHING, WAIT, OPEN }

// U3: the event draft on disk is recovered only while no event editor is open in this process. One open in another
// Planner window (a share, an .ics file or a shortcut can open a second one) is still writing that draft.
// Nor one that [ownedElsewhere] says another editor composed alongside is reopening (Q-2: a restored share's event).
internal fun <T> draftToRecover(openEditors: Int, ownedElsewhere: (T) -> Boolean = { false }, read: () -> T?): T? =
    if (openEditors > 0) null else read()?.takeUnless(ownedElsewhere)

// What a widget tap does now: nothing to open, wait for the open editor(s) to be closed, or open the day.
internal fun widgetDateStep(widgetDate: LocalDate?, openEditors: Int): WidgetDateStep = when {
    widgetDate == null -> WidgetDateStep.NOTHING
    openEditors > 0 -> WidgetDateStep.WAIT
    else -> WidgetDateStep.OPEN
}

// The Undo bar after a deletion: "Event deleted", "3 tasks deleted", "Note deleted", "4 items deleted" (a mix).
internal fun deletedMessage(events: Int, tasks: Int, notes: Int = 0): String {
    val count = events + tasks + notes
    val noun = when {
        tasks == 0 && notes == 0 -> "event"
        events == 0 && notes == 0 -> "task"
        events == 0 && tasks == 0 -> "note"
        else -> "item"
    }
    return if (count == 1) "${noun.replaceFirstChar { it.uppercase() }} deleted" else "$count ${noun}s deleted"
}
