package com.example.itinerary.ui

import androidx.compose.foundation.background
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.itinerary.data.HeadingColor

// The colour the user picked for headings, exactly as picked (see readableHeading for what is actually drawn).
// Provided once at the top of the app.
val LocalHeadingColor = compositionLocalOf { Color(HeadingColor.DEFAULT_ARGB) }

// The picked colour, adjusted only if it would be hard to read on the current background. A bright colour on the dark
// theme is used exactly as picked; on the light theme it is darkened just far enough to reach a contrast of 4.5
// (the same rule as readableOnSurface), so a pale colour never vanishes into a white page.
@Composable
fun readableHeading(color: Color): Color {
    val background = MaterialTheme.colorScheme.background
    // Worked out once per colour and background, not on every redraw of every heading.
    return remember(color, background) {
        pushUntilReadable(color, background, if (background.luminance() < 0.5f) Color.White else Color.Black, 0f)
    }
}

// The colour every heading is drawn in.
@Composable
fun headingTextColor(): Color = readableHeading(LocalHeadingColor.current)

// Text in the heading colour. The style defaults to the surrounding one, so a dialog title keeps its size.
@Composable
fun HeadingText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    fontWeight: FontWeight? = null,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    // One line that steps its size down (to 70% at most) rather than being cut, e.g. a screen title beside icons.
    shrinkToFit: Boolean = false,
) {
    if (shrinkToFit) BoxWithConstraints(modifier) {
        var scale by remember(text, style, maxWidth) { mutableFloatStateOf(1f) }
        var fits by remember(text, style, maxWidth) { mutableStateOf(false) }
        Text(
            text,
            modifier = Modifier.drawWithContent { if (fits) drawContent() },
            color = headingTextColor(),
            style = style.copy(fontSize = style.fontSize * scale),
            fontWeight = fontWeight,
            maxLines = 1,
            softWrap = false,
            overflow = overflow,
            onTextLayout = { if (it.hasVisualOverflow && scale > 0.7f) scale = (scale - 0.05f).coerceAtLeast(0.7f) else fits = true },
        )
    } else Text(
        text,
        modifier = modifier,
        color = headingTextColor(),
        style = style,
        fontWeight = fontWeight,
        maxLines = maxLines,
        overflow = overflow,
    )
}

internal class HeadingSwatch(val name: String, val color: Color)

// The ready-made choices; the first is the default (tapping it resets the colour).
internal val HEADING_SWATCHES = listOf(
    HeadingSwatch("Red orange", Color(HeadingColor.DEFAULT_ARGB)),
    HeadingSwatch("Teal", Color(0xFF7FD3CB)),
    HeadingSwatch("Sky blue", Color(0xFF5CC8FF)),
    HeadingSwatch("Yellow", Color(0xFFFFE14D)),
    HeadingSwatch("Matrix green", Color(0xFF00FF41)),
    HeadingSwatch("Pink", Color(0xFFFF6FB5)),
    HeadingSwatch("White", Color(0xFFF2F2F2)),
)

// The Settings block for the heading colour: a row of swatches (the default first) plus one for a colour of one's own,
// and a sample heading that shows the result.
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HeadingColorSettingsSection(argb: Int, onChange: (Int) -> Unit) {
    var picking by rememberSaveable { mutableStateOf(false) }
    val current = Color(argb)
    val rgb = argb and 0xFFFFFF
    val ready = HEADING_SWATCHES.firstOrNull { it.color.toArgb() and 0xFFFFFF == rgb }

    SettingsHeading("Heading colour")
    Text(
        "The colour of the headings and titles in the app. On the light theme it is darkened a little when needed so it stays readable.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(12.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        HEADING_SWATCHES.forEach { option ->
            Swatch(
                modifier = Modifier.background(option.color),
                chosen = option === ready,
                description = option.name,
                onClick = { onChange(option.color.toArgb()) },
                tick = if (option.color.luminance() > 0.5f) Color.Black else Color.White,
                outlined = true,
            )
        }
        Swatch(
            modifier = if (ready == null) Modifier.background(current) else Modifier.background(CUSTOM_COLOUR_BRUSH),
            chosen = ready == null,
            description = if (ready == null) "Custom colour, tap to change" else "Pick a custom colour",
            onClick = { picking = true },
            tick = if (current.luminance() > 0.5f) Color.Black else Color.White,
            outlined = true,
            content = if (ready == null) null else {
                { Icon(Icons.Filled.Add, contentDescription = null, tint = Color.White) }
            },
        )
    }
    Spacer(Modifier.height(12.dp))
    HeadingText("Sample heading", style = MaterialTheme.typography.titleLarge)

    if (picking) {
        CustomColorDialog(
            initial = current,
            forHeading = true,
            onDismiss = { picking = false },
            onConfirm = { onChange(it.toArgb()); picking = false },
        )
    }
}
