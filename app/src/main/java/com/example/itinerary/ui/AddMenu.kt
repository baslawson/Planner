package com.example.itinerary.ui
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import com.example.itinerary.data.Attachment
import com.example.itinerary.data.EventSaveOptions
import com.example.itinerary.data.ItineraryItem
import com.example.itinerary.data.PlanColors
import com.example.itinerary.data.PlannerTask
import com.example.itinerary.data.QuickEntrySuggestion
import com.example.itinerary.data.Reminder
import com.example.itinerary.data.RepeatRule
import com.example.itinerary.data.eachTime
import com.example.itinerary.data.quickToken
import com.example.itinerary.data.quickReminders
import com.example.itinerary.data.quickTask
import java.time.LocalDate

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.example.itinerary.R
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.itinerary.data.AddButton

private val AddButtonSize = 84.dp

// The colour of the big + button: teal, see-through by [seeThroughPercent] (0 = solid). The + stays solid.
@Composable
private fun addButtonFill(seeThroughPercent: Int): Color =
    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 1f - seeThroughPercent.coerceIn(0, 100) / 100f)

// The big + button on its own: in Settings as a live sample, and on the agenda to add an event ([label] is what a
// screen reader says).
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

// A new event being added: from the big + menu, or handed over by a planning tool. [quickDefaults] carries what Quick
// entry understood when its suggestion is opened for review.
class NewEventState {
    var adding by mutableStateOf<ItineraryItem?>(null)
    var scanBill by mutableStateOf(false)
    var quickDefaults by mutableStateOf<QuickEntrySuggestion?>(null)

    fun start(item: ItineraryItem, scan: Boolean = false, quick: QuickEntrySuggestion? = null) {
        adding = item; scanBill = scan; quickDefaults = quick
    }
}

// The big + and its menu (Add task, Add event, Quick entry) with everything they open, shared by the agenda and the
// calendar. A new event starts on [day]; [usedColors] lists the palette colours a day's events already take, so it
// gets the one used least. [onEventSaved] follows each new event once it is saved.
@Composable
fun BoxScope.AddMenuHost(
    state: NewEventState,
    showButton: Boolean,
    day: LocalDate,
    today: LocalDate,
    usedColors: (LocalDate) -> List<Int>,
    seeThroughPercent: Int,
    categoryCounts: Map<String, Int>,
    hiddenCategories: Set<String>,
    onRemoveCategories: (Set<String>) -> Unit,
    onShowCategory: (String) -> Unit,
    planningTools: PlanningToolsState,
    saveEvent: suspend (ItineraryItem, List<Attachment>, List<Attachment>, List<Reminder>, List<Reminder>, EventSaveOptions) -> Unit,
    onEventSaved: (ItineraryItem) -> Unit = {},
) {
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    var quickEntry by rememberSaveable { mutableStateOf(false) }
    var choosingTaskType by rememberSaveable { mutableStateOf(false) }
    var newTaskId by rememberSaveable { mutableStateOf<String?>(null) }
    var quickTask by remember { mutableStateOf<PlannerTask?>(null) }
    val repository = (LocalContext.current.applicationContext as com.example.itinerary.ItineraryApp).repository

    fun addEvent(bill: Boolean = false, scan: Boolean = false) {
        if (state.adding != null) return
        state.start(ItineraryItem(
            // Storage ownership is assigned when the new event is saved.
            tripId = 0L,
            date = day,
            startTime = null,
            title = "",
            category = if (bill) "Bills" else "Other",
            colorIndex = PlanColors.next(usedColors(day), PlanColors.EVENT_COUNT),
        ), scan = scan)
    }

    if (showButton) {
        if (menuOpen) {
            // The popup stays bright above this scrim; its outside-touch handler dismisses it.
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
                    onClickLabel = "Close add menu") { menuOpen = false })
        }
        AgendaActionsButton(
            seeThroughPercent = seeThroughPercent,
            open = menuOpen,
            onOpenChange = { menuOpen = it },
            onAddEvent = { addEvent() },
            onQuickEntry = { quickEntry = true },
            onAddTask = { choosingTaskType = true },
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 16.dp),
        )
    }

    fun quickEvent(suggestion: QuickEntrySuggestion) = ItineraryItem(
        tripId = 0L, title = suggestion.title, date = suggestion.date, endDate = suggestion.endDate, startTime = suggestion.time,
        durationMinutes = suggestion.durationMinutes, location = suggestion.location, repeatRule = suggestion.repeat.name,
        colorIndex = PlanColors.next(usedColors(suggestion.date), PlanColors.EVENT_COUNT),
    )
    if (quickEntry) QuickEntryDialog(today, onDismiss = { quickEntry = false },
        onAdd = { suggestion, task, token ->
            if (task) repository.saveTask(suggestion.quickTask().copy(id = token))
            // One event per time. The first keeps the draft's token and is saved last, so that token is only there
            // once every event is; a retried save skips the ones already in.
            else {
                val parts: List<QuickEntrySuggestion> = suggestion.eachTime()
                for (i in parts.indices.reversed()) {
                    val part = parts[i]
                    val event = quickEvent(part)
                    saveEvent(event, emptyList(), emptyList(), part.quickReminders(), emptyList(), EventSaveOptions(repeat = part.repeat, count = if (part.repeat == RepeatRule.NONE) 1 else part.repeatCount, draftToken = quickToken(token, i)))
                    onEventSaved(event)
                }
            }
        },
        onReview = { suggestion, task ->
            if (task) quickTask = suggestion.quickTask()
            else state.start(quickEvent(suggestion), quick = suggestion)
            quickEntry = false
        })
    quickTask?.let { task ->
        key(task.id) { TaskEditor(task, true) { quickTask = null } }
    }
    state.adding?.let { current ->
        val quick = state.quickDefaults
        ItemEditorSheet(
            initial = current,
            startWithBillScan = state.scanBill,
            initialAddedReminders = quick?.quickReminders().orEmpty(),
            initialRepeatCount = quick?.repeatCount ?: 12,
            existingAttachments = emptyList(),
            existingReminders = emptyList(),
            categoryCounts = categoryCounts,
            hiddenCategories = hiddenCategories,
            onRemoveCategories = onRemoveCategories,
            onShowCategory = onShowCategory,
            onDismiss = { state.adding = null; state.quickDefaults = null; planningTools.eventEditorDismissed() },
            onSave = { item, added, removed, addedReminders, removedReminders, options ->
                saveEvent(item, added, removed, addedReminders, removedReminders, options)
                planningTools.eventSaved()
                onEventSaved(item)
            },
            // A new event has nothing to delete.
            onDelete = { _, _ -> },
        )
    }
    if (choosingTaskType) TaskTypeDialog(
        onTask = { choosingTaskType = false; newTaskId = java.util.UUID.randomUUID().toString() },
        onBill = { choosingTaskType = false; addEvent(bill = true) },
        onScan = { choosingTaskType = false; addEvent(bill = true, scan = true) },
        onDismiss = { choosingTaskType = false })
    newTaskId?.let { id -> key(id) { TaskEditor(PlannerTask(id = id), true) { newTaskId = null } } }
}
