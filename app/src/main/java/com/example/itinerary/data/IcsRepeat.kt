package com.example.itinerary.data

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

// The repeat patterns (RRULE) people actually use, worked out into dates for importing a calendar file: daily, weekly,
// monthly and yearly, every N, on chosen weekdays, the Nth or last weekday of a month, a day of the month, ending after
// a number of times or on a date. [parse] returns null for anything else (hourly, BYSETPOS, several month days, …), and
// the caller then keeps only the first date.
internal class IcsRepeat private constructor(
    private val freq: String,
    private val interval: Int,
    private val count: Int?,
    private val until: LocalDateTime?, // on the event's own clock
    private val days: List<Pair<Int?, DayOfWeek>>, // BYDAY, with the optional ordinal (1 = first, -1 = last)
    private val monthDay: Int?,
    private val month: Int?,
) {
    // Dates on the event's own clock, in order: [start] first (it always counts, even if the rule wouldn't pick it), then
    // each later date the rule picks, up to [limit] and the rule's own end, at most [max] in all.
    fun dates(start: LocalDate, startTime: java.time.LocalTime, limit: LocalDate, max: Int = 5000): List<LocalDate> {
        val result = mutableListOf(start)
        if (count == 1) return result
        for (period in 0L until 200_000L) {
            if (periodStart(start, period) > limit) break
            for (date in candidates(start, period)) {
                if (date <= start) continue
                if (date > limit || until != null && date.atTime(startTime).isAfter(until)) return result
                result += date
                if (count != null && result.size >= count || result.size >= max) return result
            }
        }
        return result
    }

    // Periods are days, weeks (from Monday), months or years, [interval] apart; each is later than the one before.
    private fun periodStart(start: LocalDate, period: Long): LocalDate = when (freq) {
        "DAILY" -> start.plusDays(period * interval)
        "WEEKLY" -> start.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusWeeks(period * interval)
        "MONTHLY" -> YearMonth.from(start).plusMonths(period * interval).atDay(1)
        else -> YearMonth.of(start.year, month ?: start.monthValue).plusYears(period * interval).atDay(1)
    }

    // The dates the rule picks in one period, in order.
    private fun candidates(start: LocalDate, period: Long): List<LocalDate> {
        val from = periodStart(start, period)
        return when (freq) {
            "DAILY" -> listOf(from).filter { d -> days.isEmpty() || days.any { it.second == d.dayOfWeek } }
            "WEEKLY" -> (if (days.isEmpty()) listOf(start.dayOfWeek) else days.map { it.second }).distinct().sorted().map { from.plusDays(it.value - 1L) }
            else -> listOfNotNull(inMonth(YearMonth.from(from), start))
        }
    }

    // The one date this rule picks in [month]: the Nth/last weekday, the given day of the month, or the start's day.
    // Null when the month doesn't have it (the 31st in April, 29 February in most years).
    private fun inMonth(month: YearMonth, start: LocalDate): LocalDate? {
        days.singleOrNull()?.let { (ordinal, day) ->
            val n = ordinal ?: return null
            return if (n > 0) month.atDay(1).with(TemporalAdjusters.dayOfWeekInMonth(n, day)).takeIf { YearMonth.from(it) == month }
            else month.atEndOfMonth().with(TemporalAdjusters.lastInMonth(day))
        }
        val day = monthDay ?: start.dayOfMonth
        val actual = if (day > 0) day else month.lengthOfMonth() + day + 1
        return if (actual in 1..month.lengthOfMonth()) month.atDay(actual) else null
    }

    // The closest Planner rule, so the series can be changed as a whole later; NONE when there is no equivalent (the
    // dates are kept either way).
    fun plannerRule(start: LocalDate): RepeatRule {
        val weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
        val plainDays = days.map { it.second }.toSet()
        return when (freq) {
            "DAILY" -> when {
                days.isEmpty() && interval == 1 -> RepeatRule.DAILY
                days.isEmpty() && interval in 2..365 -> RepeatRule.everyDays(interval)
                plainDays == weekdays && interval == 1 -> RepeatRule.WEEKDAYS
                interval == 1 -> RepeatRule.onDays(plainDays)
                else -> RepeatRule.NONE
            }
            "WEEKLY" -> when {
                plainDays.size > 1 -> if (interval == 1) RepeatRule.onDays(plainDays) else RepeatRule.NONE
                plainDays.isNotEmpty() && plainDays.single() != start.dayOfWeek -> RepeatRule.NONE
                interval == 1 -> RepeatRule.WEEKLY
                interval == 2 -> RepeatRule.FORTNIGHTLY
                interval in 3..52 -> RepeatRule.everyWeeks(interval)
                else -> RepeatRule.NONE
            }
            "MONTHLY" -> when {
                interval != 1 -> RepeatRule.NONE
                days.isEmpty() && (monthDay == null || monthDay == start.dayOfMonth) -> RepeatRule.MONTHLY
                days.size == 1 -> days.single().let { (n, day) ->
                    if (n != null && (n in 1..4 || n == -1)) RepeatRule.monthlyOn(if (n == -1) RepeatRule.LAST else n, day) else RepeatRule.NONE
                }
                else -> RepeatRule.NONE
            }
            "YEARLY" -> if (interval == 1 && days.isEmpty() && monthDay == null) RepeatRule.YEARLY else RepeatRule.NONE
            else -> RepeatRule.NONE
        }
    }

    companion object {
        private val DAYS = mapOf("MO" to DayOfWeek.MONDAY, "TU" to DayOfWeek.TUESDAY, "WE" to DayOfWeek.WEDNESDAY,
            "TH" to DayOfWeek.THURSDAY, "FR" to DayOfWeek.FRIDAY, "SA" to DayOfWeek.SATURDAY, "SU" to DayOfWeek.SUNDAY)
        private val KNOWN = setOf("FREQ", "INTERVAL", "COUNT", "UNTIL", "BYDAY", "BYMONTHDAY", "BYMONTH", "WKST")

        // [zone] is the event's own time zone (UNTIL in UTC is moved onto its clock); [start] is its first date.
        fun parse(value: String, zone: ZoneId, start: LocalDate): IcsRepeat? = runCatching {
            val parts = value.split(';').filter { it.isNotBlank() }.associate { it.substringBefore('=').uppercase() to it.substringAfter('=') }
            if (parts.keys.any { it !in KNOWN }) return null
            val freq = parts["FREQ"]?.uppercase()?.takeIf { it in setOf("DAILY", "WEEKLY", "MONTHLY", "YEARLY") } ?: return null
            val interval = parts["INTERVAL"]?.toInt() ?: 1
            if (interval !in 1..1000) return null
            val count = parts["COUNT"]?.toInt()?.also { if (it < 1) return null }
            val until = parts["UNTIL"]?.let { text ->
                if (text.length == 8) LocalDate.parse(text, java.time.format.DateTimeFormatter.BASIC_ISO_DATE).atTime(23, 59, 59)
                else Ics.time(Ics.Property("UNTIL", emptyMap(), text), zone, strictGap = false) { zone }.withZoneSameInstant(zone).toLocalDateTime()
            }
            val days = parts["BYDAY"]?.split(',')?.map { code ->
                val match = Regex("([+-]?[0-9]{1,2})?([A-Za-z]{2})").matchEntire(code.trim()) ?: return null
                match.groupValues[1].takeIf { it.isNotEmpty() }?.toInt() to (DAYS[match.groupValues[2].uppercase()] ?: return null)
            }.orEmpty()
            val monthDay = parts["BYMONTHDAY"]?.let { if (',' in it) return null else it.toInt().also { d -> if (d == 0 || d !in -31..31) return null } }
            val month = parts["BYMONTH"]?.let { if (',' in it) return null else it.toInt().also { m -> if (m !in 1..12) return null } }
            // Combinations beyond the everyday ones are refused rather than guessed.
            when (freq) {
                "DAILY" -> if (monthDay != null || month != null || days.any { it.first != null }) return null
                "WEEKLY" -> if (monthDay != null || month != null || days.any { it.first != null }) return null
                "MONTHLY" -> if (month != null || days.size > 1 || days.any { it.first == null } || days.isNotEmpty() && monthDay != null) return null
                "YEARLY" -> if (days.size > 1 || days.any { it.first == null } || days.isNotEmpty() && monthDay != null ||
                    month == null && (days.isNotEmpty() || monthDay != null) || month != null && days.isEmpty() && monthDay == null && month != start.monthValue) return null
            }
            IcsRepeat(freq, interval, count, until, days, monthDay, month)
        }.getOrNull()
    }
}
