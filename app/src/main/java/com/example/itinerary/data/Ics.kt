package com.example.itinerary.data

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.time.temporal.ChronoUnit

// iCalendar (.ics) reading shared by CalendarFileImport (a file copied into Planner) and OutsideEventReader (calendar
// sync): folded lines, properties with parameters, escaped text, date-times, the VEVENTs in a file, and how an event's
// start and end become Planner's date, time, duration and span.
internal object Ics {
    class Property(val name: String, val params: Map<String, String>, val value: String)

    // Unfolded, non-blank content lines.
    fun lines(text: String): List<String> = text.removePrefix("﻿").replace("\r\n", "\n").replace("\r", "\n")
        .replace(Regex("\n[ \t]"), "").lines().filter { it.isNotBlank() }

    fun property(line: String): Property {
        var quoted = false
        val colon = line.indices.firstOrNull { i ->
            if (line[i] == '"') quoted = !quoted
            line[i] == ':' && !quoted
        } ?: error("Invalid calendar property.")
        // Parameters end at a ';' outside double quotes (CN="Smith; Jane").
        val pieces = mutableListOf(StringBuilder())
        quoted = false
        for (c in line.substring(0, colon)) {
            if (c == '"') quoted = !quoted
            if (c == ';' && !quoted) pieces += StringBuilder() else pieces.last().append(c)
        }
        return Property(pieces[0].toString().uppercase(), pieces.drop(1).map { it.toString() }.associate {
            require('=' in it) { "Invalid calendar parameter." }
            it.substringBefore('=').uppercase() to it.substringAfter('=').trim('"')
        }, line.substring(colon + 1))
    }

    fun unescape(value: String): String {
        val result = StringBuilder(); var i = 0
        while (i < value.length) {
            val c = value[i++]
            if (c == '\\' && i < value.length) {
                val next = value[i++]; result.append(if (next == 'n' || next == 'N') '\n' else next)
            } else result.append(c)
        }
        return result.toString()
    }

    private val dateTime = DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss").withResolverStyle(ResolverStyle.STRICT)

    // A DATE-TIME value as a moment: UTC ("…Z"), in its TZID, or floating (read as [localZone]). [zoneFor] decides what an
    // unknown TZID means; [strictGap] refuses a clock time that doesn't exist because of a daylight-saving change.
    fun time(p: Property, localZone: ZoneId, strictGap: Boolean, zoneFor: (String) -> ZoneId): ZonedDateTime {
        val utc = p.value.endsWith("Z")
        require(p.params["VALUE"] in listOf(null, "DATE-TIME")) { "Unsupported date type." }
        val local = LocalDateTime.parse(p.value.removeSuffix("Z"), dateTime)
        val zone = if (utc) ZoneOffset.UTC else p.params["TZID"]?.let(zoneFor) ?: localZone
        if (strictGap) require(zone.rules.getValidOffsets(local).isNotEmpty()) { "An invitation time falls in a daylight-saving gap." }
        return local.atZone(zone)
    }

    fun isDate(p: Property): Boolean = p.params["VALUE"] == "DATE" || p.value.matches(Regex("[0-9]{8}"))

    // The properties of each VEVENT, in file order; those of components inside an event (such as an alarm) are left out.
    fun events(lines: List<String>, max: Int, tooMany: String, unfinished: String = "Incomplete calendar component."): List<List<Property>> {
        val stack = mutableListOf<String>(); val events = mutableListOf<List<Property>>(); var current: MutableList<Property>? = null
        for (line in lines) {
            val p = property(line)
            when (p.name) {
                "BEGIN" -> {
                    val component = p.value.uppercase()
                    if (component == "VEVENT") { require(current == null); current = mutableListOf() }
                    stack += component
                }
                "END" -> {
                    val component = p.value.uppercase()
                    require(stack.lastOrNull() == component) { "Incomplete calendar component." }
                    if (component == "VEVENT") { events += requireNotNull(current).toList(); current = null; require(events.size <= max) { tooMany } }
                    stack.removeAt(stack.lastIndex)
                }
                else -> if (stack.lastOrNull() == "VEVENT") current?.add(p)
            }
        }
        require(stack.isEmpty()) { unfinished }
        return events
    }

    fun date(p: Property): LocalDate = LocalDate.parse(p.value.take(8), DateTimeFormatter.BASIC_ISO_DATE)

    // iCalendar durations may be in weeks ("P2W"), which java.time.Duration doesn't read.
    fun duration(value: String): Duration {
        val weeks = Regex("([+-]?)P([0-9]+)W").matchEntire(value) ?: return Duration.parse(value)
        val length = Duration.ofDays(weeks.groupValues[2].toLong() * 7)
        return if (weeks.groupValues[1] == "-") length.negated() else length
    }

    // How long an all-day event lasts in days as its file says: from its exclusive end date or its duration; one day when
    // it has neither. At least one day.
    fun allDayDays(first: LocalDate, end: Property?, duration: Property?): Long {
        val after = when {
            end != null && isDate(end) -> date(end)
            duration != null -> first.plusDays(duration(duration.value).toDays())
            else -> first.plusDays(1)
        }
        return ChronoUnit.DAYS.between(first, after).coerceAtLeast(1)
    }

    // The same, at most MultiDay.MAX_DAYS: what a read-only copy shows (a longer event is cut there).
    fun allDayLength(first: LocalDate, end: Property?, duration: Property?): Long =
        allDayDays(first, end, duration).coerceAtMost(MultiDay.MAX_DAYS.toLong())

    // Planner's view of one occurrence. Timed events longer than a day are shown across their days like an all-day
    // event; timedStart/timedEnd keep their real clock times (the end is on endDate, or the midnight after it at 00:00).
    data class Timing(val date: LocalDate, val startTime: LocalTime?, val durationMinutes: Int? = null, val endDate: LocalDate? = null,
                      val timedStart: LocalTime? = null, val timedEnd: LocalTime? = null)

    fun allDay(first: LocalDate, days: Long) = Timing(first, null, endDate = first.plusDays(days - 1).takeIf { days > 1 })

    // [begin] and [finish] on the phone's clock, to the minute. Minutes are wall-clock minutes, as Planner shows them.
    fun timed(begin: LocalDateTime, finish: LocalDateTime?): Timing {
        val minutes = finish?.let { ChronoUnit.MINUTES.between(begin, it) }?.takeIf { it > 0 }
        if (minutes == null || minutes <= MAX_TIMED_MINUTES) return Timing(begin.toLocalDate(), begin.toLocalTime(), minutes?.toInt())
        // Ending exactly at midnight means the day before was the last one it covered.
        val lastDay = finish!!.toLocalDate().let { if (finish.toLocalTime() == LocalTime.MIDNIGHT) it.minusDays(1) else it }
            .coerceAtMost(begin.toLocalDate().plusDays(MultiDay.MAX_DAYS - 1L))
        return Timing(begin.toLocalDate(), null, endDate = lastDay.takeIf { it > begin.toLocalDate() },
            timedStart = begin.toLocalTime(), timedEnd = finish.toLocalTime())
    }

    private const val MAX_TIMED_MINUTES = 1440L
}
