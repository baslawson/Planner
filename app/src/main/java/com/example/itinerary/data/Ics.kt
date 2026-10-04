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
    fun lines(text: String): List<String> = text.removePrefix("\uFEFF").replace("\r\n", "\n").replace("\r", "\n")
        .replace(Regex("\n[ \t]"), "").lines().filter { it.isNotBlank() }

    // A file's logical lines (a folded line with its continuation lines), each as the physical lines it came from, kept
    // as they are so a patch can write them back unchanged. Empty lines are dropped.
    fun logicalBlocks(text: String): List<List<String>> {
        val logical = mutableListOf<MutableList<String>>()
        for (line in text.removePrefix("\uFEFF").replace("\r\n", "\n").replace("\r", "\n").split("\n")) {
            if ((line.startsWith(" ") || line.startsWith("\t")) && logical.isNotEmpty()) logical.last() += line
            else if (line.isNotEmpty()) logical += mutableListOf(line)
        }
        return logical
    }

    // The property name of a logical line from [logicalBlocks] ("DTSTART" for "DTSTART;TZID=…:…"), in capitals.
    fun blockName(block: List<String>): String = block.first().substringBefore(':').substringBefore(';').uppercase()

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

    // A TZID as a zone: an IANA name as it is; one with a unique-id prefix such as "/mozilla.org/20050126_1/Europe/Berlin"
    // (older Thunderbird, libical) without it; a Windows name such as "W. Europe Standard Time" (Outlook, Exchange) as its
    // usual IANA zone. Throws for anything else, which callers treat as an unknown zone.
    fun zone(tzid: String): ZoneId {
        val id = tzid.trim()
        runCatching { return ZoneId.of(id) }
        if (id.startsWith("/")) {
            val parts = id.split('/').filter { it.isNotEmpty() }
            for (i in 1 until parts.size) runCatching { return ZoneId.of(parts.drop(i).joinToString("/")) }
        }
        // Android knows every Windows name; plain JVM tests don't have it, so the common ones are listed too.
        val windows = runCatching { android.icu.util.TimeZone.getIDForWindowsID(id, null) }.getOrNull() ?: WINDOWS_ZONES[id]
        return ZoneId.of(requireNotNull(windows) { "Unknown time zone." })
    }

    private val WINDOWS_ZONES = mapOf(
        "Dateline Standard Time" to "Etc/GMT+12", "Hawaiian Standard Time" to "Pacific/Honolulu", "Alaskan Standard Time" to "America/Anchorage",
        "Pacific Standard Time" to "America/Los_Angeles", "US Mountain Standard Time" to "America/Phoenix",
        "Mountain Standard Time" to "America/Denver", "Central Standard Time" to "America/Chicago",
        "Central Standard Time (Mexico)" to "America/Mexico_City", "Canada Central Standard Time" to "America/Regina",
        "Eastern Standard Time" to "America/New_York", "SA Pacific Standard Time" to "America/Bogota",
        "Atlantic Standard Time" to "America/Halifax", "Newfoundland Standard Time" to "America/St_Johns",
        "Pacific SA Standard Time" to "America/Santiago", "E. South America Standard Time" to "America/Sao_Paulo",
        "Argentina Standard Time" to "America/Argentina/Buenos_Aires", "GMT Standard Time" to "Europe/London",
        "Greenwich Standard Time" to "Atlantic/Reykjavik", "Morocco Standard Time" to "Africa/Casablanca",
        "W. Europe Standard Time" to "Europe/Berlin", "Central Europe Standard Time" to "Europe/Budapest",
        "Romance Standard Time" to "Europe/Paris", "Central European Standard Time" to "Europe/Warsaw",
        "W. Central Africa Standard Time" to "Africa/Lagos", "GTB Standard Time" to "Europe/Bucharest",
        "E. Europe Standard Time" to "Europe/Chisinau", "FLE Standard Time" to "Europe/Kiev", "Egypt Standard Time" to "Africa/Cairo",
        "South Africa Standard Time" to "Africa/Johannesburg", "Israel Standard Time" to "Asia/Jerusalem",
        "Turkey Standard Time" to "Europe/Istanbul", "Russian Standard Time" to "Europe/Moscow", "Arab Standard Time" to "Asia/Riyadh",
        "E. Africa Standard Time" to "Africa/Nairobi", "Iran Standard Time" to "Asia/Tehran", "Arabian Standard Time" to "Asia/Dubai",
        "Pakistan Standard Time" to "Asia/Karachi", "India Standard Time" to "Asia/Kolkata", "Nepal Standard Time" to "Asia/Kathmandu",
        "Bangladesh Standard Time" to "Asia/Dhaka", "Myanmar Standard Time" to "Asia/Yangon", "SE Asia Standard Time" to "Asia/Bangkok",
        "China Standard Time" to "Asia/Shanghai", "Singapore Standard Time" to "Asia/Singapore", "Taipei Standard Time" to "Asia/Taipei",
        "W. Australia Standard Time" to "Australia/Perth", "Tokyo Standard Time" to "Asia/Tokyo", "Korea Standard Time" to "Asia/Seoul",
        "Cen. Australia Standard Time" to "Australia/Adelaide", "AUS Central Standard Time" to "Australia/Darwin",
        "E. Australia Standard Time" to "Australia/Brisbane", "AUS Eastern Standard Time" to "Australia/Sydney",
        "Tasmania Standard Time" to "Australia/Hobart", "New Zealand Standard Time" to "Pacific/Auckland",
    )

    fun isDate(p: Property): Boolean =p.params["VALUE"] == "DATE" || p.value.matches(Regex("[0-9]{8}"))

    // The properties of each VEVENT (or [component], such as VTODO), in file order; those of components inside one (such as
    // an alarm) are left out. With [unreadable], a line that isn't a property (a raw line break inside a description) only
    // costs its own event, which is left out and reported there; outside an event such a line is ignored. Without it the
    // whole text is refused (a server's reply holds one item).
    // R5-1: with [unreadable], so is a raw line that reads as BEGIN or END ("End: 17:00" in a shift's description): one
    // whose value isn't a component name is ignored outside an event; inside one, it or an END that doesn't close what
    // is open costs that event. An event cut short (no END:VEVENT before the next BEGIN:VEVENT) costs only itself.
    fun events(lines: List<String>, max: Int, tooMany: String, unfinished: String = "Incomplete calendar component.",
               component: String = "VEVENT", unreadable: (() -> Unit)? = null): List<List<Property>> {
        val stack = mutableListOf<String>(); val events = mutableListOf<List<Property>>(); var current: MutableList<Property>? = null
        var broken = false
        // The last thing read was an event, kept (see the END of nothing open below).
        var justRead = false
        val forgiving = unreadable != null
        for (line in lines) {
            val p = if (unreadable == null) property(line) else runCatching { property(line) }.getOrNull()
            if (p == null) { if (current != null) broken = true; continue }
            if (forgiving && (p.name == "BEGIN" || p.name == "END") && !p.value.matches(COMPONENT_NAME)) {
                if (current != null) broken = true; continue
            }
            when (p.name) {
                "BEGIN" -> {
                    val name = p.value.uppercase()
                    if (name == component && forgiving && current != null) {
                        // The open one was cut short: it is skipped, and what it had open goes with it.
                        unreadable?.invoke(); current = null
                        stack.subList(stack.lastIndexOf(component), stack.size).clear()
                    }
                    if (name == component) { require(current == null); current = mutableListOf(); broken = false }
                    // AS-4: a raw "Begin:Lunch" after a raw "End:Vevent" is more of the same text, so the event just read
                    // is still the one its real END:VEVENT skips.
                    stack += name; if (name == component || name == "VCALENDAR") justRead = false
                }
                "END" -> {
                    val name = p.value.uppercase()
                    if (forgiving && current != null && stack.lastOrNull() != name) {
                        // A stray END inside the event, or its own END with something inside still open: either way
                        // the event can't be trusted. Its own END still closes it.
                        broken = true
                        val open = stack.lastIndexOf(component)
                        if (name == component) stack.subList(open + 1, stack.size).clear()
                        // S6-3: the END of what the event sits in (the file's END:VCALENDAR after an event that never
                        // ended): the event is skipped, and that END closes its own component below.
                        else if (name in stack.subList(0, open)) { unreadable?.invoke(); current = null; stack.subList(open, stack.size).clear() }
                        else continue
                    }
                    if (forgiving && current == null && stack.lastOrNull() != name) {
                        // S6-3: outside an event, an END of nothing open is text ("End:Monday"), except a second END of
                        // an event: a raw "End:Vevent" in its text closed it early, so the event just read is skipped
                        // after all. An END of something further out closes what a raw "Begin:Notes" left open.
                        val at = stack.lastIndexOf(name)
                        if (at < 0) {
                            if (name == component && justRead) { events.removeAt(events.lastIndex); unreadable?.invoke() }
                            justRead = false; continue
                        }
                        stack.subList(at + 1, stack.size).clear()
                    }
                    require(stack.lastOrNull() == name) { "Incomplete calendar component." }
                    justRead = false
                    if (name == component) {
                        if (broken) unreadable?.invoke() else { events += requireNotNull(current).toList(); justRead = true }
                        current = null; require(events.size <= max) { tooMany }
                    }
                    stack.removeAt(stack.lastIndex)
                }
                else -> if (stack.lastOrNull() == component) current?.add(p)
            }
        }
        require(stack.isEmpty()) { unfinished }
        return events
    }

    private val COMPONENT_NAME = Regex("[A-Za-z][A-Za-z0-9-]*")

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
