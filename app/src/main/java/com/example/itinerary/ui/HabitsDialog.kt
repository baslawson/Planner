package com.example.itinerary.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.itinerary.ItineraryApp
import com.example.itinerary.data.Habits
import com.example.itinerary.data.TaskRepeat
import com.example.itinerary.data.TaskStreaks

/** The Habits page (⋮ › Habits; Habits): each repeating task with its streak and its last occurrences as marks. */
@Composable
fun HabitsDialog(onDismiss: () -> Unit) {
    val app = LocalContext.current.applicationContext as ItineraryApp
    // null until the tasks have loaded, so the "no habits" words don't flash first (bug hunt 30).
    val tasks by app.repository.tasks.collectAsStateWithLifecycle(initialValue = null)
    val habits = remember(tasks) { tasks?.let { Habits.of(it) } }
    val colors = MaterialTheme.colorScheme
    PlannerDialog("Habits", onDismissRequest = onDismiss, dismiss = DialogAction("Close", onClick = onDismiss)) {
        if (habits?.isEmpty() == true) Text("Repeating tasks show here, with how often they were done on time. Give a task a repeat to start one.",
            style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
        habits.orEmpty().forEachIndexed { i, habit ->
            if (i > 0) HorizontalDivider(color = colors.outlineVariant)
            Column(Modifier.padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(habit.task.title.ifBlank { "Untitled task" }, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(TaskRepeat.label(habit.task.repeat, habit.task.repeatDays) +
                    (if (habit.streak > 0) " · " + TaskStreaks.label(habit.streak) else "") +
                    (if (habit.done > 0) " · ${habit.onTime} of the last ${habit.done} on time" else ""),
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                Row(Modifier.clearAndSetSemantics { contentDescription = Habits.spoken(habit) }, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    habit.recent.forEach { mark ->
                        val shape = CircleShape
                        Box(Modifier.size(14.dp).then(when (mark) {
                            Habits.Mark.ON_TIME -> Modifier.background(colors.primary, shape)
                            Habits.Mark.LATE -> Modifier.background(colors.tertiary.copy(alpha = 0.6f), shape)
                            Habits.Mark.TO_DO -> Modifier.border(2.dp, colors.outline, shape).background(Color.Transparent, shape)
                        }))
                    }
                }
            }
        }
        Text("Filled: done on time. Faded: done late. Ring: still to do.", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
    }
}
