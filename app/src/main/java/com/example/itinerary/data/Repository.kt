package com.example.itinerary.data

import androidx.room.withTransaction
import android.util.Log
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import com.example.itinerary.reminders.ReminderAlarms
import com.example.itinerary.reminders.MissedReminders
import com.example.itinerary.reminders.AlarmWindow
import com.example.itinerary.reminders.eventReminderAt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

data class PendingDeletion(
    val token: String = UUID.randomUUID().toString(),
    val items: List<ItineraryItem>,
    val attachments: List<Attachment>,
    val reminders: List<Reminder>,
    val tasks: List<PlannerTask> = emptyList(),
)

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
    private val onChanged: () -> Unit = {},
    // A deletion's Undo is no longer on offer (calendar sync may now delete Planner's copy on Nextcloud).
    private val onDeletionFinished: () -> Unit = {},
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

    private val changes = Mutex()

    // Events from other calendars are shown with negative ids and belong to their calendar, not to Planner.
    private fun requirePlannerEvent(id: Long) = require(!OutsideCalendars.isOutside(id)) { OutsideCalendars.READ_ONLY }
    private fun requirePlannerEvents(ids: Collection<Long>) = ids.forEach(::requirePlannerEvent)
    private val _pendingPayments = MutableStateFlow<List<PendingPayment>>(emptyList())
    val pendingPayments = _pendingPayments.asStateFlow()
    // Snackbar dismissal must not invalidate a still-visible notification Undo action.
    private val paymentUndo = mutableMapOf<String, Pair<PendingPayment, Long>>()
    private fun recordPayment(change: PendingPayment) {
        val now = System.currentTimeMillis()
        paymentUndo.entries.removeAll { it.value.first.before.id == change.before.id || it.value.second < now }
        paymentUndo[change.token] = change to (now + 60_000)
        _pendingPayments.value = _pendingPayments.value.filterNot { it.before.id == change.before.id } + change
    }

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
        _maintenanceIssues.value = followUp.keys.mapTo(linkedSetOf()) { it.substringBefore(':') }
    }

    private suspend fun afterCommit(files: Collection<String> = emptyList(), reminderIds: Collection<Long> = emptyList(),
                                    cancelFirst: Set<Long> = emptySet(), notify: Boolean = true, taskIds: Collection<String> = emptyList(),
                                    resetTaskIds: Set<String> = emptySet()) {
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
                if (task == null || id in resetTaskIds) scheduler.cancelTask(id)
                if (task != null) scheduler.scheduleTask(task)
            }
        }
        // A long series can leave too many alarms armed, a deletion too few while later ones wait (AlarmWindow).
        if (reminderIds.isNotEmpty() || taskIds.isNotEmpty()) work["reminders:window"] = {
            if (AlarmWindow.needsRefill(scheduler.armedCount(), scheduler.armHorizon())) armReminders()
        }
        if (notify) work["widget"] = { onChanged() }
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
            } else options.repeat.dates(item.date, options.count)
            val excluded = if (item.id == 0L) emptySet() else members.mapTo(hashSetOf(item.id)) { it.id }
            Bills.duplicates(item, itemDao.billCandidates(item.billAmountMinor, item.billCurrency, dates), dates.toSet(), excluded)
        }
    }

    // Copies and recurring occurrences can share a file. Pending Undo entries also own their files.
    private suspend fun deleteUnusedFiles(candidates: Collection<String>) {
        val keep = attachmentDao.allFileNames().toHashSet()
        taskDao.all().flatMap { it.attachments }.forEach { keep.add(it.fileName) }
        keep.addAll(store.taskDraftFiles())
        deletedDao.all().flatMap { contents(it).storedAttachments }.forEach { keep.add(it.fileName) }
        _pendingDeletions.value.flatMap { it.attachments }.forEach { keep.add(it.fileName) }
        candidates.filter { it.isNotBlank() && it !in keep }.distinct().forEach(store::delete)
    }

    private val tripDao = db.tripDao()
    private val itemDao = db.itemDao()
    private val attachmentDao = db.attachmentDao()
    private val reminderDao = db.reminderDao()
    private val taskDao = db.taskDao()
    val tasks = taskDao.observe()
    suspend fun saveTask(task: PlannerTask, create: Boolean = true) = changes.withLock {
        val clean = Tasks.capText(task.copy(title = task.title.trim(), notes = task.notes.trim()))
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
                resetTaskIds = if (existing?.activeReminderAt != saved?.activeReminderAt) setOf(task.id) else emptySet())
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
    suspend fun deleteTask(id: String) = changes.withLock {
        withContext(NonCancellable) {
            val bundle = archiving { archived ->
                val task = taskDao.byId(id) ?: return@archiving null
                val deleted = PendingDeletion(items = emptyList(), attachments = emptyList(), reminders = emptyList(), tasks = listOf(task))
                archive(deleted, archived, emptyList())
                taskDao.delete(id)
                deleted
            } ?: return@withContext
            _pendingDeletions.value += bundle
            afterCommit(taskIds = listOf(id))
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
            val moved = archiving { archived ->
                val task = taskDao.byId(id)?.takeIf(still) ?: return@archiving false
                archive(PendingDeletion(items = emptyList(), attachments = emptyList(), reminders = emptyList(), tasks = listOf(task)), archived, emptyList())
                taskDao.delete(id)
                true
            }
            if (moved) afterCommit(taskIds = listOf(id))
            moved
        }
    }

    private val deletedDao = db.deletedDao()
    private val payloads = store.deletedPayloads()
    private fun contents(entry: DeletedEntry) = DeletedCodec.decode(payloads.read(entry.payload))
    val recentlyDeleted = deletedDao.observe().map { entries ->
        entries.filter { it.deletedAt > System.currentTimeMillis() - TRASH_RETENTION_MS }
    }

    // A task as saved, for an editor that goes on editing it after Save.
    suspend fun task(id: String): PlannerTask? = taskDao.byId(id)

    // One task as stored, as it changes (null once gone): an open editor notices a sync pull's update, or its deletion.
    fun observeTask(id: String): Flow<PlannerTask?> = taskDao.observe(id).distinctUntilChanged()

    suspend fun eventDetails(id: Long): Triple<ItineraryItem, List<Attachment>, List<Reminder>>? = db.withTransaction {
        itemDao.byId(id)?.let { Triple(it, attachmentDao.forItem(id), reminderDao.forItem(id)) }
    }

    // One event as stored, as it changes (null once gone): an open editor notices a sync pull's update of it.
    fun observeItem(id: Long): Flow<ItineraryItem?> = itemDao.observe(id).distinctUntilChanged()

    private suspend fun archive(bundle: PendingDeletion, archived: MutableList<DeletedEntry>, plans: List<Trip>? = null) {
        val owners = plans ?: readIds(bundle.items.map { it.tripId }, tripDao::byIds).sortedWith(compareBy({ it.sortOrder }, { it.id }))
        val label = bundle.items.firstOrNull()?.title ?: bundle.tasks.firstOrNull()?.title ?: owners.firstOrNull()?.name ?: "Deleted events"
        val count = bundle.items.size + bundle.tasks.size
        val entry = DeletedEntry(id = bundle.token, label = if (count > 1) "$label + ${count - 1}" else label,
            payload = payloads.store(DeletedCodec.encode(DeletedContents(owners, bundle.items, bundle.attachments, bundle.reminders, bundle.tasks))))
        archived += entry
        deletedDao.insert(entry)
    }

    // A transaction that archives: if it doesn't commit, a payload file archive() wrote for it goes again.
    private suspend fun <T> archiving(block: suspend (MutableList<DeletedEntry>) -> T): T {
        val archived = mutableListOf<DeletedEntry>()
        try { return db.withTransaction { block(archived) } }
        catch (e: Throwable) { dropUncommitted(archived); throw e }
    }

    // After a failed transaction: the payload files of [entries] whose rows didn't get saved.
    private suspend fun dropUncommitted(entries: List<DeletedEntry>) = withContext(NonCancellable) {
        runCatching { entries.forEach { if (deletedDao.byId(it.id)?.payload != it.payload) payloads.delete(it.payload) } }
    }

    suspend fun restoreDeleted(id: String) = changes.withLock { restoreDeletedLocked(id) }

    private suspend fun restoreDeletedLocked(id: String) {
        val restoredTasks = mutableListOf<String>()
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
        stored?.let(payloads::delete)
        _pendingDeletions.value = _pendingDeletions.value.filterNot { it.token == id }
        afterCommit(reminderIds = reminderIds, taskIds = restoredTasks)
    }

    suspend fun permanentlyDelete(id: String) = changes.withLock {
        val entry = deletedDao.byId(id)
        val files = entry?.let { contents(it).storedAttachments.map { a -> a.fileName } }.orEmpty()
        deletedDao.delete(id)
        entry?.let { payloads.delete(it.payload) }
        _pendingDeletions.value = _pendingDeletions.value.filterNot { it.token == id }
        afterCommit(files = files, notify = false)
    }

    private suspend fun purgeExpiredDeleted() {
        val expired = deletedDao.all().filter { it.deletedAt <= System.currentTimeMillis() - TRASH_RETENTION_MS }
        if (expired.isEmpty()) return
        val files = expired.flatMap { contents(it).storedAttachments }.map { it.fileName }
        db.withTransaction { expired.forEach { deletedDao.delete(it.id) } }
        expired.forEach { payloads.delete(it.payload) }
        val ids = expired.map { it.id }.toSet()
        _pendingDeletions.value = _pendingDeletions.value.filterNot { it.token in ids }
        afterCommit(files = files, notify = false)
    }

    val trips: Flow<List<Trip>> = tripDao.observeTrips()
    fun trip(id: Long): Flow<Trip?> = tripDao.observeTrip(id)

    // Across every trip, for search.
    val allItems: Flow<List<ItineraryItem>> = itemDao.observeAll()

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
        keep.addAll(store.taskDraftFiles())
            candidates.filter { it.isNotBlank() && it !in keep } to alarms
        }
        afterCommit(files, reminders.map { it.id })
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
        val removedFiles = mutableListOf<String>()
        val scheduled = mutableListOf<Pair<ItineraryItem, List<Reminder>>>()
        val payments = mutableListOf<PendingPayment>()
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
            check(item.id == 0L || original != null) { "This event no longer exists" }
            val owner = if (item.id == 0L && item.tripId == 0L) {
                tripDao.firstId() ?: tripDao.upsert(
                    Trip(name = "Agenda", destination = "", startDate = item.date, endDate = item.date),
                )
            } else item.tripId
            val targets = when {
                item.id == 0L || original?.seriesId == null && options.repeat != RepeatRule.NONE -> {
                    val dates = options.repeat.dates(item.date, options.count)
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
                    changed.forEach { reminderDao.setRing(it.id, it.ringUntilDismissed) }
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

    suspend fun deleteWithUndo(item: ItineraryItem, entireSeries: Boolean = false) = changes.withLock {
        requirePlannerEvent(item.id)
        val deleted = archiving { archived ->
            val current = itemDao.byId(item.id) ?: return@archiving null
            val selected = if (entireSeries && current.seriesId != null) {
                itemDao.forSeries(current.seriesId).sortedBy { it.id }
            } else listOf(current)
            val ids = selected.mapTo(HashSet()) { it.id }
            val bundle = PendingDeletion(items = selected,
                attachments = readIds(ids, attachmentDao::forItems).sortedBy { it.id },
                reminders = readIds(ids, reminderDao::forItems).sortedBy { it.id })
            archive(bundle, archived)
            selected.forEach { itemDao.delete(it) }
            bundle
        } ?: return@withLock
        _pendingDeletions.value += deleted
        afterCommit(reminderIds = deleted.reminders.map { it.id })
    }

    // Delete exactly these occurrences together, with one Undo bundle for the entire selection.
    // Selected events and tasks go together: one Undo, one entry in Recently deleted.
    suspend fun deleteEventsWithUndo(ids: Set<Long>, taskIds: Set<String> = emptySet()) = changes.withLock {
        requirePlannerEvents(ids)
        withContext(NonCancellable) {
            val deleted = archiving { archived ->
                val selected = readIds(ids, itemDao::byIds).sortedBy { it.id }
                val tasks = taskIds.mapNotNull { taskDao.byId(it) }.sortedBy { it.id }
                if (selected.isEmpty() && tasks.isEmpty()) return@archiving null
                val selectedIds = selected.mapTo(hashSetOf()) { it.id }
                val bundle = PendingDeletion(items = selected,
                    attachments = readIds(selectedIds, attachmentDao::forItems).sortedBy { it.id },
                    reminders = readIds(selectedIds, reminderDao::forItems).sortedBy { it.id }, tasks = tasks)
                archive(bundle, archived)
                selected.forEach { itemDao.delete(it) }
                tasks.forEach { taskDao.delete(it.id) }
                bundle
            } ?: return@withContext
            _pendingDeletions.value += deleted
            afterCommit(reminderIds = deleted.reminders.map { it.id }, taskIds = deleted.tasks.map { it.id })
        }
    }

    // Two-way calendar sync: events deleted on Nextcloud go to Recently deleted (restorable there), without the Undo
    // message a deletion in Planner shows. Only those for which [only] holds as they are at that moment (one edited in
    // Planner meanwhile stays); returns the ids moved.
    suspend fun archiveEvents(ids: Set<Long>, only: (ItineraryItem) -> Boolean = { true }): Set<Long> = changes.withLock {
        withContext(NonCancellable) {
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
            moved
        }
    }

    suspend fun undoDeletion(token: String) = changes.withLock {
        restoreDeletedLocked(token)
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
        val (change, expires) = paymentUndo.remove(token) ?: return@withLock false
        if (expires < System.currentTimeMillis()) {
            _pendingPayments.value = _pendingPayments.value.filterNot { it.token == token }
            return@withLock false
        }
        val ids = mutableListOf<Long>()
        val restored = db.withTransaction {
            val current = itemDao.byId(change.before.id)
            if (current == null || current.category != "Bills" || current.paid != change.paid || current.payments != change.paymentsAfter) false
            else {
                itemDao.upsert(current.copy(paid = change.before.paid, payments = change.before.payments))
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

    // Serialize delivery with edits/deletion/payment, and reject an alarm already replaced by a snooze.
    suspend fun deliverReminder(id: Long, trigger: Long, deliver: (ItineraryItem, Reminder) -> Unit) = changes.withLock {
        val reminder = reminderDao.byId(id) ?: return@withLock
        val item = itemDao.byId(reminder.itemId) ?: return@withLock
        val expected = reminder.snoozedUntil ?: reminderTrigger(item.date, item.startTime, reminder.offsetMinutes).toInstant().toEpochMilli()
        if (item.paid || item.skipped || !ReminderDeliveries.accepts(trigger, expected, reminder.snoozedUntil != null, System.currentTimeMillis())) return@withLock
        deliver(item, reminder)
        ReminderDeliveries.key(item, reminder)?.let { reminderDao.recordDelivery(ReminderDelivery(reminder.id, it)) }
            ?: scheduler.markDelivered(MissedReminders.eventKey(id), expected)
    }

    suspend fun deliverTaskReminder(id: String, trigger: Long, deliver: (PlannerTask) -> Unit) = changes.withLock {
        val task = taskDao.byId(id) ?: return@withLock
        if (!task.done && task.activeReminderAt == trigger && trigger <= System.currentTimeMillis()) {
            deliver(task)
            scheduler.markDelivered(MissedReminders.taskKey(id), trigger)
        }
    }

    // Everything in the database, read in one go so the pieces agree with each other (for backups). Recently deleted
    // bundles come with their JSON, wherever it is kept.
    suspend fun snapshot(): DataSnapshot = db.withTransaction {
        DataSnapshot(tripDao.all(), itemDao.all(), reminderDao.all(), attachmentDao.all(), db.templateDao().all(),
            deletedDao.all().map { it.copy(payload = payloads.read(it.payload)) }, taskDao.all())
    }

    // Throws away all current data and puts [data] in its place, keeping its ids. The attachment
    // files named in [data] must already be in the store. Old files nothing refers to any more are removed.
    suspend fun replaceAll(data: DataSnapshot) = changes.withLock {
        data.tasks.forEach(Tasks::validate)
        TaskDependencies.validateGraph(data.tasks)
        require(data.tasks.map { it.id }.distinct().size == data.tasks.size)
        val oldTasks = taskDao.all()
        val oldReminders = reminderDao.all()
        val oldDeleted = deletedDao.all()
        val oldFiles = oldTasks.flatMap { it.attachments }.map { it.fileName } + attachmentDao.allFileNames() + oldDeleted.flatMap { contents(it).storedAttachments }.map { it.fileName } + _pendingDeletions.value.flatMap { it.attachments }.map { it.fileName }
        // New files for big bundles; the old ones go once the new data is in.
        val deleted = mutableListOf<DeletedEntry>()
        try {
            data.deleted.forEach { deleted += it.copy(payload = payloads.store(payloads.read(it.payload))) }
            db.withTransaction {
                taskDao.deleteAll()
                taskDao.insertAll(data.tasks)
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
                db.outsideDao().sources().filter { it.tasksHere }.forEach { db.outsideDao().updateSource(it.copy(taskCtag = null)) }
            }
        } catch (e: Throwable) {
            dropUncommitted(deleted)
            throw e
        }
        oldDeleted.forEach { payloads.delete(it.payload) }
        _pendingDeletions.value = emptyList()
        _pendingMoves.value = emptyList()
        _pendingPayments.value = emptyList()
        paymentUndo.clear()
        afterCommit(oldFiles, (oldReminders + data.reminders).map { it.id },
            cancelFirst = oldReminders.mapTo(hashSetOf()) { it.id }, taskIds = (oldTasks + data.tasks).map { it.id },
            resetTaskIds = oldTasks.mapTo(hashSetOf()) { it.id })
    }

    // After a reboot, update or time change, and whenever the app opens (the exact-alarm permission may have changed).
    suspend fun rescheduleAllReminders(zone: java.time.ZoneId = java.time.ZoneId.systemDefault()) = changes.withLock {
        // Cleanup is independent maintenance: its failure must not prevent scheduling alarms.
        performFollowUp(linkedMapOf("cleanup:expired" to { purgeExpiredDeleted() }, "reminders:zone" to { followTimeZone(zone) },
            "reminders:reload" to { armReminders() }))
    }

    // A reminder has rung and others wait for a free alarm (AlarmWindow): the window moves on.
    suspend fun refillReminders() = changes.withLock {
        if (scheduler.armHorizon() != null) performFollowUp(linkedMapOf("reminders:reload" to { armReminders() }))
    }

    // Every reminder's alarm, the nearest AlarmWindow.LIMIT armed and the rest waiting.
    private suspend fun armReminders() {
        val reminders = reminderDao.all()
        val items = readIds(reminders.map { it.itemId }, itemDao::byIds).associateBy { it.id }
        val delivered = readIds(reminders.map { it.id }, reminderDao::deliveries).associate { it.reminderId to it.key }
        val tasks = taskDao.all()
        val triggers = reminders.mapNotNull { r -> items[r.itemId]?.takeIf { !it.paid && !it.skipped && !ReminderDeliveries.delivered(delivered[r.id], it, r) }
            ?.let { eventReminderAt(it, r) } } + tasks.mapNotNull { t -> t.activeReminderAt?.takeIf { !t.done } }
        scheduler.setArmHorizon(AlarmWindow.horizon(triggers, System.currentTimeMillis()))
        // The latest first, so the alarms of the ones that now wait are freed before nearer ones are set.
        val jobs: List<Pair<Long, () -> Unit>> = reminders.mapNotNull { reminder -> items[reminder.itemId]?.let { item -> eventReminderAt(item, reminder) to {
            if (item.paid || item.skipped) scheduler.cancel(reminder.id)
            else if (!ReminderDeliveries.delivered(delivered[reminder.id], item, reminder)) scheduler.reconcile(item, reminder)
        } } } + tasks.map { task -> (task.activeReminderAt ?: Long.MAX_VALUE) to { scheduler.scheduleTask(task) } }
        var failure: Exception? = null
        jobs.sortedByDescending { it.first }.forEach { (_, job) -> try { job() } catch (e: Exception) { failure = e } }
        failure?.let { throw it }
    }

    // Task reminders keep their clock time when the phone's time zone changes, as event reminders do (inTimeZone). The
    // zone they were set in is remembered; the first time there is none, nothing moves.
    private suspend fun followTimeZone(zone: java.time.ZoneId) = withContext(NonCancellable) {
        val from = scheduler.reminderZone()?.let { runCatching { java.time.ZoneId.of(it) }.getOrNull() }
        if (from != null && from != zone) {
            val now = System.currentTimeMillis()
            db.withTransaction {
                taskDao.all().forEach { task -> task.inTimeZone(from, zone, now).let { if (it != task) taskDao.update(it) } }
            }
        }
        scheduler.setReminderZone(zone.id)
    }

    /**
     * After a reboot, or when the app finds alarms [graceMs] late: [post] the [pending] alarms that fell due without
     * ringing; missed event reminders then count as delivered.
     */
    suspend fun deliverMissedReminders(pending: Map<String, Long>, now: Long, graceMs: Long = 0L, post: (List<MissedReminders.Missed>) -> Unit) = changes.withLock {
        val due = MissedReminders.due(pending, now, graceMs)
        val events = due.keys.mapNotNull(MissedReminders::eventId).mapNotNull { id ->
            reminderDao.byId(id)?.let { r -> itemDao.byId(r.itemId)?.let { id to (it to r) } } }.toMap()
        val delivered = readIds(events.keys, reminderDao::deliveries).associate { it.reminderId to it.key }
        val tasks = due.keys.mapNotNull(MissedReminders::taskId).mapNotNull { taskDao.byId(it) }.associateBy { it.id }
        val missed = MissedReminders.select(due, events, delivered, tasks)
        post(missed)
        missed.forEach { m ->
            when (m) {
                is MissedReminders.Event -> ReminderDeliveries.key(m.item, m.reminder)?.let { reminderDao.recordDelivery(ReminderDelivery(m.reminder.id, it)) }
                    ?: scheduler.markDelivered(MissedReminders.eventKey(m.reminder.id), m.due)
                is MissedReminders.Task -> scheduler.markDelivered(MissedReminders.taskKey(m.task.id), m.due)
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
    seriesSiblingChanges(own, added, removed, { a, b -> a.offsetMinutes == b.offsetMinutes && a.ringUntilDismissed == b.ringUntilDismissed },
        { a, b -> a.offsetMinutes == b.offsetMinutes })

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
