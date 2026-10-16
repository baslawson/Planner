package com.example.itinerary.reminders

import java.time.ZonedDateTime

enum class SnoozeChoice(private val text: String) {
    TEN_MINUTES("10 minutes"), ONE_HOUR("1 hour"), TOMORROW("Tomorrow at 9 am");
    // Bug hunt 37 R4: in the small hours (before [EARLY_MORNING_HOUR]) "tomorrow" is the coming morning, today at 9, and
    // is said so.
    fun label(now: ZonedDateTime = ZonedDateTime.now()): String =
        if (this == TOMORROW && now.hour < EARLY_MORNING_HOUR) "This morning at 9 am" else text
    val label: String get() = label(ZonedDateTime.now())
    fun until(now: ZonedDateTime = ZonedDateTime.now()): Long = when (this) {
        TEN_MINUTES -> now.plusMinutes(10)
        ONE_HOUR -> now.plusHours(1)
        TOMORROW -> nineAm(now)
    }.toInstant().toEpochMilli()
}

/** Bug hunt 37 R4: before this hour, a snooze to "tomorrow at 9 am" is today's 9 am (00:30 would otherwise be ~32 h). */
internal const val EARLY_MORNING_HOUR = 4

internal fun nineAm(now: ZonedDateTime): ZonedDateTime =
    now.toLocalDate().let { if (now.hour < EARLY_MORNING_HOUR) it else it.plusDays(1) }.atTime(9, 0).atZone(now.zone)
