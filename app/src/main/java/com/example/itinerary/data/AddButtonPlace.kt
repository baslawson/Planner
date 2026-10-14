package com.example.itinerary.data

/**
 * Where the big + button sits: how far across and down the space it can move in, each 0..1 (0 = left/top edge,
 * 1 = right/bottom edge). Kept as fractions so the place still makes sense after a rotation or a text size change.
 * The default is bottom centre, where it always was.
 */
data class AddButtonPlace(val x: Float = DEFAULT_X, val y: Float = DEFAULT_Y) {
    /** The button's top-left corner in a space [width] x [height] (pixels) for a button of [size]. */
    fun offset(width: Int, height: Int, size: Int): Pair<Int, Int> =
        Math.round(x * (width - size).coerceAtLeast(0)) to Math.round(y * (height - size).coerceAtLeast(0))

    /** With the button in the top half its menu opens downwards, so it stays on screen beside the button. */
    val opensDown: Boolean get() = y < 0.5f

    companion object {
        const val DEFAULT_X = 0.5f
        const val DEFAULT_Y = 1f

        /** Clamped into 0..1; anything unreadable (NaN) is the default. */
        fun of(x: Float, y: Float) = AddButtonPlace(clean(x, DEFAULT_X), clean(y, DEFAULT_Y))

        /** The place for a button whose top-left corner was dropped at ([left], [top]) in that space. */
        fun fromOffset(left: Float, top: Float, width: Int, height: Int, size: Int): AddButtonPlace {
            val freeX = (width - size).coerceAtLeast(0)
            val freeY = (height - size).coerceAtLeast(0)
            return of(if (freeX == 0) DEFAULT_X else left / freeX, if (freeY == 0) DEFAULT_Y else top / freeY)
        }

        private fun clean(value: Float, default: Float) = if (value.isNaN()) default else value.coerceIn(0f, 1f)
    }
}

/** The rows of the + menu, top to bottom, which the user can rearrange. */
object AddMenuOrder {
    const val TASK = "task"
    const val EVENT = "event"
    const val NOTE = "note"
    const val QUICK = "quick"

    // Quick entry at the bottom, just above the button's ✕.
    val DEFAULT = listOf(TASK, EVENT, NOTE, QUICK)

    fun encode(order: List<String>): String = order.joinToString(",")

    /**
     * A stored order, made whole: unknown names and repeats are dropped, and a row it doesn't name (one added by a later
     * version) goes in at its default place.
     */
    fun decode(stored: String?): List<String> {
        val known = stored.orEmpty().split(',').map { it.trim() }.filter { it in DEFAULT }.distinct().toMutableList()
        DEFAULT.forEachIndexed { index, row -> if (row !in known) known.add(index.coerceAtMost(known.size), row) }
        return known
    }

    /**
     * The rows top to bottom on screen. The order is kept from the farthest row to the one next to the button, so a menu
     * opening downwards (✕ on top) shows it mirrored. Mirroring twice gives the order back, so this also turns the rows
     * as shown into the order to keep.
     */
    fun onScreen(order: List<String>, opensDown: Boolean): List<String> = if (opensDown) order.reversed() else order

    /** [order] with the row at [from] moved to [to] (both clamped). */
    fun move(order: List<String>, from: Int, to: Int): List<String> {
        if (from !in order.indices) return order
        val list = order.toMutableList()
        val row = list.removeAt(from)
        list.add(to.coerceIn(0, list.size), row)
        return list
    }
}
