package com.example.itinerary.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.itinerary.ItineraryApp
import com.example.itinerary.data.AppTheme
import com.example.itinerary.data.ThemeMode
import com.example.itinerary.ui.theme.plannerColorScheme
import com.example.itinerary.ui.theme.plannerHeadingColor

@Composable
fun ThemesDialog(onDismiss: () -> Unit) {
    val settings = (LocalContext.current.applicationContext as ItineraryApp).settings
    val chosen by settings.appTheme.collectAsStateWithLifecycle()
    val mode by settings.themeMode.collectAsStateWithLifecycle()
    val heading by settings.headingColor.collectAsStateWithLifecycle()
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    PlannerDialog("Themes", onDismiss, dismiss = DialogAction("Done", onClick = onDismiss)) {
            Column(Modifier.fillMaxWidth().padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    AppTheme.entries.forEach { theme ->
                        val palette = plannerColorScheme(theme, dark)
                        val selected = theme == chosen
                        Surface(color = palette.background, contentColor = palette.onBackground,
                            shape = MaterialTheme.shapes.large,
                            border = BorderStroke(if (selected) 3.dp else 1.dp,
                                if (selected) palette.primary else palette.outline),
                            modifier = Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton,
                                onClick = { settings.setAppTheme(theme) })) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(if (selected) "✓ ${theme.label}" else theme.label, fontWeight = FontWeight.Bold)
                                MaterialTheme(colorScheme = palette, typography = MaterialTheme.typography) {
                                    Text("Heading", color = readableHeading(plannerHeadingColor(theme, palette, androidx.compose.ui.graphics.Color(heading))),
                                        style = MaterialTheme.typography.titleMedium)
                                }
                                Text(if (theme == AppTheme.COLOUR_BLIND) "Blue controls · amber headings" else "Text and details", style = MaterialTheme.typography.bodySmall)
                                Surface(color = palette.primaryContainer, contentColor = palette.primary,
                                    shape = MaterialTheme.shapes.small, border = BorderStroke(2.dp, palette.primary)) {
                                    Text("Button", Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                                }
                            }
                        }
                    }
                }
                Text("Appearance", fontWeight = FontWeight.Bold)
                Column(Modifier.selectableGroup()) {
                    ThemeMode.entries.forEach { option ->
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .selectable(selected = mode == option, role = Role.RadioButton,
                                onClick = { settings.setThemeMode(option) }), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = mode == option, onClick = null)
                            Text(option.label, Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }
    }
}
