package com.example.itinerary.data

// A category is plain text on each event. A few are built in; anything else is a category the
// user made up, which exists for as long as some event uses it.
object Categories {
    const val OTHER = "Other"
    const val MAX_LENGTH = 24

    val BUILT_IN = listOf("Flight", "Stay", "Food", "Bills", "Transport")

    private val SPACES = Regex("\\s+")

    // Turns typed text into the name to store, or null if nothing usable was typed. A name that matches a
    // built-in category or one in [inUse] (ignoring capitals) reuses that spelling, so "food" picks Food.
    fun clean(input: String, inUse: Collection<String> = emptyList()): String? {
        val text = input.trim().replace(SPACES, " ").take(MAX_LENGTH).trim()
        if (text.isEmpty()) return null
        return (BUILT_IN + OTHER + inUse).firstOrNull { it.equals(text, ignoreCase = true) } ?: text
    }

    // The categories to offer as chips, most used first. Ties keep the built-in order, then the user's own
    // in alphabetical order. Other is left out because it has its own button. A built-in the user removed
    // (in [hidden]) stays out, unless some event still has it.
    fun ordered(counts: Map<String, Int>, hidden: Set<String> = emptySet()): List<String> {
        val own = counts.keys.filter { it != OTHER && it !in BUILT_IN }.sortedBy { it.lowercase() }
        val builtIn = BUILT_IN.filter { it !in hidden || (counts[it] ?: 0) > 0 }
        return (builtIn + own).sortedByDescending { counts[it] ?: 0 } // a stable sort, so ties stay put
    }

    // Before 20 Sep 2026 categories were a fixed list stored by these upper-case names.
    fun fromLegacy(name: String): String = when (name) {
        "FLIGHT" -> "Flight"
        "STAY" -> "Stay"
        "FOOD" -> "Food"
        "SIGHT" -> "Sightseeing"
        "TRANSPORT" -> "Transport"
        "OTHER" -> OTHER
        else -> name
    }
}
