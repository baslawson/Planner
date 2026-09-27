package com.example.itinerary.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

// The heading of one block on the Settings page. Every block except the first starts with a thick horizontal line
// with space around it, so the blocks are clearly separate; the heading itself is bold and in the accent colour.
@Composable
fun SettingsHeading(text: String, withDivider: Boolean = true) {
    Column(Modifier.fillMaxWidth()) {
        if (withDivider) {
            Spacer(Modifier.height(20.dp))
            HorizontalDivider(thickness = 2.dp, color = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.height(16.dp))
        }
        HeadingText(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
    }
}
