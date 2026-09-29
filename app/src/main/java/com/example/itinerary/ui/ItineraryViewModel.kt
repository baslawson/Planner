package com.example.itinerary.ui

import kotlinx.coroutines.async
import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.example.itinerary.data.Attachment
import com.example.itinerary.data.ItineraryItem
import com.example.itinerary.data.Reminder
import com.example.itinerary.data.Repository
import com.example.itinerary.data.SettingsRepository
import com.example.itinerary.data.OutsideInfo
import com.example.itinerary.data.toItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth

class ItineraryViewModel(
    private val repo: Repository,
    private val settings: SettingsRepository,
    startDate: LocalDate? = null, // set when arriving from a search result
    private val savedState: SavedStateHandle = SavedStateHandle(),
    // Events of ticked Nextcloud calendars (see CalendarSync.shown).
    outside: Flow<Map<Long, OutsideInfo>> = flowOf(emptyMap()),
) : ViewModel() {
    suspend fun deleteEvents(ids: Set<Long>) = viewModelScope.async {
        repo.deleteEventsWithUndo(ids)
    }.await()


    val calendarCollapsed: StateFlow<Boolean> = settings.calendarCollapsed

    fun setCalendarCollapsed(collapsed: Boolean) = settings.setCalendarCollapsed(collapsed)

    // How see-through the big + button is, in percent.
    val addButtonSeeThrough: StateFlow<Int> = settings.addButtonSeeThrough


    // Planner's own events plus those of ticked Nextcloud calendars (negative ids; tapping one opens a read-only view).
    val items: StateFlow<List<ItineraryItem>> = combine(repo.allItems, outside) { own, other ->
        if (other.isEmpty()) own else own + other.values.map { it.event.toItem(it.color) }
    }.stateInWhileVisible(viewModelScope, emptyList())

    val attachments: StateFlow<List<Attachment>> = repo.allAttachments
        .stateInWhileVisible(viewModelScope, emptyList())

    val reminders: StateFlow<List<Reminder>> = repo.allReminders
        .stateInWhileVisible(viewModelScope, emptyList())

    // Uses of each category across all events and which built-in ones are hidden, so the editor can offer the most used
    // ones first; and removing or restoring categories.
    private val categories = CategoryState(repo, settings, viewModelScope)
    val categoryCounts: StateFlow<Map<String, Int>> = categories.counts
    val hiddenCategories: StateFlow<Set<String>> = categories.hidden

    fun removeCategories(names: Set<String>) = categories.remove(names)

    fun showCategory(name: String) = categories.show(name)

    private val restoredDate = savedState.get<String>("selectedDate")?.let {
        runCatching { LocalDate.parse(it) }.getOrNull()
    }
    private val _selected = MutableStateFlow(restoredDate ?: startDate ?: settings.lastCalendarDate ?: LocalDate.now())
    val selected: StateFlow<LocalDate> = _selected.asStateFlow()

    private val _month = MutableStateFlow(
        savedState.get<String>("displayedMonth")?.let { runCatching { YearMonth.parse(it) }.getOrNull() }
            ?: startDate?.takeIf { restoredDate == null }?.let(YearMonth::from)
            ?: settings.lastCalendarMonth ?: YearMonth.from(_selected.value),
    )
    val month: StateFlow<YearMonth> = _month.asStateFlow()

    init {
        settings.lastCalendarDate = _selected.value
        settings.lastCalendarMonth = _month.value
        savedState["selectedDate"] = _selected.value.toString()
        savedState["displayedMonth"] = _month.value.toString()
    }

    fun select(date: LocalDate) {
        _selected.value = date
        settings.lastCalendarDate = date
        savedState["selectedDate"] = date.toString()
    }

    fun showMonth(month: YearMonth) {
        _month.value = month
        settings.lastCalendarMonth = month
        savedState["displayedMonth"] = month.toString()
    }

    suspend fun saveItem(
        item: ItineraryItem,
        added: List<Attachment> = emptyList(),
        removed: List<Attachment> = emptyList(),
        addedReminders: List<Reminder> = emptyList(),
        removedReminders: List<Reminder>,
        options: com.example.itinerary.data.EventSaveOptions = com.example.itinerary.data.EventSaveOptions(),
    ) {
        repo.saveItem(item, added, removed, addedReminders, removedReminders, options)
    }

    suspend fun moveToTomorrow(id: Long) = repo.moveToTomorrow(id)

    suspend fun deleteItem(item: ItineraryItem, entireSeries: Boolean) {
        repo.deleteWithUndo(item, entireSeries)
    }
}
