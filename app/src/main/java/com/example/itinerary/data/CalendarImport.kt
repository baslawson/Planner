package com.example.itinerary.data

import java.time.*
import java.time.format.DateTimeFormatter

/** Deliberately bounded invitation reader. Unsupported scheduling semantics fail visibly, never silently flatten. */
object CalendarImport {
    const val MAX_BYTES = 1_048_576
    data class Invitation(val item: ItineraryItem, val warnings: List<String>)
    private data class Property(val name: String, val params: Map<String, String>, val value: String)
    private fun property(line: String): Property {
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
    private fun unescape(value: String): String {
        val result = StringBuilder(); var i = 0
        while (i < value.length) {
            val c = value[i++]
            if (c == '\\' && i < value.length) {
                val next = value[i++]; result.append(if (next == 'n' || next == 'N') '\n' else next)
            } else result.append(c)
        }
        return result.toString()
    }
    private val dateTime = DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss").withResolverStyle(java.time.format.ResolverStyle.STRICT)
    private fun time(p: Property, localZone: ZoneId): ZonedDateTime {
        val utc = p.value.endsWith("Z")
        require(p.params["VALUE"] in listOf(null, "DATE-TIME")) { "Unsupported date type." }
        val local = LocalDateTime.parse(p.value.removeSuffix("Z"), dateTime)
        val zone = if (utc) ZoneOffset.UTC else p.params["TZID"]?.let {
            runCatching { ZoneId.of(it) }.getOrElse { error("Unsupported time zone: $it") }
        } ?: localZone
        require(zone.rules.getValidOffsets(local).isNotEmpty()) { "An invitation time falls in a daylight-saving gap." }
        return local.atZone(zone).withZoneSameInstant(localZone)
    }
    fun parse(text: String, zone: ZoneId = ZoneId.systemDefault()): List<Invitation> {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Calendar file is too large (maximum 1 MB)." }
        val lines = text.removePrefix("\uFEFF").replace("\r\n", "\n").replace("\r", "\n")
            .replace(Regex("\n[ \t]"), "").lines().filter { it.isNotBlank() }
        require(lines.firstOrNull()?.uppercase() == "BEGIN:VCALENDAR" && lines.lastOrNull()?.uppercase() == "END:VCALENDAR") { "This is not a complete calendar file." }
        require(lines.none { it.equals("METHOD:CANCEL", true) }) { "This is a cancellation, not a new appointment." }
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
                    if (component == "VEVENT") { events += requireNotNull(current).toList(); current = null; require(events.size <= 100) { "Import at most 100 appointments at once." } }
                    stack.removeAt(stack.lastIndex)
                }
                else -> if (stack.lastOrNull() == "VEVENT") current?.add(p)
            }
        }
        require(stack.isEmpty() && events.isNotEmpty()) { "No appointments found." }
        return events.flatMap { props ->
            fun one(name: String): Property? = props.filter { it.name == name }.also { require(it.size <= 1) { "Repeated $name property." } }.firstOrNull()
            require(props.none { it.name in setOf("RRULE", "RDATE", "EXDATE", "RECURRENCE-ID") }) { "Repeating invitations are not supported yet. Export individual occurrences." }
            require(one("STATUS")?.value != "CANCELLED") { "This appointment was cancelled." }
            val start = requireNotNull(one("DTSTART")) { "Appointment has no start date." }
            val end = one("DTEND"); val duration = one("DURATION")
            require(end == null || duration == null) { "Appointment has both an end and a duration." }
            val title = one("SUMMARY")?.value?.let(::unescape)?.takeIf { it.isNotBlank() } ?: "Imported appointment"
            require(title.length <= 500) { "Appointment title is too long." }
            val notes = one("DESCRIPTION")?.value?.let(::unescape).orEmpty()
            val location = one("LOCATION")?.value?.let(::unescape).orEmpty()
            require(notes.length <= 20_000 && location.length <= 2000) { "Appointment details are too long." }
            val warnings = mutableListOf("Review before saving. Invitation responses, attachments and alarms are not imported.")
            if (start.params["TZID"] != null || start.value.endsWith("Z")) warnings += "Times converted to ${zone.id}; Planner stores local clock times."
            if (one("TRANSP")?.value == "TRANSPARENT") warnings += "This invitation was marked available; Planner will treat it as busy."
            if (start.params["VALUE"] == "DATE" || start.value.matches(Regex("[0-9]{8}"))) {
                val first = LocalDate.parse(start.value, DateTimeFormatter.BASIC_ISO_DATE)
                val last = if (end != null) {
                    require(end.params["VALUE"] == "DATE" || end.value.matches(Regex("[0-9]{8}")))
                    LocalDate.parse(end.value, DateTimeFormatter.BASIC_ISO_DATE)
                } else if (duration != null) {
                    val match = Regex("P([0-9]+)([DW])").matchEntire(duration.value) ?: error("Unsupported all-day duration.")
                    first.plusDays(match.groupValues[1].toLong() * if (match.groupValues[2] == "W") 7 else 1)
                } else first.plusDays(1)
                val days = java.time.temporal.ChronoUnit.DAYS.between(first, last)
                require(days in 1..MultiDay.MAX_DAYS) { "All-day appointments must last 1–${MultiDay.MAX_DAYS} days." }
                // One event, however many days it covers (the end in the file is exclusive).
                listOf(Invitation(ItineraryItem(tripId = 0, date = first, startTime = null, title = title, notes = notes,
                    location = location, endDate = first.plusDays(days - 1).takeIf { days > 1 }), warnings))
            } else {
                val begin = time(start, zone)
                val finish = end?.let { time(it, zone) } ?: duration?.let { begin.plus(Duration.parse(it.value)) }
                val minutes = finish?.let {
                    require(it.toInstant() > begin.toInstant()) { "Appointment ends before it starts." }
                    val seconds = Duration.between(begin.toLocalDateTime(), it.toLocalDateTime()).seconds
                    require(seconds in 60..86400 && seconds % 60 == 0L) { "Timed appointments must last 1 minute–24 hours in local time." }
                    (seconds / 60).toInt()
                }
                require(begin.second == 0) { "Appointments with second precision are not supported." }
                listOf(Invitation(ItineraryItem(tripId = 0, date = begin.toLocalDate(), startTime = begin.toLocalTime(),
                    title = title, notes = notes, location = location, durationMinutes = minutes), warnings))
            }
        }.also { require(it.size <= 100) { "Import at most 100 entries at once." } }
    }
}
