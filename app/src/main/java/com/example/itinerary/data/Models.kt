package com.example.itinerary.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.LocalDate
import java.time.LocalTime

@Entity(tableName = "trips")
data class Trip(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val destination: String,
    val startDate: LocalDate,
    val endDate: LocalDate,
    // Position in the former "My plans" list; lower comes first. Kept so existing data and backups stay the same.
    @ColumnInfo(defaultValue = "0") val sortOrder: Int = 0,
    // Which entry of the plan colour palette (see PlanColors) this plan used on the former "My plans" list.
    @ColumnInfo(defaultValue = "0") val colorIndex: Int = 0,
    // A colour the user picked freely (opaque ARGB). When set it is used instead of the palette entry.
    val customColor: Int? = null,
)

// Plans and events each get one of a fixed number of colours. The colours themselves live in the UI (ui/PlanColors.kt).
object PlanColors {
    // Plans can be any of the 8 palette colours.
    const val COUNT = 8

    // The event editor offers only the first 7, to leave room for the custom-colour swatch in a single row. An event
    // that used the eighth (Slate) keeps that exact colour as a custom colour instead.
    const val EVENT_COUNT = 7

    // Slate, the eighth palette colour, as opaque ARGB (must match ui/PlanColors.kt).
    val SLATE_ARGB: Int = 0xFF6B7A86L.toInt()

    // The palette entry (among the first [count]) used by the fewest of [used], so a new plan or event looks different
    // from the ones already there. Ties go to the lowest entry.
    fun next(used: List<Int>, count: Int = COUNT): Int {
        if (count <= 0) return 0
        val usage = IntArray(count)
        used.forEach { colour ->
            val index = Math.floorMod(colour, COUNT)
            if (index < count) usage[index]++
        }
        return usage.indices.minByOrNull { usage[it] } ?: 0
    }
}

@Entity(
    tableName = "items",
    foreignKeys = [
        ForeignKey(
            entity = Trip::class,
            parentColumns = ["id"],
            childColumns = ["tripId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("tripId"), Index("date")],
)
data class ItineraryItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long,
    val date: LocalDate,
    val startTime: LocalTime?, // null = all day
    val title: String,
    val location: String = "",
    val notes: String = "",
    val category: String = Categories.OTHER, // see Categories
    // Which entry of the colour palette (see PlanColors) colours this event's bar and title, so events on
    // the same day can be told apart.
    @ColumnInfo(defaultValue = "0") val colorIndex: Int = 0,
    // A colour the user picked freely (opaque ARGB). When set it is used instead of the palette entry.
    val customColor: Int? = null,
    val linkedTaskId: String? = null, // Stable task ID; deletion leaves a visible unavailable link until restored.
    val seriesId: String? = null,
    @ColumnInfo(defaultValue = "'NONE'") val repeatRule: String = "NONE",
    @ColumnInfo(defaultValue = "0") val bufferBeforeMinutes: Int = 0,
    @ColumnInfo(defaultValue = "0") val bufferAfterMinutes: Int = 0,
    val durationMinutes: Int? = null, // null = no duration; timed events allow 1–1440 minutes
    @ColumnInfo(defaultValue = "'[]'") val checklist: List<ChecklistEntry> = emptyList(),
    @ColumnInfo(defaultValue = "0") val paid: Boolean = false,
    val billAmountMinor: Long? = null,
    @ColumnInfo(defaultValue = "'AUD'") val billCurrency: String = "AUD",
    @ColumnInfo(defaultValue = "0") val skipped: Boolean = false,
    @ColumnInfo(defaultValue = "''") val paymentLink: String = "",
    @ColumnInfo(defaultValue = "''") val paymentReference: String = "",
    @ColumnInfo(defaultValue = "''") val bpayBillerCode: String = "",
    @ColumnInfo(defaultValue = "''") val bpayReference: String = "",
    val draftToken: String? = null, // receipt for a draft save interrupted after commit
    @ColumnInfo(defaultValue = "'[]'") val payments: List<BillPayment> = emptyList(),
    // Last day of an all-day event that spans several days (inclusive); null = one day. See MultiDay.
    val endDate: LocalDate? = null,
)

enum class ReminderUnit(val minutes: Long, val singular: String, val plural: String) {
    MINUTES(1, "minute", "minutes"),
    HOURS(60, "hour", "hours"),
    DAYS(1_440, "day", "days"),
}

// Fires [amount] [unit] before the event starts; an amount of 0 means "at the time".
@Entity(
    tableName = "reminders",
    foreignKeys = [
        ForeignKey(
            entity = ItineraryItem::class,
            parentColumns = ["id"],
            childColumns = ["itemId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("itemId")],
)
data class Reminder(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val itemId: Long,
    val amount: Int,
    val unit: ReminderUnit,
    // Rings like an alarm until the user stops it, instead of a single notification.
    @ColumnInfo(defaultValue = "0") val ringUntilDismissed: Boolean = false,
    val snoozedUntil: Long? = null,
) {
    val offsetMinutes: Long get() = amount * unit.minutes
    // Units are equivalent below one day; at or above it, calendar days and elapsed time are different.
    val scheduleKey: Pair<Long, Boolean> get() = offsetMinutes to (unit == ReminderUnit.DAYS && amount != 0)

    val label: String
        get() = if (amount == 0) "At the time" else "$amount ${if (amount == 1) unit.singular else unit.plural} before"
}

// The last on-time (not snoozed) delivery of each reminder, keyed by the event's local date, time and offset. Those don't
// change when the phone's time zone does, so a reminder that already went off is not scheduled again after a westward
// move. Derived state: not in backups, and it goes with its reminder.
@Entity(
    tableName = "reminder_deliveries",
    foreignKeys = [ForeignKey(entity = Reminder::class, parentColumns = ["id"], childColumns = ["reminderId"], onDelete = ForeignKey.CASCADE)],
)
data class ReminderDelivery(@PrimaryKey val reminderId: Long, val key: String)

object ReminderDeliveries {
    // Time zones span UTC-12 to UTC+14.
    const val MAX_ZONE_SHIFT_MS = 26 * 3_600_000L

    /** Null for a snoozed reminder: a snooze is an absolute time, so a zone change never moves it. */
    fun key(item: ItineraryItem, reminder: Reminder): String? =
        if (reminder.snoozedUntil != null) null else key(item.date, item.startTime, reminder.offsetMinutes) +
            if (reminder.unit != ReminderUnit.DAYS && reminder.offsetMinutes >= 1440) "|elapsed" else ""
    fun key(date: LocalDate, time: LocalTime?, offsetMinutes: Long): String = "$date|${time ?: ""}|$offsetMinutes"

    fun delivered(recorded: String?, item: ItineraryItem, reminder: Reminder): Boolean =
        recorded != null && recorded == key(item, reminder)

    /**
     * Whether an alarm set for [trigger] may still show. Besides an exact match, an on-time alarm set before the phone
     * moved east is accepted: its recomputed time [expected] is earlier and already past, and scheduling leaves the old
     * alarm in place rather than drop the reminder. (A move west re-times the still-future alarm instead.)
     */
    fun accepts(trigger: Long, expected: Long, snoozed: Boolean, now: Long): Boolean =
        trigger == expected || !snoozed && (trigger == 0L || trigger in (expected + 1)..minOf(now, expected + MAX_ZONE_SHIFT_MS))
}

// The file itself lives in app-private storage under [fileName]; [name] is what the user sees. A link has no file:
// its [url] is set (see Links), [fileName] is empty and [mimeType] is Links.MIME_TYPE.
@Entity(
    tableName = "attachments",
    foreignKeys = [
        ForeignKey(
            entity = ItineraryItem::class,
            parentColumns = ["id"],
            childColumns = ["itemId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("itemId")],
)
data class Attachment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val itemId: Long,
    val name: String,
    val fileName: String,
    val mimeType: String,
    val url: String? = null,
    @ColumnInfo(defaultValue = "''") val recognizedText: String = "",
    @ColumnInfo(defaultValue = "'NOT_INDEXED'") val textStatus: String = "NOT_INDEXED",
)
