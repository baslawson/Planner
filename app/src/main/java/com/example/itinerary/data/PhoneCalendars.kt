package com.example.itinerary.data

import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

// Calendar sync step 3: the calendars already on this phone (Google, Samsung, Outlook, DAVx⁵…), read-only through
// Android's calendar storage. Planner holds READ_CALENDAR only, so it can't change them.

// A calendar the phone's own calendar app shows.
data class PhoneCalendar(val id: Long, val name: String, val accountName: String, val accountType: String, val color: Int?)

// One date of an event, as Android lists it (repeats already expanded). All-day dates are UTC midnights; [end] is
// exclusive.
data class PhoneInstance(val calendarId: Long, val begin: Long, val end: Long, val allDay: Boolean, val title: String?,
                         val location: String?, val description: String?, val cancelled: Boolean = false, val declined: Boolean = false)

// What CalendarSync needs from the phone; the Android version is AndroidPhoneCalendars, tests use a fake. H17-S3: a
// query the phone can't answer throws (CalendarSync then changes nothing), rather than reading as an empty list.
interface PhoneCalendarReader {
    fun permitted(): Boolean
    fun calendars(): List<PhoneCalendar>
    fun instances(calendarIds: Collection<Long>, from: Instant, until: Instant): List<PhoneInstance>
}

object PhoneEvents {
    // DAVx⁵ puts a CalDAV server's calendars (often the same Nextcloud) on the phone.
    private val DAVX5 = setOf("bitfire.at.davdroid", "at.bitfire.davdroid", "com.davdroid")

    fun detail(calendar: PhoneCalendar): String = calendar.accountName.ifBlank { "This phone" } +
        if (calendar.accountType in DAVX5) " · synced by DAVx⁵. If it's also ticked under Nextcloud, its events show twice." else ""

    // One date as Planner shows it; null for a cancelled one or one the user declined.
    fun toEvent(instance: PhoneInstance, zone: ZoneId): OutsideEvent? {
        if (instance.cancelled || instance.declined) return null
        val timing = if (instance.allDay) {
            val first = Instant.ofEpochMilli(instance.begin).atZone(ZoneOffset.UTC).toLocalDate()
            val after = Instant.ofEpochMilli(instance.end).atZone(ZoneOffset.UTC).toLocalDate()
            Ics.allDay(first, ChronoUnit.DAYS.between(first, after).coerceIn(1, MultiDay.MAX_DAYS.toLong()))
        } else {
            val begin = Instant.ofEpochMilli(instance.begin).atZone(zone).truncatedTo(ChronoUnit.MINUTES).toLocalDateTime()
            val finish = Instant.ofEpochMilli(instance.end).atZone(zone).truncatedTo(ChronoUnit.MINUTES).toLocalDateTime()
            Ics.timed(begin, finish)
        }
        return OutsideEvent(sourceId = 0, date = timing.date, startTime = timing.startTime, durationMinutes = timing.durationMinutes,
            endDate = timing.endDate, timedStart = timing.timedStart, timedEnd = timing.timedEnd,
            title = instance.title?.trim()?.takeIf { it.isNotEmpty() }?.take(500) ?: "(No title)",
            location = instance.location?.trim().orEmpty().take(2000), notes = instance.description?.trim().orEmpty().take(20_000))
    }
}
