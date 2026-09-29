package com.example.itinerary.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp

// Scroll bar: a thin bar along the right edge of anything that scrolls, always showing while there is more than fits,
// so it is clear there is more and where you are. Its colour and brightness come from Settings (LocalScrollBar).
// Nothing is drawn when everything fits.

// A scrolling column with the bar. [fitContent]: only as tall as the content (up to the space available), for a short
// dialog; otherwise it fills the height it is given, as a full screen does.
@Composable
fun ScrollHints(state: ScrollState, modifier: Modifier = Modifier, fitContent: Boolean = false,
    content: @Composable ColumnScope.() -> Unit) {
    ScrollBarFrame(
        modifier = modifier,
        // Where the visible part sits: (top, height) as fractions of the whole, or null when it all fits.
        thumb = { height -> if (state.maxValue <= 0 || height <= 0f) null else
            (state.value.toFloat() / (height + state.maxValue)) to (height / (height + state.maxValue)) },
    ) {
        Column((if (fitContent) Modifier.fillMaxWidth() else Modifier.fillMaxSize()).verticalScroll(state), content = content)
    }
}

// The same bar for a LazyColumn that uses [state]: put the LazyColumn in [content].
@Composable
fun LazyScrollHints(state: LazyListState, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    ScrollBarFrame(
        modifier = modifier,
        // Items differ in height, so the bar is an estimate from which items are showing.
        thumb = { _ ->
            val info = state.layoutInfo
            val total = info.totalItemsCount
            val visible = info.visibleItemsInfo.size
            if (total == 0 || !state.canScrollForward && !state.canScrollBackward) null
            else (state.firstVisibleItemIndex.toFloat() / total) to (visible.toFloat() / total)
        },
    ) { content() }
}

// The same, owning the list state: LazyScrollHints(modifier) { state -> LazyColumn(state = state) { … } }.
@Composable
fun LazyScrollHints(modifier: Modifier = Modifier, content: @Composable (LazyListState) -> Unit) {
    val state = rememberLazyListState()
    LazyScrollHints(state, modifier) { content(state) }
}

@Composable
private fun ScrollBarFrame(
    modifier: Modifier,
    thumb: (viewportHeight: Float) -> Pair<Float, Float>?,
    content: @Composable () -> Unit,
) {
    val thumbColor = scrollBarColor()
    val trackColor = thumbColor.copy(alpha = thumbColor.alpha * TRACK_ALPHA)
    Box(
        modifier.drawWithContent {
            drawContent()
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
    ) { content() }
}
