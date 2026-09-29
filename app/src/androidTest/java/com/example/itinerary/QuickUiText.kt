package com.example.itinerary

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * The text field to type into, found by its current text. In Quick entry, "" means the lowest empty field below Title
 * (an AI clarification answer when one is asked, otherwise When), or else the When box, whose text is then replaced
 * (an empty field may report null or its placeholder). Typing a whole entry into Title would never be read as a date.
 */
/**
 * The active window's accessibility tree, read fresh from the app. The test's accessibility cache is not reliably
 * updated for Compose screens: without clearing it, a folded list's cards or a toggled label ("Collapse") could stay
 * in the tree for seconds after the screen changed. clearCache() exists from API 34.
 */
internal val android.app.UiAutomation.freshRoot: AccessibilityNodeInfo?
    get() {
        if (android.os.Build.VERSION.SDK_INT >= 34) clearCache()
        return rootInActiveWindow
    }

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

/**
 * The scrolling area on screen (the last visible scrollable node: a dialog's or screen's own list), and scrolling it.
 * The scroll bar is drawn only, so tests read and move the scroll position through accessibility instead.
 */
internal fun scrollableIn(nodes: List<AccessibilityNodeInfo>): AccessibilityNodeInfo? =
    nodes.lastOrNull { it.isScrollable && it.isVisibleToUser }

/** Whether this area can scroll further down ([forward]) or up. */
internal fun AccessibilityNodeInfo.canScroll(forward: Boolean): Boolean {
    val action = if (forward) AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD
        else AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD
    return actionList.any { it.id == action.id }
}

/** Scrolls the area on screen one step down ([forward]) or up; false when it could not. */
internal fun scrollStep(nodes: List<AccessibilityNodeInfo>, forward: Boolean): Boolean =
    scrollableIn(nodes)?.performAction(
        if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) == true

/** Scrolls the area on screen all the way up, a step at a time. */
internal fun scrollToTop(nodes: () -> List<AccessibilityNodeInfo>) {
    repeat(20) { if (!scrollStep(nodes(), false)) return; Thread.sleep(400) }
}
