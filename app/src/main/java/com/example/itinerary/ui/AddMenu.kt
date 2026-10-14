package com.example.itinerary.ui
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.example.itinerary.data.AddButtonPlace
import com.example.itinerary.data.AddMenuOrder
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt

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
    Spacer(Modifier.height(12.dp))
    Text(
        "Drag the + button to move it anywhere. In its menu, long press a row and drag it to change the order.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val settings = (LocalContext.current.applicationContext as com.example.itinerary.ItineraryApp).settings
    StackedButton("Reset the + button", settings::resetAddButton)
}

// The + button, dragged anywhere on the screen (a tap still opens its menu): [place] says where, and [onMoved] keeps a
// new place once it is let go. It stays inside the system bars with 16 dp to spare. TalkBack can move it with actions.
// [content] draws the button with the modifier that places it, told whether its menu should open downwards.
@Composable
private fun BoxScope.MovableAddButton(
    place: AddButtonPlace,
    onMoved: (AddButtonPlace) -> Unit,
    onReset: () -> Unit,
    content: @Composable (opensDown: Boolean, modifier: Modifier) -> Unit,
) {
    BoxWithConstraints(Modifier.matchParentSize().windowInsetsPadding(WindowInsets.systemBars).padding(16.dp),
        // Measured from the left in every layout direction (Hunt 27): the drag adds the finger's own movement.
        contentAlignment = androidx.compose.ui.AbsoluteAlignment.TopLeft) {
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val size = with(LocalDensity.current) { AddButtonSize.roundToPx() }
        // The button's top-left corner while a finger moves it; null when it rests at [place].
        var dragged by remember { mutableStateOf<Offset?>(null) }
        val resting = place.offset(width, height, size).let { (x, y) -> Offset(x.toFloat(), y.toFloat()) }
        val shown = dragged ?: resting
        val currentResting by rememberUpdatedState(resting)
        val currentOnMoved by rememberUpdatedState(onMoved)
        fun clamp(o: Offset) = Offset(o.x.coerceIn(0f, (width - size).coerceAtLeast(0).toFloat()), o.y.coerceIn(0f, (height - size).coerceAtLeast(0).toFloat()))
        fun nudge(dx: Float, dy: Float): Boolean { onMoved(AddButtonPlace.of(place.x + dx, place.y + dy)); return true }
        val opensDown = (dragged?.let { AddButtonPlace.fromOffset(it.x, it.y, width, height, size) } ?: place).opensDown
        content(opensDown, Modifier
            .absoluteOffset { IntOffset(shown.x.roundToInt(), shown.y.roundToInt()) }
            .pointerInput(width, height, size) {
                detectDragGestures(
                    onDragStart = { dragged = currentResting },
                    onDrag = { change, amount -> change.consume(); dragged = clamp((dragged ?: currentResting) + amount) },
                    onDragEnd = { dragged?.let { currentOnMoved(AddButtonPlace.fromOffset(it.x, it.y, width, height, size)) }; dragged = null },
                    onDragCancel = { dragged = null },
                )
            }
            .semantics {
                customActions = listOf(
                    CustomAccessibilityAction("Move button up") { nudge(0f, -0.1f) },
                    CustomAccessibilityAction("Move button down") { nudge(0f, 0.1f) },
                    CustomAccessibilityAction("Move button left") { nudge(-0.1f, 0f) },
                    CustomAccessibilityAction("Move button right") { nudge(0.1f, 0f) },
                    CustomAccessibilityAction("Put button back") { onReset(); true },
                )
            })
    }
}

// The +/close button with its menu of action rows. The menu opens above the button, or below it when [opensDown] (the
// button is in the top half), with the ✕ where the button was. The rows follow [order] top to bottom; a long press on a
// row and a drag moves it, and [onOrderChange] keeps the new order.
@Composable
fun AgendaActionsButton(
    seeThroughPercent: Int,
    open: Boolean,
    onOpenChange: (Boolean) -> Unit,
    onAddEvent: () -> Unit,
    onQuickEntry: () -> Unit,
    onAddTask: () -> Unit = {},
    onAddNote: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    opensDown: Boolean = false,
    order: List<String> = AddMenuOrder.DEFAULT,
    onOrderChange: (List<String>) -> Unit = {},
) {
    val configuration = LocalConfiguration.current
    val menuWidth = (configuration.screenWidthDp - 32).coerceIn(0, 320).dp
    // Hunt 20: from Android 15 the screen height includes the status and navigation bars; the menu stays clear of them.
    val systemBars = androidx.compose.foundation.layout.WindowInsets.systemBars.asPaddingValues()
    val gap = with(LocalDensity.current) { 12.dp.roundToPx() }
    // How far the menu was pushed sideways to stay on screen (the button near an edge): the ✕ is moved back by as much,
    // so it sits exactly where the + was.
    var closeShift by remember { mutableIntStateOf(0) }
    // The room from the button to the window's far edge (Hunt 27): the menu is kept within it, so it is never pushed back
    // over the button and the ✕ stays where the + was. Worked out from where the button sits while the menu is closed:
    // set while the popup opens (in its placing, then read while composing or laying out) it rebuilt the menu just as it
    // opened, and a tap could miss it.
    var anchorTop by remember { mutableIntStateOf(0) }
    var anchorBottom by remember { mutableIntStateOf(0) }
    val windowHeight = androidx.compose.ui.platform.LocalView.current.rootView.height
    val room = (if (opensDown) windowHeight - anchorTop - gap else anchorBottom - gap).takeIf { windowHeight > 0 && anchorBottom > 0 }
    val position = remember(gap, opensDown) {
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: androidx.compose.ui.unit.IntRect,
                windowSize: androidx.compose.ui.unit.IntSize, layoutDirection: androidx.compose.ui.unit.LayoutDirection,
                popupContentSize: androidx.compose.ui.unit.IntSize): IntOffset {
                val centred = anchorBounds.left + (anchorBounds.width - popupContentSize.width) / 2
                val x = centred.coerceIn(gap, (windowSize.width - popupContentSize.width - gap).coerceAtLeast(gap))
                closeShift = centred - x
                return IntOffset(x,
                    if (opensDown) anchorBounds.top.coerceAtMost(windowSize.height - popupContentSize.height - gap).coerceAtLeast(gap)
                    else (anchorBounds.bottom - popupContentSize.height).coerceAtLeast(gap),
                )
            }
        }
    }
    Box(modifier.onGloballyPositioned { coordinates ->
        val top = coordinates.positionInWindow().y.roundToInt()
        anchorTop = top; anchorBottom = top + coordinates.size.height
    }) {
        if (open) {
            // Keep the popup anchor in place; the popup draws the close button exactly once.
            Spacer(Modifier.size(AddButtonSize))
        } else {
            AddButtonSample(seeThroughPercent = seeThroughPercent, onClick = { onOpenChange(true) }, label = "Add menu")
        }
        if (open) Popup(popupPositionProvider = position, onDismissRequest = { onOpenChange(false) },
            properties = PopupProperties(focusable = true)) {
            val close: @Composable () -> Unit = {
                AddButtonSample(seeThroughPercent = seeThroughPercent, open = true,
                    onClick = { onOpenChange(false) },
                    modifier = Modifier.absoluteOffset { IntOffset(closeShift, 0) }.size(AddButtonSize))
            }
            // Bug hunt 19: scrolls when taller than the window (landscape, large text), starting by the button.
            Column(
                Modifier.width(menuWidth).heightIn(max = (configuration.screenHeightDp.dp - systemBars.calculateTopPadding() - systemBars.calculateBottomPadding() - 24.dp)
                    .let { screen -> room?.let { r -> minOf(screen, with(LocalDensity.current) { r.coerceAtLeast(0).toDp() }) } ?: screen }.coerceAtLeast(0.dp))
                    .verticalScroll(rememberScrollState(), reverseScrolling = !opensDown),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(MENU_GAP),
            ) {
                if (opensDown) close()
                // Bug notes 3: "Add note" opens a new note on the Notes screen (which has its own + for notes).
                val rows = order.filter { it != AddMenuOrder.NOTE || onAddNote != null }
                // The order counts from the button outwards (the last row is next to it), so a menu opening downwards is
                // mirrored: Quick entry stays next to the button either way. A row not shown here keeps its place.
                ReorderableRows(AddMenuOrder.onScreen(rows, opensDown), onOrderChange = { shown ->
                    val saved = AddMenuOrder.onScreen(shown, opensDown)
                    onOrderChange(saved.toMutableList().also { merged -> order.forEachIndexed { i, r -> if (r !in merged) merged.add(i.coerceAtMost(merged.size), r) } })
                }) { row, rowModifier, wasDragged ->
                    fun act(action: () -> Unit) { if (!wasDragged()) { onOpenChange(false); action() } }
                    when (row) {
                        AddMenuOrder.TASK -> AgendaActionRow("Add task", R.drawable.action_task, rowModifier) { act(onAddTask) }
                        AddMenuOrder.EVENT -> AgendaActionRow("Add event", R.drawable.shortcut_event, rowModifier) { act(onAddEvent) }
                        AddMenuOrder.NOTE -> AgendaActionRow("Add note", R.drawable.action_note, rowModifier) { act { onAddNote?.invoke() } }
                        AddMenuOrder.QUICK -> AgendaActionRow("Quick entry", R.drawable.action_quick_entry, rowModifier) { act(onQuickEntry) }
                    }
                }
                if (!opensDown) close()
            }
        }
    }
}

private val MENU_GAP = 12.dp

// The menu's rows, each moved by a long press and a drag: the row follows the finger and swaps places with a neighbour
// once it is past that neighbour's middle; letting go keeps the order. TalkBack has Move up / Move down on each row.
// [row] draws one with the modifier that does this; its wasDragged() says a long press just ended, so the tap that
// comes with lifting the finger doesn't also run the row's action.
@Composable
private fun ReorderableRows(
    order: List<String>,
    onOrderChange: (List<String>) -> Unit,
    row: @Composable (row: String, modifier: Modifier, wasDragged: () -> Boolean) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val gapPx = with(LocalDensity.current) { MENU_GAP.toPx() }
    var working by remember(order) { mutableStateOf(order) }
    var dragging by remember { mutableStateOf<String?>(null) }
    var offset by remember { mutableFloatStateOf(0f) }
    var longPressed by remember { mutableStateOf(false) }
    val heights = remember { mutableMapOf<String, Int>() }
    val currentOnOrderChange by rememberUpdatedState(onOrderChange)
    fun moveBy(id: String, steps: Int): Boolean {
        val from = working.indexOf(id)
        val to = from + steps
        if (from < 0 || to !in working.indices) return false
        working = AddMenuOrder.move(working, from, to)
        currentOnOrderChange(working)
        return true
    }
    working.forEach { id ->
        key(id) {
            val isDragged = dragging == id
            row(id, Modifier
                .onSizeChanged { heights[id] = it.height }
                .zIndex(if (isDragged) 1f else 0f)
                .graphicsLayer {
                    translationY = if (isDragged) offset else 0f
                    if (isDragged) { scaleX = 1.03f; scaleY = 1.03f; shadowElevation = 12f }
                }
                // Keyed on the order too (Hunt 27): after a drag or a TalkBack move is kept, the next drag works on the new order.
                .pointerInput(id, order) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = {
                            longPressed = true
                            dragging = id
                            offset = 0f
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        },
                        onDrag = { change, amount ->
                            change.consume()
                            offset += amount.y
                            val i = working.indexOf(id)
                            if (offset > 0 && i < working.lastIndex) {
                                val step = (heights[working[i + 1]] ?: 0) + gapPx
                                if (offset > step / 2) { working = AddMenuOrder.move(working, i, i + 1); offset -= step }
                            } else if (offset < 0 && i > 0) {
                                val step = (heights[working[i - 1]] ?: 0) + gapPx
                                if (-offset > step / 2) { working = AddMenuOrder.move(working, i, i - 1); offset += step }
                            }
                        },
                        onDragEnd = {
                            if (working != order) currentOnOrderChange(working)
                            dragging = null; offset = 0f; longPressed = false
                        },
                        onDragCancel = { working = order; dragging = null; offset = 0f; longPressed = false },
                    )
                }
                .semantics {
                    customActions = listOf(
                        CustomAccessibilityAction("Move up") { moveBy(id, -1) },
                        CustomAccessibilityAction("Move down") { moveBy(id, 1) },
                    )
                },
                { longPressed })
        }
    }
}

@Composable
private fun AgendaActionRow(label: String, icon: Int, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val foreground = MaterialTheme.colorScheme.primary
    Surface(onClick = onClick, border = BorderStroke(com.example.itinerary.ui.theme.controlBorderWidth(), foreground),
        shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = foreground, modifier = modifier.fillMaxWidth().heightIn(min = 64.dp)) {
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
    saveEvent: suspend (ItineraryItem, List<Attachment>, List<Attachment>, List<Reminder>, List<Reminder>, EventSaveOptions) -> Long,
    onEventSaved: (ItineraryItem) -> Unit = {},
    // Quick entry's "Add" closed it: the screen shows what was added.
    onQuickAdded: (QuickAdded) -> Unit = {},
    onAddNote: (() -> Unit)? = null,
) {
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    var quickEntry by rememberSaveable { mutableStateOf(false) }
    var choosingTaskType by rememberSaveable { mutableStateOf(false) }
    var newTaskId by rememberSaveable { mutableStateOf<String?>(null) }
    // T16-4: kept across process death, as the Quick entry draft it replaced was (that is cleared at the handover).
    var quickTask by rememberSaveable(stateSaver = androidx.compose.runtime.saveable.Saver<PlannerTask?, String>(
        save = { task -> task?.let { com.example.itinerary.data.TaskCodec.encode(listOf(it)).toString() } ?: "" },
        restore = { text -> text.takeIf { it.isNotEmpty() }?.let { com.example.itinerary.data.TaskCodec.decode(org.json.JSONArray(it)).single() } },
    )) { mutableStateOf<PlannerTask?>(null) }
    val repository = (LocalContext.current.applicationContext as com.example.itinerary.ItineraryApp).repository
    val settings = (LocalContext.current.applicationContext as com.example.itinerary.ItineraryApp).settings

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
        val place by settings.addButtonPlace.collectAsState()
        val order by settings.addMenuOrder.collectAsState()
        MovableAddButton(place, onMoved = settings::setAddButtonPlace, onReset = settings::resetAddButton) { opensDown, placed ->
            AgendaActionsButton(
                seeThroughPercent = seeThroughPercent,
                open = menuOpen,
                onOpenChange = { menuOpen = it },
                onAddEvent = { addEvent() },
                onQuickEntry = { quickEntry = true },
                onAddTask = { choosingTaskType = true },
                onAddNote = onAddNote,
                modifier = placed,
                opensDown = opensDown,
                order = order,
                onOrderChange = settings::setAddMenuOrder,
            )
        }
    }

    fun quickEvent(suggestion: QuickEntrySuggestion) = ItineraryItem(
        tripId = 0L, title = suggestion.title, date = suggestion.date, endDate = suggestion.endDate, startTime = suggestion.time,
        durationMinutes = suggestion.durationMinutes, location = suggestion.location, repeatRule = suggestion.repeat.name,
        colorIndex = PlanColors.next(usedColors(suggestion.date), PlanColors.EVENT_COUNT),
    )
    if (quickEntry) QuickEntryDialog(today, onDismiss = { quickEntry = false }, onAddedAndClosed = onQuickAdded,
        onAdd = { suggestion, task, token ->
            if (task) repository.saveTask(suggestion.quickTask().copy(id = token))
            // One event per time. The first keeps the draft's token and is saved last, so that token is only there
            // once every event is; a retried save skips the ones already in.
            else {
                val parts: List<QuickEntrySuggestion> = suggestion.eachTime()
                for (i in parts.indices.reversed()) {
                    val part = parts[i]
                    val event = quickEvent(part)
                    saveEvent(event, emptyList(), emptyList(), part.quickReminders(), emptyList(), EventSaveOptions(repeat = part.repeat, count = if (part.repeat == RepeatRule.NONE) 1 else part.repeatCount, draftToken = quickToken(token, i), anchorDay = part.repeatAnchorDay))
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
        // T16-4: what Quick entry handed over is unsaved until saved: Close asks rather than dropping it.
        key(task.id) { TaskEditor(task, true, prefilled = true) { quickTask = null } }
    }
    state.adding?.let { current ->
        val quick = state.quickDefaults
        ItemEditorSheet(
            initial = current,
            startWithBillScan = state.scanBill,
            initialAddedReminders = quick?.quickReminders().orEmpty(),
            initialRepeatCount = quick?.repeatCount ?: 12,
            initialRepeatAnchorDay = quick?.repeatAnchorDay ?: 0,
            // T16-4: handed over by Quick entry (its draft cleared), so Close asks rather than dropping it.
            prefilled = quick != null,
            existingAttachments = emptyList(),
            existingReminders = emptyList(),
            categoryCounts = categoryCounts,
            hiddenCategories = hiddenCategories,
            onRemoveCategories = onRemoveCategories,
            onShowCategory = onShowCategory,
            onDismiss = { state.adding = null; state.quickDefaults = null; planningTools.eventEditorDismissed() },
            onSave = { item, added, removed, addedReminders, removedReminders, options ->
                val id = saveEvent(item, added, removed, addedReminders, removedReminders, options)
                planningTools.eventSaved()
                onEventSaved(item)
                id
            },
            // Once saved, the new event stays open and can be deleted.
            onDelete = { item, series -> repository.deleteWithUndo(item, series) },
        )
    }
    if (choosingTaskType) TaskTypeDialog(
        onTask = { choosingTaskType = false; newTaskId = java.util.UUID.randomUUID().toString() },
        onBill = { choosingTaskType = false; addEvent(bill = true) },
        onScan = { choosingTaskType = false; addEvent(bill = true, scan = true) },
        onDismiss = { choosingTaskType = false })
    newTaskId?.let { id -> key(id) { TaskEditor(PlannerTask(id = id), true) { newTaskId = null } } }
}
