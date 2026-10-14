package com.example.itinerary.data

import java.time.Instant
import java.time.LocalDate
import java.time.MonthDay
import java.time.ZoneOffset

/**
 * The birthdays saved with the phone's contacts (user, 14 Oct), shown as one more read-only phone calendar, "Birthdays
 * (contacts)", beside the calendars Android lists (AndroidPhoneCalendars). Each birthday is an all-day event every year;
 * with a year of birth it says the age. 29 February falls on 28 February in other years.
 */
object ContactBirthdays {
    // The calendar's id among the phone's (Android's own are positive).
    const val CALENDAR_ID = -1L
    const val NAME = "Birthdays (contacts)"

    // A contact's birthday as saved: [year] when known.
    data class Birthday(val name: String, val day: MonthDay, val year: Int?)

    // The ways contacts apps save the date: 1990-05-17 (perhaps with a time), --05-17 without a year, 19900517.
    private val FULL = Regex("""^(\d{4})-(\d{1,2})-(\d{1,2})(?:[T ].*)?$""")
    private val NO_YEAR = Regex("""^--(\d{1,2})-?(\d{1,2})$""")
    private val COMPACT = Regex("""^(\d{4})(\d{2})(\d{2})$""")

    // Bug hunt 28 P11: as some contact syncs save it: 17.05.1990 (day first), and 17/05/1990 or 05/17/1990 (day first,
    // unless it can't be, or the phone is set to the US, which writes the month first).
    private val DOTTED = Regex("""^(\d{1,2})\.(\d{1,2})\.(\d{4})$""")
    private val SLASHED = Regex("""^(\d{1,2})/(\d{1,2})/(\d{4})$""")
    internal var monthFirstLocale: () -> Boolean = { java.util.Locale.getDefault().country == "US" }

    fun parse(name: String, raw: String?): Birthday? {
        val text = raw?.trim().orEmpty()
        val label = name.trim().ifEmpty { return null }
        fun of(month: String, day: String, year: String?) = runCatching {
            Birthday(label, MonthDay.of(month.toInt(), day.toInt()), year?.toInt()?.takeIf { it in 1800..9999 })
        }.getOrNull()
        FULL.matchEntire(text)?.let { return of(it.groupValues[2], it.groupValues[3], it.groupValues[1]) }
        NO_YEAR.matchEntire(text)?.let { return of(it.groupValues[1], it.groupValues[2], null) }
        COMPACT.matchEntire(text)?.let { return of(it.groupValues[2], it.groupValues[3], it.groupValues[1]) }
        DOTTED.matchEntire(text)?.let { return of(it.groupValues[2], it.groupValues[1], it.groupValues[3]) }
        SLASHED.matchEntire(text)?.let {
            val (a, b, year) = it.destructured
            val monthFirst = a.toInt() <= 12 && (b.toInt() > 12 || monthFirstLocale())
            return if (monthFirst) of(a, b, year) else of(b, a, year)
        }
        return null
    }

    /** Every birthday from [from] up to [until] (exclusive), as all-day dates the way Android lists them (UTC midnights). */
    fun instances(birthdays: List<Birthday>, from: Instant, until: Instant): List<PhoneInstance> {
        val first = from.atZone(ZoneOffset.UTC).toLocalDate()
        val last = until.atZone(ZoneOffset.UTC).toLocalDate()
        return birthdays.distinct().flatMap { b ->
            (first.year..last.year).mapNotNull { year ->
                val date = b.day.atYear(year)
                val age = b.year?.let { year - it }
                if (date < first || date >= last || (age != null && age < 0)) return@mapNotNull null
                PhoneInstance(CALENDAR_ID, midnight(date), midnight(date.plusDays(1)), allDay = true,
                    title = "${b.name}'s birthday" + if (age != null && age > 0) " ($age)" else "", location = null, description = null)
            }
        }.sortedBy { it.begin }
    }


    // Birthday reminders (user, 15 Oct): how many days before (0 = on the day); the choices Settings offers, OFF first.
    const val REMIND_OFF = -1
    val REMIND_CHOICES = listOf(REMIND_OFF, 0, 1, 3, 7)
    fun remindLabel(days: Int): String = when (days) {
        REMIND_OFF -> "Off"
        0 -> "On the day"
        1 -> "1 day before"
        7 -> "1 week before"
        else -> "$days days before"
    }

    /** A birthday to remind of: whose, on which day, and the age turned (when the year is known). */
    data class Upcoming(val contactId: Long, val name: String, val date: LocalDate, val age: Int?)

    /** The birthdays [days] days after [today], to remind of today. */
    fun dueIn(birthdays: List<Pair<Long, Birthday>>, today: LocalDate, days: Int): List<Upcoming> {
        if (days < 0) return emptyList()
        val day = today.plusDays(days.toLong())
        return birthdays.distinct().mapNotNull { (id, b) ->
            val date = b.day.atYear(day.year)
            val age = b.year?.let { day.year - it }
            if (date != day || (age != null && age < 0)) null else Upcoming(id, b.name, date, age?.takeIf { it > 0 })
        }.sortedBy { it.name }
    }

    /** What the reminder says: "Sam's birthday tomorrow (turns 34)". */
    fun reminderText(b: Upcoming, today: LocalDate): String {
        val days = java.time.temporal.ChronoUnit.DAYS.between(today, b.date)
        val whenText = when (days) { 0L -> "today"; 1L -> "tomorrow"; else -> "in $days days" }
        return "${b.name}'s birthday $whenText" + (b.age?.let { " (turns $it)" } ?: "")
    }

    /** Whose birthday a "Sam's birthday (34)" event is, or null for another title. */
    fun nameOf(title: String): String? = Regex("""^(.+)'s birthday(?: \(\d+\))?$""").matchEntire(title)?.groupValues?.get(1)
    private fun midnight(day: LocalDate) = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
}
