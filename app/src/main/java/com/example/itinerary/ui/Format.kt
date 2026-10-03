package com.example.itinerary.ui

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.platform.LocalContext
import com.example.itinerary.data.DateFormatChoice
import com.example.itinerary.data.TimeFormat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

// A whole day written the way the user chose (Settings, "Date format"). Month and weekday names follow the phone's language.
fun LocalDate.dayLabel(choice: DateFormatChoice = DateFormatChoice.DEFAULT): String =
    format(dayFormatter(choice, Locale.getDefault()))

// Every day heading in a list asks for its label, so each format is built once per language and then reused.
private val dayFormatters = ConcurrentHashMap<Pair<DateFormatChoice, Locale>, DateTimeFormatter>()

private fun dayFormatter(choice: DateFormatChoice, locale: Locale): DateTimeFormatter =
    dayFormatters.getOrPut(choice to locale) {
        when (choice) {
            DateFormatChoice.DAY_MONTH_YEAR -> DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", locale)
            DateFormatChoice.MONTH_DAY_YEAR -> DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy", locale)
            DateFormatChoice.SHORT_NAMED -> DateTimeFormatter.ofPattern("EEE d MMM yyyy", locale)
            DateFormatChoice.NUMERIC_DMY -> DateTimeFormatter.ofPattern("EEE dd/MM/yyyy", locale)
            DateFormatChoice.NUMERIC_MDY -> DateTimeFormatter.ofPattern("EEE MM/dd/yyyy", locale)
            DateFormatChoice.ISO -> DateTimeFormatter.ofPattern("EEE yyyy-MM-dd", locale)
            DateFormatChoice.NO_YEAR -> DateTimeFormatter.ofPattern("EEEE d MMMM", locale)
            DateFormatChoice.SYSTEM -> DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale)
        }
    }

// Provided by MainActivity from the user's setting; the default only matters in previews.
val LocalDateFormat = compositionLocalOf { DateFormatChoice.DEFAULT }

fun LocalDate.shortLabel(): String =
    format(patternFormatter("d MMM"))

// A multi-day event: "3 Oct – 7 Oct · 5 days" on its first day, "Day 3 of 5 · until 7 Oct" on a later one.
fun spanLabel(start: LocalDate, end: LocalDate, displayedDate: LocalDate = start): String {
    val days = java.time.temporal.ChronoUnit.DAYS.between(start, end).toInt() + 1
    return if (displayedDate > start) "Day ${java.time.temporal.ChronoUnit.DAYS.between(start, displayedDate) + 1} of $days · until ${end.shortLabel()}"
    else "${start.shortLabel()} – ${end.shortLabel()} · $days days"
}

// A list may render the same date/time pattern hundreds of times. Formatters are immutable;
// include the locale in the key so changing the phone language cannot reuse the wrong one.
private val patternFormatters = ConcurrentHashMap<Pair<String, Locale>, DateTimeFormatter>()

internal fun patternFormatter(pattern: String, locale: Locale = Locale.getDefault()): DateTimeFormatter =
    patternFormatters.getOrPut(pattern to locale) { DateTimeFormatter.ofPattern(pattern, locale) }

// Provided by MainActivity after resolving the system clock preference; the default only matters in previews.
val LocalTimeFormat = compositionLocalOf { TimeFormat.SYSTEM }

fun TimeFormat.is24Hour(context: Context): Boolean = when (this) {
    TimeFormat.SYSTEM -> DateFormat.is24HourFormat(context)
    TimeFormat.HOUR_12 -> false
    TimeFormat.HOUR_24 -> true
}

fun LocalDate.fullLabel(): String =
    format(patternFormatter("d MMM yyyy"))

// Locale defaults can differ from Android's explicit 12/24-hour preference.
fun LocalTime.label(timeFormat: TimeFormat, context: Context): String = label(timeFormat.is24Hour(context))

internal fun LocalTime.label(is24Hour: Boolean): String = format(patternFormatter(if (is24Hour) "HH:mm" else "h:mm a"))

// A moment ([ms]) as "day, time": the day as the user writes days ([dayFormat]), or as "3 Oct" when it is null.
internal fun momentLabel(ms: Long, dayFormat: DateFormatChoice?, is24Hour: Boolean, zone: ZoneId = ZoneId.systemDefault()): String {
    val at = Instant.ofEpochMilli(ms).atZone(zone)
    val day = if (dayFormat == null) at.toLocalDate().shortLabel() else at.toLocalDate().dayLabel(dayFormat)
    return "$day, ${at.toLocalTime().label(is24Hour)}"
}

// A reminder or snooze time ("Saturday 3 October 2026, 14:05"); [short] for a "Synced 3 Oct, 14:05" line.
@Composable
internal fun momentLabel(ms: Long, short: Boolean = false): String =
    momentLabel(ms, if (short) null else LocalDateFormat.current, LocalTimeFormat.current.is24Hour(LocalContext.current))

fun YearMonth.label(): String =
    format(patternFormatter("MMMM yyyy"))

// Material date pickers speak UTC milliseconds. Converting via epoch days avoids time zone off-by-one bugs.
fun LocalDate.toPickerMillis(): Long = toEpochDay() * 86_400_000L
fun Long.toPickerDate(): LocalDate = LocalDate.ofEpochDay(Math.floorDiv(this, 86_400_000L))

fun durationLabel(minutes: Int): String = when {
    minutes < 60 -> "$minutes min"
    minutes % 60 == 0 -> "${minutes / 60} h"
    else -> "${minutes / 60} h ${minutes % 60} min"
}

fun eventEndLabel(date: LocalDate, time: LocalTime?, minutes: Int?, format: TimeFormat, context: Context, displayedDate: LocalDate = date): String {
    if (time == null || minutes == null) return ""
    val end = date.atTime(time).plusMinutes(minutes.toLong())
    val nextDay = if (end.toLocalDate() > displayedDate) " (next day)" else ""
    val carried = if (date < displayedDate) "Started ${if (date == displayedDate.minusDays(1)) "yesterday" else date.fullLabel()} · " else ""
    return "${carried}Until ${end.toLocalTime().label(format, context)}$nextDay · ${durationLabel(minutes)}"
}
