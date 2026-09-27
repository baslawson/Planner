package com.example.itinerary.data

enum class AgendaType(val label: String) { TASKS("Tasks"), BILLS("Bills"), EVENTS("Events") }

object AgendaTypes {
    fun decode(stored: Set<String>?, legacy: String?): Set<AgendaType> {
        if (stored != null && stored.isEmpty()) return emptySet()
        val selected = AgendaType.entries.filter { if (stored != null) it.name in stored else it.name == legacy }.toSet()
        return selected.ifEmpty { AgendaType.entries.toSet() }
    }

    fun toggle(selected: Set<AgendaType>, type: AgendaType): Set<AgendaType> =
        if (type in selected) selected - type else selected + type
}
