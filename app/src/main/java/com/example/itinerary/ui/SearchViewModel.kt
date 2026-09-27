package com.example.itinerary.ui

import kotlinx.coroutines.async
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.itinerary.data.Repository
import com.example.itinerary.data.Search
import com.example.itinerary.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn

// Prepare stored text off the UI thread and reuse it until the underlying data changes.
class SearchViewModel(private val repo: Repository, settings: SettingsRepository) : ViewModel() {
    suspend fun deleteEvents(ids: Set<Long>) = viewModelScope.async {
        repo.deleteEventsWithUndo(ids)
    }.await()

    val tasks = repo.tasks.stateInWhileVisible(viewModelScope, emptyList())
    private val categories = CategoryState(repo, settings, viewModelScope)
    val categoryCounts: StateFlow<Map<String, Int>> = categories.counts
    val hiddenCategories: StateFlow<Set<String>> = categories.hidden

    val index: StateFlow<Search.Index> = combine(
        repo.trips.distinctUntilChanged(),
        repo.allItems.distinctUntilChanged(),
        repo.allAttachments.distinctUntilChanged(),
        repo.tasks.distinctUntilChanged(),
    ) { trips, items, attachments, tasks -> Search.prepare(trips, items, attachments, tasks) }
        .flowOn(Dispatchers.Default)
        .stateInWhileVisible(viewModelScope, Search.Index.EMPTY)
}
