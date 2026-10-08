package com.example.itinerary.data

import androidx.room.withTransaction
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import com.example.itinerary.reminders.ReminderAlarms
import com.example.itinerary.reminders.MissedReminders
import com.example.itinerary.reminders.AlarmWindow
import com.example.itinerary.reminders.eventReminderAt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn

data class PendingDeletion(
    val token: String = UUID.randomUUID().toString(),
    val items: List<ItineraryItem>,
    val attachments: List<Attachment>,
    val reminders: List<Reminder>,
    val tasks: List<PlannerTask> = emptyList(),
    val notes: List<PlannerNote> = emptyList(),
    // A task made into an event, or events into a task: what they became, which Undo takes away again.
    val madeInto: MadeInto? = null,
)

/** What a converted task or event became ([eventIds] or [taskId]), and the tasks that stopped waiting on the task. */
data class MadeInto(val eventIds: List<Long> = emptyList(), val taskId: String? = null, val freed: List<String> = emptyList(),
                    val fromTaskId: String? = null)

data class PendingMove(
    val token: String = UUID.randomUUID().toString(),
    val itemId: Long,
    val title: String,
    val fromDate: java.time.LocalDate,
    val toDate: java.time.LocalDate,
    // A task's move (itemId 0): the task before and after, so Undo puts it back while it is still as moved.
    val task: Pair<PlannerTask, PlannerTask>? = null,
)

class Repository(
    private val db: AppDatabase,
    private val store: AttachmentStore,
    private val scheduler: ReminderAlarms,
    // [local]: made in Planner, not taken in from Nextcloud by sync (see SyncWrite).
    private val onChanged: (local: Boolean) -> Unit = {},
    // A deletion's Undo is no longer on offer (calendar sync may now delete Planner's copy on Nextcloud).
    private val onDeletionFinished: () -> Unit = {},
    // Bill payments for MyBudget (BudgetLink), kept until sent (BudgetOutbox), wherever they were made.
    private val onBudgetMessages: (List<BudgetLink.Message>) -> Unit = {},
    // DA-7: where allItems is shared among its collectors (the app's scope); null (tests) leaves it a plain Room query.
    shareScope: kotlinx.coroutines.CoroutineScope? = null,
) {
    // Stay below SQLite's bind-parameter limit, including on older Android versions.
    private suspend fun <T> readIds(ids: Collection<Long>, query: suspend (List<Long>) -> List<T>): List<T> {
        val result = mutableListOf<T>()
        for (chunk in ids.distinct().chunked(400)) result.addAll(query(chunk))
        return result
    }

    suspend fun widgetEvents(day: java.time.LocalDate): List<ItineraryItem> =
        widgetEventsOnDay(itemDao.dayCandidates(day, day.minusDays(1)), day)

    suspend fun widgetTasks(day: java.time.LocalDate): List<PlannerTask> =
        taskDao.all().filter { !it.done && it.dueDate != null && it.dueDate <= day }
            .sortedWith(compareBy<PlannerTask> { it.dueDate }.then(Tasks.order))

    // Those of [tasks] still waiting on a prerequisite: the widget shows them without an active Complete, as the app does.
    suspend fun widgetBlockedTasks(tasks: List<PlannerTask>): Set<String> =
        if (tasks.none { it.prerequisiteIds.isNotEmpty() }) emptySet() else TaskDependencies.blockedIds(tasks, taskDao.all())

    private val changes = Mutex()

    // Events from other calendars are shown with negative ids and belong to their calendar, not to Planner.
    private fun requirePlannerEvent(id: Long) = require(!OutsideCalendars.isOutside(id)) { OutsideCalendars.READ_ONLY }
    private fun requirePlannerEvents(ids: Collection<Long>) = ids.forEach(::requirePlannerEvent)
    private val _pendingPayments = MutableStateFlow<List<PendingPayment>>(emptyList())
    val pendingPayments = _pendingPayments.asStateFlow()
    // Snackbar dismissal must not invalidate a still-visible notification Undo action.
    private val paymentUndo = PaymentUndos()
    private val undoTimers = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
    private fun recordPayment(change: PendingPayment) {
        val now = System.currentTimeMillis()
        paymentUndo.record(change, now)
        _pendingPayments.value = paymentUndo.live(_pendingPayments.value.filterNot { it.before.id == change.before.id } + change, now)
        // Its bar goes when its Undo does, also when no screen was open to show it (paid from a notification).
        undoTimers.launch {
            kotlinx.coroutines.delay(PaymentUndos.UNDO_MS + 1_000)
            changes.withLock { _pendingPayments.value = paymentUndo.live(_pendingPayments.value, System.currentTimeMillis()) }
        }
    }

    // Bill payments made or undone here, for MyBudget (BudgetLink): kept in BudgetOutbox (onBudgetMessages) and
    // signalled here, so a started MainActivity sends them now and others wait until it next starts.
    private val _budgetMessages = kotlinx.coroutines.flow.MutableSharedFlow<List<BudgetLink.Message>>(extraBufferCapacity = 16)
    val budgetMessages = _budgetMessages.asSharedFlow()
    private fun tellBudget(before: ItineraryItem, after: ItineraryItem) {
        BudgetLink.changes(before, after).takeIf { it.isNotEmpty() }?.let { onBudgetMessages(it); _budgetMessages.tryEmit(it) }
    }
    // Upcoming bills for MyBudget (BudgetLink.upcoming); MainActivity sends them when Planner opens and closes.
    suspend fun upcomingBills(today: java.time.LocalDate = java.time.LocalDate.now()): List<BudgetLink.Upcoming> =
        BudgetLink.upcoming(itemDao.all(), today)

    private val _pendingMoves = MutableStateFlow<List<PendingMove>>(emptyList())
    val pendingMoves = _pendingMoves.asStateFlow()
    private val _pendingDeletions = MutableStateFlow<List<PendingDeletion>>(emptyList())
    val pendingDeletions = _pendingDeletions.asStateFlow()

    // Once a transaction commits, ancillary failures must never masquerade as a failed save.
    // Retry closures read current records, so a later edit/delete cannot resurrect stale alarms.
    private val followUp = linkedMapOf<String, suspend () -> Unit>()
    private val cleanupFiles = linkedSetOf<String>()
    private val _maintenanceIssues = MutableStateFlow<Set<String>>(emptySet())
    val maintenanceIssues = _maintenanceIssues.asStateFlow()

    private suspend fun performFollowUp(work: Map<String, suspend () -> Unit>) = withContext(NonCancellable) {
        followUp.putAll(work)
        for ((key, action) in work) {
            try { action(); followUp.remove(key) }
            catch (e: Exception) { Log.w("Repository", "Saved data; follow-up failed: $key", e) }
        }
        // RB-3: the alarms as they now are, for a reboot that stays locked.
        try { scheduler.saveLockedAlarms() } catch (e: Exception) { Log.w("Repository", "Couldn't save the locked-boot alarms", e) }
        _maintenanceIssues.value = followUp.keys.mapTo(linkedSetOf()) { it.substringBefore(':') }
    }

    // D14-1: a changed reminder time is a new reminder (the old alarm and notification go); a changed ring choice alone
    // keeps the reminder as it is: it is set again with the choice, and turned off it only stops the ringing. Hunt 22 P2:
    // "turned off" is a sound that rang changed to one that doesn't (10 s, 30 s, 1 min and Default count, not only "Until
    // I stop it"); a change from one ring to another lets the ringing go on.
    private class Ringing(val ringUntilDismissed: Boolean, val ringSeconds: Int)
    private fun ringTurnedOff(before: Ringing?, after: Ringing?) = before != null && after != null &&
        scheduler.rings(before.ringUntilDismissed, before.ringSeconds) && !scheduler.rings(after.ringUntilDismissed, after.ringSeconds)

    private suspend fun afterCommit(files: Collection<String> = emptyList(), reminderIds: Collection<Long> = emptyList(),
                                    cancelFirst: Set<Long> = emptySet(), notify: Boolean = true, taskIds: Collection<String> = emptyList(),
                                    resetTaskIds: Set<String> = emptySet(), noteIds: Collection<String> = emptyList(),
                                    resetNoteIds: Set<String> = emptySet(), quietTaskIds: Set<String> = emptySet(),
                                    quietNoteIds: Set<String> = emptySet()) {
        cleanupFiles.addAll(files.filter { it.isNotBlank() })
        val work = linkedMapOf<String, suspend () -> Unit>()
        if (cleanupFiles.isNotEmpty()) work["cleanup"] = {
            deleteUnusedFiles(cleanupFiles.toList())
            cleanupFiles.clear()
        }
        reminderIds.distinct().forEach { id ->
            work["reminders:$id"] = {
                val reminder = reminderDao.byId(id)
                val event = reminder?.let { itemDao.byId(it.itemId) }
                updateReminderAlarm(scheduler, id, event, reminder, id in cancelFirst, ::deliveredAlready)
            }
        }
        taskIds.distinct().forEach { id ->
            work["reminders:task:$id"] = {
                val task = taskDao.byId(id)
                if (task == null || id in resetTaskIds) scheduler.cancelTask(id) else if (id in quietTaskIds) scheduler.ringOffTask(id)
                if (task != null) scheduler.scheduleTask(task)
            }
        }
        noteIds.distinct().forEach { id ->
            work["reminders:note:$id"] = {
                val note = noteDao.byId(id)
                if (note == null || id in resetNoteIds) scheduler.cancelNote(id) else if (id in quietNoteIds) scheduler.ringOffNote(id)
                if (note != null) scheduler.scheduleNote(note)
            }
        }
        // A long series can leave too many alarms armed, a deletion too few while later ones wait (AlarmWindow).
        if (reminderIds.isNotEmpty() || taskIds.isNotEmpty() || noteIds.isNotEmpty()) work["reminders:window"] = {
            if (scheduler.needsArmRefill()) armReminders()
        }
        if (notify) { val local = !SyncWrite.active(); work["widget"] = { onChanged(local) } }
        performFollowUp(work)
    }

    // Went off on time already, for this date, time and offset: after a westward time-zone change its time is ahead
    // again, and scheduling it would show it twice.
    private suspend fun deliveredAlready(event: ItineraryItem, reminder: Reminder): Boolean =
        ReminderDeliveries.delivered(reminderDao.deliveries(listOf(reminder.id)).firstOrNull()?.key, event, reminder)

    suspend fun retryMaintenance() = changes.withLock { performFollowUp(followUp.toMap()) }

    suspend fun duplicateBills(item: ItineraryItem, options: EventSaveOptions, originalDate: java.time.LocalDate): List<ItineraryItem> {
        if (item.category != "Bills" || item.billAmountMinor == null) return emptyList()
        return db.withTransaction {
            val members = if (item.id != 0L && options.entireSeries && item.seriesId != null)
                itemDao.forSeries(item.seriesId) else emptyList()
            val dates = if (members.isNotEmpty()) {
                val shift = ChronoUnit.DAYS.between(originalDate, item.date)
                if (options.changeRepeat && options.repeat != RepeatRule.NONE)
                    options.repeat.dates(members.first().date.plusDays(shift), members.size)
                else members.map { it.date.plusDays(shift) }
            } else options.repeat.dates(item.date, options.count, options.anchorDay)
            val excluded = if (item.id == 0L) emptySet() else members.mapTo(hashSetOf(item.id)) { it.id }
            Bills.duplicates(item, itemDao.billCandidates(item.billAmountMinor, item.billCurrency, dates), dates.toSet(), excluded)
        }
    }

    // Copies and recurring occurrences can share a file. Pending Undo entries also own their files.
    private suspend fun deleteUnusedFiles(candidates: Collection<String>) {
        val keep = attachmentDao.allFileNames().toHashSet()
        taskDao.all().flatMap { it.attachments }.forEach { keep.add(it.fileName) }
        noteDao.all().flatMap { it.attachments }.forEach { keep.add(it.fileName) }
        keep.addAll(store.taskDraftFiles())
        keep.addAll(store.noteDraftFiles())
        keep.addAll(store.eventDraftFiles())
        val bundles = readableContents(deletedDao.all())
        // H17-D3: a bundle that can't be read may hold any of these files: delete none this round rather than guess.
        if (bundles.any { it == null }) { Log.w("Repository", "Kept unused files: a Recently deleted bundle couldn't be read"); return }
        bundles.forEach { data -> data!!.storedAttachments.forEach { keep.add(it.fileName) } }
        _pendingDeletions.value.flatMap { it.attachments }.forEach { keep.add(it.fileName) }
        val unused = candidates.filter { it.isNotBlank() && it !in keep }.distinct()
        // R18-D1: one a backup is still reading waits for it (see withPinnedFiles).
        val (held, free) = synchronized(pinned) { unused.partition { it in pinned }.also { (held, _) -> deferredFiles.addAll(held) } }
        if (held.isNotEmpty()) Log.i("Repository", "Kept ${held.size} unused files until a backup has read them")
        free.forEach(store::delete)
    }

    private val tripDao = db.tripDao()
    private val itemDao = db.itemDao()
    private val attachmentDao = db.attachmentDao()
    private val reminderDao = db.reminderDao()
    private val taskDao = db.taskDao()
    val tasks = taskDao.observe()
    suspend fun saveTask(task: PlannerTask, create: Boolean = true) = changes.withLock {
        // U14-2: ringing is a choice of a reminder: without one it is off, so a later reminder doesn't start ringing unseen.
        val clean = Tasks.capText(task.copy(title = task.title.trim(), notes = task.notes.trim(), ringUntilDismissed = task.ringUntilDismissed && task.reminderAt != null,
            ringSeconds = ReminderSound.cleanSeconds(task.ringUntilDismissed, task.ringSeconds, task.reminderAt != null)))
        Tasks.validate(clean)
        val allTasks = taskDao.all()
        val oldIds = allTasks.find { it.id == clean.id }?.prerequisiteIds.orEmpty()
        require(clean.prerequisiteIds.all { id -> id in oldIds || allTasks.any { it.id == id } }) { "A prerequisite was deleted. Remove it or restore the task." }
        TaskDependencies.validateGraph(allTasks.filterNot { it.id == clean.id } + clean)
        withContext(NonCancellable) {
            val existing = taskDao.byId(task.id)
            if (create) {
                // Retrying a save after activity/process recreation must not create another task.
                // A new task (or a duplicate of a snoozed one) starts unsnoozed.
                if (existing == null) taskDao.insert(clean.copy(snoozedUntil = null))
                else check(existing == clean.copy(snoozedUntil = null)) { "This task was already saved and changed" }
            } else {
                check(existing != null) { "This task no longer exists" }
                // A snooze (possibly made while the editor was open) lasts until the reminder time itself is changed.
                taskDao.update(clean.copy(done = existing.done, nextTaskId = existing.nextTaskId,
                    snoozedUntil = if (existing.reminderAt == clean.reminderAt) existing.snoozedUntil else null))
            }
            val saved = taskDao.byId(task.id)
            afterCommit(files = existing?.attachments.orEmpty().map { it.fileName }, taskIds = listOf(task.id),
                resetTaskIds = if (existing?.activeReminderAt != saved?.activeReminderAt) setOf(task.id) else emptySet(),
                quietTaskIds = if (ringTurnedOff(existing?.let { Ringing(it.ringUntilDismissed, it.ringSeconds) }, saved?.let { Ringing(it.ringUntilDismissed, it.ringSeconds) })) setOf(task.id) else emptySet())
        }
    }
    // A task's ⋮ "Due tomorrow", with the same Undo bar as an event's move (see undoMove).
    suspend fun moveTaskToTomorrow(id: String, today: java.time.LocalDate = java.time.LocalDate.now()) = changes.withLock {
        withContext(NonCancellable) {
            val before = taskDao.byId(id) ?: error("This task no longer exists")
            val after = before.dueTomorrow(today)
            taskDao.update(after)
            _pendingMoves.value += PendingMove(itemId = 0, title = before.title, fromDate = before.dueDate ?: after.dueDate!!,
                toDate = after.dueDate!!, task = before to after)
            afterCommit(taskIds = listOf(id), resetTaskIds = if (before.activeReminderAt != after.activeReminderAt) setOf(id) else emptySet())
        }
    }
    suspend fun setTaskDone(id: String, done: Boolean) = changes.withLock {
        check(taskDao.byId(id) != null) { "This task no longer exists" }
        setTaskDoneLocked(id, done)
    }
    private suspend fun setTaskDoneLocked(id: String, done: Boolean) = withContext(NonCancellable) {
        val changed = db.withTransaction {
            val task = taskDao.byId(id) ?: return@withTransaction emptyList<String>()
            if (task.done == done) return@withTransaction emptyList<String>()
            if (done) require(TaskDependencies.blockers(task, taskDao.all()).isEmpty()) { "Complete the prerequisites first, or edit this task to remove them." }
            // Keep the successor receipt even after reopening/deleting the next occurrence.
            val next = if (done && task.nextTaskId == null) task.nextOccurrence() else null
            if (next != null) taskDao.insert(next)
            taskDao.update(task.copy(done = done, nextTaskId = next?.id ?: task.nextTaskId, snoozedUntil = if (done) null else task.snoozedUntil))
            listOfNotNull(id, next?.id)
        }
        afterCommit(taskIds = changed)
    }
    suspend fun actOnTaskReminder(id: String, trigger: Long, snoozeUntil: Long? = null): Boolean = changes.withLock {
        val task = taskDao.byId(id) ?: return@withLock false
        if (task.done || task.activeReminderAt != trigger || trigger > System.currentTimeMillis()) return@withLock false
        if (snoozeUntil == null) setTaskDoneLocked(id, true)
        else {
            require(snoozeUntil > System.currentTimeMillis())
            withContext(NonCancellable) {
                // Keep reminderAt: a repeating task's next reminder follows it, not the snooze.
                taskDao.update(task.copy(snoozedUntil = snoozeUntil))
                afterCommit(taskIds = listOf(id), resetTaskIds = setOf(id))
            }
        }
        true
    }
    suspend fun releaseTaskFiles(files: Collection<String>) = changes.withLock {
        afterCommit(files = files, notify = false)
    }
    private val noteDao = db.noteDao()
    val notes = noteDao.observe()
    suspend fun note(id: String): PlannerNote? = noteDao.byId(id)
    // One note as stored, as it changes (null once gone): an open editor notices a change made elsewhere.
    fun observeNote(id: String): Flow<PlannerNote?> = noteDao.observe(id).distinctUntilChanged()

    // A new note ([create]) or the one being edited, stamped with the time it changed (unless nothing did). Returns it as saved.
    // [expected]: the version the editor started from (null: don't check). A note changed since (by sync, or a
    // reminder's Done) isn't overwritten: NoteChangedException carries the newer version for the editor to merge.
    // [after]: the note this new one is a copy of (the editor's Duplicate): placed next to it as card Duplicate places it,
    // where the original is at Save, not where it was at Duplicate (NW-3).
    suspend fun saveNote(note: PlannerNote, create: Boolean, expected: PlannerNote? = null, after: String? = null): PlannerNote = changes.withLock {
        val old = noteDao.byId(note.id)
        if (!create && expected != null && old != null && old != expected) throw NoteChangedException(old)
        // A changed reminder time ends its snooze, as a task's does.
        val withSnooze = note.copy(snoozedUntil = if (note.reminderAt != null && note.reminderAt == old?.reminderAt) old.snoozedUntil else null)
        // A new note goes to the top of the page; an edited one keeps the place it was dragged to.
        // A new note that comes with a place (the editor's Duplicate: next to its original) keeps it, the notes from there
        // moving down one when it is stored (D6-7).
        val original = if (create && old == null) after?.let { noteDao.byId(it) } else null
        val keepsPlace = original == null && create && old == null && withSnooze.position != 0L && withSnooze.position != Notes.topPosition(noteDao.all())
        val placed = when {
            original != null -> withSnooze.copy(position = Notes.copyPosition(original, noteDao.all()))
            create && old == null && !keepsPlace -> withSnooze.copy(position = Notes.topPosition(noteDao.all()))
            else -> withSnooze.copy(position = old?.position ?: withSnooze.position)
        }
        val clean = Notes.clean(placed).let { if (it == old) it else it.copy(modified = System.currentTimeMillis()) }
        require(Notes.hasContent(clean)) { "An empty note can't be saved" }
        Notes.validate(clean)
        withContext(NonCancellable) {
            if (create) db.withTransaction {
                if (original != null) makeRoomForCopy(original, clean.position) else if (keepsPlace) noteDao.makeRoomAt(clean.position)
                noteDao.insert(clean)
            }
            else { check(old != null) { "This note was deleted" }; noteDao.update(clean) }
            afterCommit(noteIds = listOf(clean.id),
                resetNoteIds = if (old != null && old.activeReminderAt != clean.activeReminderAt) setOf(clean.id) else emptySet(),
                quietNoteIds = if (ringTurnedOff(old?.let { Ringing(it.ringUntilDismissed, it.ringSeconds) }, Ringing(clean.ringUntilDismissed, clean.ringSeconds))) setOf(clean.id) else emptySet())
        }
        clean
    }

    // U15-1: once at start, a ring choice stored without its reminder by an earlier build is cleared (nothing is armed for it).
    suspend fun clearRingWithoutReminder() = changes.withLock {
        withContext(NonCancellable) {
            if (taskDao.clearRingWithoutReminder() + noteDao.clearRingWithoutReminder() > 0) afterCommit()
        }
    }

    // Pin, archive or tick a checklist line from the Notes page: [change] made to the note as it is at that moment.
    suspend fun updateNote(id: String, change: (PlannerNote) -> PlannerNote): PlannerNote? = changes.withLock {
        withContext(NonCancellable) {
            val note = noteDao.byId(id) ?: return@withContext null
            val changed = Notes.clean(change(note))
            if (changed == note) return@withContext note
            Notes.validate(changed)
            // Pinning and archiving only file it differently; the note itself changed only if its words did.
            val stamped = if (changed.content != note.content || changed.title != note.title) changed.copy(modified = System.currentTimeMillis()) else changed
            noteDao.update(stamped)
            afterCommit(noteIds = listOf(id), resetNoteIds = if (note.activeReminderAt != stamped.activeReminderAt) setOf(id) else emptySet(),
                quietNoteIds = if (ringTurnedOff(Ringing(note.ringUntilDismissed, note.ringSeconds), Ringing(stamped.ringUntilDismissed, stamped.ringSeconds))) setOf(id) else emptySet())
            stamped
        }
    }

    suspend fun allNotes(): List<PlannerNote> = noteDao.all()

    // Several notes changed at once from the Notes page's selection (pin, archive, move to a notebook), all or none.
    suspend fun updateNotes(ids: Collection<String>, change: (PlannerNote) -> PlannerNote) = changes.withLock {
        withContext(NonCancellable) {
            val changed = db.withTransaction {
                ids.mapNotNull { id ->
                    val note = noteDao.byId(id) ?: return@mapNotNull null
                    val next = Notes.clean(change(note)).takeIf { it != note } ?: return@mapNotNull null
                    Notes.validate(next)
                    noteDao.update(next); next
                }
            }
            if (changed.isNotEmpty()) afterCommit(noteIds = changed.map { it.id })
        }
    }

    // Duplicate: a copy of each note (Notes.copyOf), placed right after its original (a pinned one's at the top of the
    // unpinned notes); the notes after it move down one place, in one statement (positions only, as a drag does).
    // Returns the copies, in the order of [ids].
    suspend fun duplicateNotes(ids: List<String>): List<PlannerNote> = changes.withLock {
        withContext(NonCancellable) {
            val copies = db.withTransaction {
                val pinned = ids.filter { noteDao.byId(it)?.pinned == true }.toSet()
                val made = Notes.duplicateOrder(ids, pinned::contains).mapNotNull { id ->
                    val original = noteDao.byId(id) ?: return@mapNotNull null
                    val place = Notes.copyPosition(original, noteDao.all())
                    makeRoomForCopy(original, place)
                    val copy = Notes.clean(Notes.copyOf(original).copy(position = place))
                    Notes.validate(copy)
                    noteDao.insert(copy)
                    id to copy
                }.toMap()
                ids.mapNotNull(made::get)
            }
            afterCommit(noteIds = copies.map { it.id })
            copies
        }
    }

    // Room for a copy of [original] at [place] (Notes.copyPosition), in a transaction: the notes from there move down one.
    // N6-4: a note sharing the original's place that shows after it goes after the copy too. A pinned one's copy goes on top.
    private suspend fun makeRoomForCopy(original: PlannerNote, place: Long) {
        if (original.pinned) return
        val tied = Notes.tiedAfter(original, noteDao.all())
        noteDao.makeRoomAt(place, 1L + tied.size)
        tied.forEachIndexed { i, note -> noteDao.update(note.copy(position = place + 1 + i)) }
    }

    // Notes dragged into a new order: [places] from Notes.reorder. Their words didn't change, so neither does their time.
    suspend fun placeNotes(places: Map<String, Long>) = changes.withLock {
        if (places.isEmpty()) return@withLock
        withContext(NonCancellable) {
            db.withTransaction { places.forEach { (id, position) -> noteDao.byId(id)?.let { noteDao.update(it.copy(position = position)) } } }
            afterCommit(noteIds = places.keys.toList(), notify = false)
        }
    }

    // Note sync: [note] put in place exactly as given (Nextcloud's time and all), only if the note is still [expected]
    // (null: not there yet), so an edit made meanwhile is never overwritten. True when it was.
    suspend fun putSyncedNote(note: PlannerNote, expected: PlannerNote?): Boolean = changes.withLock {
        withContext(NonCancellable) {
            val current = noteDao.byId(note.id)
            if (current != expected) return@withContext false
            // Nextcloud has no order: a note new here goes to the top, a known one keeps its place.
            val clean = Notes.clean(note.copy(position = current?.position ?: Notes.topPosition(noteDao.all())))
            Notes.validate(clean)
            if (current == null) noteDao.insert(clean) else noteDao.update(clean)
            afterCommit(noteIds = listOf(clean.id), resetNoteIds = if (current != null && current.activeReminderAt != clean.activeReminderAt) setOf(clean.id) else emptySet(),
                quietNoteIds = if (ringTurnedOff(current?.let { Ringing(it.ringUntilDismissed, it.ringSeconds) }, Ringing(clean.ringUntilDismissed, clean.ringSeconds))) setOf(clean.id) else emptySet())
            true
        }
    }

    // Note sync: deleted on Nextcloud, so to Recently deleted (restorable there) without the Undo bar, if still [expected].
    suspend fun archiveSyncedNote(id: String, expected: PlannerNote): Boolean = changes.withLock {
        withContext(NonCancellable) {
            val moved = archiving { archived ->
                val note = noteDao.byId(id)?.takeIf { it == expected } ?: return@archiving false
                archive(PendingDeletion(items = emptyList(), attachments = emptyList(), reminders = emptyList(), notes = listOf(note)), archived, emptyList())
                noteDao.delete(id)
                true
            }
            if (moved) afterCommit(noteIds = listOf(id))
            moved
        }
    }

    // To Recently deleted, with the Undo bar, as a task goes.
    suspend fun deleteNote(id: String) = deleteNotes(listOf(id))

    // Several notes to Recently deleted together, with one Undo bar for all of them.
    suspend fun deleteNotes(ids: Collection<String>) = changes.withLock {
        withContext(NonCancellable) {
            val bundle = deletingWithUndo({ archived ->
                val notes = ids.mapNotNull { noteDao.byId(it) }
                if (notes.isEmpty()) return@deletingWithUndo null
                val deleted = PendingDeletion(items = emptyList(), attachments = emptyList(), reminders = emptyList(), notes = notes)
                archive(deleted, archived, emptyList())
                deleted
            }) { deleted -> deleted.notes.forEach { noteDao.delete(it.id) } } ?: return@withContext
            afterCommit(noteIds = bundle.notes.map { it.id })
        }
    }

    // A note reminder's alarm: [deliver] shows it, if the note still has that reminder at that time.
    suspend fun deliverNoteReminder(id: String, trigger: Long, deliver: (PlannerNote) -> Unit) = forDelivery {
        val note = noteDao.byId(id) ?: return@forDelivery
        if (note.activeReminderAt == trigger && trigger <= System.currentTimeMillis() &&
            !scheduler.wasDelivered(MissedReminders.noteKey(id), trigger)) {
            deliver(note)
            scheduler.markDelivered(MissedReminders.noteKey(id), trigger)
        }
    }

    // From a note reminder's notification: Done clears the reminder; Snooze moves it to [snoozeUntil]. False when the
    // note no longer has that reminder (changed or deleted since).
    suspend fun actOnNoteReminder(id: String, trigger: Long, snoozeUntil: Long? = null): Boolean = changes.withLock {
        val note = noteDao.byId(id) ?: return@withLock false
        if (note.activeReminderAt != trigger || trigger > System.currentTimeMillis()) return@withLock false
        if (snoozeUntil != null) require(snoozeUntil > System.currentTimeMillis())
        withContext(NonCancellable) {
            noteDao.update(if (snoozeUntil == null) note.copy(reminderAt = null, snoozedUntil = null, ringUntilDismissed = false, ringSeconds = 0) else note.copy(snoozedUntil = snoozeUntil))
            afterCommit(noteIds = listOf(id), resetNoteIds = setOf(id))
        }
        true
    }

    suspend fun deleteTask(id: String) = changes.withLock {
        withContext(NonCancellable) {
            val earlier = conversionsMaking(taskIds = setOf(id))
            deletingWithUndo({ archived ->
                val task = taskDao.byId(id) ?: return@deletingWithUndo null
                val deleted = PendingDeletion(items = emptyList(), attachments = emptyList(), reminders = emptyList(), tasks = listOf(task))
                archive(deleted, archived, emptyList())
                deleted
            }) { taskDao.delete(id) } ?: return@withContext
            afterCommit(taskIds = listOf(id))
            settleConversions(earlier)
        }
    }

    // Task → event: once its event ([eventId], a series' first) is saved, the task goes to Recently deleted with an Undo
    // that also removes the event. Tasks waiting on it stop waiting, rather than staying blocked for good.
    suspend fun replaceTaskWithEvent(taskId: String, eventId: Long) = changes.withLock {
        withContext(NonCancellable) {
            val earlier = conversionsMaking(taskIds = setOf(taskId))
            val freed = mutableListOf<String>()
            deletingWithUndo({ archived ->
                val task = taskDao.byId(taskId) ?: return@deletingWithUndo null
                taskDao.all().filter { taskId in it.prerequisiteIds }.forEach { waiting ->
                    taskDao.update(waiting.copy(prerequisiteIds = waiting.prerequisiteIds - taskId)); freed += waiting.id
                }
                val deleted = PendingDeletion(items = emptyList(), attachments = emptyList(), reminders = emptyList(), tasks = listOf(task),
                    madeInto = MadeInto(eventIds = listOf(eventId), freed = freed.toList(), fromTaskId = taskId))
                archive(deleted, archived, emptyList())
                deleted
            }) { taskDao.delete(taskId) } ?: return@withContext
            afterCommit(taskIds = listOf(taskId) + freed)
            settleConversions(earlier)
        }
    }

    // Event(s) → task: once the task is saved, the events ([ids]: one occurrence or a whole series) go to Recently deleted
    // with an Undo that also removes the task.
    suspend fun replaceEventsWithTask(ids: Set<Long>, taskId: String) = changes.withLock {
        requirePlannerEvents(ids)
        withContext(NonCancellable) {
            val earlier = conversionsMaking(eventIds = ids)
            val deleted = deletingWithUndo({ archived ->
                val selected = readIds(ids, itemDao::byIds).sortedBy { it.id }
                if (selected.isEmpty()) return@deletingWithUndo null
                val selectedIds = selected.mapTo(hashSetOf()) { it.id }
                val bundle = PendingDeletion(items = selected,
                    attachments = readIds(selectedIds, attachmentDao::forItems).sortedBy { it.id },
                    reminders = readIds(selectedIds, reminderDao::forItems).sortedBy { it.id }, madeInto = MadeInto(taskId = taskId))
                archive(bundle, archived)
                bundle
            }) { bundle -> bundle.items.forEach { itemDao.delete(it) } } ?: return@withContext
            afterCommit(reminderIds = deleted.reminders.map { it.id })
            settleConversions(earlier)
        }
    }

    // Task sync: [change] made to task [id] as it is at that moment, with no other change in between (null leaves it): the
    // task as saved, or null when nothing was saved (also when it's gone). Done there completes it as here (a repeating
    // task's next one is made), without asking for its prerequisites first: it was already done on Nextcloud.
    suspend fun saveTaskIf(id: String, change: (PlannerTask) -> PlannerTask?): PlannerTask? = changes.withLock {
        withContext(NonCancellable) {
            val result: Pair<List<String>, Set<String>>? = db.withTransaction {
                val task = taskDao.byId(id) ?: return@withTransaction null
                val changed = change(task) ?: return@withTransaction null
                val clean = Tasks.capText(changed.copy(title = changed.title.trim(), notes = changed.notes.trim()))
                Tasks.validate(clean)
                val next = if (clean.done && !task.done && task.nextTaskId == null) clean.nextOccurrence() else null
                if (next != null) taskDao.insert(next)
                val saved = clean.copy(nextTaskId = next?.id ?: task.nextTaskId, snoozedUntil = if (clean.done) null else task.snoozedUntil)
                taskDao.update(saved)
                listOfNotNull(id, next?.id) to (if (saved.activeReminderAt != task.activeReminderAt) setOf(id) else emptySet())
            }
            val (ids, reset) = result ?: return@withContext null
            afterCommit(taskIds = ids, resetTaskIds = reset)
            taskDao.byId(id)
        }
    }

    // Task sync: a task deleted on Nextcloud goes to Recently deleted (restorable there), without the Undo message a
    // deletion in Planner shows; only if [still] holds for it at that moment. True when it was moved.
    suspend fun archiveTask(id: String, still: (PlannerTask) -> Boolean): Boolean = changes.withLock {
        withContext(NonCancellable) {
            // A converted item gone this way too drops its conversion's Undo (CW-4).
            val earlier = conversionsMaking(taskIds = setOf(id))
            val moved = archiving { archived ->
                val task = taskDao.byId(id)?.takeIf(still) ?: return@archiving false
                archive(PendingDeletion(items = emptyList(), attachments = emptyList(), reminders = emptyList(), tasks = listOf(task)), archived, emptyList())
                taskDao.delete(id)
                true
            }
            if (moved) { afterCommit(taskIds = listOf(id)); settleConversions(earlier) }
            moved
        }
    }

    private val deletedDao = db.deletedDao()
    private val payloads = store.deletedPayloads()
    private fun contents(entry: DeletedEntry) = DeletedCodec.decode(payloads.read(entry.payload))
    // H17-D3: null for a bundle that can't be read (logged), so one damaged entry doesn't block every cleanup.
    private fun readableContents(entries: List<DeletedEntry>) = readEachDeleted(entries, ::contents) { entry, e ->
        Log.w("Repository", "Couldn't read Recently deleted entry ${entry.id}", e)
    }
    val recentlyDeleted = deletedDao.observe().map { entries ->
        entries.filter { it.deletedAt > System.currentTimeMillis() - TRASH_RETENTION_MS }
    }

    // A task as saved, for an editor that goes on editing it after Save.
    suspend fun task(id: String): PlannerTask? = taskDao.byId(id)

    // One task as stored, as it changes (null once gone): an open editor notices a sync pull's update, or its deletion.
    fun observeTask(id: String): Flow<PlannerTask?> = taskDao.observe(id).distinctUntilChanged()

    /** The ids of every event in [id]'s series, or just [id] when it doesn't repeat. */
    suspend fun seriesIds(id: Long): Set<Long> = itemDao.byId(id)?.let { e -> e.seriesId?.let { itemDao.forSeries(it).mapTo(hashSetOf()) { it.id } } ?: setOf(e.id) }.orEmpty()

    /** Every event in [id]'s series, by date, or just that event when it doesn't repeat. */
    suspend fun seriesEvents(id: Long): List<ItineraryItem> = itemDao.byId(id)?.let { e -> e.seriesId?.let { itemDao.forSeries(it) } ?: listOf(e) }.orEmpty()

    // How many tasks wait for task [taskId] (RS-3).
    suspend fun waitingOn(taskId: String): Int = taskDao.all().count { taskId in it.prerequisiteIds }

    // Whether any event is a time block for task [taskId].
    suspend fun hasTimeBlocks(taskId: String): Boolean = itemDao.hasTimeBlocks(taskId)

    suspend fun eventDetails(id: Long): Triple<ItineraryItem, List<Attachment>, List<Reminder>>? = db.withTransaction {
        itemDao.byId(id)?.let { Triple(it, attachmentDao.forItem(id), reminderDao.forItem(id)) }
    }

    // One event as stored, as it changes (null once gone): an open editor notices a sync pull's update of it.
    fun observeItem(id: Long): Flow<ItineraryItem?> = itemDao.observe(id).distinctUntilChanged()

    private suspend fun archive(bundle: PendingDeletion, archived: MutableList<DeletedEntry>, plans: List<Trip>? = null) {
        val owners = plans ?: readIds(bundle.items.map { it.tripId }, tripDao::byIds).sortedWith(compareBy({ it.sortOrder }, { it.id }))
        val label = bundle.items.firstOrNull()?.title ?: bundle.tasks.firstOrNull()?.title ?: bundle.notes.firstOrNull()?.let(Notes::label)
            ?: owners.firstOrNull()?.name ?: "Deleted events"
        val count = bundle.items.size + bundle.tasks.size + bundle.notes.size
        val entry = DeletedEntry(id = bundle.token, label = if (count > 1) "$label + ${count - 1}" else label,
            payload = payloads.store(DeletedCodec.encode(DeletedContents(owners, bundle.items, bundle.attachments, bundle.reminders, bundle.tasks, bundle.notes))))
        archived += entry
        deletedDao.insert(entry)
    }

    // A transaction that archives: if it doesn't commit, a payload file archive() wrote for it goes again.
    private suspend fun <T> archiving(block: suspend (MutableList<DeletedEntry>) -> T): T {
        val archived = mutableListOf<DeletedEntry>()
        try { return db.withTransaction { block(archived) } }
        catch (e: Throwable) { dropUncommitted(archived); throw e }
    }

    // A deletion with Undo: [archive] puts what goes into Recently deleted and returns its bundle (null: nothing to
    // delete), then [remove] deletes it. S6-7: the bundle is offered for Undo before the delete commits, so a send pass
    // never finds it gone without its Undo waiting (it would delete Nextcloud's copy); a delete that fails takes it back.
    private suspend fun deletingWithUndo(archive: suspend (MutableList<DeletedEntry>) -> PendingDeletion?,
                                         remove: suspend (PendingDeletion) -> Unit): PendingDeletion? {
        var offered: PendingDeletion? = null
        try {
            return archiving { archived ->
                val bundle = archive(archived) ?: return@archiving null
                _pendingDeletions.value += bundle; offered = bundle
                remove(bundle)
                bundle
            }
        } catch (e: Throwable) {
            offered?.let { bundle -> _pendingDeletions.value = _pendingDeletions.value.filterNot { it.token == bundle.token } }
            throw e
        }
    }

    // After a failed transaction: the payload files of [entries] whose rows didn't get saved.
    private suspend fun dropUncommitted(entries: List<DeletedEntry>) = withContext(NonCancellable) {
        runCatching { entries.forEach { if (deletedDao.byId(it.id)?.payload != it.payload) payloads.delete(it.payload) } }
    }

    // Once started, a restore finishes (its reminders set, its stored bundle removed) even if the screen goes.
    suspend fun restoreDeleted(id: String) = withContext(NonCancellable) { changes.withLock { restoreDeletedLocked(id) } }

    private suspend fun restoreDeletedLocked(id: String) = finishRestore(id, restoreDeletedRows(id))

    private class Restored(val reminderIds: List<Long>, val tasks: List<String>, val notes: List<String>, val stored: String?)

    // The database half of a restore, so an Undo of a conversion can do it in the same transaction as its take-back.
    private suspend fun restoreDeletedRows(id: String): Restored {
        val restoredTasks = mutableListOf<String>()
        val restoredNotes = mutableListOf<String>()
        var stored: String? = null
        val reminderIds = db.withTransaction {
            val entry = deletedDao.byId(id) ?: return@withTransaction emptyList<Long>()
            check(entry.deletedAt > System.currentTimeMillis() - TRASH_RETENTION_MS) { "The recovery period has expired" }
            val data = contents(entry)
            stored = entry.payload
            data.tasks.forEach { task ->
                val restored = task.copy(id = if (taskDao.byId(task.id) == null) task.id else UUID.randomUUID().toString())
                taskDao.insert(restored)
                restoredTasks += restored.id
            }
            TaskDependencies.validateGraph(taskDao.all())
            // A note whose id came back meanwhile (a backup restored since) returns as a copy beside it.
            data.notes.forEach { note ->
                val restored = note.copy(id = if (noteDao.byId(note.id) == null) note.id else UUID.randomUUID().toString())
                noteDao.insert(restored); restoredNotes += restored.id
            }
            val planIds = tripDao.all().mapTo(hashSetOf()) { it.id }
            data.trips.filter { it.id !in planIds }.forEach { tripDao.upsert(it) }
            val itemIds = itemDao.all().mapTo(hashSetOf()) { it.id }
            val attachmentIds = attachmentDao.all().mapTo(hashSetOf()) { it.id }
            val alarmIds = reminderDao.all().mapTo(hashSetOf()) { it.id }
            val ids = data.items.sortedBy { it.id in itemIds }.associate { old -> old.id to itemDao.upsert(old.copy(id = if (old.id in itemIds) 0 else old.id)) }
            data.attachments.sortedBy { it.id in attachmentIds }.forEach { attachmentDao.insert(it.copy(id = if (it.id in attachmentIds) 0 else it.id, itemId = ids.getValue(it.itemId))) }
            val restored = data.reminders.sortedBy { it.id in alarmIds }.map { reminderDao.insert(it.copy(id = if (it.id in alarmIds) 0 else it.id, itemId = ids.getValue(it.itemId))) }
            deletedDao.delete(id)
            restored
        }
        return Restored(reminderIds, restoredTasks, restoredNotes, stored)
    }

    private suspend fun finishRestore(id: String, restored: Restored) {
        restored.stored?.let(payloads::delete)
        _pendingDeletions.value = _pendingDeletions.value.filterNot { it.token == id }
        afterCommit(reminderIds = restored.reminderIds, taskIds = restored.tasks, noteIds = restored.notes)
    }

    // Recently deleted's selection: each entry on its own, as one Restore / Delete forever would; one that fails doesn't
    // stop the rest. Returns how many failed.
    // Returns how many failed; [failedIds] gets which.
    suspend fun restoreDeleted(ids: Collection<String>, failedIds: MutableCollection<String> = mutableListOf()): Int = ids.count { id ->
        try { restoreDeleted(id); false } catch (e: CancellationException) { throw e } catch (_: Exception) { failedIds += id; true }
    }

    suspend fun permanentlyDelete(ids: Collection<String>, failedIds: MutableCollection<String> = mutableListOf()): Int = ids.count { id ->
        try { permanentlyDelete(id); false } catch (e: CancellationException) { throw e } catch (_: Exception) { failedIds += id; true }
    }

    // Bug hunt 19: not cut off by leaving Recently deleted mid-way, which would leave its files and held-back sync delete.
    suspend fun permanentlyDelete(id: String) = withContext(NonCancellable) { changes.withLock {
        val entry = deletedDao.byId(id)
        // H17-D3: an entry that can't be read still goes; files only it held are left (cleanup can't tell they're unused).
        val files = entry?.let { readableContents(listOf(it)).single()?.storedAttachments?.map { a -> a.fileName } }.orEmpty()
        deletedDao.delete(id)
        entry?.let { payloads.delete(it.payload) }
        val dropped = dropPending { it.token == id }
        afterCommit(files = files, notify = false)
        if (dropped) onDeletionFinished()
    } }

    // S5-1: a deletion whose Undo was still on offer, deleted for good (Delete forever, or gone from Recently deleted):
    // its Undo has passed too, so sync may now delete Planner's copy on Nextcloud: the caller says so (onDeletionFinished,
    // as finishDeletion does) once the files are seen to. Whether any was dropped.
    private fun dropPending(which: (PendingDeletion) -> Boolean): Boolean {
        val dropped = _pendingDeletions.value.filter(which)
        if (dropped.isNotEmpty()) _pendingDeletions.value -= dropped.toSet()
        return dropped.isNotEmpty()
    }

    private suspend fun purgeExpiredDeleted() {
        val expired = deletedDao.all().filter { it.deletedAt <= System.currentTimeMillis() - TRASH_RETENTION_MS }
        if (expired.isEmpty()) return
        // H17-D3: an expired entry that can't be read is removed all the same.
        val files = readableContents(expired).flatMap { it?.storedAttachments.orEmpty() }.map { it.fileName }
        db.withTransaction { expired.forEach { deletedDao.delete(it.id) } }
        expired.forEach { payloads.delete(it.payload) }
        val ids = expired.map { it.id }.toSet()
        val dropped = dropPending { it.token in ids }
        afterCommit(files = files, notify = false)
        if (dropped) onDeletionFinished()
    }

    val trips: Flow<List<Trip>> = tripDao.observeTrips()
    fun trip(id: Long): Flow<Trip?> = tripDao.observeTrip(id)

    // Across every trip, for search.
    // DA-7: one query per change, shared by every screen watching (seven or more collect it), not one each. Kept while
    // anything watches and 5 seconds after (rotation), then dropped, so a later collector never starts from an old list.
    val allItems: Flow<List<ItineraryItem>> = itemDao.observeAll().let { query ->
        if (shareScope == null) query
        else query.shareIn(shareScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000, replayExpirationMillis = 0), replay = 1)
    }

    // Across every trip, trimmed to the columns a plan card's event summary draws (see PlanEvent).
    val planEvents: Flow<List<PlanEvent>> = itemDao.observePlanEvents()
    val allReminders: Flow<List<Reminder>> = reminderDao.observeAll()
    val allAttachments: Flow<List<Attachment>> = attachmentDao.observeAll()

    // How many events use each category, across every plan; decides which categories are offered first.
    val categoryCounts: Flow<Map<String, Int>> = itemDao.observeCategoryCounts()
        .map { counts -> counts.associate { it.category to it.count } }
        .distinctUntilChanged()

    suspend fun saveTrip(trip: Trip) {
        // A brand-new plan goes to the top of the list.
        val toSave = if (trip.id == 0L) trip.copy(sortOrder = tripDao.minOrder() - 1) else trip
        tripDao.upsert(toSave)
    }

    suspend fun deleteTrip(trip: Trip) = deleteTrips(listOf(trip))

    suspend fun deleteTrips(trips: List<Trip>) = changes.withLock {
        if (trips.isEmpty()) return@withLock
        val tripIds = trips.mapTo(hashSetOf()) { it.id }
        val earlier = conversionsMaking(eventIds = itemDao.all().filter { it.tripId in tripIds }.mapTo(hashSetOf()) { it.id })
        val (files, reminders) = archiving { archived ->
            val selected = tripDao.all().filter { current -> trips.any { it.id == current.id } }
            if (selected.isEmpty()) return@archiving emptyList<String>() to emptyList<Reminder>()
            val candidates = selected.flatMap { attachmentDao.fileNamesForTrip(it.id) }.toSet()
            val alarms = selected.flatMap { reminderDao.forTrip(it.id) }
            val selectedIds = selected.map { it.id }.toSet()
            val events = itemDao.all().filter { it.tripId in selectedIds }
            val eventIds = events.map { it.id }.toSet()
            archive(PendingDeletion(items = events, attachments = attachmentDao.all().filter { it.itemId in eventIds }, reminders = alarms), archived, selected)
            selected.forEach { tripDao.delete(it) }
            // Imported backups can share attachment files between plans.
            val keep = attachmentDao.allFileNames().toHashSet()
        taskDao.all().flatMap { it.attachments }.forEach { keep.add(it.fileName) }
        noteDao.all().flatMap { it.attachments }.forEach { keep.add(it.fileName) }
        keep.addAll(store.taskDraftFiles())
        keep.addAll(store.noteDraftFiles())
        keep.addAll(store.eventDraftFiles())
            candidates.filter { it.isNotBlank() && it !in keep } to alarms
        }
        afterCommit(files, reminders.map { it.id })
        settleConversions(earlier)
    }

    // [added]/[removed] change attachments (added files are already in the store) and
    // [addedReminders]/[removedReminders] change reminders.
    suspend fun saveItem(
        item: ItineraryItem,
        added: List<Attachment> = emptyList(),
        removed: List<Attachment> = emptyList(),
        addedReminders: List<Reminder> = emptyList(),
        removedReminders: List<Reminder> = emptyList(),
        options: EventSaveOptions = EventSaveOptions(),
    ) { saveItemId(item, added, removed, addedReminders, removedReminders, options) }

    // saveItem, returning the id of the event the editor was showing, so it can go on editing the saved event: its own
    // id, or for a new event (or new series) the first one added. A retried save (its token already in) finds the same.
    suspend fun saveItemId(
        item: ItineraryItem,
        added: List<Attachment> = emptyList(),
        removed: List<Attachment> = emptyList(),
        addedReminders: List<Reminder> = emptyList(),
        removedReminders: List<Reminder> = emptyList(),
        options: EventSaveOptions = EventSaveOptions(),
    ): Long = changes.withLock { saveItemIdLocked(item, added, removed, addedReminders, removedReminders, options) }

    // Two-way sync: [change] made to event [id] as it is at that moment and saved, with no other change in between (a
    // save made meanwhile is never overwritten by an older copy). [change] returns null to leave it. Returns the event
    // as saved, or null when nothing was saved (also when the event is gone).
    suspend fun saveItemIf(id: Long, change: (ItineraryItem) -> ItineraryItem?): ItineraryItem? = changes.withLock {
        val next = itemDao.byId(id)?.let(change) ?: return@withLock null
        saveItemIdLocked(next)
        itemDao.byId(id)
    }

    private suspend fun saveItemIdLocked(
        item: ItineraryItem,
        added: List<Attachment> = emptyList(),
        removed: List<Attachment> = emptyList(),
        addedReminders: List<Reminder> = emptyList(),
        removedReminders: List<Reminder> = emptyList(),
        options: EventSaveOptions = EventSaveOptions(),
    ): Long = run {
        requirePlannerEvent(item.id)
        require(item.tripId != OutsideCalendars.TRIP_ID) { OutsideCalendars.READ_ONLY }
        ChecklistCodec.validate(item.checklist)
        Bills.validate(item.billAmountMinor, item.billCurrency)
        Payments.validate(item.payments)
        require(item.payments.none { !it.reversed } || item.billAmountMinor != null) { "A bill with payments needs an amount." }
        require(item.bufferBeforeMinutes in 0..1440 && item.bufferAfterMinutes in 0..1440)
        require(item.durationMinutes == null || item.startTime != null && item.durationMinutes in 1..1440)
        MultiDay.validate(item)
        EventText.validate(item, if (item.id == 0L) null else itemDao.byId(item.id))
        val cancelled = mutableListOf<Reminder>()
        val resetReminders = mutableSetOf<Long>()
        val quieted = mutableListOf<Long>()
        val removedFiles = mutableListOf<String>()
        val scheduled = mutableListOf<Pair<ItineraryItem, List<Reminder>>>()
        val payments = mutableListOf<PendingPayment>()
        val budgetChanges = mutableListOf<Pair<ItineraryItem, ItineraryItem>>()
        val editedId = db.withTransaction {
            if (options.draftToken != null && itemDao.hasDraftToken(options.draftToken))
                return@withTransaction item.id.takeIf { it != 0L } ?: itemDao.firstIdForDraftToken(options.draftToken) ?: 0L
            if (item.id == 0L && item.linkedTaskId != null) {
                require(taskDao.byId(item.linkedTaskId) != null) { "The task this time block belongs to was deleted." }
                require(item.startTime != null && item.durationMinutes != null) { "A time block needs a start time and a duration." }
                require(item.category != "Bills") { "A time block can't be a bill." }
            }
            val original = if (item.id == 0L) null else itemDao.byId(item.id)
            val members = if (options.entireSeries && original?.seriesId != null)
                itemDao.forSeries(original.seriesId).sortedWith(compareBy({ it.date }, { it.id })) else listOfNotNull(original)
            val previousById = members.associateBy { it.id }
            val item = if (original != null && options.paymentBaseline != null)
                Payments.mergeEditor(item, original, options.paymentBaseline) else item
            val originalReminders = if (original != null) reminderDao.forItem(original.id) else emptyList()
            // A readable reason (the editor shows IllegalArgumentException's message): retrying can't help.
            require(item.id == 0L || original != null) { "This event was deleted elsewhere. Save again to keep your version as a new event." }
            val owner = if (item.id == 0L && item.tripId == 0L) {
                tripDao.firstId() ?: tripDao.upsert(
                    Trip(name = "Agenda", destination = "", startDate = item.date, endDate = item.date),
                )
            } else item.tripId
            val targets = when {
                item.id == 0L || original?.seriesId == null && options.repeat != RepeatRule.NONE -> {
                    val dates = options.repeat.dates(item.date, options.count, options.anchorDay)
                    val series = if (dates.size > 1) UUID.randomUUID().toString() else null
                    dates.mapIndexed { index, date -> item.startingOn(date).copy(id = if (index == 0) item.id else 0, tripId = owner, seriesId = series,
                        paid = index == 0 && item.paid, payments = if (index == 0) item.payments else emptyList(), skipped = false,
                        checklist = if (index == 0) item.checklist else item.checklist.map { it.copy(done = false) },
                        repeatRule = if (series == null) "NONE" else options.repeat.name) }
                }
                options.entireSeries && original?.seriesId != null -> {
                    val shift = ChronoUnit.DAYS.between(original.date, item.date)
                    val dates = if (options.changeRepeat && options.repeat != RepeatRule.NONE)
                        options.repeat.dates(members.first().date.plusDays(shift), members.size) else members.map { it.date.plusDays(shift) }
                    members.mapIndexed { index, old -> seriesOccurrence(item, old, dates[index], options.changeRepeat, options.repeat) }
                }
                else -> listOf(item)
            }
            val seriesSave = options.entireSeries && original?.seriesId != null
            val newSeries = !seriesSave && item.id != 0L && targets.size > 1
            // A new series copies this event's files and reminders to the NEW occurrences; the edited event keeps its own
            // records and takes only this edit's changes, like a plain save (D8: a snooze, a due-but-undelivered or ringing
            // alarm and text being recognized stay). An entire-series save gives the other occurrences only this edit's
            // changes (seriesSiblingChanges): each keeps its own, e.g. its receipts.
            val ownAttachments = if (newSeries) attachmentDao.forItem(item.id) else emptyList()
            // By id: text recognition or a snooze may have changed the record since the editor read it.
            val selectedAttachments = ownAttachments.filter { old -> removed.none { it.id == old.id } } + added
            val selectedReminders = if (newSeries) {
                reminderDao.forItem(item.id).filter { old -> removedReminders.none { it.id == old.id } } + addedReminders
            } else emptyList()
            for (target in targets) {
                val paymentTotal = Payments.total(target.payments)
                require(target.category != "Bills" || paymentTotal == 0L || target.billAmountMinor != null && paymentTotal <= target.billAmountMinor) {
                    "\"${target.title}\" on ${target.date} has payments of more than its amount. Raise the amount or remove payments first." }
                val previous = previousById[target.id]
                require(previous == null || !Payments.anyLive(previous.payments) || target.category != "Bills" || previous.billCurrency == target.billCurrency) {
                    "\"${target.title}\" on ${target.date} has payments in ${previous?.billCurrency}. Remove them before changing the currency." }
                val normalized = target.copy(paid = target.category == "Bills" && (if (target.payments.any { !it.reversed })
                    target.billAmountMinor != null && paymentTotal >= target.billAmountMinor else target.paid),
                    payments = target.payments, draftToken = options.draftToken)
                Payments.validate(normalized)
                val scheduleChanged = previous != null && (previous.date != normalized.date || previous.startTime != normalized.startTime)
                if (scheduleChanged) {
                    reminderDao.forItem(normalized.id).forEach {
                        resetReminders += it.id
                        reminderDao.snooze(it.id, null)
                    }
                }
                val rowId = itemDao.upsert(normalized)
                val saved = normalized.copy(id = if (rowId > 0) rowId else target.id)
                // A new bill saved as paid counts as paid from unpaid.
                budgetChanges += (previous ?: saved.copy(paid = false, payments = emptyList())) to saved
                if (newSeries && saved.id != item.id) {
                    selectedAttachments.forEach { attachmentDao.insert(it.copy(id = 0, itemId = saved.id)) }
                } else if (seriesSave && saved.id != item.id) {
                    val (drop, add) = seriesSiblingAttachments(attachmentDao.forItem(saved.id), added, removed)
                    drop.forEach { attachmentDao.delete(it); removedFiles.add(it.fileName) }
                    add.forEach { attachmentDao.insert(it.copy(id = 0, itemId = saved.id)) }
                } else {
                    added.forEach { attachmentDao.insert(it.copy(id = 0, itemId = saved.id)) }
                    removed.filter { it.itemId == saved.id }.forEach { attachmentDao.delete(it); removedFiles.add(it.fileName) }
                }
                // A reminder copied to another occurrence or added anew must not keep an old snooze.
                if (newSeries && saved.id != item.id) {
                    selectedReminders.forEach { reminderDao.insert(it.copy(id = 0, itemId = saved.id, snoozedUntil = null)) }
                } else if (seriesSave && saved.id != item.id) {
                    val (drop, add) = seriesSiblingReminders(reminderDao.forItem(saved.id), addedReminders, removedReminders)
                    drop.forEach { reminderDao.delete(it); cancelled.add(it) }
                    add.forEach { reminderDao.insert(it.copy(id = 0, itemId = saved.id, snoozedUntil = null)) }
                } else {
                    // A saved reminder changed in the editor ("Ring until I stop it") comes back with its id, in place of
                    // itself: it is updated, keeping its snooze and delivery record, not deleted and added again.
                    val changed = addedReminders.filter { new -> new.id != 0L && removedReminders.any { it.id == new.id } &&
                        reminderDao.byId(new.id)?.itemId == saved.id }
                    changed.forEach { new ->
                        // Hunt 22 P2: changed to a sound that doesn't ring while it rings: it goes quiet (after the commit).
                        reminderDao.byId(new.id)?.let { old -> if (ringTurnedOff(Ringing(old.ringUntilDismissed, old.ringSeconds),
                            Ringing(new.ringUntilDismissed, new.ringSeconds))) quieted.add(new.id) }
                        reminderDao.setRing(new.id, new.ringUntilDismissed, new.ringSeconds)
                    }
                    addedReminders.filterNot { it in changed }.forEach { reminderDao.insert(it.copy(id = 0, itemId = saved.id, snoozedUntil = null)) }
                    removedReminders.filter { old -> old.itemId == saved.id && changed.none { it.id == old.id } }
                        .forEach { reminderDao.delete(it); cancelled.add(it) }
                }
                if (previous?.paid != saved.paid) {
                    reminderDao.forItem(saved.id).forEach { reminderDao.snooze(it.id, null) }
                }
                val savedReminders = reminderDao.forItem(saved.id)
                if (original != null && saved.id == original.id && original.category == "Bills" && saved.category == "Bills" && original.paid != saved.paid)
                    payments += PendingPayment(before = original, paid = saved.paid, remindersBefore = originalReminders, remindersAfter = savedReminders, paymentsAfter = saved.payments)
                scheduled.add(saved to savedReminders)
            }
            if (item.id != 0L) item.id else scheduled.first().first.id
        }
        payments.forEach(::recordPayment)
        budgetChanges.forEach { (before, after) -> tellBudget(before, after) }
        quieted.forEach(scheduler::ringOff)
        afterCommit(removedFiles, cancelled.map { it.id } + scheduled.flatMap { it.second }.map { it.id }, cancelFirst = resetReminders)
        editedId
    }

    // Import calendar file: many new, plain events (no reminders, attachments or bills) in one transaction, with one
    // widget refresh. Series members share their seriesId. Returns the new ids, for Undo import.
    suspend fun importEvents(items: List<ItineraryItem>): List<Long> = changes.withLock {
        items.forEach { item ->
            require(item.id == 0L && item.tripId == 0L && item.category != "Bills") { "Only new events can be imported" }
            require(item.durationMinutes == null || item.startTime != null && item.durationMinutes in 1..1440)
            MultiDay.validate(item)
        }
        if (items.isEmpty()) return@withLock emptyList()
        val ids = withContext(NonCancellable) {
            db.withTransaction {
                val owner = tripDao.firstId() ?: tripDao.upsert(Trip(name = "Agenda", destination = "", startDate = items.first().date, endDate = items.first().date))
                items.map { itemDao.upsert(it.copy(tripId = owner)) }
            }
        }
        afterCommit()
        ids
    }

    // Every event in [names], in any plan, becomes Other.
    suspend fun removeCategories(names: Collection<String>) = changes.withLock {
        if (names.isEmpty()) return@withLock
        val reminders = db.withTransaction {
            val affected = itemDao.all().filter { it.category in names }.mapTo(hashSetOf()) { it.id }
            itemDao.replaceCategories(names.toList(), Categories.OTHER)
            reminderDao.all().filter { it.itemId in affected }
        }
        // Reconciled, not reset (B8, C9): no date or time changed, so a due-but-undelivered, showing or ringing alarm stays;
        // a bill that was paid is not paid any more and gets its alarms back.
        afterCommit(reminderIds = reminders.map { it.id })
    }

    suspend fun deleteItem(item: ItineraryItem) = deleteWithUndo(item)

    suspend fun deleteWithUndo(item: ItineraryItem, entireSeries: Boolean = false) = deleteWithUndo(item.id, entireSeries)

    // By id, as it is now: the card's ⋮ only has the id and whether it repeats.
    suspend fun deleteWithUndo(id: Long, entireSeries: Boolean = false) = changes.withLock {
        requirePlannerEvent(id)
        val earlier = conversionsMaking(eventIds = setOf(id))
        val deleted = deletingWithUndo({ archived ->
            val current = itemDao.byId(id) ?: return@deletingWithUndo null
            val selected = if (entireSeries && current.seriesId != null) {
                itemDao.forSeries(current.seriesId).sortedBy { it.id }
            } else listOf(current)
            val ids = selected.mapTo(HashSet()) { it.id }
            val bundle = PendingDeletion(items = selected,
                attachments = readIds(ids, attachmentDao::forItems).sortedBy { it.id },
                reminders = readIds(ids, reminderDao::forItems).sortedBy { it.id })
            archive(bundle, archived)
            bundle
        }) { bundle -> bundle.items.forEach { itemDao.delete(it) } } ?: return@withLock
        afterCommit(reminderIds = deleted.reminders.map { it.id })
        settleConversions(earlier)
    }

    // Delete exactly these occurrences together, with one Undo bundle for the entire selection.
    // Selected events and tasks go together: one Undo, one entry in Recently deleted.
    suspend fun deleteEventsWithUndo(ids: Set<Long>, taskIds: Set<String> = emptySet()) = changes.withLock {
        requirePlannerEvents(ids)
        withContext(NonCancellable) {
            val earlier = conversionsMaking(eventIds = ids, taskIds = taskIds)
            val deleted = deletingWithUndo({ archived ->
                val selected = readIds(ids, itemDao::byIds).sortedBy { it.id }
                val tasks = taskIds.mapNotNull { taskDao.byId(it) }.sortedBy { it.id }
                if (selected.isEmpty() && tasks.isEmpty()) return@deletingWithUndo null
                val selectedIds = selected.mapTo(hashSetOf()) { it.id }
                val bundle = PendingDeletion(items = selected,
                    attachments = readIds(selectedIds, attachmentDao::forItems).sortedBy { it.id },
                    reminders = readIds(selectedIds, reminderDao::forItems).sortedBy { it.id }, tasks = tasks)
                archive(bundle, archived)
                bundle
            }) { bundle ->
                bundle.items.forEach { itemDao.delete(it) }
                bundle.tasks.forEach { taskDao.delete(it.id) }
            } ?: return@withContext
            afterCommit(reminderIds = deleted.reminders.map { it.id }, taskIds = deleted.tasks.map { it.id })
            settleConversions(earlier)
        }
    }

    // Two-way calendar sync: events deleted on Nextcloud go to Recently deleted (restorable there), without the Undo
    // message a deletion in Planner shows. Only those for which [only] holds as they are at that moment (one edited in
    // Planner meanwhile stays); returns the ids moved.
    suspend fun archiveEvents(ids: Set<Long>, only: (ItineraryItem) -> Boolean = { true }): Set<Long> = changes.withLock {
        withContext(NonCancellable) {
            val earlier = conversionsMaking(eventIds = ids)
            val (moved, reminders) = archiving { archived ->
                val selected = readIds(ids, itemDao::byIds).filter(only).sortedBy { it.id }
                if (selected.isEmpty()) return@archiving emptySet<Long>() to emptyList()
                val selectedIds = selected.mapTo(hashSetOf()) { it.id }
                val bundle = PendingDeletion(items = selected,
                    attachments = readIds(selectedIds, attachmentDao::forItems).sortedBy { it.id },
                    reminders = readIds(selectedIds, reminderDao::forItems).sortedBy { it.id })
                archive(bundle, archived)
                selected.forEach { itemDao.delete(it) }
                selectedIds to bundle.reminders
            }
            afterCommit(reminderIds = reminders.map { it.id })
            // Any occurrence of a made series, as a delete in Planner does (CX-4).
            if (moved.isNotEmpty()) settleConversions(earlier)
            moved
        }
    }

    suspend fun undoDeletion(token: String) = withContext(NonCancellable) { changes.withLock {
        val madeInto = _pendingDeletions.value.find { it.token == token }?.madeInto
            ?: return@withLock restoreDeletedLocked(token)
        // A conversion's take-back, the original's return and the waits put back are one transaction: a failure (or
        // Android ending Planner) leaves all of it undone, with the Undo still pending, never one without the other
        // (TE-7, CV-4).
        lateinit var taken: TakenBack
        lateinit var again: List<String>
        val restored = archiving { archived ->
            taken = takeBackRows(madeInto, archived)
            restoreDeletedRows(token).also { again = waitAgainRows(madeInto) }
        }
        finishRestore(token, restored)
        afterCommit(files = taken.files, reminderIds = taken.reminderIds, taskIds = taken.taskIds + again)
    } }

    // Undo of a conversion: what the original was made into goes (a series' every event; a repeating task's next ones,
    // made by completing it meanwhile) to Recently deleted, with no Undo of its own: edits and files added to it after
    // its first Save can still be found there (RS-2). Tasks made to wait on it meanwhile stop waiting (TE-9).
    private class TakenBack(val reminderIds: List<Long>, val taskIds: Set<String>, val files: List<String>)
    private suspend fun takeBackRows(made: MadeInto, archived: MutableList<DeletedEntry>): TakenBack {
        val result = db.withTransaction {
            val events = made.eventIds.mapNotNull { itemDao.byId(it) }.flatMap { e -> e.seriesId?.let { itemDao.forSeries(it) } ?: listOf(e) }.distinctBy { it.id }
            val eventIds = events.mapTo(hashSetOf()) { it.id }
            val reminders = readIds(eventIds, reminderDao::forItems)
            val files = readIds(eventIds, attachmentDao::forItems).map { it.fileName }.toMutableList()
            val tasks = mutableListOf<PlannerTask>()
            var next = made.taskId
            while (next != null && tasks.size < 1000) { val t = taskDao.byId(next) ?: break; tasks += t; next = t.nextTaskId }
            val goneIds = tasks.mapTo(hashSetOf()) { it.id }
            if (events.isNotEmpty() || tasks.isNotEmpty()) archive(PendingDeletion(items = events,
                attachments = readIds(eventIds, attachmentDao::forItems).sortedBy { it.id }, reminders = reminders.sortedBy { it.id },
                tasks = tasks), archived, if (events.isEmpty()) emptyList() else null)
            events.forEach { itemDao.delete(it) }
            tasks.forEach { files += it.attachments.map { a -> a.fileName }; taskDao.delete(it.id) }
            val unblocked = taskDao.all().filter { t -> t.prerequisiteIds.any { it in goneIds } }
                .onEach { taskDao.update(it.copy(prerequisiteIds = it.prerequisiteIds - goneIds)) }
            TakenBack(reminders.map { it.id }, goneIds + unblocked.map { it.id }, files)
        }
        return result
    }

    // After the Undo of a task made into an event: the tasks that stopped waiting on it wait again, unless a wait added
    // meanwhile would make that a loop (TE-9).
    private suspend fun waitAgainRows(made: MadeInto): List<String> {
        val from = made.fromTaskId ?: return emptyList()
        return db.withTransaction {
            if (taskDao.byId(from) == null) return@withTransaction emptyList()
            made.freed.mapNotNull { taskDao.byId(it) }.mapNotNull { waiting ->
                val updated = waiting.copy(prerequisiteIds = (waiting.prerequisiteIds + from).distinct())
                val all = taskDao.all().map { if (it.id == waiting.id) updated else it }
                if (runCatching { TaskDependencies.validateGraph(all) }.isFailure) return@mapNotNull null
                taskDao.update(updated)
                waiting.id
            }
        }
    }

    // The conversions still offering their Undo that made one of these events (or their series) or tasks (or a task's
    // next ones, made by completing it). Read before those go.
    private suspend fun conversionsMaking(eventIds: Set<Long> = emptySet(), taskIds: Set<String> = emptySet()): List<PendingDeletion> =
        _pendingDeletions.value.filter { bundle ->
            val made = bundle.madeInto ?: return@filter false
            made.eventIds.any { e -> e in eventIds || seriesIds(e).any(eventIds::contains) } || made.taskId != null && run {
                var next: String? = made.taskId
                var steps = 0
                while (next != null && steps++ < 1000) { if (next in taskIds) return@run true; next = taskDao.byId(next)?.nextTaskId }
                false
            }
        }

    // An item converted onward or deleted that an earlier conversion, still offering its Undo, made: that Undo is dropped
    // (it would bring back the first original beside it); the original stays in Recently deleted (TE-9, CV-3).
    private suspend fun settleConversions(earlier: List<PendingDeletion>) {
        if (earlier.isEmpty()) return
        val tokens = earlier.mapTo(hashSetOf()) { it.token }
        _pendingDeletions.value = _pendingDeletions.value.filterNot { it.token in tokens }
        afterCommit(files = earlier.flatMap { bundle -> bundle.attachments.map { it.fileName } }, notify = false)
        onDeletionFinished()
    }

    suspend fun finishDeletion(token: String) = changes.withLock {
        val bundle = _pendingDeletions.value.find { it.token == token } ?: return@withLock
        _pendingDeletions.value -= bundle
        afterCommit(files = bundle.attachments.map { it.fileName }, notify = false)
        onDeletionFinished()
    }

    // A new date or time makes an old snooze meaningless: it would stand in for the reminder at the new time.
    private suspend fun clearSnoozes(itemId: Long): List<Reminder> =
        reminderDao.forItem(itemId).onEach { if (it.snoozedUntil != null) reminderDao.snooze(it.id, null) }.map { it.copy(snoozedUntil = null) }

    // Change only the date of the latest saved record; one occurrence of a series stays independent.
    suspend fun moveToTomorrow(id: Long, today: java.time.LocalDate = java.time.LocalDate.now()) = changes.withLock {
        requirePlannerEvent(id)
        val (move, reminders) = db.withTransaction {
            val current = itemDao.byId(id) ?: error("This event no longer exists")
            val tomorrow = today.plusDays(1)
            if (current.date == tomorrow) return@withTransaction null
            itemDao.moveDate(id, tomorrow)
            PendingMove(itemId = id, title = current.title, fromDate = current.date, toDate = tomorrow) to clearSnoozes(id)
        } ?: return@withLock
        _pendingMoves.value += move
        afterCommit(reminderIds = reminders.map { it.id }, cancelFirst = reminders.mapTo(hashSetOf()) { it.id })
    }

    // Undo only the date. Preserve edits made since the move, and never recreate a deleted event.
    suspend fun undoMove(token: String): Boolean = changes.withLock {
        val move = _pendingMoves.value.find { it.token == token } ?: return@withLock false
        move.task?.let { (before, after) ->
            // Only while the task is still as the move left it: an edit since wins.
            _pendingMoves.value = _pendingMoves.value.filterNot { it.token == token }
            if (taskDao.byId(after.id) != after) return@withLock false
            withContext(NonCancellable) {
                taskDao.update(before)
                afterCommit(taskIds = listOf(before.id), resetTaskIds = if (before.activeReminderAt != after.activeReminderAt) setOf(before.id) else emptySet())
            }
            return@withLock true
        }
        val (restored, reminders) = db.withTransaction {
            val current = itemDao.byId(move.itemId)
            if (current == null || current.date != move.toDate) false to emptyList<Reminder>()
            else { itemDao.moveDate(current.id, move.fromDate); true to clearSnoozes(current.id) }
        }
        _pendingMoves.value = _pendingMoves.value.filterNot { it.token == token }
        if (restored) {
            afterCommit(reminderIds = reminders.map { it.id }, cancelFirst = reminders.mapTo(hashSetOf()) { it.id })
        }
        restored
    }

    suspend fun finishMove(token: String) = changes.withLock {
        _pendingMoves.value = _pendingMoves.value.filterNot { it.token == token }
    }

    val templates = db.templateDao().observeAll()
    suspend fun saveTemplate(name: String, content: TemplateContent) = changes.withLock {
        val clean = name.trim()
        require(clean.length in 1..80)
        val old = db.templateDao().all().find { it.name.equals(clean, ignoreCase = true) }
        db.templateDao().upsert(EventTemplate(old?.id ?: UUID.randomUUID().toString(), clean, content.encode()))
    }
    suspend fun deleteTemplate(template: EventTemplate) = changes.withLock { db.templateDao().delete(template) }

    suspend fun setSkipped(id: Long, skipped: Boolean) = changes.withLock {
        requirePlannerEvent(id)
        val reminders = db.withTransaction {
            val current = itemDao.byId(id) ?: error("This event no longer exists")
            require(current.seriesId != null || !skipped) { "Only repeating events can be skipped" }
            itemDao.upsert(current.copy(skipped = skipped))
            reminderDao.forItem(id).also { list -> list.forEach { reminderDao.snooze(it.id, null) } }
        }
        afterCommit(reminderIds = reminders.map { it.id }, cancelFirst = reminders.mapTo(hashSetOf()) { it.id })
    }

    suspend fun setPaid(id: Long, paid: Boolean) = changes.withLock {
        requirePlannerEvent(id)
        changePayment(id, paid)
    }

    // Called only while holding changes. A payment and its reminder cleanup commit together.
    private suspend fun changePayment(id: Long, paid: Boolean): Boolean {
        val change = db.withTransaction {
            val current = itemDao.byId(id) ?: return@withTransaction null
            if (current.category != "Bills" || current.paid == paid) return@withTransaction null
            val reminders = reminderDao.forItem(id)
            val updated = Payments.setPaid(current, paid)
            Payments.validate(updated)
            itemDao.upsert(updated)
            reminders.forEach { reminderDao.snooze(it.id, null) }
            PendingPayment(before = current, paid = paid, remindersBefore = reminders,
                remindersAfter = reminderDao.forItem(id), paymentsAfter = updated.payments)
        } ?: return false
        recordPayment(change)
        tellBudget(change.before, change.before.copy(paid = change.paid, payments = change.paymentsAfter))
        val ids = change.remindersAfter.map { it.id }
        afterCommit(reminderIds = ids, cancelFirst = ids.toSet())
        return true
    }

    suspend fun payFromReminder(id: Long, expectedToken: String): Boolean = changes.withLock {
        val reminder = reminderDao.byId(id) ?: return@withLock false
        val item = itemDao.byId(reminder.itemId) ?: return@withLock false
        if (item.category != "Bills" || item.paid || item.skipped || billReminderToken(item, reminder) != expectedToken)
            return@withLock false
        changePayment(item.id, true)
    }

    suspend fun undoPayment(token: String): Boolean = changes.withLock {
        // Expired or gone, its bar goes too: otherwise it would stay first in the queue (R-4).
        val change = paymentUndo.take(token, System.currentTimeMillis()) ?: run {
            _pendingPayments.value = _pendingPayments.value.filterNot { it.token == token }
            return@withLock false
        }
        val ids = mutableListOf<Long>()
        var undone: ItineraryItem? = null
        val restored = db.withTransaction {
            val current = itemDao.byId(change.before.id)
            if (current == null || current.category != "Bills" || current.paid != change.paid || current.payments != change.paymentsAfter) false
            else {
                itemDao.upsert(current.copy(paid = change.before.paid, payments = change.before.payments))
                undone = current
                reminderDao.forItem(current.id).forEach { reminder ->
                    ids += reminder.id
                    // Keep reminders edited or snoozed since payment; restore only unchanged ones.
                    if (change.remindersAfter.any { it == reminder }) {
                        val old = change.remindersBefore.find { it.id == reminder.id }?.snoozedUntil
                        reminderDao.snooze(reminder.id, old?.takeIf { it > System.currentTimeMillis() })
                    }
                }
                true
            }
        }
        _pendingPayments.value = _pendingPayments.value.filterNot { it.token == token }
        undone?.takeIf { restored }?.let { tellBudget(it, it.copy(paid = change.before.paid, payments = change.before.payments)) }
        if (restored) afterCommit(reminderIds = ids, cancelFirst = ids.toSet())
        restored
    }

    suspend fun finishPayment(token: String) = changes.withLock {
        _pendingPayments.value = _pendingPayments.value.filterNot { it.token == token }
    }

    suspend fun snoozeReminder(id: Long, until: Long, expectedToken: String? = null): Boolean = changes.withLock {
        require(until > System.currentTimeMillis())
        val reminder = reminderDao.byId(id) ?: return@withLock false
        val item = itemDao.byId(reminder.itemId) ?: return@withLock false
        if (item.paid || item.skipped || expectedToken != null && eventReminderToken(item, reminder) != expectedToken) return@withLock false
        reminderDao.snooze(id, until)
        afterCommit(reminderIds = listOf(id), cancelFirst = setOf(id), notify = false)
        true
    }

    // R18-A2: a reminder waits for the lock (an edit, payment or delete going on) only so long: Android lets it start
    // ringing for about 10 s after the alarm, and kills a receiver that takes a minute. Past that it's checked and shown
    // without the lock; its delivery record still keeps it from ringing twice.
    private suspend fun <T> forDelivery(block: suspend () -> T): T {
        // Bug hunt 19: tried, not lock() under a timeout, which can take the lock just as it times out and never give it back.
        val deadline = System.nanoTime() + DELIVERY_LOCK_WAIT_MS * 1_000_000
        var locked = changes.tryLock()
        while (!locked && System.nanoTime() - deadline < 0) { kotlinx.coroutines.delay(20); locked = changes.tryLock() }
        if (!locked) Log.w("Repository", "Reminder delivered without waiting any longer for a change in progress")
        try { return block() } finally { if (locked) changes.unlock() }
    }

    // Serialize delivery with edits/deletion/payment, and reject an alarm already replaced by a snooze.
    suspend fun deliverReminder(id: Long, trigger: Long, deliver: (ItineraryItem, Reminder) -> Unit) = forDelivery {
        val reminder = reminderDao.byId(id) ?: return@forDelivery
        val item = itemDao.byId(reminder.itemId) ?: return@forDelivery
        val expected = reminder.snoozedUntil ?: reminderTrigger(item.date, item.startTime, reminder).toInstant().toEpochMilli()
        if (item.paid || item.skipped || deliveredAlready(item, reminder) ||
            scheduler.wasDelivered(MissedReminders.eventKey(id), expected) ||
            !ReminderDeliveries.accepts(trigger, expected, reminder.snoozedUntil != null, System.currentTimeMillis())) return@forDelivery
        deliver(item, reminder)
        ReminderDeliveries.key(item, reminder)?.let { reminderDao.recordDelivery(ReminderDelivery(reminder.id, it)) }
            ?: scheduler.markDelivered(MissedReminders.eventKey(id), expected)
    }

    suspend fun deliverTaskReminder(id: String, trigger: Long, deliver: (PlannerTask) -> Unit) = forDelivery {
        val task = taskDao.byId(id) ?: return@forDelivery
        if (!task.done && task.activeReminderAt == trigger && trigger <= System.currentTimeMillis() &&
            !scheduler.wasDelivered(MissedReminders.taskKey(id), trigger)) {
            deliver(task)
            scheduler.markDelivered(MissedReminders.taskKey(id), trigger)
        }
    }

    // Everything in the database, read in one go so the pieces agree with each other (for backups). Recently deleted
    // bundles come with their JSON, wherever it is kept.
    // Materialize backup files while mutations and their file cleanup are excluded.
    suspend fun <T> withSnapshotFiles(block: suspend (DataSnapshot) -> T): T = changes.withLock { block(snapshot()) }

    // R18-D1: a backup reads its snapshot under the lock but its files outside it, so a big export no longer holds up
    // saves and reminders (past ~10 s a reminder couldn't start ringing; R18-A2). [prepare] runs under the lock and names
    // the files [use] reads; until [use] is done, cleanup keeps them and deletes the ones it let go of afterwards.
    suspend fun <T, R> withPinnedFiles(prepare: suspend (DataSnapshot) -> Pair<T, Collection<String>>, use: suspend (T) -> R): R {
        val (data, names) = changes.withLock { prepare(snapshot()).also { (_, names) -> pin(names) } }
        try { return use(data) } finally { withContext(NonCancellable) { unpin(names) } }
    }
    private val pinned = HashMap<String, Int>()
    // Unused files cleanup kept only because a backup was reading them.
    private val deferredFiles = HashSet<String>()
    private fun pin(names: Collection<String>) = synchronized(pinned) { names.distinct().forEach { pinned[it] = (pinned[it] ?: 0) + 1 } }
    private suspend fun unpin(names: Collection<String>) {
        val free = synchronized(pinned) {
            names.distinct().forEach { n -> val left = (pinned[n] ?: 1) - 1; if (left <= 0) pinned.remove(n) else pinned[n] = left }
            deferredFiles.filter { it !in pinned }.also { deferredFiles.removeAll(it.toSet()) }
        }
        if (free.isNotEmpty()) releaseTaskFiles(free)
    }

    suspend fun snapshot(): DataSnapshot = db.withTransaction {
        DataSnapshot(tripDao.all(), itemDao.all(), reminderDao.all(), attachmentDao.all(), db.templateDao().all(),
            // H17-D3: a bundle whose file can't be read is left out (logged) rather than failing every backup.
            deletedDao.all().mapNotNull { entry ->
                try { entry.copy(payload = payloads.read(entry.payload)) }
                catch (e: Exception) { Log.w("Repository", "Left unreadable Recently deleted entry ${entry.id} out", e); null }
            }, taskDao.all(), noteDao.all())
    }

    // Throws away all current data and puts [data] in its place, keeping its ids. The attachment
    // files named in [data] must already be in the store. Old files nothing refers to any more are removed.
    suspend fun replaceAll(data: DataSnapshot) = changes.withLock { replaceAllLocked(data) }

    // Restore owns both newly installed and existing files until their database references commit.
    // Waits for ownership remain cancellable; once copying starts, finish or roll back under the same lock.
    internal suspend fun restoreWithFiles(data: DataSnapshot, installFiles: suspend () -> Unit, rollbackFiles: () -> Unit,
                                          afterReplace: suspend () -> Unit = {}) =
        changes.withLock {
            withContext(NonCancellable) {
                var committed = false
                var maintenanceFailure: Throwable? = null
                try {
                    installFiles()
                    replaceAllLocked(data) { committed = true }
                } catch (e: Throwable) {
                    if (!committed) {
                        rollbackFiles()
                        throw e
                    }
                    maintenanceFailure = e
                }
                // Committed files stay owned even if cleanup failed; finish settings and sync records regardless.
                afterReplace()
                if (maintenanceFailure != null) {
                    Log.w("Repository", "Restored backup; cleanup failed", maintenanceFailure)
                    throw BackupException("The backup data was restored, but some cleanup did not finish.")
                }
            }
        }

    private suspend fun replaceAllLocked(data: DataSnapshot, committed: () -> Unit = {}) {
        data.tasks.forEach(Tasks::validate)
        TaskDependencies.validateGraph(data.tasks)
        require(data.tasks.map { it.id }.distinct().size == data.tasks.size)
        data.notes.forEach(Notes::validate)
        require(data.notes.map { it.id }.distinct().size == data.notes.size)
        val oldTasks = taskDao.all()
        val oldNotes = noteDao.all()
        val oldReminders = reminderDao.all()
        val oldDeleted = deletedDao.all()
        val oldFiles = oldTasks.flatMap { it.attachments }.map { it.fileName } + oldNotes.flatMap { it.attachments }.map { it.fileName } + attachmentDao.allFileNames() + readableContents(oldDeleted).flatMap { it?.storedAttachments.orEmpty() }.map { it.fileName } + _pendingDeletions.value.flatMap { it.attachments }.map { it.fileName }
        // New files for big bundles; the old ones go once the new data is in.
        val deleted = mutableListOf<DeletedEntry>()
        try {
            data.deleted.forEach { deleted += it.copy(payload = payloads.store(payloads.read(it.payload))) }
            db.withTransaction {
                taskDao.deleteAll()
                taskDao.insertAll(data.tasks)
                noteDao.deleteAll()
                noteDao.insertAll(data.notes)
                deletedDao.deleteAll()
                deletedDao.insertAll(deleted)
                db.templateDao().deleteAll()
                db.templateDao().insertAll(data.templates)
                tripDao.deleteAll()
                tripDao.insertAll(data.trips)
                itemDao.insertAll(data.items)
                reminderDao.insertAll(data.reminders)
                attachmentDao.insertAll(data.attachments)
                // What was sent to Nextcloud is recorded by event id, and the ids now mean other events: the record goes
                // with them, in the same step. The synced calendar is then read before anything new is sent, so its files
                // are linked to events by content, not sent twice (a backup's own record is put back by the restore).
                db.sentDao().deleteAll()
                db.outsideDao().sources().filter { it.sendHere }.forEach { db.outsideDao().updateSource(it.copy(ctag = null, fetchedFor = null)) }
                // The same for tasks (T5): a send before TaskSync.restore/forget would otherwise delete the files of tasks
                // the backup doesn't have; the list is read again before anything new is sent to it.
                db.sentTaskDao().deleteAll()
                // And for notes: the next notes sync links Nextcloud's notes to the restored ones by their words.
                db.sentNoteDao().deleteAll()
                db.outsideDao().sources().filter { it.tasksHere }.forEach { db.outsideDao().updateSource(it.copy(taskCtag = null)) }
            }
        } catch (e: Throwable) {
            dropUncommitted(deleted)
            throw e
        }
        // The transaction has committed: failures below must never remove newly restored attachment files.
        committed()
        oldDeleted.forEach { payloads.delete(it.payload) }
        _pendingDeletions.value = emptyList()
        _pendingMoves.value = emptyList()
        _pendingPayments.value = emptyList()
        paymentUndo.clear()
        afterCommit(oldFiles, (oldReminders + data.reminders).map { it.id },
            cancelFirst = oldReminders.mapTo(hashSetOf()) { it.id }, taskIds = (oldTasks + data.tasks).map { it.id },
            resetTaskIds = oldTasks.mapTo(hashSetOf()) { it.id }, noteIds = (oldNotes + data.notes).map { it.id },
            resetNoteIds = oldNotes.mapTo(hashSetOf()) { it.id })
    }

    // After a reboot, update or time change, and whenever the app opens (the exact-alarm permission may have changed).
    suspend fun rescheduleAllReminders(zone: java.time.ZoneId = java.time.ZoneId.systemDefault()) = changes.withLock {
        // Cleanup is independent maintenance: its failure must not prevent scheduling alarms.
        performFollowUp(linkedMapOf("cleanup:expired" to { purgeExpiredDeleted() }, "reminders:zone" to { followTimeZone(zone) },
            "reminders:reload" to { armReminders() }))
    }

    // A reminder has rung and others wait for a free alarm (AlarmWindow): the window moves on.
    suspend fun refillReminders() = changes.withLock {
        if (scheduler.armHorizon() != null || scheduler.needsArmRefill())
            performFollowUp(linkedMapOf("reminders:reload" to { armReminders() }))
    }

    // Every reminder's alarm, the nearest AlarmWindow.LIMIT armed and the rest waiting.
    private suspend fun armReminders() {
        val reminders = reminderDao.all()
        val items = readIds(reminders.map { it.itemId }, itemDao::byIds).associateBy { it.id }
        val delivered = readIds(reminders.map { it.id }, reminderDao::deliveries).associate { it.reminderId to it.key }
        val tasks = taskDao.all()
        val notes = noteDao.all()
        val triggers = reminders.mapNotNull { r -> items[r.itemId]?.takeIf { !it.paid && !it.skipped && !ReminderDeliveries.delivered(delivered[r.id], it, r) }
            ?.let { MissedReminders.eventKey(r.id) to eventReminderAt(it, r) } } +
            tasks.mapNotNull { t -> t.activeReminderAt?.takeIf { !t.done }?.let { MissedReminders.taskKey(t.id) to it } } +
            notes.mapNotNull { n -> n.activeReminderAt?.let { MissedReminders.noteKey(n.id) to it } }
        scheduler.setArmWindow(triggers.toMap(), System.currentTimeMillis())
        // The latest first, so the alarms of the ones that now wait are freed before nearer ones are set.
        val jobs: List<Pair<Long, () -> Unit>> = reminders.mapNotNull { reminder -> items[reminder.itemId]?.let { item -> eventReminderAt(item, reminder) to {
            if (item.paid || item.skipped) scheduler.cancel(reminder.id)
            else if (!ReminderDeliveries.delivered(delivered[reminder.id], item, reminder)) scheduler.reconcile(item, reminder)
        } } } +
            // Hunt 23: a task done with no reminder, or one two days gone, has had its alarm taken away (completing it
            // cancels it): going through each again (a file read and calls into Android) held the lock for seconds once
            // done tasks piled up (a daily task leaves one a day).
            tasks.filter { t -> !t.done || t.activeReminderAt?.let { it > System.currentTimeMillis() - 2 * 86_400_000L } == true }
                .map { task -> (task.activeReminderAt ?: Long.MAX_VALUE) to { scheduler.scheduleTask(task) } } +
            notes.filter { it.reminderAt != null }.map { note -> note.activeReminderAt!! to { scheduler.scheduleNote(note) } }
        var failure: Exception? = null
        jobs.sortedByDescending { it.first }.forEach { (_, job) -> try { job() } catch (e: Exception) { failure = e } }
        failure?.let { throw it }
        scheduler.lockedAlarmsComplete()
    }

    // Task reminders keep their clock time when the phone's time zone changes, as event reminders do (inTimeZone). The
    // zone they were set in is remembered; the first time there is none, nothing moves.
    private suspend fun followTimeZone(zone: java.time.ZoneId) = withContext(NonCancellable) {
        val from = scheduler.reminderZone()?.let { runCatching { java.time.ZoneId.of(it) }.getOrNull() }
        if (from != null && from != zone) {
            val now = System.currentTimeMillis()
            db.withTransaction {
                taskDao.all().forEach { task -> task.inTimeZone(from, zone, now).let { if (it != task) taskDao.update(it) } }
                noteDao.all().forEach { note -> note.inTimeZone(from, zone, now).let { if (it != note) noteDao.update(it) } }
            }
        }
        scheduler.setReminderZone(zone.id)
    }

    /**
     * After a reboot, or when the app finds alarms [graceMs] late: [post] the [pending] alarms that fell due without
     * ringing; missed event reminders then count as delivered.
     */
    suspend fun deliverMissedReminders(pending: Map<String, Long>, now: Long, graceMs: Long = 0L, post: (List<MissedReminders.Missed>) -> Unit) = changes.withLock {
        val due = MissedReminders.due(pending, now, graceMs).filter { (key, trigger) -> !scheduler.wasDelivered(key, trigger) }
        val events = due.keys.mapNotNull(MissedReminders::eventId).mapNotNull { id ->
            reminderDao.byId(id)?.let { r -> itemDao.byId(r.itemId)?.let { id to (it to r) } } }.toMap()
        val delivered = readIds(events.keys, reminderDao::deliveries).associate { it.reminderId to it.key }
        val tasks = due.keys.mapNotNull(MissedReminders::taskId).mapNotNull { taskDao.byId(it) }.associateBy { it.id }
        val notes = due.keys.mapNotNull(MissedReminders::noteId).mapNotNull { noteDao.byId(it) }.associateBy { it.id }
        val missed = MissedReminders.select(due, events, delivered, tasks, notes)
        post(missed)
        missed.forEach { m ->
            when (m) {
                is MissedReminders.Event -> ReminderDeliveries.key(m.item, m.reminder)?.let { reminderDao.recordDelivery(ReminderDelivery(m.reminder.id, it)) }
                    ?: scheduler.markDelivered(MissedReminders.eventKey(m.reminder.id), m.due)
                is MissedReminders.Task -> scheduler.markDelivered(MissedReminders.taskKey(m.task.id), m.due)
                is MissedReminders.Note -> scheduler.markDelivered(MissedReminders.noteKey(m.note.id), m.due)
            }
        }
    }
}

/**
 * One occurrence [old] of an entire-series save: it takes the edited occurrence's details, moved to [date], but keeps its
 * own paid state, payments, skip and checklist ticks. A bill with live (not reversed) payments also keeps its amount, currency and category:
 * the payments were made against those, and a new amount would un-pay it or leave it paid for more than it costs.
 */
internal fun seriesOccurrence(edited: ItineraryItem, old: ItineraryItem, date: java.time.LocalDate, changeRepeat: Boolean, repeat: RepeatRule): ItineraryItem {
    val moved = edited.startingOn(date).copy(id = old.id, tripId = old.tripId, skipped = old.skipped,
        seriesId = if (changeRepeat && repeat == RepeatRule.NONE) null else old.seriesId,
        repeatRule = if (changeRepeat) repeat.name else old.repeatRule)
    if (old.id == edited.id) return moved
    val own = moved.copy(paid = old.paid, payments = old.payments, checklist = keepChecklistTicks(edited.checklist, old.checklist))
    return if (!Payments.anyLive(old.payments)) own else own.copy(billAmountMinor = old.billAmountMinor, billCurrency = old.billCurrency, category = old.category)
}

/** The edited list (entries added, removed, renamed), each ticked only if the same entry was ticked in [own]. */
internal fun keepChecklistTicks(edited: List<ChecklistEntry>, own: List<ChecklistEntry>): List<ChecklistEntry> {
    val done = own.associate { it.id to it.done }
    return edited.map { it.copy(done = done[it.id] ?: false) }
}

/**
 * An entire-series save reaches each other occurrence's own attachments or reminders as a diff; returns (delete, insert).
 * One removed here goes only where the [same] one is held; one [added] in the [slot] of a removed one (text read into a
 * file, "ring until dismissed" toggled) replaces it only there; any other added one goes everywhere its slot is free.
 */
private fun <T> seriesSiblingChanges(own: List<T>, added: List<T>, removed: List<T>, same: (T, T) -> Boolean, slot: (T, T) -> Boolean): Pair<List<T>, List<T>> {
    val delete = own.filter { o -> removed.any { same(it, o) } }
    val insert = added.filter { a ->
        val replaced = removed.filter { slot(it, a) }
        if (replaced.isEmpty()) own.none { slot(it, a) } else delete.any { o -> replaced.any { same(it, o) } }
    }
    return delete to insert
}

// A file is known by its stored file name; a web link has none (fileName ""), so it is known by its url.
private fun Attachment.seriesKey(): Pair<String, String?> = if (fileName.isEmpty()) "link" to url else "file" to fileName

internal fun seriesSiblingAttachments(own: List<Attachment>, added: List<Attachment>, removed: List<Attachment>) =
    seriesSiblingChanges(own, added, removed, { a, b -> a.seriesKey() == b.seriesKey() }, { a, b -> a.seriesKey() == b.seriesKey() })

internal fun seriesSiblingReminders(own: List<Reminder>, added: List<Reminder>, removed: List<Reminder>) =
    seriesSiblingChanges(own, added, removed, { a, b -> a.scheduleKey == b.scheduleKey && a.sound == b.sound },
        { a, b -> a.scheduleKey == b.scheduleKey })

/**
 * One event reminder's alarm after a commit. It is reconciled, so an alarm that is due but not yet delivered (inexact
 * alarms, Doze, an eastward time-zone change) survives an unrelated save; a changed schedule ([reset]), a deleted
 * reminder or event, and a paid or skipped event cancel it.
 */
internal suspend fun updateReminderAlarm(scheduler: ReminderAlarms, id: Long, event: ItineraryItem?, reminder: Reminder?, reset: Boolean,
                                          delivered: suspend (ItineraryItem, Reminder) -> Boolean) {
    if (event == null || reset) scheduler.cancel(id)
    if (event != null && reminder != null) { if (event.paid || event.skipped) scheduler.cancel(id) else if (!delivered(event, reminder)) scheduler.reconcile(event, reminder) }
}

// R18-A2: how long a reminder waits for a change in progress before it is shown without the lock.
internal const val DELIVERY_LOCK_WAIT_MS = 3_000L
