package com.example.itinerary.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.itinerary.data.ItineraryItem
import com.example.itinerary.data.PlanColors
import com.example.itinerary.data.PlanEvent
import com.example.itinerary.data.Trip

class PlanColor(val name: String, val color: Color)

// The colours a plan can have. There must be exactly PlanColors.COUNT of them; a plan stores its position here.
val PLAN_COLORS = listOf(
    PlanColor("Blue", Color(0xFF3D7BD9)),
    PlanColor("Purple", Color(0xFF8A5CC2)),
    PlanColor("Pink", Color(0xFFD1487F)),
    PlanColor("Red", Color(0xFFD9534F)),
    PlanColor("Amber", Color(0xFFE0A030)),
    PlanColor("Green", Color(0xFF3E9E5F)),
    PlanColor("Teal", Color(0xFF1F9C9C)),
    PlanColor("Slate", Color(0xFF6B7A86)),
).also {
    check(it.size == PlanColors.COUNT)
    check(it.last().color.toArgb() == PlanColors.SLATE_ARGB) // events that used Slate are stored with this value
}

fun planColor(index: Int): PlanColor = PLAN_COLORS[Math.floorMod(index, PLAN_COLORS.size)]

// The colour an event is shown in: the one the user picked freely, else its palette entry.
fun ItineraryItem.accentColor(): Color = customColor?.let { Color(it) } ?: planColor(colorIndex).color

// The same for a plan on the "My plans" list.
fun Trip.accentColor(): Color = customColor?.let { Color(it) } ?: planColor(colorIndex).color

// And for an event as a plan card summarises it, which carries the same two colour columns (see PlanEvent).
fun PlanEvent.accentColor(): Color = customColor?.let { Color(it) } ?: planColor(colorIndex).color

// The palette colours these plans use, to give a new plan the least used one. Plans with a colour of their own don't
// use up a palette colour.
fun List<Trip>.usedPaletteColors(): List<Int> = filter { it.customColor == null }.map { it.colorIndex }

// A colour adjusted for use as text: lightened on a dark theme and darkened on a light one, then pushed
// further until it has enough contrast with the background (WCAG 4.5), so that even a very pale or a very dark
// custom colour stays readable. The bar keeps the exact colour; only the text is adjusted.
@Composable
fun readableOnSurface(color: Color): Color {
    // Saved accent colours remain decorative; event names stay neutral in this theme.
    if (com.example.itinerary.ui.theme.LocalColourBlindFriendly.current) return MaterialTheme.colorScheme.onSurface
    val highContrast = com.example.itinerary.ui.theme.LocalHighContrast.current
    val background = MaterialTheme.colorScheme.background
    // Worked out once per colour and background, not on every redraw of every row.
    return remember(color, background, highContrast) {
        val dark = background.luminance() < 0.5f
        pushUntilReadable(color, background, if (dark) Color.White else Color.Black, if (dark) 0.25f else 0.35f, if (highContrast) 7f else 4.5f)
    }
}

// Moves [color] towards [target] (white on a dark background, black on a light one), starting [startAmount] of the way
// (0 = the colour as it is), in steps of 5 percent until its contrast with [background] is at least 4.5.
fun pushUntilReadable(color: Color, background: Color, target: Color, startAmount: Float, minimumContrast: Float = 4.5f): Color {
    var amount = startAmount
    var result = if (amount == 0f) color else lerp(color, target, amount)
    while (contrastRatio(result, background) < minimumContrast && amount < 1f) {
        amount = (amount + 0.05f).coerceAtMost(1f)
        result = lerp(color, target, amount)
    }
    return result
}

fun contrastRatio(a: Color, b: Color): Float {
    val la = a.luminance() + 0.05f
    val lb = b.luminance() + 0.05f
    return maxOf(la, lb) / minOf(la, lb)
}

private val RAINBOW = Brush.sweepGradient(
    listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red),
)

// A row of round colour swatches; the chosen one has a tick and a ring. With [onPickCustom] there is one more
// swatch at the end for a colour of the user's own: a rainbow with a plus until one is picked, then that colour.
// While [custom] is set no palette swatch counts as chosen.
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlanColorPicker(
    selected: Int,
    onSelect: (Int) -> Unit,
    custom: Color? = null,
    onPickCustom: (() -> Unit)? = null,
    // How many of the palette colours to offer (the first ones).
    count: Int = PLAN_COLORS.size,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PLAN_COLORS.take(count).forEachIndexed { index, option ->
            val chosen = custom == null && index == Math.floorMod(selected, PLAN_COLORS.size)
            Swatch(
                modifier = Modifier.background(option.color),
                chosen = chosen,
                description = option.name,
                onClick = { onSelect(index) },
            )
        }
        if (onPickCustom != null) {
            Swatch(
                modifier = if (custom != null) Modifier.background(custom) else Modifier.background(RAINBOW),
                chosen = custom != null,
                description = if (custom != null) "Custom colour, tap to change" else "Pick a custom colour",
                onClick = onPickCustom,
                content = if (custom == null) {
                    { Icon(Icons.Filled.Add, contentDescription = null, tint = Color.White) }
                } else null,
            )
        }
    }
}

@Composable
fun Swatch(
    modifier: Modifier,
    chosen: Boolean,
    description: String,
    onClick: () -> Unit,
    // The colour of the tick on the chosen swatch; white unless the swatch is pale.
    tick: Color = Color.White,
    // A thin outline round an unchosen swatch, so a pale one still shows on a pale page.
    outlined: Boolean = false,
    content: (@Composable () -> Unit)? = null,
) {
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .then(modifier)
            .then(
                when {
                    chosen -> Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                    outlined -> Modifier.border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                    else -> Modifier
                },
            )
            .selectable(selected = chosen, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        if (chosen) Icon(Icons.Filled.Check, contentDescription = null, tint = tick) else content?.invoke()
    }
}
