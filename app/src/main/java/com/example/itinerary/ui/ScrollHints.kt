package com.example.itinerary.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

// A scrolling column that makes it obvious when there is more to see. Where there is more content off screen, that
// edge gets a soft fade and a small pill ("More below" / "More above") that scrolls a screenful when tapped, and a thin
// scroll bar along the right edge shows where you are in the page. All of it goes away when there is nothing more.
@Composable
fun ScrollHints(state: ScrollState, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val scope = rememberCoroutineScope()
    var viewport by remember { mutableIntStateOf(0) }
    val background = MaterialTheme.colorScheme.background
    val thumbColor = MaterialTheme.colorScheme.outline
    val trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)

    fun scrollScreenful(direction: Int) {
        scope.launch { state.animateScrollBy(direction * viewport * 0.8f) }
    }

    Box(
        modifier
            .onSizeChanged { viewport = it.height }
            .drawWithContent {
                drawContent()
                // The scroll bar, drawn last so it sits over the fades.
                val max = state.maxValue
                if (max > 0 && size.height > 0f) {
                    val thumbHeight = (size.height * size.height / (size.height + max)).coerceAtLeast(40.dp.toPx())
                    val top = state.value.toFloat() / max * (size.height - thumbHeight)
                    val width = 6.dp.toPx()
                    val x = size.width - width - 2.dp.toPx()
                    drawRoundRect(trackColor, Offset(x, 0f), Size(width, size.height), CornerRadius(width / 2))
                    drawRoundRect(thumbColor, Offset(x, top), Size(width, thumbHeight), CornerRadius(width / 2))
                }
            },
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(state), content = content)
        if (state.canScrollBackward) {
            Fade(atTop = true, background = background)
            HintPill(up = true, onClick = { scrollScreenful(-1) })
        }
        if (state.canScrollForward) {
            Fade(atTop = false, background = background)
            HintPill(up = false, onClick = { scrollScreenful(1) })
        }
    }
}

@Composable
private fun BoxScope.Fade(atTop: Boolean, background: Color) {
    val colors = if (atTop) listOf(background, Color.Transparent) else listOf(Color.Transparent, background)
    Box(
        Modifier
            .align(if (atTop) Alignment.TopCenter else Alignment.BottomCenter)
            .fillMaxWidth()
            .height(72.dp)
            .background(Brush.verticalGradient(colors)),
    )
}

@Composable
private fun BoxScope.HintPill(up: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        border = androidx.compose.foundation.BorderStroke(com.example.itinerary.ui.theme.controlBorderWidth(), MaterialTheme.colorScheme.primary),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shadowElevation = 4.dp,
        modifier = Modifier
            .align(if (up) Alignment.TopCenter else Alignment.BottomCenter)
            .padding(vertical = 10.dp),
    ) {
        Row(
            Modifier.padding(start = 10.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (up) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(if (up) "More above" else "More below", style = MaterialTheme.typography.labelLarge)
        }
    }
}
