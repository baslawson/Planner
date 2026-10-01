package com.example.itinerary.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** The sync icon's cloud: plain when all is well, raining while a sync runs, struck through when it isn't synced. */
enum class CloudLook { SYNCED, RAINING, STRUCK }

// Material's "cloud" shape, on a 24 x 24 grid (x 0..24, y 4..20).
private val cloud = PathParser().parsePathString(
    "M19.35,10.04C18.67,6.59 15.64,4 12,4 9.11,4 6.6,5.64 5.35,8.04 2.34,8.36 0,10.91 0,14c0,3.31 2.69,6 6,6h13" +
        "c2.76,0 5,-2.24 5,-5 0,-2.64 -2.05,-4.78 -4.65,-4.96z").toPath()

// Three drops under a smaller cloud, each a third of a fall behind the one before.
private val drops = listOf(7.5f to 0f, 12f to 0.34f, 16.5f to 0.67f)

@Composable
fun SyncCloud(look: CloudLook, color: Color, contentDescription: String, modifier: Modifier = Modifier) {
    val fall = if (look == CloudLook.RAINING) {
        val t by rememberInfiniteTransition(label = "rain").animateFloat(0f, 1f,
            infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "fall")
        t
    } else 0f
    // Offscreen, so the strike's gap clears the cloud, not the top bar behind it.
    Canvas(modifier.size(24.dp)
        .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
        .semantics { this.contentDescription = contentDescription; role = Role.Image }) {
        scale(size.minDimension / 24f, pivot = Offset.Zero) {
            when (look) {
                CloudLook.SYNCED -> drawPath(cloud, color)
                CloudLook.STRUCK -> {
                    drawPath(cloud, color)
                    strike(color)
                }
                CloudLook.RAINING -> {
                    // The cloud at 80 %, lifted to y 1..14, leaves y 14..23 for the rain.
                    translate(left = 2.4f, top = -2.2f) { scale(0.8f, pivot = Offset.Zero) { drawPath(cloud, color) } }
                    drops.forEach { (x, lag) ->
                        val p = (fall + lag) % 1f
                        val y = 15f + p * 6f
                        drawLine(color.copy(alpha = color.alpha * (1f - p * 0.7f)), Offset(x, y), Offset(x - 0.8f, y + 2.5f),
                            strokeWidth = 1.6f, cap = StrokeCap.Round)
                    }
                }
            }
        }
    }
}

// Corner to corner, with a clear gap either side so it reads on a filled cloud.
private fun DrawScope.strike(color: Color) {
    val from = Offset(3f, 2.5f); val to = Offset(21f, 21.5f)
    drawLine(Color.Black, from, to, strokeWidth = 4.6f, cap = StrokeCap.Round, blendMode = BlendMode.Clear)
    drawLine(color, from, to, strokeWidth = 2f, cap = StrokeCap.Round)
}
