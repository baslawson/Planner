package com.example.itinerary

/** Only these explicit launcher actions can request a fresh editor. */
object EntryShortcuts {
    const val ADD_EVENT = "com.example.itinerary.ADD_EVENT"
    const val ADD_BILL = "com.example.itinerary.ADD_BILL"
    const val SCAN = "com.example.itinerary.SCAN_DOCUMENT"
    fun accepts(action: String?) = action == ADD_EVENT || action == ADD_BILL || action == SCAN
}
