package com.example.itinerary.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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

// Scroll hints: where there is more content off screen, that edge gets a soft fade and a small round arrow that scrolls
// a screenful when tapped, and a thin scroll bar along the right edge shows where you are. All of it goes away when
// there is nothing more. The arrows carry no text so they cover little; screen readers hear "More above" / "More below".

// A scrolling column with hints. [fitContent]: only as tall as the content (up to the space available), for a short
// dialog; otherwise it fills the height it is given, as a full screen does.
@Composable
fun ScrollHints(state: ScrollState, modifier: Modifier = Modifier, fitContent: Boolean = false,
    content: @Composable ColumnScope.() -> Unit) {
    val scope = rememberCoroutineScope()
    HintsFrame(
        modifier = modifier,
        canScrollBackward = state.canScrollBackward,
        canScrollForward = state.canScrollForward,
        // Where the visible part sits: (top, height) as fractions of the whole, or null when it all fits.
        thumb = { height -> if (state.maxValue <= 0 || height <= 0f) null else
            (state.value.toFloat() / (height + state.maxValue)) to (height / (height + state.maxValue)) },
        scrollBy = { amount -> scope.launch { state.animateScrollBy(amount) } },
    ) {
        Column((if (fitContent) Modifier.fillMaxWidth() else Modifier.fillMaxSize()).verticalScroll(state), content = content)
    }
}

// The same hints for a LazyColumn that uses [state]: put the LazyColumn in [content].
@Composable
fun LazyScrollHints(state: LazyListState, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    HintsFrame(
        modifier = modifier,
        canScrollBackward = state.canScrollBackward,
        canScrollForward = state.canScrollForward,
        // Items differ in height, so the bar is an estimate from which items are showing.
        thumb = { _ ->
            val info = state.layoutInfo
            val total = info.totalItemsCount
            val visible = info.visibleItemsInfo.size
            if (total == 0 || visible >= total && !state.canScrollForward && !state.canScrollBackward) null
            else (state.firstVisibleItemIndex.toFloat() / total) to (visible.toFloat() / total)
        },
        scrollBy = { amount -> scope.launch { state.animateScrollBy(amount) } },
    ) { content() }
}

// The same, owning the list state: LazyScrollHints(modifier) { state -> LazyColumn(state = state) { … } }.
@Composable
fun LazyScrollHints(modifier: Modifier = Modifier, content: @Composable (LazyListState) -> Unit) {
    val state = androidx.compose.foundation.lazy.rememberLazyListState()
    LazyScrollHints(state, modifier) { content(state) }
}

@Composable
private fun HintsFrame(
    modifier: Modifier,
    canScrollBackward: Boolean,
    canScrollForward: Boolean,
    thumb: (viewportHeight: Float) -> Pair<Float, Float>?,
    scrollBy: (Float) -> Unit,
    content: @Composable () -> Unit,
) {
    var viewport by remember { mutableIntStateOf(0) }
    val background = MaterialTheme.colorScheme.background
    val thumbColor = MaterialTheme.colorScheme.outline
    val trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    Box(
        modifier
            .onSizeChanged { viewport = it.height }
            .drawWithContent {
                drawContent()
                // The scroll bar, drawn last so it sits over the fades.
                thumb(size.height)?.let { (topFraction, heightFraction) ->
                    // A very short area (landscape with the keyboard up) can be under 40 dp: never ask for more than it has.
                    val thumbHeight = (size.height * heightFraction).coerceIn(40.dp.toPx().coerceAtMost(size.height), size.height)
                    val top = (size.height * topFraction).coerceIn(0f, size.height - thumbHeight)
                    val width = 6.dp.toPx()
                    val x = size.width - width - 2.dp.toPx()
                    drawRoundRect(trackColor, Offset(x, 0f), Size(width, size.height), CornerRadius(width / 2))
                    drawRoundRect(thumbColor, Offset(x, top), Size(width, thumbHeight), CornerRadius(width / 2))
                }
            },
    ) {
        content()
        // In a very short area (landscape with the keyboard up) arrows and fades would sit on the text being typed:
        // there, only the scroll bar shows.
        val roomy = viewport >= with(androidx.compose.ui.platform.LocalDensity.current) { 120.dp.roundToPx() }
        if (roomy && canScrollBackward) {
            Fade(atTop = true, background = background)
            HintArrow(up = true, onClick = { scrollBy(-viewport * 0.8f) })
        }
        if (roomy && canScrollForward) {
            Fade(atTop = false, background = background)
            HintArrow(up = false, onClick = { scrollBy(viewport * 0.8f) })
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
            .height(56.dp)
            .background(Brush.verticalGradient(colors)),
    )
}

// A small round arrow: less in the way than a labelled pill.
@Composable
private fun BoxScope.HintArrow(up: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        border = androidx.compose.foundation.BorderStroke(com.example.itinerary.ui.theme.controlBorderWidth(), MaterialTheme.colorScheme.primary),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shadowElevation = 4.dp,
        modifier = Modifier
            .align(if (up) Alignment.TopCenter else Alignment.BottomCenter)
            .padding(vertical = 8.dp)
            .size(36.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                if (up) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = if (up) "More above" else "More below",
                modifier = Modifier.size(24.dp),
            )
        }
    }
}
