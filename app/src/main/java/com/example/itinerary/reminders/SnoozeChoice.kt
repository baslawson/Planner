package com.example.itinerary.reminders

import java.time.ZonedDateTime

enum class SnoozeChoice(val label: String) {
    TEN_MINUTES("10 minutes"), ONE_HOUR("1 hour"), TOMORROW("Tomorrow at 9 am");
    fun until(now: ZonedDateTime = ZonedDateTime.now()): Long = when (this) {
        TEN_MINUTES -> now.plusMinutes(10)
        ONE_HOUR -> now.plusHours(1)
        TOMORROW -> now.toLocalDate().plusDays(1).atTime(9, 0).atZone(now.zone)
    }.toInstant().toEpochMilli()
}
