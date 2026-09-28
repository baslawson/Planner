package com.example.itinerary.ui

import com.example.itinerary.ui.MatrixFilterChip as FilterChip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.itinerary.data.RepeatRule
import java.time.DayOfWeek
import java.time.LocalDate

/** How a repeat that needs a further choice is named in a Repeat list, before that choice is made. */
fun RepeatRule.Kind.choiceLabel(): String = when (this) {
    RepeatRule.Kind.EVERY_N_DAYS -> "Every few days…"
    RepeatRule.Kind.EVERY_N_WEEKS -> "Every few weeks…"
    RepeatRule.Kind.DAYS_OF_WEEK -> "On chosen weekdays…"
    RepeatRule.Kind.MONTHLY_WEEKDAY -> "Monthly on a weekday (e.g. first Monday)…"
    else -> name
}

/** A starting point for a repeat that needs a further choice, taken from the date where it helps. */
fun RepeatRule.Kind.startingRule(date: LocalDate): RepeatRule = when (this) {
    RepeatRule.Kind.EVERY_N_DAYS -> RepeatRule.everyDays(2)
    RepeatRule.Kind.EVERY_N_WEEKS -> RepeatRule.everyWeeks(3)
    RepeatRule.Kind.DAYS_OF_WEEK -> RepeatRule.onDays(setOf(date.dayOfWeek))
    else -> RepeatRule.monthlyLike(date)
}

/** Every repeat a Repeat list offers: the plain ones, then those that need a further choice. */
fun repeatChoices(date: LocalDate): List<RepeatRule> = RepeatRule.entries + RepeatRule.customKinds.map { it.startingRule(date) }

/** The further choice a repeat needs: how many days or weeks, which weekdays, or which week of the month. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RepeatDetails(rule: RepeatRule, enabled: Boolean, onChange: (RepeatRule) -> Unit) {
    when (rule.kind) {
        RepeatRule.Kind.EVERY_N_DAYS, RepeatRule.Kind.EVERY_N_WEEKS -> {
            val weeks = rule.kind == RepeatRule.Kind.EVERY_N_WEEKS
            OutlinedTextField(
                value = if (rule.every == 0) "" else rule.every.toString(),
                onValueChange = { value -> onChange(rule.copy(every = value.filter(Char::isDigit).take(3).toIntOrNull() ?: 0)) },
                label = { Text(if (weeks) "Every how many weeks (2–52)" else "Every how many days (2–365)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true, enabled = enabled, isError = !rule.valid, modifier = Modifier.fillMaxWidth(),
            )
        }
        RepeatRule.Kind.DAYS_OF_WEEK -> {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DayOfWeek.entries.forEach { day ->
                    FilterChip(selected = day in rule.days, enabled = enabled,
                        onClick = { onChange(rule.copy(days = if (day in rule.days) rule.days - day else rule.days + day)) },
                        label = { Text(RepeatRule.shortDay(day)) })
                }
            }
            if (rule.days.isEmpty()) Text("Choose at least one day.", color = MaterialTheme.colorScheme.error)
            else Text("A start on another day moves to the next chosen day.", style = MaterialTheme.typography.bodySmall)
        }
        RepeatRule.Kind.MONTHLY_WEEKDAY -> {
            SettingsDropdown(label = "Week of the month", current = RepeatRule.weekName(rule.week).replaceFirstChar(Char::uppercase),
                options = listOf(1, 2, 3, 4, RepeatRule.LAST), onSelect = { if (enabled) onChange(rule.copy(week = it)) },
                entry = { Text(RepeatRule.weekName(it).replaceFirstChar(Char::uppercase)) })
            SettingsDropdown(label = "Day", current = rule.days.firstOrNull()?.let(RepeatRule::fullDay).orEmpty(),
                options = DayOfWeek.entries, onSelect = { if (enabled) onChange(rule.copy(days = setOf(it))) },
                entry = { Text(RepeatRule.fullDay(it)) })
            Text("A start on another date moves to the next ${rule.label.removePrefix("Monthly on the ")}.", style = MaterialTheme.typography.bodySmall)
        }
        else -> Unit
    }
}
