package com.example.itinerary

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * The text field to type into, found by its current text. In Quick entry, "" means the lowest empty field below Title
 * (an AI clarification answer when one is asked, otherwise When), or else the When box, whose text is then replaced
 * (an empty field may report null or its placeholder). Typing a whole entry into Title would never be read as a date.
 */
internal fun pickEditable(nodes: List<AccessibilityNodeInfo>, old: String): AccessibilityNodeInfo? {
    val visible = nodes.filter { it.isVisibleToUser && it.isEditable }
    val quickEntry = nodes.any { it.isVisibleToUser && it.text?.toString() == "Quick entry" && !it.isClickable }
    if (old.isEmpty() && quickEntry && visible.size >= 2) {
        val belowTitle = visible.sortedBy { Rect().also(it::getBoundsInScreen).top }.drop(1)
        return belowTitle.lastOrNull { it.text.isNullOrEmpty() } ?: belowTitle.first()
    }
    return visible.firstOrNull { (it.text?.toString() ?: "") == old }
}

/**
 * Runs [block] with [choice] as the date format, then puts the original back. A numeric date such as 03/04 only asks
 * "Which date did you mean?" when the format has no day or month order of its own (the default is day first).
 */
internal fun <T> withDateFormat(app: ItineraryApp, ins: android.app.Instrumentation,
                                choice: com.example.itinerary.data.DateFormatChoice, block: () -> T): T {
    val original = app.settings.dateFormat.value
    ins.runOnMainSync { app.settings.setDateFormat(choice) }
    try { return block() } finally { ins.runOnMainSync { app.settings.setDateFormat(original) } }
}
