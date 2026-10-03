package com.example.itinerary.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.itinerary.data.ScrollBar

// The scroll bar the user chose: its colour exactly as picked and how see-through it is, in percent. Provided once at the top
// of the app; scrollBarColor() gives what is actually drawn.
@Immutable
data class ScrollBarStyle(val color: Color, val seeThroughPercent: Int)

val LocalScrollBar = compositionLocalOf { ScrollBarStyle(Color(ScrollBar.DEFAULT_ARGB), ScrollBar.DEFAULT_SEE_THROUGH) }

// The colour the bar is drawn in: darkened on the light theme when needed, by the same rule as headings, then
// made see-through by the transparency setting.
@Composable
fun scrollBarColor(style: ScrollBarStyle = LocalScrollBar.current): Color =
    readableHeading(style.color).copy(alpha = 1f - style.seeThroughPercent / 100f)

// The ready-made colours are the heading ones, with the default green first (tapping it resets the colour).
private val SCROLL_BAR_SWATCHES = HEADING_SWATCHES.sortedByDescending { it.color.toArgb() == ScrollBar.DEFAULT_ARGB }

// The Settings block for the scroll bar: colour swatches plus one of one's own, a transparency slider and a sample bar.
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScrollBarSettingsSection(argb: Int, seeThroughPercent: Int, onColorChange: (Int) -> Unit, onSeeThroughChange: (Int) -> Unit) {
    var picking by rememberSaveable { mutableStateOf(false) }
    val current = Color(argb)
    val rgb = argb and 0xFFFFFF
    val ready = SCROLL_BAR_SWATCHES.firstOrNull { it.color.toArgb() and 0xFFFFFF == rgb }

    SettingsHeading("Scroll bar")
    Text(
        "The bar on the right of any screen that scrolls: it shows where you are and that there is more. " +
            "On the light theme it is darkened a little when needed so it stays visible.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(12.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SCROLL_BAR_SWATCHES.forEach { option ->
            Swatch(
                modifier = Modifier.background(option.color),
                chosen = option === ready,
                description = "Scroll bar ${option.name}",
                onClick = { onColorChange(option.color.toArgb()) },
                tick = if (option.color.luminance() > 0.5f) Color.Black else Color.White,
                outlined = true,
            )
        }
        Swatch(
            modifier = if (ready == null) Modifier.background(current) else Modifier.background(CUSTOM_COLOUR_BRUSH),
            chosen = ready == null,
            description = if (ready == null) "Custom scroll bar colour, tap to change" else "Pick a custom scroll bar colour",
            onClick = { picking = true },
            tick = if (current.luminance() > 0.5f) Color.Black else Color.White,
            outlined = true,
            content = if (ready == null) null else {
                { Icon(Icons.Filled.Add, contentDescription = null, tint = Color.White) }
            },
        )
    }
    Spacer(Modifier.height(12.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Slider(
                value = seeThroughPercent.toFloat(),
                onValueChange = { onSeeThroughChange(it.toInt()) },
                valueRange = ScrollBar.MIN_SEE_THROUGH.toFloat()..ScrollBar.MAX_SEE_THROUGH.toFloat(),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Scroll bar transparency" },
            )
            Text(
                if (seeThroughPercent == 0) "Solid" else "$seeThroughPercent% see-through",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Spacer(Modifier.width(16.dp))
        // A live sample: a strip of screen with the bar on its right, a third of the way down.
        Box(
            Modifier
                .size(width = 72.dp, height = 96.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.background),
        ) {
            val bar = scrollBarColor(ScrollBarStyle(current, seeThroughPercent))
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 24.dp, end = 2.dp)
                    .width(6.dp)
                    .height(40.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(bar),
            )
            Box(Modifier.align(Alignment.TopEnd).padding(end = 2.dp).width(6.dp).fillMaxHeight()
                .clip(RoundedCornerShape(3.dp)).background(bar.copy(alpha = bar.alpha * TRACK_ALPHA)))
        }
    }

    if (picking) {
        CustomColorDialog(
            initial = current,
            onDismiss = { picking = false },
            onConfirm = { onColorChange(it.toArgb()); picking = false },
        )
    }
}

// How strong the bar's track is, as a share of the bar's own colour.
internal const val TRACK_ALPHA = 0.2f
