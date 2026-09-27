package com.example.itinerary.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class BackupReminder(val enabled: Boolean = true, val deferredUntil: LocalDate? = null) {
    fun due(lastSuccess: String?, today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Boolean {
        if (!enabled || deferredUntil?.let { today < it } == true) return false
        val last = lastSuccess?.let { runCatching { Instant.parse(it).atZone(zone).toLocalDate() }.getOrNull() }
        return last == null || !today.isBefore(last.plusDays(7))
    }
}
