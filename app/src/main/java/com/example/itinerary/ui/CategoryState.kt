package com.example.itinerary.ui

import com.example.itinerary.data.Categories
import com.example.itinerary.data.Repository
import com.example.itinerary.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

// What every screen that offers event categories needs, in one place: how much each category is used (most used are
// offered first), which built-in categories the user removed, and removing or restoring them.
class CategoryState(
    private val repo: Repository,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
) {
    val counts: StateFlow<Map<String, Int>> = repo.categoryCounts
        .stateInWhileVisible(scope, emptyMap())

    // Built-in categories that stay out of the chips until added again.
    val hidden: StateFlow<Set<String>> = settings.hiddenCategories

    // Events using any of [names], in every plan, become Other. Built-in ones are also hidden from now on.
    fun remove(names: Set<String>) {
        if (names.isEmpty()) return
        settings.hideCategories(names.filter { it in Categories.BUILT_IN })
        scope.launch { repo.removeCategories(names) }
    }

    fun show(name: String) = settings.showCategory(name)
}
