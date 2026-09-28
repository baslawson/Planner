package com.example.itinerary.data

import java.time.*
import java.time.temporal.ChronoUnit

data class FreeWindow(val start: LocalDateTime, val end: LocalDateTime)

object FreeTime {
    fun find(events: List<ItineraryItem>, from: LocalDate, through: LocalDate,
             start: LocalTime, end: LocalTime, minutes: Int, weekdaysOnly: Boolean,
             blockAllDay: Boolean, unknownMinutes: Int, now: LocalDateTime = LocalDateTime.now()): List<FreeWindow> {
        require(minutes in 1..1440 && unknownMinutes in 1..1440)
        require(!through.isBefore(from) && ChronoUnit.DAYS.between(from, through) <= 366)
        require(end > start)
        val busy = events.filter { !it.skipped && it.category != "Bills" }.mapNotNull {
            if (it.startTime == null) {
                // A multi-day event blocks every day it covers.
                if (blockAllDay) FreeWindow(it.date.atStartOfDay(), it.lastDay.plusDays(1).atStartOfDay()) else null
            } else {
                val begin = it.date.atTime(it.startTime)
                FreeWindow(begin.minusMinutes(it.bufferBeforeMinutes.toLong()), begin.plusMinutes((it.durationMinutes ?: unknownMinutes).toLong() + it.bufferAfterMinutes))
            }
        }.sortedBy { it.start }
        val result = mutableListOf<FreeWindow>()
        var day = from
        val roundedNow = if (now.second != 0 || now.nano != 0) now.truncatedTo(ChronoUnit.MINUTES).plusMinutes(1) else now
        while (day <= through) {
            if (!weekdaysOnly || day.dayOfWeek.value <= 5) {
                var cursor = maxOf(day.atTime(start), roundedNow)
                val limit = day.atTime(end)
                for (block in busy) {
                    if (block.end <= cursor || block.start >= limit) continue
                    val gapEnd = minOf(block.start, limit)
                    if (ChronoUnit.MINUTES.between(cursor, gapEnd) >= minutes) result += FreeWindow(cursor, gapEnd)
                    cursor = maxOf(cursor, block.end)
                    if (cursor >= limit) break
                }
                if (ChronoUnit.MINUTES.between(cursor, limit) >= minutes) result += FreeWindow(cursor, limit)
            }
            day = day.plusDays(1)
        }
        return result
    }
}

fun PlannerTask.duplicateForEditing(): PlannerTask = copy(
    id = java.util.UUID.randomUUID().toString(), done = false, dueDate = null, reminderAt = null,
    nextTaskId = null, repeatAnchorDay = 0,
    checklist = checklist.map { it.copy(id = java.util.UUID.randomUUID().toString(), done = false) },
    attachments = attachments.map { it.copy(id = 0, itemId = 0) },
)
