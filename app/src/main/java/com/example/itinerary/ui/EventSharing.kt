package com.example.itinerary.ui

import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.example.itinerary.data.TimeFormat
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

fun eventShareIntent(context: Context, title: String, date: LocalDate, time: LocalTime?, duration: Int?, location: String, format: TimeFormat): Intent {
    val dateLabel = date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL))
    val whenText = if (time == null) "All day" else buildString {
        append(time.label(format, context))
        if (duration != null) {
            val end = date.atTime(time).plusMinutes(duration.toLong())
            append("–").append(end.toLocalTime().label(format, context))
            if (end.toLocalDate() > date) append(" (next day)")
        }
    }
    val text = listOf(title, dateLabel, whenText, location.trim()).filter { it.isNotBlank() }.joinToString("\n")
    return Intent(Intent.ACTION_SEND).setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, title).putExtra(Intent.EXTRA_TEXT, text)
}

fun shareEvent(context: Context, title: String, date: LocalDate, time: LocalTime?, duration: Int?, location: String, format: TimeFormat) {
    try {
        context.startActivity(Intent.createChooser(eventShareIntent(context, title, date, time, duration, location, format), "Share event"))
    } catch (_: android.content.ActivityNotFoundException) {
        Toast.makeText(context, "No sharing app is available", Toast.LENGTH_SHORT).show()
    }
}

// A task through the share sheet: its title, due date and notes.
fun shareTask(context: Context, title: String, due: LocalDate?, notes: String) {
    val text = listOfNotNull(title, due?.let { "Due " + it.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)) }, notes.trim().takeIf { it.isNotEmpty() })
        .joinToString("\n")
    val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, title).putExtra(Intent.EXTRA_TEXT, text)
    try { context.startActivity(Intent.createChooser(intent, "Share task")) }
    catch (_: android.content.ActivityNotFoundException) { Toast.makeText(context, "No sharing app is available", Toast.LENGTH_SHORT).show() }
}
