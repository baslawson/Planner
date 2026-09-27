package com.example.itinerary.ui
import com.example.itinerary.ui.MatrixIconButton as IconButton
import com.example.itinerary.ui.MatrixOutlinedButton as OutlinedButton


import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.itinerary.data.ItineraryItem
import com.example.itinerary.data.PlanColors
import com.example.itinerary.data.Search
import com.example.itinerary.data.Trip
import com.example.itinerary.data.UpcomingPlans
import kotlinx.coroutines.launch
import java.time.LocalDate

// Manage plans: the plans themselves, reached from the agenda (the home screen). Creating, editing, ordering and
// deleting plans all happen here. [onOpenTrip]'s date is the day to open the plan on, from a tap on one of the events
// its card lists; null opens the plan on its own starting day, as tapping the rest of the card does. [onBack] is null
// only where the screen is shown on its own (the instrumented tests), and then there is no back arrow.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripsScreen(
    vm: TripsViewModel,
    onOpenTrip: (Long, LocalDate?) -> Unit,
    onOpenSearch: () -> Unit,
    onBack: (() -> Unit)? = null,
) {
    // Null until the database has answered; the screen stays blank until then rather than flashing "No plans yet".
    val loadedTrips by vm.trips.collectAsStateWithLifecycle()
    val trips = loadedTrips.orEmpty()
    // What each plan's card is made of; see TripList.
    val eventsByPlan by vm.eventsByPlan.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }
    // "Add quick event": the New event form, which makes a new plan named after the event when it is saved.
    var addingQuickEvent by remember { mutableStateOf(false) }
    val categoryCounts by vm.categoryCounts.collectAsStateWithLifecycle()
    val hiddenCategories by vm.hiddenCategories.collectAsStateWithLifecycle()
    val quickContext = LocalContext.current
    var addMenuOpen by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Trip?>(null) }
    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var confirmDelete by remember { mutableStateOf<List<Trip>?>(null) }
    val deletingPlans by vm.deletingPlans.collectAsStateWithLifecycle()
    val deletionError by vm.planDeletionError.collectAsStateWithLifecycle()
    fun cancelSelection() {
        selectionMode = false
        selectedIds = emptySet()
        confirmDelete = null
        vm.clearPlanDeletionError()
    }
    fun toggleSelection(id: Long) {
        if (!deletingPlans) selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
    }
    BackHandler(enabled = selectionMode && confirmDelete == null) { if (!deletingPlans) cancelSelection() }

    // List filter: a status chip plus optional text, kept across rotation.
    var chip by rememberSaveable { mutableStateOf(PlanFilter.ALL) }
    var filterText by rememberSaveable { mutableStateOf("") }
    val filtering = (chip != PlanFilter.ALL) || filterText.isNotBlank()

    // Plans that start soon (or are under way) can float to the top. Display only: the saved order is untouched.
    val upcomingOnTop by vm.upcomingOnTop.collectAsStateWithLifecycle()
    val upcomingDays by vm.upcomingDays.collectAsStateWithLifecycle()
    val addButtonSeeThrough by vm.addButtonSeeThrough.collectAsStateWithLifecycle()
    // Re-read when the app comes back to the front, so a list left open overnight catches up.
    var today by remember { mutableStateOf(LocalDate.now()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { today = LocalDate.now() }
    val arranged = remember(trips, today, upcomingOnTop, upcomingDays) {
        UpcomingPlans.arrange(trips, today, upcomingOnTop, upcomingDays)
    }
    val pinnedIds = remember(trips, today, upcomingOnTop, upcomingDays) {
        if (upcomingOnTop) trips.asSequence().filter { UpcomingPlans.isSoon(it, today, upcomingDays) }.mapTo(HashSet()) { it.id }
        else emptySet()
    }
    val visibleTrips = remember(arranged, chip, filterText, today) {
        arranged.matching(chip, filterText, today)
    }
    // Read on every recomposition (the selection bar, the effect below), so it is worked out once per list.
    val visibleIds = remember(visibleTrips) { visibleTrips.mapTo(HashSet()) { it.id } }
    LaunchedEffect(visibleIds) {
        selectedIds = selectedIds.intersect(visibleIds)
        if (trips.isEmpty() && !deletingPlans) cancelSelection()
    }

    Box(Modifier.fillMaxSize()) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    HeadingText(
                        "Manage plans",
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                    )
                },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack, enabled = !selectionMode) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to agenda")
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSearch, enabled = !selectionMode) {
                        Icon(Icons.Filled.Search, contentDescription = "Search")
                    }
                },
            )
        },
    ) { inner ->
        if (loadedTrips == null) {
            Box(Modifier.fillMaxSize().padding(inner))
        } else if (trips.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(inner).padding(32.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No plans yet", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Tap the + button, then New plan, to get started. Pick your dates, then fill each day with things to do, " +
                            "and add reminders so nothing slips by.",
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            Column(Modifier.fillMaxSize().padding(inner)) {
                // Only worth showing once there is something to narrow down (or a filter is still on).
                if (trips.size >= 2 || filtering) {
                    FilterBar(
                        text = filterText,
                        onText = { cancelSelection(); filterText = it },
                        selected = chip
                    ) { cancelSelection(); chip = it }
                }
                if (visibleTrips.isEmpty()) {
                    Column(Modifier.fillMaxWidth().padding(20.dp)) {
                        Text("No plans match this filter.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = { filterText = ""; chip = PlanFilter.ALL }) { Text("Show all plans") }
                    }
                } else {
                    TripList(
                        trips = visibleTrips,
                        eventsByPlan = eventsByPlan,
                        pinnedIds = pinnedIds,
                        autoArrangeEnabled = upcomingOnTop,
                        today = today,
                        // Reordering a filtered subset would be confusing, so dragging waits for the full list.
                        canDrag = !filtering,
                        onOpen = onOpenTrip,
                        onEdit = { editing = it },
                        onDelete = {
                            selectionMode = true
                            selectedIds = selectedIds + it.id
                        },
                        onReorder = vm::reorder,
                        selectionMode = selectionMode,
                        selectedIds = selectedIds,
                        onToggleSelection = ::toggleSelection,
                    )
                }
            }
        }
    }

    // The big + in the middle of the bottom edge, with its pop-up. Drawn over the list.
    if (selectionMode) {
        PlanSelectionBar(
            count = selectedIds.size,
            visibleCount = visibleIds.size,
            busy = deletingPlans,
            onCancel = ::cancelSelection,
            onDelete = {
                vm.clearPlanDeletionError()
                confirmDelete = visibleTrips.filter { it.id in selectedIds }.takeIf { it.isNotEmpty() }
            },
            onSelectAll = { selectedIds = if (selectedIds == visibleIds) emptySet() else visibleIds },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    } else AddButtonWithMenu(
        seeThroughPercent = addButtonSeeThrough,
        open = addMenuOpen,
        onOpenChange = { addMenuOpen = it },
        onNewPlan = { creating = true },
        onQuickEvent = { addingQuickEvent = true },
    )
    // The system Back key closes the pop-up first.
    BackHandler(enabled = addMenuOpen) { addMenuOpen = false }
    }

    if (creating) {
        TripEditDialog(
            initial = null,
            usedColors = trips.usedPaletteColors(),
            onDismiss = { creating = false },
            onSave = { vm.save(it); creating = false },
        )
    }
    if (addingQuickEvent) {
        ItemEditorSheet(
            initial = ItineraryItem(
                tripId = 0, // a plan is made for it when it is saved
                date = LocalDate.now(),
                startTime = null,
                title = "",
                // A new plan has no other events, so the first palette colour.
                colorIndex = PlanColors.next(emptyList(), PlanColors.EVENT_COUNT),
            ),
            existingAttachments = emptyList(),
            existingReminders = emptyList(),
            categoryCounts = categoryCounts,
            hiddenCategories = hiddenCategories,
            onRemoveCategories = vm::removeCategories,
            onShowCategory = vm::showCategory,
            onDismiss = { addingQuickEvent = false },
            onSave = { item, added, _, addedReminders, _, _ ->
                vm.createQuickEvent(item, added, addedReminders) { name ->
                    Toast.makeText(quickContext, "Created plan \"$name\"", Toast.LENGTH_LONG).show()
                }
            },
            onDelete = { _, _ -> },
        )
    }
    editing?.let { trip ->
        TripEditDialog(
            initial = trip,
            // Only used to pick the colour of a brand-new plan, which this isn't.
            usedColors = emptyList(),
            onDismiss = { editing = null },
            onSave = { vm.save(it); editing = null },
        )
    }
    confirmDelete?.let { plans ->
        ConfirmPlanDeletion(
            plans = plans,
            busy = deletingPlans,
            error = deletionError,
            onDismiss = { confirmDelete = null; vm.clearPlanDeletionError() },
            onConfirm = { vm.deletePlans(plans, ::cancelSelection) },
        )
    }
}
