package com.example.itinerary.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.itinerary.data.AppFont
import com.example.itinerary.data.TextSize
import com.example.itinerary.ui.theme.fontFamilyFor
import com.example.itinerary.ui.theme.fontSizeScaleFor
import kotlin.math.roundToInt

// The Settings block for the typeface and the text size. The font is a dropdown (one line when closed) whose entries are
// each drawn in their own font; the slider makes all text in the app smaller or larger. Both apply to the whole app.
@Composable
fun FontSettingsSection(
    selected: AppFont,
    onSelect: (AppFont) -> Unit,
    textSizePercent: Int,
    onTextSize: (Int) -> Unit,
) {
    SettingsHeading("Font")
    val base = remember { Typography().bodyLarge }
    SettingsDropdown(label = "Font", current = selected.label, options = AppFont.entries, onSelect = onSelect) { font ->
        Text(
            font.label,
            style = base.copy(
                fontFamily = fontFamilyFor(font),
                fontSize = base.fontSize * fontSizeScaleFor(font) * textSizePercent / 100f,
            ),
        )
    }

    Spacer(Modifier.height(16.dp))
    // The slider follows the finger without touching the setting; the text size is applied when the finger lifts.
    var draft by remember(textSizePercent) { mutableFloatStateOf(textSizePercent.toFloat()) }
    Text("Text size: ${draft.roundToInt()}%", style = MaterialTheme.typography.bodyMedium)
    Slider(
        value = draft,
        onValueChange = { draft = it },
        onValueChangeFinished = { onTextSize(draft.roundToInt()) },
        valueRange = TextSize.MIN_PERCENT.toFloat()..TextSize.MAX_PERCENT.toFloat(),
        steps = (TextSize.MAX_PERCENT - TextSize.MIN_PERCENT) / TextSize.STEP_PERCENT - 1,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Text size" },
    )
    // What the text will look like at the size being chosen (the page itself only changes on release).
    val body = MaterialTheme.typography.bodyLarge
    Text(
        "The quick brown fox: 09:30 Lunch 1234",
        style = body.copy(fontSize = body.fontSize * draft / textSizePercent, lineHeight = body.lineHeight * draft / textSizePercent),
    )
}
