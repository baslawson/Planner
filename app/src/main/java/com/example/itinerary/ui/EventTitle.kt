package com.example.itinerary.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp

/** Keeps the event's colour and search highlighting; wraps the badge on narrow rows. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EventTitle(title: AnnotatedString, color: Color, isBill: Boolean) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = color, modifier = Modifier.align(Alignment.CenterVertically))
        if (isBill) Surface(
            modifier = Modifier.align(Alignment.CenterVertically),
            shape = RoundedCornerShape(6.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ) {
            Row(Modifier.padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                val ink = MaterialTheme.colorScheme.onSecondaryContainer
                Canvas(Modifier.size(16.dp)) {
                    val w = size.width; val h = size.height
                    val outline = Path().apply {
                        moveTo(w * .2f, h * .1f); lineTo(w * .8f, h * .1f)
                        lineTo(w * .8f, h * .9f); lineTo(w * .65f, h * .8f)
                        lineTo(w * .5f, h * .9f); lineTo(w * .35f, h * .8f)
                        lineTo(w * .2f, h * .9f); close()
                    }
                    drawPath(outline, ink, style = Stroke(1.2.dp.toPx()))
                    for (y in listOf(.32f, .5f, .68f)) {
                        drawLine(ink, Offset(w * .32f, h * y), Offset(w * .68f, h * y), strokeWidth = 1.dp.toPx())
                    }
                }
                Text("Bills", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
