package com.example.itinerary.ui
import com.example.itinerary.ui.MatrixIconButton as IconButton
import com.example.itinerary.ui.MatrixFilterChip as FilterChip

import com.example.itinerary.data.billTaskSummary

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.itinerary.data.Categories
import com.example.itinerary.data.Search
import com.example.itinerary.data.SearchHit
import com.example.itinerary.data.OutsideCalendars
import com.example.itinerary.data.SearchOutcome
import java.time.LocalDate
import androidx.compose.runtime.key
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive

internal data class SearchRequest(val query: String, val categories: Set<String>, val today: LocalDate)

// What a search shows: the latest finished result if it answers this very request ([last] says which it answered),
// else "Searching…". A new index alone (any data change) keeps the last result on screen until the new one is ready.
internal fun shownOutcome(request: SearchRequest, last: Pair<SearchRequest, SearchOutcome>?): SearchOutcome =
    last?.takeIf { it.first == request }?.second ?: SearchOutcome.LOADING

@Composable
internal fun rememberSearchOutcome(
    query: String,
    categories: Set<String>,
    index: Search.Index,
    today: LocalDate = rememberCurrentDate(),
): SearchOutcome {
    val request = SearchRequest(query, categories, today)
    var last by remember { mutableStateOf<Pair<SearchRequest, SearchOutcome>?>(null) }
    // Restarted (the old run cancelled) whenever the request or the index changes, so a slow obsolete result can never
    // replace a newer one.
    LaunchedEffect(request, index) {
        val result = withContext(Dispatchers.Default) {
            val workerContext = coroutineContext
            index.run(query, categories, today) { workerContext.ensureActive() }
        }
        last = request to result
    }
    return shownOutcome(request, last)
}

private val WORD = Regex("[\\p{L}\\p{N}]+")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    vm: SearchViewModel,
    onBack: () -> Unit,
    onOpenResult: (date: LocalDate) -> Unit,
) {
    val index by vm.index.collectAsStateWithLifecycle()
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    var editingBillId by rememberSaveable { mutableStateOf<Long?>(null) }
    var editingTaskId by rememberSaveable { mutableStateOf<String?>(null) }
    var showCompleted by rememberSaveable { mutableStateOf(false) }
    val today = rememberCurrentDate()
    val categoryCounts by vm.categoryCounts.collectAsStateWithLifecycle()
    val hiddenCategories by vm.hiddenCategories.collectAsStateWithLifecycle()

    var query by rememberSaveable { mutableStateOf("") }
    var categories by rememberSaveable { mutableStateOf(emptySet<String>()) }
    // Most used first, the user's own categories included. Other only shows once something uses it.
    val categoryChips = remember(categoryCounts, hiddenCategories, categories, tasks.isNotEmpty(), categoryCounts["Bills"]) {
        val shown = Categories.ordered(categoryCounts, hiddenCategories) + listOfNotNull(Categories.OTHER.takeIf { (categoryCounts[it] ?: 0) > 0 })
        (shown + (categories - shown.toSet()) + if (tasks.isNotEmpty() || (categoryCounts["Bills"] ?: 0) > 0) listOf("Tasks") else emptyList()).distinct()
    }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    // Stored text is already indexed; only the query and matching work changes while typing.
    val outcome = rememberSearchOutcome(query, categories, index, today)
    val visibleOutcome = remember(outcome, showCompleted) {
        SearchOutcome(outcome.hits.filter { it.item.category != "Bills" || showCompleted || !it.item.paid && !it.item.skipped },
            outcome.tokens, outcome.dateLabels, outcome.invalidDates, outcome.taskHits)
    }
    val taskHits = remember(outcome, showCompleted) { outcome.taskHits.filter { showCompleted || !it.done } }
    val searching = query.isNotBlank() || categories.isNotEmpty()

    val selectable = remember(searching, visibleOutcome) {
        if (searching && visibleOutcome.invalidDates.isEmpty()) visibleOutcome.hits.filterNot { OutsideCalendars.isOutside(it.item.id) }.map { SelectableEvent(it.item.id, it.item.title, it.item.date, bill = it.item.category == "Bills") } else emptyList()
    }
    val selectableTasks = remember(searching, taskHits, visibleOutcome) {
        if (searching && visibleOutcome.invalidDates.isEmpty()) taskHits.map { SelectableTask(it.id, it.title, it.dueDate) } else emptyList()
    }
    val selection = rememberEventSelection(selectable, prune = outcome !== SearchOutcome.LOADING, visibleTasks = selectableTasks)

    // Bill results open their ⋮ menu through this, drawn above the whole screen.
    val overlayMenu = remember { OverlayMenuState() }
    OverlayMenuScreen(overlayMenu) {
        Scaffold(
            bottomBar = { EventSelectionBar(selection, selectable, vm::deleteEvents, selectableTasks) },
            topBar = {
                TopAppBar(
                    title = { HeadingText("Search", style = MaterialTheme.typography.titleLarge) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                )
            },
        ) { inner ->
            Column(Modifier.fillMaxSize().padding(inner).imePadding()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it.replace('\n', ' ') },
                    label = { Text("Search events and tasks") },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                    isError = outcome.invalidDates.isNotEmpty(),
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(Icons.Filled.Close, contentDescription = "Clear search")
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .focusRequester(focusRequester),
                )
                // Only the search box stays put; the controls under it scroll away with the results, so a short
                // screen (landscape, large text) still shows what was found.
                val grouped = remember(visibleOutcome) { groupResults(visibleOutcome) }
                LazyScrollHints(Modifier.fillMaxSize()) { hintState -> LazyColumn(Modifier.fillMaxSize(), state = hintState, contentPadding = PaddingValues(bottom = 24.dp)) {
                    item(key = "search-controls") { Column {
                        SavedSearchControls(query, categories, showCompleted) { saved ->
                            query = saved.query; categories = saved.categories; showCompleted = saved.showCompleted
                        }
                        // The categories work as tags: pick one or more to narrow the search.
                        Row(
                            Modifier
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            categoryChips.forEach { category ->
                                FilterChip(
                                    selected = category in categories,
                                    onClick = {
                                        categories = if (category in categories) categories - category else categories + category
                                    },
                                    label = { Text(category) },
                                )
                            }
                        }
                        if (tasks.any { it.done } || (categoryCounts["Bills"] ?: 0) > 0) FilterChip(selected = showCompleted, enabled = !selection.active,
                            onClick = { showCompleted = !showCompleted }, label = { Text("Show completed tasks") }, modifier = Modifier.padding(horizontal = 16.dp))
                        if (outcome.dateLabels.isNotEmpty()) {
                            Text(
                                "Date: " + outcome.dateLabels.joinToString(" or "),
                                modifier = Modifier.padding(horizontal = 16.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } }
                    when {
                        searching && outcome === SearchOutcome.LOADING -> item(key = "hint") { Hint("Searching…") }
                        outcome.invalidDates.isNotEmpty() -> item(key = "hint") { Hint(
                            (if (outcome.invalidDates.size == 1) "Invalid date: " else "Invalid dates: ") +
                                outcome.invalidDates.joinToString(", ") + ". Check the year, month and day.",
                            isError = true,
                        ) }
                        !searching -> item(key = "hint") { Hint(
                            "Search tasks, event titles, places, notes, attachment names and recognised document text. " +
                                "Try words like tomorrow, next friday or 12 june, or pick a category above.",
                        ) }
                        visibleOutcome.hits.isEmpty() && taskHits.isEmpty() -> item(key = "hint") { Hint("Nothing found. Try fewer words or check the spelling.") }
                        else -> results(visibleOutcome, grouped, selection, onOpenResult, taskHits, today, onBill = { editingBillId = it }) { editingTaskId = it.id }
                    }
                } }
            }
        }
    }
    editingBillId?.let { id -> BillTaskEditor(id) { editingBillId = null } }
    editingTaskId?.let { id -> tasks.find { it.id == id }?.let { task ->
        key(id) { TaskEditor(task, creating = false) { editingTaskId = null } }
    } }
}

@Composable
private fun Hint(text: String, isError: Boolean = false) {
    Text(
        text,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private class GroupedResults(val dayGroups: Map<LocalDate, List<SearchHit>>, val billHits: List<SearchHit>)

private fun groupResults(outcome: SearchOutcome) = GroupedResults(
    outcome.hits.filter { it.item.category != "Bills" }.groupBy { it.item.date }.toSortedMap().mapValues { (_, hits) ->
        hits.sortedWith(compareBy<SearchHit> { it.item.startTime != null }
            .thenBy { it.item.startTime }.thenBy { it.item.id })
    },
    outcome.hits.filter { it.item.category == "Bills" }.sortedBy { it.item.date },
)

private fun LazyListScope.results(outcome: SearchOutcome, grouped: GroupedResults, selection: EventSelection, onOpenResult: (LocalDate) -> Unit,
    taskHits: List<com.example.itinerary.data.PlannerTask>, today: LocalDate, onBill: (Long) -> Unit, onTask: (com.example.itinerary.data.PlannerTask) -> Unit) {
    val dayGroups = grouped.dayGroups
    val billHits = grouped.billHits
    val count = outcome.hits.size + taskHits.size
    item(key = "count") {
        Text(
            if (count == 1) "1 result" else "$count results",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (taskHits.isNotEmpty() || billHits.isNotEmpty()) {
        item(key = "tasks-heading") { HeadingText("Tasks", Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.titleMedium) }
        billHits.forEach { hit -> item(key = "bill-${hit.item.id}") {
            BillTaskCard(hit.item.billTaskSummary(), today, selection, hit.documentName) { onBill(hit.item.id) }
        } }
        taskHits.forEach { task -> item(key = "task-${task.id}") { TaskCard(task, today, selection = selection) { onTask(task) } } }
    }
    dayGroups.forEach { (date, hits) ->
        item(key = "day-$date") {
            HeadingText(
                date.dayLabel(LocalDateFormat.current),
                modifier = Modifier.padding(horizontal = 16.dp).padding(top = 12.dp, bottom = 4.dp),
                style = MaterialTheme.typography.titleMedium,
            )
        }
        hits.forEach { hit ->
            item(key = "item-${hit.item.id}") {
                HitRow(hit, outcome.tokens, selection, today) {
                    if (selection.active) { if (!OutsideCalendars.isOutside(hit.item.id)) selection.toggle(hit.item.id) }
                    else onOpenResult(hit.item.date)
                }
            }
        }
    }
}

@Composable
private fun HitRow(hit: SearchHit, tokens: List<String>, selection: EventSelection, today: LocalDate, onClick: () -> Unit) {
    val item = hit.item
    // Same colours as in the plan's day list, so an event looks the same everywhere.
    val accent = item.accentColor()
    val outside = LocalOutsideEvents.current[item.id]
    TappableRow(onClick = onClick, onLongClick = if (outside != null) null else ({ selection.toggle(item.id) }),
        selected = (item.id in selection.ids).takeIf { selection.active }, arrow = !selection.active, tint = outside?.let { androidx.compose.ui.graphics.Color(it.color) }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(
            item.startTime?.label(LocalTimeFormat.current, LocalContext.current) ?: "All day",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.width(76.dp),
        )
        Box(
            Modifier
                .width(4.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(2.dp))
                .background(accent),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            EventTitle(highlight(item.title, tokens), readableOnSurface(accent), item.category == "Bills")
            if (outside == null) SyncMarkLabel(item.id)
            // Grouped under its first day, so say how far it runs; a match on a later day is inside this span.
            item.endDate?.let { end -> Text(spanLabel(item.date, end), style = MaterialTheme.typography.bodySmall) }
            if (item.durationMinutes != null && item.startTime != null) Text(
                eventEndLabel(item.date, item.startTime, item.durationMinutes, LocalTimeFormat.current, LocalContext.current),
                style = MaterialTheme.typography.bodySmall,
            )
            hit.documentName?.let { Text("Matches document: $it", style = MaterialTheme.typography.bodySmall) }
            if (item.category == "Bills") {
                BillStatus(item.paid)
                BillBalance(item.billAmountMinor, item.billCurrency, item.paid, item.payments)
                OverdueBill(item.date, item.paid, item.skipped, today)
            }
            if (item.skipped) Text("Skipped", style = MaterialTheme.typography.labelMedium)
            if (item.location.isNotBlank()) {
                Text(
                    highlight(item.location, tokens),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (outside != null) OutsideEventLabel(outside)
            else if (item.category != "Bills") Text(
                item.category,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (selection.active && outside == null) androidx.compose.material3.Checkbox(checked = item.id in selection.ids, onCheckedChange = null)
    }
}

// Bolds the words that matched. Whole words are highlighted, which stays correct for accents and typos.
@Composable
private fun highlight(text: String, tokens: List<String>): AnnotatedString {
    if (tokens.isEmpty() || text.isEmpty()) return AnnotatedString(text)
    val color = MaterialTheme.colorScheme.primary
    return remember(text, tokens, color) {
        buildAnnotatedString {
            var last = 0
            WORD.findAll(text).forEach { match ->
                val word = Search.normalize(match.value)
                if (tokens.any { Search.matchesWord(word, it) }) {
                    append(text.substring(last, match.range.first))
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = color)) { append(match.value) }
                    last = match.range.last + 1
                }
            }
            append(text.substring(last))
        }
    }
}
