package com.example.itinerary.data

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/**
 * How a series repeats. Stored by [name]: the original rules by their plain name (WEEKLY), the newer ones with their
 * value after a colon: EVERY_N_DAYS:3, EVERY_N_WEEKS:3, EVERY_N_MONTHS:3, DAYS_OF_WEEK:MON,WED,FRI, MONTHLY_WEEKDAY:1,MON
 * (the first Monday) or MONTHLY_WEEKDAY:LAST,FRI. Event series are saved as separate dated events, so the rule
 * describes a series; tasks use it to find their next due date.
 */
data class RepeatRule(val kind: Kind, val every: Int = 1, val days: Set<DayOfWeek> = emptySet(), val week: Int = 0) {
    enum class Kind { NONE, DAILY, WEEKDAYS, WEEKLY, FORTNIGHTLY, MONTHLY, YEARLY, EVERY_N_DAYS, EVERY_N_WEEKS, EVERY_N_MONTHS, DAYS_OF_WEEK, MONTHLY_WEEKDAY }

    val name: String get() = when (kind) {
        Kind.EVERY_N_DAYS, Kind.EVERY_N_WEEKS, Kind.EVERY_N_MONTHS -> "${kind.name}:$every"
        Kind.DAYS_OF_WEEK -> "${kind.name}:" + days.sorted().joinToString(",") { it.name.take(3) }
        Kind.MONTHLY_WEEKDAY -> "${kind.name}:${if (week == LAST) "LAST" else week},${days.firstOrNull()?.name?.take(3).orEmpty()}"
        else -> kind.name
    }

    val label: String get() = when (kind) {
        Kind.NONE -> "Does not repeat"
        Kind.DAILY -> "Daily"
        Kind.WEEKDAYS -> "Weekdays"
        Kind.WEEKLY -> "Weekly"
        Kind.FORTNIGHTLY -> "Fortnightly"
        Kind.MONTHLY -> "Monthly"
        Kind.YEARLY -> "Yearly"
        Kind.EVERY_N_DAYS -> "Every $every days"
        Kind.EVERY_N_WEEKS -> "Every $every weeks"
        Kind.EVERY_N_MONTHS -> "Every $every months"
        Kind.DAYS_OF_WEEK -> "Every " + days.sorted().joinToString(", ") { shortDay(it) }
        Kind.MONTHLY_WEEKDAY -> "Monthly on the ${weekName(week)} ${days.firstOrNull()?.let(::fullDay) ?: "weekday"}"
    }

    /** Every 2–365 days, 2–52 weeks or 2–24 months; at least one weekday; the 1st–4th or last of one weekday. */
    val valid: Boolean get() = when (kind) {
        Kind.EVERY_N_DAYS -> every in 2..365
        Kind.EVERY_N_WEEKS -> every in 2..52
        Kind.EVERY_N_MONTHS -> every in 2..24
        Kind.DAYS_OF_WEEK -> days.isNotEmpty()
        Kind.MONTHLY_WEEKDAY -> days.size == 1 && (week in 1..4 || week == LAST)
        else -> true
    }

    // Always advance from the original date: Jan 31 → Feb 28 → Mar 31, without month-end drift. Monthly ones fall on
    // [anchorDay] (0: the start's own day), or the month's last day when it's shorter: 31 keeps 30 Nov → 31 Dec (T16-3).
    fun dates(start: LocalDate, count: Int, anchorDay: Int = 0): List<LocalDate> {
        require(count in 1..365) { "Choose between 1 and 365 occurrences" }
        require(valid) { "Choose a complete repeat" }
        return when (kind) {
            Kind.NONE -> listOf(start)
            // Monday to Friday, or the chosen weekdays; a start on another day moves to the next matching day.
            Kind.WEEKDAYS -> generateSequence(start) { it.plusDays(1) }.filter { it.dayOfWeek.value <= 5 }.take(count).toList()
            Kind.DAYS_OF_WEEK -> generateSequence(start) { it.plusDays(1) }.filter { it.dayOfWeek in days }.take(count).toList()
            Kind.MONTHLY_WEEKDAY -> generateSequence(YearMonth.from(start)) { it.plusMonths(1) }.map(::inMonth).filter { it >= start }.take(count).toList()
            else -> List(count) { index ->
                val i = index.toLong()
                when (kind) {
                    Kind.DAILY -> start.plusDays(i)
                    Kind.WEEKLY -> start.plusWeeks(i)
                    Kind.FORTNIGHTLY -> start.plusWeeks(i * 2)
                    Kind.MONTHLY -> monthOn(start, i, anchorDay)
                    Kind.YEARLY -> start.plusYears(i)
                    Kind.EVERY_N_DAYS -> start.plusDays(i * every)
                    Kind.EVERY_N_WEEKS -> start.plusWeeks(i * every)
                    // Always from the start date, like MONTHLY: 31 Jan every 3 months → 30 Apr, 31 Jul.
                    Kind.EVERY_N_MONTHS -> monthOn(start, i * every, anchorDay)
                    else -> error("Handled above")
                }
            }
        }
    }

    private fun monthOn(start: LocalDate, months: Long, anchorDay: Int): LocalDate =
        YearMonth.from(start).plusMonths(months).let { it.atDay(minOf(anchorDay.takeIf { day -> day > 0 } ?: start.dayOfMonth, it.lengthOfMonth())) }

    /** Whether [date] is one this rule can fall on: the right weekday, or the right week of the month. */
    fun fits(date: LocalDate): Boolean = when (kind) {
        Kind.WEEKDAYS -> date.dayOfWeek.value <= 5
        Kind.DAYS_OF_WEEK -> date.dayOfWeek in days
        Kind.MONTHLY_WEEKDAY -> valid && inMonth(YearMonth.from(date)) == date
        else -> true
    }

    /** For the newer rules: the first date of the series from [start] that is after [after]. Every few months falls on
     *  [anchorDay], or the month's last day when it's shorter (a series that began on the 31st keeps coming back to it). */
    fun nextAfter(start: LocalDate, after: LocalDate, anchorDay: Int = start.dayOfMonth): LocalDate = when (kind) {
        Kind.EVERY_N_DAYS, Kind.EVERY_N_WEEKS -> {
            val step = every.toLong() * if (kind == Kind.EVERY_N_WEEKS) 7 else 1
            start.plusDays((maxOf(0L, ChronoUnit.DAYS.between(start, after)) / step + 1) * step)
        }
        Kind.EVERY_N_MONTHS -> generateSequence(1L) { it + 1 }.map { YearMonth.from(start).plusMonths(it * every) }
            .map { it.atDay(minOf(anchorDay, it.lengthOfMonth())) }.first { it > after }
        Kind.DAYS_OF_WEEK -> generateSequence(after.plusDays(1)) { it.plusDays(1) }.first { it.dayOfWeek in days }
        Kind.MONTHLY_WEEKDAY -> generateSequence(YearMonth.from(after)) { it.plusMonths(1) }.map(::inMonth).first { it > after }
        else -> error("Only for the newer repeat rules")
    }

    /** The chosen weekday in [month]: its first to fourth, or last. */
    private fun inMonth(month: YearMonth): LocalDate {
        val day = days.first()
        return if (week == LAST) month.atEndOfMonth().with(TemporalAdjusters.previousOrSame(day))
        else month.atDay(1).with(TemporalAdjusters.dayOfWeekInMonth(week, day))
    }

    companion object {
        const val LAST = -1
        val NONE = RepeatRule(Kind.NONE)
        val DAILY = RepeatRule(Kind.DAILY)
        val WEEKDAYS = RepeatRule(Kind.WEEKDAYS)
        val WEEKLY = RepeatRule(Kind.WEEKLY)
        val FORTNIGHTLY = RepeatRule(Kind.FORTNIGHTLY)
        val MONTHLY = RepeatRule(Kind.MONTHLY)
        val YEARLY = RepeatRule(Kind.YEARLY)
        /** The rules that need no further choice. */
        val entries = listOf(NONE, DAILY, WEEKDAYS, WEEKLY, FORTNIGHTLY, MONTHLY, YEARLY)
        /** The rules that take a number, weekdays or a week of the month, in the editor's order. */
        val customKinds = listOf(Kind.EVERY_N_DAYS, Kind.EVERY_N_WEEKS, Kind.EVERY_N_MONTHS, Kind.DAYS_OF_WEEK, Kind.MONTHLY_WEEKDAY)

        fun everyDays(n: Int) = RepeatRule(Kind.EVERY_N_DAYS, every = n)
        fun everyWeeks(n: Int) = RepeatRule(Kind.EVERY_N_WEEKS, every = n)
        fun everyMonths(n: Int) = RepeatRule(Kind.EVERY_N_MONTHS, every = n)
        fun onDays(days: Set<DayOfWeek>) = RepeatRule(Kind.DAYS_OF_WEEK, days = days)
        fun monthlyOn(week: Int, day: DayOfWeek) = RepeatRule(Kind.MONTHLY_WEEKDAY, days = setOf(day), week = week)
        /** The week of the month [date] is in, as a monthly weekday rule: its last week counts as last from the 5th on. */
        fun monthlyLike(date: LocalDate) = monthlyOn((date.dayOfMonth - 1) / 7 + 1, date.dayOfWeek).let { if (it.week == 5) it.copy(week = LAST) else it }

        /** A stored rule, or null when it is unknown or incomplete ([complete] false: kept while being edited). */
        fun parse(name: String, complete: Boolean = true): RepeatRule? = runCatching {
            val kind = Kind.valueOf(name.substringBefore(':'))
            val value = name.substringAfter(':', "")
            fun day(code: String) = DayOfWeek.entries.first { it.name.take(3) == code }
            when (kind) {
                Kind.EVERY_N_DAYS, Kind.EVERY_N_WEEKS, Kind.EVERY_N_MONTHS -> RepeatRule(kind, every = value.toInt())
                Kind.DAYS_OF_WEEK -> RepeatRule(kind, days = value.split(',').filter { it.isNotEmpty() }.map(::day).toSet())
                Kind.MONTHLY_WEEKDAY -> value.split(',').let { (w, d) -> RepeatRule(kind, days = setOf(day(d)), week = if (w == "LAST") LAST else w.toInt()) }
                else -> RepeatRule(kind).takeIf { value.isEmpty() }
            }
        }.getOrNull()?.takeIf { it.valid || !complete }

        fun valueOf(name: String): RepeatRule = parse(name) ?: throw IllegalArgumentException("Unknown repeat: $name")

        fun weekName(week: Int) = when (week) { 1 -> "first"; 2 -> "second"; 3 -> "third"; 4 -> "fourth"; LAST -> "last"; else -> "?" }
        fun shortDay(day: DayOfWeek) = day.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
        fun fullDay(day: DayOfWeek) = day.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
    }
}

data class EventSaveOptions(
    val repeat: RepeatRule = RepeatRule.NONE,
    val count: Int = 1,
    val entireSeries: Boolean = false,
    val changeRepeat: Boolean = false,
    val draftToken: String? = null,
    val paymentBaseline: PaymentState? = null,
    /** A new monthly series' day of the month (RepeatRule.dates); 0 = its first date's own. */
    val anchorDay: Int = 0,
    /**
     * Hunt 25 E4: a new event saved as a copy of one deleted elsewhere, keeping its paid state: MyBudget already has a bill
     * paid without an amount by the old event's id, so the copy doesn't send it again as a new expense (its payments,
     * same ids, still go: MyBudget knows those).
     */
    val copiesPaid: Boolean = false,
)

fun millisUntilNextDay(now: java.time.ZonedDateTime): Long =
    java.time.Duration.between(now, now.toLocalDate().plusDays(1).atStartOfDay(now.zone))
        .toMillis().coerceAtLeast(1)
