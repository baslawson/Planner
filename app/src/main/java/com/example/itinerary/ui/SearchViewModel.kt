package com.example.itinerary.ui

import kotlinx.coroutines.async
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.itinerary.data.Repository
import com.example.itinerary.data.Search
import com.example.itinerary.data.SettingsRepository
import com.example.itinerary.data.OutsideCalendars
import com.example.itinerary.data.OutsideInfo
import com.example.itinerary.data.Trip
import com.example.itinerary.data.toItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import java.time.LocalDate
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn

// Prepare stored text off the UI thread and reuse it until the underlying data changes.
class SearchViewModel(
    private val repo: Repository,
    settings: SettingsRepository,
    // Events of ticked Nextcloud calendars (see CalendarSync.shown); they are found like any other event.
    outside: Flow<Map<Long, OutsideInfo>> = flowOf(emptyMap()),
) : ViewModel() {
    suspend fun deleteEvents(ids: Set<Long>) = viewModelScope.async {
        repo.deleteEventsWithUndo(ids)
    }.await()

    val tasks = repo.tasks.stateInWhileVisible(viewModelScope, emptyList())
    private val categories = CategoryState(repo, settings, viewModelScope)
    val categoryCounts: StateFlow<Map<String, Int>> = categories.counts
    val hiddenCategories: StateFlow<Set<String>> = categories.hidden

    val index: StateFlow<Search.Index> = combine(
        repo.trips.distinctUntilChanged(),
        combine(repo.allItems.distinctUntilChanged(), outside) { own, other -> own to other.values.map { it.event.toItem(it.color) } },
        repo.allAttachments.distinctUntilChanged(),
        repo.tasks.distinctUntilChanged(),
    ) { trips, (items, other), attachments, tasks ->
        // Search keeps only events whose plan it knows, so outside events get a made-up plan of their own.
        val outsideTrip = if (other.isEmpty()) emptyList() else listOf(Trip(OutsideCalendars.TRIP_ID, "Nextcloud calendars", "",
            LocalDate.MIN, LocalDate.MAX))
        Search.prepare(trips + outsideTrip, items + other, attachments, tasks)
    }
        .flowOn(Dispatchers.Default)
        .stateInWhileVisible(viewModelScope, Search.Index.EMPTY)
}
