package com.example.itinerary.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.example.itinerary.data.Notes
import org.junit.Assert.assertEquals
import org.junit.Test

// DU-6: onColour now uses PlanColors' contrastRatio instead of its own copy of the formula. Same answers.
class ContrastSharedTest {
    // The copy NotesScreen had, kept here to compare against.
    private fun oldContrast(a: Color, b: Color): Float {
        val la = a.luminance() + 0.05f; val lb = b.luminance() + 0.05f
        return maxOf(la, lb) / minOf(la, lb)
    }
    private fun oldOnColour(background: Color) =
        if (oldContrast(Color.White, background) >= oldContrast(Color.Black, background)) Color.White else Color.Black

    @Test fun contrastNumbers() {
        assertEquals(21f, contrastRatio(Color.White, Color.Black), 0.001f)
        assertEquals(1f, contrastRatio(Color.Red, Color.Red), 0.0001f)
        assertEquals(contrastRatio(Color.Blue, Color.Yellow), contrastRatio(Color.Yellow, Color.Blue))
    }

    @Test fun onColourMatchesTheOldCopyEverywhere() {
        val samples = Notes.colors.map { Color(it) } + (0..255 step 5).flatMap { r ->
            (0..255 step 15).flatMap { g -> (0..255 step 15).map { b -> Color(r, g, b) } }
        }
        for (c in samples) {
            assertEquals(oldContrast(Color.White, c), contrastRatio(Color.White, c))
            assertEquals(oldContrast(Color.Black, c), contrastRatio(Color.Black, c))
            assertEquals("$c", oldOnColour(c), onColour(c))
        }
    }
}
