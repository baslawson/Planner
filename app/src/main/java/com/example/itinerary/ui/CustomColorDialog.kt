package com.example.itinerary.ui
import com.example.itinerary.ui.MatrixOutlinedButton as OutlinedButton
import com.example.itinerary.ui.MatrixButton as Button

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

private val HEX_DIGITS = Regex("[0-9A-Fa-f]{6}")

// Shows what is typed in capitals without changing the text itself. Rewriting the text on every key (to upper-case
// it) makes the cursor jump when typing fast, so the box keeps exactly what was typed and only draws it in capitals.
private val UpperCaseDisplay = VisualTransformation { text ->
    TransformedText(AnnotatedString(text.text.uppercase()), OffsetMapping.Identity)
}

private fun hsvOf(color: Color): FloatArray =
    FloatArray(3).also { android.graphics.Color.colorToHSV(color.toArgb(), it) }

private fun colorOf(hue: Float, saturation: Float, value: Float): Color =
    Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, value)))

private fun hexOf(color: Color): String = String.format("#%06X", color.toArgb() and 0xFFFFFF)

// Lets the user pick any colour: a preview of how it will look in the day's list, a hue bar, saturation and
// brightness sliders, and a box for typing an exact #RRGGBB. Starts from [initial]. The result is opaque.
@Composable
fun CustomColorDialog(
    initial: Color,
    onDismiss: () -> Unit,
    onConfirm: (Color) -> Unit,
    // True when picking the colour of the app's headings: the preview is then a sample heading, not an event.
    forHeading: Boolean = false,
) {
    val start = remember { hsvOf(initial) }
    var hue by rememberSaveable { mutableStateOf(start[0]) }
    var saturation by rememberSaveable { mutableStateOf(start[1]) }
    var brightness by rememberSaveable { mutableStateOf(start[2]) }
    // The six digits only; the "#" is drawn by the box as a prefix. Kept as a TextFieldValue so the cursor stays
    // consistent with the text however fast it is typed.
    var hex by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(hexOf(initial).drop(1), TextRange(6)))
    }
    // Set while the hex box holds a complete colour, so the exact typed value is kept (going through the sliders'
    // floating point could be a step off). Cleared as soon as a slider moves.
    var typed by rememberSaveable { mutableStateOf<Int?>(null) }
    val color = typed?.let { Color(it) } ?: colorOf(hue, saturation, brightness)

    fun moveSliders(h: Float, s: Float, b: Float) {
        hue = h
        saturation = s
        brightness = b
        typed = null
        hex = TextFieldValue(hexOf(colorOf(h, s, b)).drop(1), TextRange(6))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { HeadingText("Pick a colour") },
        text = {
            ScrollHints(rememberScrollState(), Modifier.fillMaxWidth(), fitContent = true) { Column(Modifier.fillMaxWidth()) {
                // A heading, or an event, as it will look in the app.
                val shape = RoundedCornerShape(12.dp)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(shape)
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), shape)
                        .padding(12.dp)
                        .height(IntrinsicSize.Min),
                ) {
                    if (forHeading) {
                        Text(
                            "Sample heading",
                            style = MaterialTheme.typography.titleLarge,
                            color = readableHeading(color),
                        )
                    } else {
                        Box(Modifier.width(4.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(color))
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "Sample event",
                            style = MaterialTheme.typography.titleMedium,
                            color = readableOnSurface(color),
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))

                GradientSlider(
                    label = "Hue",
                    value = hue,
                    range = 0f..360f,
                    colors = (0..6).map { colorOf(it * 60f % 360f, 1f, 1f) }.dropLast(1) + colorOf(359.9f, 1f, 1f),
                    onChange = { moveSliders(it, saturation, brightness) },
                )
                GradientSlider(
                    label = "Saturation",
                    value = saturation,
                    range = 0f..1f,
                    colors = listOf(colorOf(hue, 0f, brightness), colorOf(hue, 1f, brightness)),
                    onChange = { moveSliders(hue, it, brightness) },
                )
                GradientSlider(
                    label = "Brightness",
                    value = brightness,
                    range = 0f..1f,
                    colors = listOf(colorOf(hue, saturation, 0f), colorOf(hue, saturation, 1f)),
                    onChange = { moveSliders(hue, saturation, it) },
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = hex,
                    onValueChange = { edit ->
                        val digits = edit.text.filter { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }.take(6)
                        // Only touch the text when something invalid was typed or pasted.
                        hex = if (digits == edit.text) edit else TextFieldValue(digits, TextRange(digits.length))
                        if (HEX_DIGITS.matches(digits)) {
                            val exact = Color(0xFF000000L or digits.toLong(16))
                            typed = exact.toArgb()
                            val hsv = hsvOf(exact)
                            hue = hsv[0]
                            saturation = hsv[1]
                            brightness = hsv[2]
                        }
                    },
                    label = { Text("Hex colour") },
                    prefix = { Text("#") },
                    visualTransformation = UpperCaseDisplay,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                        keyboardType = KeyboardType.Ascii,
                        imeAction = ImeAction.Done,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            } }
        },
        confirmButton = { Button(onClick = { onConfirm(color) }) { Text("Use this colour") } },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// A slider whose bar is drawn in the colours it covers, with a white round handle that shows on any of them.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GradientSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    colors: List<Color>,
    onChange: (Float) -> Unit,
) {
    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Slider(
        value = value,
        onValueChange = onChange,
        valueRange = range,
        modifier = Modifier.semantics { contentDescription = label },
        thumb = {
            Box(
                Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(Color.White)
                    .border(2.dp, Color.Black.copy(alpha = 0.55f), CircleShape),
            )
        },
        track = {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(14.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(Brush.horizontalGradient(colors)),
            )
        },
    )
}
