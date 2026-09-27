package com.example.itinerary.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.example.itinerary.data.AppTheme
import org.junit.Assert.*
import org.junit.Test

class ThemeContrastTest {
    private fun contrast(a: Color, b: Color): Float {
        val x = a.luminance() + 0.05f
        val y = b.luminance() + 0.05f
        return maxOf(x, y) / minOf(x, y)
    }

    @Test fun highContrastTextAndActionsRemainReadableOnEverySurface() {
        for (dark in listOf(false, true)) {
            val c = plannerColorScheme(AppTheme.HIGH_CONTRAST, dark)
            for (surface in listOf(c.background, c.surface, c.surfaceContainerLow, c.surfaceContainerHigh)) {
                assertTrue("Text contrast: dark=$dark", contrast(c.onSurface, surface) >= 7f)
                assertTrue("Action contrast: dark=$dark", contrast(c.primary, surface) >= 7f)
                assertTrue("Outline contrast: dark=$dark", contrast(c.outline, surface) >= 3f)
            }
            assertTrue(contrast(c.primary, c.primaryContainer) >= 7f)
            assertTrue(contrast(c.onPrimary, c.primary) >= 7f)
        }
    }

    @Test fun existingMatrixPaletteIsPreserved() {
        assertEquals(Color(0xFF00FF41), plannerColorScheme(AppTheme.MATRIX, true).primary)
        assertEquals(Color(0xFF101514), plannerColorScheme(AppTheme.MATRIX, true).background)
        assertEquals(Color(0xFF006B1B), plannerColorScheme(AppTheme.MATRIX, false).primary)
    }

    @Test fun missingAndUnknownThemeNamesUseMatrix() {
        assertEquals(AppTheme.MATRIX, AppTheme.fromStored(null))
        assertEquals(AppTheme.MATRIX, AppTheme.fromStored("future-theme"))
        assertEquals(AppTheme.HIGH_CONTRAST, AppTheme.fromStored("HIGH_CONTRAST"))
    }
    @Test fun colourBlindPaletteKeepsTextAndStatusReadableInBothModes() {
        for (dark in listOf(false, true)) {
            val c = plannerColorScheme(AppTheme.COLOUR_BLIND, dark)
            for (surface in listOf(c.background,c.surface,c.surfaceContainerLowest,c.surfaceContainerLow,
                c.surfaceContainer,c.surfaceContainerHigh,c.surfaceContainerHighest,c.surfaceVariant)) {
                for (text in listOf(c.onSurface,c.onSurfaceVariant,c.primary,c.tertiary,c.error))
                    assertTrue("Text $text on $surface dark=$dark",contrast(text,surface)>=4.5f)
                for (outline in listOf(c.outline,c.outlineVariant)) assertTrue(contrast(outline,surface)>=3f)
            }
            for ((text,background) in listOf(c.onPrimary to c.primary,c.onSecondary to c.secondary,
                c.onTertiary to c.tertiary,c.onError to c.error,c.onPrimaryContainer to c.primaryContainer,
                c.onSecondaryContainer to c.secondaryContainer,c.onTertiaryContainer to c.tertiaryContainer,
                c.onErrorContainer to c.errorContainer,c.inverseOnSurface to c.inverseSurface))
                assertTrue("Pair contrast dark=$dark",contrast(text,background)>=4.5f)
            assertNotEquals(c.primary,c.tertiary)
        }
    }
    @Test fun presetHeadingsDoNotOverwriteCustomMatrixColour() {
        val custom=Color(0xFFFF6FB5)
        for (dark in listOf(false,true)) {
            val c=plannerColorScheme(AppTheme.COLOUR_BLIND,dark)
            assertEquals(c.tertiary,plannerHeadingColor(AppTheme.COLOUR_BLIND,c,custom))
            assertEquals(custom,plannerHeadingColor(AppTheme.MATRIX,plannerColorScheme(AppTheme.MATRIX,dark),custom))
        }
        assertEquals(AppTheme.COLOUR_BLIND,AppTheme.fromStored("COLOUR_BLIND"))
    }

}
