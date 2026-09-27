package com.example.itinerary.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp

@Composable
fun BillStatus(paid: Boolean, label: String = if (paid) "Paid" else "Unpaid",
    style: TextStyle = MaterialTheme.typography.labelMedium) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = style)
        Icon(
            imageVector = if (paid) Icons.Default.Check else Icons.Default.Close,
            contentDescription = null, // The adjacent label already announces the status.
            tint = if (com.example.itinerary.ui.theme.LocalColourBlindFriendly.current) {
                if (paid) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary
            } else if (paid) { if (dark) Color(0xFF81C784) else Color(0xFF2E7D32) }
                else { if (dark) Color(0xFFEF9A9A) else Color(0xFFC62828) },
            modifier = Modifier.size(18.dp),
        )
    }
}
