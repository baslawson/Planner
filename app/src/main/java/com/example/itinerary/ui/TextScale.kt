package com.example.itinerary.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.example.itinerary.ui.theme.LocalTextSizePercent

/**
 * Keeps text inside fixed-width layouts (the 7-column month grid, date pickers, the time column) from growing past
 * [max] times normal, counting the system font size and the app's own text size together. Beyond that, day numbers
 * were cut to one digit ("1 1 1 1" for 10–16). Up to [max] nothing changes; everywhere else text keeps its full size.
 */
@Composable
fun LimitTextScale(max: Float = 2f, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val app = LocalTextSizePercent.current / 100f
    if (density.fontScale * app <= max) content()
    else CompositionLocalProvider(LocalDensity provides Density(density.density, (max / app).coerceAtLeast(0.5f)), content = content)
}
