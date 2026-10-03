package com.example.itinerary.data

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

// Task sync: task files (VTODO) in the Nextcloud list Planner keeps its tasks in sync with, as ServerEvents is for events.
// Synced both ways: title, notes, due date, priority and done. Planner-only: reminders, checklist, attachments,
// prerequisites and the repeat rule (each occurrence of a repeating Planner task is its own task on Nextcloud).
object ServerTasks {
    // What a task file says, as far as Planner holds it.
    data class Fields(val title: String, val notes: String, val dueDate: LocalDate?, val priority: TaskPriority, val done: Boolean)

    // [fields] null when Planner can't hold the task exactly: a repeating one ([repeating]), several tasks in one file, a
    // cancelled or unreadable one, one due outside the years a calendar file can hold, or one with a longer title or notes
    // than Planner takes. Those are left alone on Nextcloud and not shown.
    class Parsed(val uid: String?, val fields: Fields?, val repeating: Boolean = false)

    fun parse(text: String, zone: ZoneId): Parsed = read(text, zone, limited = true)

    private val REPEAT = setOf("RRULE", "RDATE", "EXDATE", "RECURRENCE-ID")

    private fun read(text: String, zone: ZoneId, limited: Boolean): Parsed = runCatching {
        val todos = Ics.events(Ics.lines(text), 50, "Too many tasks.", component = "VTODO")
        val uid = todos.firstNotNullOfOrNull { props -> props.firstOrNull { it.name == "UID" }?.value?.trim() }
        if (todos.size != 1) return Parsed(uid, null, repeating = todos.any { props -> props.any { it.name in REPEAT } })
        val props = todos.single()
        fun one(name: String) = props.firstOrNull { it.name == name }
        if (props.any { it.name in REPEAT }) return Parsed(uid, null, repeating = true)
        val status = one("STATUS")?.value?.trim()?.uppercase()
        if (status == "CANCELLED") return Parsed(uid, null)
        val due = one("DUE")?.let { dueDate(it, zone) }
        if (due != null && due.year !in 1..9998) return Parsed(uid, null)
        fun text(name: String) = one(name)?.value?.let(Ics::unescape)?.trim().orEmpty()
        val title = text("SUMMARY"); val notes = text("DESCRIPTION")
        if (limited && (title.length > MAX_TITLE || notes.length > MAX_NOTES)) return Parsed(uid, null)
        val priority = when (one("PRIORITY")?.value?.trim()?.toIntOrNull() ?: 0) { in 1..4 -> TaskPriority.HIGH; in 6..9 -> TaskPriority.LOW; else -> TaskPriority.NORMAL }
        // STATUS decides when there is one; otherwise a completion time or 100% means done.
        val done = if (status != null) status == "COMPLETED" else one("COMPLETED") != null || one("PERCENT-COMPLETE")?.value?.trim() == "100"
        Parsed(uid, Fields(title.ifEmpty { "(No title)" }, notes, due, priority, done))
    }.getOrElse { Parsed(null, null) }

    // Planner holds a due date, not a moment. A due date-time in a zone (TZID) or floating counts on its own calendar day
    // there, whatever zone the phone is in, so travelling never moves it and moving it writes exactly the day chosen
    // (movedDue keeps its clock time). Only a UTC one ("…Z", no day of its own) counts on the phone's day.
    private fun dueDate(p: Ics.Property, zone: ZoneId): LocalDate = when {
        Ics.isDate(p) -> Ics.date(p)
        p.value.endsWith("Z") -> Ics.time(p, zone, strictGap = false, Ics::zone).withZoneSameInstant(zone).toLocalDate()
        else -> Ics.time(p, zone, strictGap = false, Ics::zone).toLocalDate()
    }

    fun fields(task: PlannerTask) = Fields(task.title, task.notes, task.dueDate, task.priority, task.done)

    // The fields sync carries from Nextcloud into a Planner task; everything Planner-only stays as it is, except that a
    // moved due date starts a repeating task's month day afresh (as the editor and "Due tomorrow" do).
    fun apply(task: PlannerTask, server: Fields): PlannerTask =
        task.copy(title = server.title, notes = server.notes, dueDate = server.dueDate, priority = server.priority, done = server.done,
            repeatAnchorDay = if (server.dueDate != task.dueDate) 0 else task.repeatAnchorDay)

    // What Planner syncs for [task], as a short fingerprint: a change in any synced field changes it.
    fun fingerprint(task: PlannerTask): String = fingerprint(fields(task))
    fun fingerprint(fields: Fields): String {
        val text = listOf(fields.title.trim(), fields.notes.trim(), fields.dueDate?.toString().orEmpty(), fields.priority.name, fields.done.toString())
            .joinToString("\u0000")
        return PRINT + CalendarExport.shortHash(text)
    }
    private const val PRINT = "t1:"

    // Whether [stored] (a row's fingerprint) still describes [task]: unchanged in Planner since the last sync.
    fun inSync(stored: String, task: PlannerTask) = stored == fingerprint(task)

    // A new task file for [task].
    fun encode(task: PlannerTask, uid: String, now: Instant): String {
        require(uid.matches(CalendarExport.UID))
        val stamp = CalendarExport.stamp(now)
        val lines = listOf("BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Planner//Tasks//EN", "BEGIN:VTODO", "UID:$uid", "DTSTAMP:$stamp",
            "CREATED:$stamp", "LAST-MODIFIED:$stamp") + MANAGED.flatMap { managed(it, task, now, null, ZoneOffset.UTC) } + listOf("END:VTODO", "END:VCALENDAR")
        return lines.joinToString("\r\n", postfix = "\r\n") { CalendarExport.fold(it) }
    }

    // The properties Planner manages in a task; sync replaces only these in a file that has more.
    private val MANAGED = listOf("SUMMARY", "DESCRIPTION", "DUE", "PRIORITY", "STATUS", "COMPLETED", "PERCENT-COMPLETE")
    private val day = DateTimeFormatter.BASIC_ISO_DATE
    private val local = DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss")

    // The lines for property [name] of [task] (unfolded). [oldDue]: the file's DUE line, so a due time is kept on the new
    // date (in its own zone, or UTC, or floating, as it was).
    private fun managed(name: String, task: PlannerTask, now: Instant, oldDue: String?, zone: ZoneId): List<String> = when (name) {
        "SUMMARY" -> listOf("SUMMARY:${CalendarExport.escape(task.title)}")
        "DESCRIPTION" -> listOfNotNull(task.notes.takeIf { it.isNotEmpty() }?.let { "DESCRIPTION:${CalendarExport.escape(it)}" })
        "DUE" -> listOfNotNull(task.dueDate?.let { date -> oldDue?.let { movedDue(it, date, zone) } ?: "DUE;VALUE=DATE:${date.format(day)}" })
        "PRIORITY" -> when (task.priority) { TaskPriority.HIGH -> listOf("PRIORITY:1"); TaskPriority.LOW -> listOf("PRIORITY:9"); TaskPriority.NORMAL -> emptyList() }
        "STATUS" -> listOf("STATUS:${if (task.done) "COMPLETED" else "NEEDS-ACTION"}")
        "COMPLETED" -> if (task.done) listOf("COMPLETED:${CalendarExport.stamp(now)}") else emptyList()
        "PERCENT-COMPLETE" -> if (task.done) listOf("PERCENT-COMPLETE:100") else emptyList()
        else -> emptyList()
    }

    // [line] (a DUE with a time) moved to [date] at the same clock time: in its own zone (or floating), where [date] is its
    // own day (see dueDate); a UTC one at the same clock time on the phone. Null when it holds only a date, or can't be read.
    private fun movedDue(line: String, date: LocalDate, zone: ZoneId): String? = runCatching {
        val p = Ics.property(line)
        if (Ics.isDate(p)) return null
        val head = line.substring(0, line.length - p.value.length - 1)
        val value = if (p.value.endsWith("Z")) {
            val clock = LocalDateTime.parse(p.value.removeSuffix("Z"), local).atOffset(ZoneOffset.UTC).atZoneSameInstant(zone).toLocalTime()
            date.atTime(clock).atZone(zone).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime().format(local) + "Z"
        } else date.format(day) + p.value.substring(8)
        "$head:$value"
    }.getOrNull()

    // [original] with only the managed properties [task] changed replaced by [task]'s, plus a fresh DTSTAMP/LAST-MODIFIED
    // and a higher SEQUENCE. Everything else (start date, categories, alarms, subtask links, unknown properties) is kept
    // exactly as it was, lines and folding included. A start that doesn't go with a new due date is dropped (a task can't
    // start after it's due, and both must be dates or both date-times: Nextcloud refuses the file otherwise).
    // [syncedPrint]: the fingerprint of the task when [original] was last synced, if it was; a due date unchanged in Planner
    // since then keeps its DUE exactly as written, even where it reads as another day here (a UTC one after travelling).
    fun patch(original: String, task: PlannerTask, zone: ZoneId, now: Instant, syncedPrint: String? = null): String {
        val before = read(original, zone, limited = false).fields
        fun same(theirs: String, mine: String, max: Int) = theirs == mine || theirs.length > max && theirs.take(max) == mine
        val kept = if (before == null) emptySet() else buildSet {
            if (same(before.title, task.title, MAX_TITLE)) add("SUMMARY")
            if (same(before.notes, task.notes, MAX_NOTES)) add("DESCRIPTION")
            if (before.dueDate == task.dueDate || syncedPrint != null && dueUnchanged(before, syncedPrint, task)) add("DUE")
            if (before.priority == task.priority) add("PRIORITY")
            if (before.done == task.done) addAll(listOf("STATUS", "COMPLETED", "PERCENT-COMPLETE"))
        }
        val replaced = MANAGED.toSet() - kept
        val physical = original.removePrefix("\uFEFF").replace("\r\n", "\n").replace("\r", "\n").split("\n")
        val logical = mutableListOf<MutableList<String>>()
        for (line in physical) {
            if ((line.startsWith(" ") || line.startsWith("\t")) && logical.isNotEmpty()) logical.last() += line
            else if (line.isNotEmpty()) logical += mutableListOf(line)
        }
        fun name(block: List<String>) = block.first().substringBefore(':').substringBefore(';').uppercase()
        fun unfolded(block: List<String>) = block.first() + block.drop(1).joinToString("") { it.drop(1) }
        // The task's own top-level lines (not those of an alarm inside it).
        val stack = ArrayDeque<String>()
        var oldDue: String? = null
        var start: String? = null
        for (block in logical) {
            val n = name(block)
            when {
                n == "BEGIN" -> stack.addLast(block.first().substringAfter(':').trim().uppercase())
                n == "END" -> stack.removeLastOrNull()
                stack.lastOrNull() == "VTODO" && n == "DUE" && oldDue == null -> oldDue = unfolded(block)
                stack.lastOrNull() == "VTODO" && n == "DTSTART" && start == null -> start = unfolded(block)
            }
        }
        // A new due date and a start that wouldn't go with it (after it, or a date where the other has a time; a server may
        // refuse the file): the start is dropped.
        val newDue = if ("DUE" in replaced) managed("DUE", task, now, oldDue, zone).singleOrNull() else null
        val dropStart = newDue != null && start?.let { !startFits(it, newDue, zone) } == true
        val out = mutableListOf<String>()
        stack.clear()
        var done = false
        var sequence = 0
        for (block in logical) {
            val text = block.joinToString("\r\n")
            val n = name(block)
            val inTask = !done && stack.lastOrNull() == "VTODO"
            when {
                n == "BEGIN" -> { stack.addLast(block.first().substringAfter(':').trim().uppercase()); out += text }
                n == "END" -> {
                    if (block.first().substringAfter(':').trim().uppercase() == "VTODO" && !done) {
                        (MANAGED.filter { it in replaced }.flatMap { managed(it, task, now, oldDue, zone) } + listOf("DTSTAMP:${CalendarExport.stamp(now)}",
                            "LAST-MODIFIED:${CalendarExport.stamp(now)}", "SEQUENCE:${sequence + 1}")).forEach { out += CalendarExport.fold(it) }
                        done = true
                    }
                    stack.removeLastOrNull(); out += text
                }
                inTask && (n in replaced || n == "DTSTAMP" || n == "LAST-MODIFIED" || n == "DTSTART" && dropStart) -> Unit
                inTask && n == "SEQUENCE" -> sequence = block.first().substringAfter(':').trim().toIntOrNull() ?: 0
                else -> out += text
            }
        }
        require(done) { "No task in this file" }
        return out.joinToString("\r\n", postfix = "\r\n")
    }

    // Whether [task]'s due date is the one it had when [synced] (a task file's text) was last synced with fingerprint
    // [syncedPrint], however the file's due reads in [zone] now. The other synced fields read the same anywhere, so the
    // fingerprint then was the file's fields with that day's due date: tried with Planner's due date now.
    fun dueUnchanged(synced: String, syncedPrint: String, task: PlannerTask, zone: ZoneId): Boolean =
        read(synced, zone, limited = false).fields?.let { dueUnchanged(it, syncedPrint, task) } == true

    private fun dueUnchanged(synced: Fields, syncedPrint: String, task: PlannerTask) = fingerprint(synced.copy(dueDate = task.dueDate)) == syncedPrint

    // The task's own DUE line in [text] (unfolded, not one of an alarm inside it); null when it has none.
    fun dueLine(text: String): String? = runCatching {
        val stack = ArrayDeque<String>()
        var block: StringBuilder? = null
        var found: String? = null
        fun finish() {
            val line = block?.toString() ?: return
            block = null
            val name = line.substringBefore(':').substringBefore(';').uppercase()
            when {
                name == "BEGIN" -> stack.addLast(line.substringAfter(':').trim().uppercase())
                name == "END" -> stack.removeLastOrNull()
                name == "DUE" && stack.lastOrNull() == "VTODO" && found == null -> found = line
            }
        }
        for (line in text.removePrefix("\uFEFF").replace("\r\n", "\n").replace("\r", "\n").split("\n")) {
            if ((line.startsWith(" ") || line.startsWith("\t")) && block != null) block!!.append(line.drop(1))
            else { finish(); if (line.isNotEmpty()) block = StringBuilder(line) }
        }
        finish()
        found
    }.getOrNull()

    // Whether start line [start] may stay with due line [due] (RFC 5545: the same kind of value, and not after it; compared
    // as moments when they have a time). Unreadable counts as not.
    private fun startFits(start: String, due: String, zone: ZoneId): Boolean = runCatching {
        val s = Ics.property(start); val d = Ics.property(due)
        when {
            Ics.isDate(s) != Ics.isDate(d) -> false
            Ics.isDate(s) -> Ics.date(s) <= Ics.date(d)
            else -> !Ics.time(s, zone, strictGap = false, Ics::zone).toInstant().isAfter(Ics.time(d, zone, strictGap = false, Ics::zone).toInstant())
        }
    }.getOrDefault(false)

    // The most text Planner takes in a task (see Tasks.validate).
    const val MAX_TITLE = 500
    const val MAX_NOTES = 20_000
}
