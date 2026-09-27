package com.example.itinerary.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

@Composable
fun ViewModeToggle(agendaSelected: Boolean, onSwitch: () -> Unit) {
    val green = MaterialTheme.colorScheme.primary
    Surface(onClick = onSwitch,
        modifier = Modifier.semantics {
            contentDescription = if (agendaSelected) "Switch to Calendar view" else "Switch to Agenda view"
            stateDescription = if (agendaSelected) "Agenda view" else "Calendar view"
        },
        shape = RoundedCornerShape(50), color = Color.Transparent,
        border = BorderStroke(com.example.itinerary.ui.theme.controlBorderWidth(), MaterialTheme.colorScheme.outline)) {
        Row {
            repeat(2) { index ->
                val selected = (index == 0) == agendaSelected
                Box(
                    Modifier.size(48.dp)
                        .background(if (selected) green.copy(alpha = 0.08f) else Color.Transparent),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (index == 0) Icons.AutoMirrored.Filled.List else Icons.Default.DateRange,
                        contentDescription = null,
                        tint = if (selected) green else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
