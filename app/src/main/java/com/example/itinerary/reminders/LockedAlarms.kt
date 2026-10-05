package com.example.itinerary.reminders

import com.example.itinerary.data.ItineraryItem
import com.example.itinerary.data.Reminder

/**
 * RB-3: after a reboot Android clears every alarm, and Planner's database (credential-encrypted storage) can't be read
 * until the phone is first unlocked. So the alarms ReminderScheduler sets are also kept, with what each one shows, in a
 * small snapshot in device-protected storage (LockedAlarmStore). At LOCKED_BOOT_COMPLETED the nearest of them are set again
 * from it (BootReceiver), and while the phone is still locked the receivers show them from it (their intent extras); once
 * it is unlocked BOOT_COMPLETED sets every alarm from the database with the same request codes, replacing these.
 *
 * Only what a notification on the lock screen needs is kept: an event reminder's title, place, day, time, offset label,
 * bill and ring flags and the two tokens its Snooze and Mark paid buttons check (hashes); a task's title; a note's id
 * and time only — on the lock screen a note reminder says just "Note reminder" (R-2), so no word of the note is stored.
 */
sealed interface LockedAlarm {
    val key: String
    val trigger: Long

    data class Event(val reminderId: Long, override val trigger: Long, val title: String, val location: String,
                     val date: String, val time: String, val offsetLabel: String, val bill: Boolean, val ring: Boolean,
                     val snoozeToken: String?, val billToken: String?) : LockedAlarm {
        override val key get() = MissedReminders.eventKey(reminderId)

        companion object {
            /** What [reminderIntent] carries for this reminder, at [trigger]. */
            fun of(item: ItineraryItem, reminder: Reminder, trigger: Long) = Event(reminder.id, trigger, item.title,
                item.location, item.date.toString(), item.startTime?.toString() ?: "", reminder.label, item.category == "Bills",
                reminder.ringUntilDismissed, com.example.itinerary.data.eventReminderToken(item, reminder),
                if (item.category == "Bills" && !item.paid && !item.skipped) com.example.itinerary.data.billReminderToken(item, reminder) else null)
        }
    }

    data class Task(val id: String, override val trigger: Long, val title: String, val ring: Boolean = false) : LockedAlarm {
        override val key get() = MissedReminders.taskKey(id)
    }

    data class Note(val id: String, override val trigger: Long, val ring: Boolean = false) : LockedAlarm {
        override val key get() = MissedReminders.noteKey(id)
    }
}

/** The snapshot: the alarms, and the time format the user chose (the reminder text says the time, as the app does). */
data class LockedSnapshot(val timeFormat: String?, val alarms: List<LockedAlarm>)

/** An alarm that rang before the first unlock: the ledger and delivery records are brought up to date once it's unlocked. */
data class LockedFired(val key: String, val trigger: Long, val at: Long)

object LockedAlarmSelection {
    /** Only the next two weeks are kept: a phone stays locked after a reboot for hours, not weeks. */
    const val HORIZON_MS = 14 * 24 * 3_600_000L
    /** ...and at most this many of them, the nearest. */
    const val MAX = 64
    /** R6-3: ...but always at least this many of the nearest, however far: sparse reminders (a monthly bill) are kept too. */
    const val MIN_KEPT = 5
    /**
     * R6-3: a snapshot that left later alarms out is written again this long after, a day before its two weeks run out,
     * also with no reminder ringing and Planner unopened (ReminderScheduler's refresh alarm, inexact).
     */
    const val REWRITE_AFTER_MS = HORIZON_MS - 24 * 3_600_000L

    /**
     * D6-4: the snapshot is written after a change, and a fresh process knows only the alarms in it; with Planner left
     * unopened it would run out after two weeks. So a reminder ringing rewrites it from every alarm once it is this old.
     */
    const val REFRESH_MS = 12 * 3_600_000L

    /** Whether a snapshot written at [writtenAt] (null: none) is due for that ([REFRESH_MS]; or the clock went back). */
    fun stale(writtenAt: Long?, now: Long): Boolean = writtenAt == null || writtenAt > now || now - writtenAt >= REFRESH_MS

    /** The alarms still ahead of [now], within [HORIZON_MS] or among the [MIN_KEPT] nearest, nearest first, at most [MAX]. */
    fun select(alarms: Collection<LockedAlarm>, now: Long): List<LockedAlarm> =
        alarms.filter { it.trigger > now }.sortedBy { it.trigger }
            .filterIndexed { i, alarm -> i < MIN_KEPT || alarm.trigger <= now + HORIZON_MS }.take(MAX)

    /**
     * When a snapshot written at [now] from [alarms] is to be written again: null when it kept every alarm ahead. AS-5:
     * when [MAX] is what cut it short, no later than the last alarm it kept, which may be well within the two weeks.
     */
    fun rewriteAt(alarms: Collection<LockedAlarm>, now: Long): Long? {
        val kept = select(alarms, now)
        if (kept.size >= alarms.count { it.trigger > now }) return null
        return if (kept.size == MAX) minOf(now + REWRITE_AFTER_MS, kept.last().trigger) else now + REWRITE_AFTER_MS
    }
}

/**
 * Plain text, one alarm a line, fields split by tabs (no JSON: the JVM tests have no org.json). A field's backslashes,
 * tabs and line breaks are escaped; `\0` is null. Lines it doesn't know are skipped, so a damaged line costs one alarm.
 */
object LockedAlarmCodec {
    private const val HEADER = "planner-locked-alarms"
    private const val VERSION = "1"
    private const val NULL = "\\0"

    fun encode(snapshot: LockedSnapshot): String = buildString {
        line(HEADER, VERSION, snapshot.timeFormat)
        snapshot.alarms.forEach { a ->
            when (a) {
                is LockedAlarm.Event -> line("e", a.reminderId.toString(), a.trigger.toString(), a.title, a.location, a.date, a.time,
                    a.offsetLabel, flag(a.bill), flag(a.ring), a.snoozeToken, a.billToken)
                is LockedAlarm.Task -> line("t", a.id, a.trigger.toString(), a.title, flag(a.ring))
                is LockedAlarm.Note -> line("n", a.id, a.trigger.toString(), flag(a.ring))
            }
        }
    }

    /** Null when [text] isn't a snapshot of this version. */
    fun decode(text: String): LockedSnapshot? {
        val lines = text.split('\n').filter { it.isNotEmpty() }
        val header = lines.firstOrNull()?.let(::fields) ?: return null
        if (header.size < 3 || header[0] != HEADER || header[1] != VERSION) return null
        val alarms = lines.drop(1).mapNotNull { line ->
            val f = fields(line)
            runCatching {
                when (f[0]) {
                    "e" -> LockedAlarm.Event(f[1]!!.toLong(), f[2]!!.toLong(), f[3]!!, f[4]!!, f[5]!!, f[6]!!, f[7]!!,
                        f[8] == "1", f[9] == "1", f[10], f[11])
                    "t" -> LockedAlarm.Task(f[1]!!.takeIf { it.isNotEmpty() }!!, f[2]!!.toLong(), f[3]!!, f.getOrNull(4) == "1")
                    "n" -> LockedAlarm.Note(f[1]!!.takeIf { it.isNotEmpty() }!!, f[2]!!.toLong(), f.getOrNull(3) == "1")
                    else -> null
                }
            }.getOrNull()
        }
        return LockedSnapshot(header[2], alarms)
    }

    fun encodeFired(fired: List<LockedFired>): String = buildString { fired.forEach { line(it.key, it.trigger.toString(), it.at.toString()) } }

    fun decodeFired(text: String): List<LockedFired> = text.split('\n').filter { it.isNotEmpty() }.mapNotNull { line ->
        val f = fields(line)
        runCatching { LockedFired(f[0]!!, f[1]!!.toLong(), f[2]!!.toLong()) }.getOrNull()
    }

    private fun flag(value: Boolean) = if (value) "1" else "0"

    private fun StringBuilder.line(vararg values: String?) {
        values.joinTo(this, "\t") { escape(it) }
        append('\n')
    }

    internal fun escape(value: String?): String = value?.replace("\\", "\\\\")?.replace("\t", "\\t")?.replace("\n", "\\n")
        ?.replace("\r", "\\r") ?: NULL

    private fun fields(line: String): List<String?> = line.split('\t').map(::unescape)

    internal fun unescape(field: String): String? {
        if (field == NULL) return null
        if ('\\' !in field) return field
        val out = StringBuilder()
        var i = 0
        while (i < field.length) {
            val c = field[i]
            if (c == '\\' && i + 1 < field.length) {
                out.append(when (field[i + 1]) { 't' -> '\t'; 'n' -> '\n'; 'r' -> '\r'; else -> field[i + 1] })
                i += 2
            } else { out.append(c); i++ }
        }
        return out.toString()
    }
}

/**
 * The alarms set now, as ReminderScheduler sets and clears them, written out (the [LockedAlarmSelection]) when [save] is
 * called after a batch of changes — Repository does so once its alarm work is done — rather than once per alarm.
 * It starts from the last snapshot written: alarms further off than that kept are added as they are set again (every
 * open of the app and every boot sets them all).
 */
class LockedAlarmMirror(private val read: () -> LockedSnapshot?, private val write: (LockedSnapshot) -> Unit,
                        private val timeFormat: () -> String?) {
    private var alarms: MutableMap<String, LockedAlarm>? = null
    private var written: LockedSnapshot? = null
    private var dirty = false

    private fun loaded(): MutableMap<String, LockedAlarm> = alarms ?: run {
        val last = runCatching { read() }.getOrNull()
        written = last
        (last?.alarms.orEmpty().associateByTo(linkedMapOf()) { it.key }).also { alarms = it }
    }

    @Synchronized fun put(alarm: LockedAlarm) {
        val map = loaded()
        if (map[alarm.key] != alarm) { map[alarm.key] = alarm; dirty = true }
    }

    @Synchronized fun remove(key: String) {
        if (loaded().remove(key) != null) dirty = true
    }

    /**
     * Writes the snapshot if an alarm or the time format changed since it was last written, or when [force]d. Null when
     * nothing was written; else when it is to be written again ([LockedAlarmSelection.rewriteAt], Long.MAX_VALUE: no need).
     */
    @Synchronized fun save(now: Long, force: Boolean = false): Long? {
        val map = loaded()
        val format = runCatching { timeFormat() }.getOrNull()
        if (!force && !dirty && written?.timeFormat == format) return null
        val snapshot = LockedSnapshot(format, LockedAlarmSelection.select(map.values, now))
        write(snapshot)
        written = snapshot
        dirty = false
        return LockedAlarmSelection.rewriteAt(map.values, now) ?: Long.MAX_VALUE
    }
}
