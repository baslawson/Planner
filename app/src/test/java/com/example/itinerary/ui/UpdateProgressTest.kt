package com.example.itinerary.ui

import com.example.itinerary.data.AppRelease
import com.example.itinerary.data.Updates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import kotlin.math.roundToInt

// UI-13: cut to its whole percent, a download shows the same number as before, and chunks within a percent are equal.
class UpdateProgressTest {
    private val release = AppRelease("1.0", "", "a", "b", "c")
    private fun percentShown(state: Updates.State) = ((state as Updates.State.Downloading).progress * 100).roundToInt()

    @Test fun wholePercentShowsTheSameNumber() {
        for (i in 0..100_000) {
            val p = i / 100_000f
            val before = (p * 100).toInt()
            assertEquals("at $p", before, percentShown(wholePercent(Updates.State.Downloading(release, p, true))))
        }
    }

    @Test fun chunksWithinAPercentAreEqual() {
        assertEquals(wholePercent(Updates.State.Downloading(release, 0.421f, true)), wholePercent(Updates.State.Downloading(release, 0.4299f, true)))
        // Unknown size and other states pass through.
        val unknown = Updates.State.Downloading(release, -1f, false)
        assertSame(unknown, wholePercent(unknown))
    }
}
