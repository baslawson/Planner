package com.example.itinerary.data

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

// iCalendar (.ics) reading shared by CalendarImport (strict, one invitation) and OutsideEventReader (forgiving, a whole
// calendar): folded lines, properties with parameters, escaped text, date-times and the VEVENTs in a file.
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
        val pieces = line.substring(0, colon).split(';')
        return Property(pieces[0].uppercase(), pieces.drop(1).associate {
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
}
