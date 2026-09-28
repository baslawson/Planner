package com.example.itinerary.ui

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.runtime.compositionLocalOf
import com.example.itinerary.data.DateFormatChoice
import com.example.itinerary.data.TimeFormat
import com.example.itinerary.data.Trip
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
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

// A list may render the same date/time pattern hundreds of times. Formatters are immutable;
// include the locale in the key so changing the phone language cannot reuse the wrong one.
private val patternFormatters = ConcurrentHashMap<Pair<String, Locale>, DateTimeFormatter>()

private fun patternFormatter(pattern: String): DateTimeFormatter {
    val locale = Locale.getDefault()
    return patternFormatters.getOrPut(pattern to locale) { DateTimeFormatter.ofPattern(pattern, locale) }
}

// Provided by MainActivity after resolving the system clock preference; the default only matters in previews.
val LocalTimeFormat = compositionLocalOf { TimeFormat.SYSTEM }

fun TimeFormat.is24Hour(context: Context): Boolean = when (this) {
    TimeFormat.SYSTEM -> DateFormat.is24HourFormat(context)
    TimeFormat.HOUR_12 -> false
    TimeFormat.HOUR_24 -> true
}

fun LocalDate.fullLabel(): String =
    format(patternFormatter("d MMM yyyy"))

fun LocalTime.label(timeFormat: TimeFormat, context: Context): String {
    // Locale defaults can differ from Android's explicit 12/24-hour preference.
    val pattern = if (timeFormat.is24Hour(context)) "HH:mm" else "h:mm a"
    return format(patternFormatter(pattern))
}

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
