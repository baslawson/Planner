package com.example.itinerary.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.itinerary.data.DateFormatChoice

// The Settings block for how a whole day is written (day headings, the event form's date, Search headings). A dropdown
// whose entries each show today's date in that format, so the choices can be compared at a glance.
@Composable
fun DateFormatSettingsSection(selected: DateFormatChoice, onSelect: (DateFormatChoice) -> Unit) {
    SettingsHeading("Date format")
    val today = rememberCurrentDate()
    SettingsDropdown(
        label = "Date format",
        current = selected.label,
        options = DateFormatChoice.entries,
        onSelect = onSelect,
    ) { choice ->
        Column {
            Text(choice.label, style = MaterialTheme.typography.bodyLarge)
            Text(
                today.dayLabel(choice),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Spacer(Modifier.height(8.dp))
    Text(
        "Example: ${today.dayLabel(selected)}",
        style = MaterialTheme.typography.bodyMedium,
    )
    Text(
        "Used for the day heading on a plan, the date in the event form and the day headings in Search.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
