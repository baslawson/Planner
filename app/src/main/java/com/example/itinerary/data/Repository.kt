package com.example.itinerary.data

import androidx.room.withTransaction
import android.util.Log
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import com.example.itinerary.reminders.ReminderAlarms
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
)

class Repository(
    private val db: AppDatabase,
    private val store: AttachmentStore,
    private val scheduler: ReminderAlarms,
    private val onChanged: () -> Unit = {},
) {
    // Stay below SQLite's bind-parameter limit, including on older Android versions.
    private suspend fun <T> readIds(ids: Collection<Long>, query: suspend (List<Long>) -> List<T>): List<T> {
        val result = mutableListOf<T>()
        for (chunk in ids.distinct().chunked(400)) result.addAll(query(chunk))
        return result
    }

    suspend fun widgetEvents(day: java.time.LocalDate): List<ItineraryItem> =
        eventsOnDay(itemDao.dayCandidates(day, day.minusDays(1)), day)

    suspend fun widgetTasks(day: java.time.LocalDate): List<PlannerTask> =
        taskDao.all().filter { !it.done && it.dueDate != null && it.dueDate <= day }
            .sortedWith(compareBy<PlannerTask> { it.dueDate }.then(Tasks.order))

    private val changes = Mutex()
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
                if (event == null || id in cancelFirst) scheduler.cancel(id)
                if (event != null) { if (event.paid || event.skipped) scheduler.cancel(id) else scheduler.schedule(event, reminder) }
            }
        }
        taskIds.distinct().forEach { id ->
            work["reminders:task:$id"] = {
                val task = taskDao.byId(id)
                if (task == null || id in resetTaskIds) scheduler.cancelTask(id)
                if (task != null) scheduler.scheduleTask(task)
            }
        }
        if (notify) work["widget"] = { onChanged() }
        performFollowUp(work)
    }

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
        deletedDao.all().flatMap { DeletedCodec.decode(it.payload).storedAttachments }.forEach { keep.add(it.fileName) }
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
        val clean = task.copy(title = task.title.trim(), notes = task.notes.trim())
        Tasks.validate(clean)
        val allTasks = taskDao.all()
        val oldIds = allTasks.find { it.id == clean.id }?.prerequisiteIds.orEmpty()
        require(clean.prerequisiteIds.all { id -> id in oldIds || allTasks.any { it.id == id } }) { "A prerequisite was deleted. Remove it or restore the task." }
        TaskDependencies.validateGraph(allTasks.filterNot { it.id == clean.id } + clean)
        withContext(NonCancellable) {
            val existing = taskDao.byId(task.id)
            if (create) {
                // Retrying a save after activity/process recreation must not create another task.
                if (existing == null) taskDao.insert(clean)
                else check(existing == clean) { "This task was already saved and changed" }
            } else {
                check(existing != null) { "This task no longer exists" }
                taskDao.update(clean.copy(done = existing.done, nextTaskId = existing.nextTaskId))
            }
            afterCommit(files = existing?.attachments.orEmpty().map { it.fileName }, taskIds = listOf(task.id),
                resetTaskIds = if (existing?.reminderAt != clean.reminderAt) setOf(task.id) else emptySet())
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
            taskDao.update(task.copy(done = done, nextTaskId = next?.id ?: task.nextTaskId))
            listOfNotNull(id, next?.id)
        }
        afterCommit(taskIds = changed)
    }
    suspend fun actOnTaskReminder(id: String, trigger: Long, snoozeUntil: Long? = null): Boolean = changes.withLock {
        val task = taskDao.byId(id) ?: return@withLock false
        if (task.done || task.reminderAt != trigger || trigger > System.currentTimeMillis()) return@withLock false
        if (snoozeUntil == null) setTaskDoneLocked(id, true)
        else {
            require(snoozeUntil > System.currentTimeMillis())
            withContext(NonCancellable) {
                taskDao.update(task.copy(reminderAt = snoozeUntil))
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
            val bundle = db.withTransaction {
                val task = taskDao.byId(id) ?: return@withTransaction null
                val deleted = PendingDeletion(items = emptyList(), attachments = emptyList(), reminders = emptyList(), tasks = listOf(task))
                archive(deleted, emptyList())
                taskDao.delete(id)
                deleted
            } ?: return@withContext
            _pendingDeletions.value += bundle
            afterCommit(taskIds = listOf(id))
        }
    }

    private val deletedDao = db.deletedDao()
    val recentlyDeleted = deletedDao.observe().map { entries ->
        entries.filter { it.deletedAt > System.currentTimeMillis() - TRASH_RETENTION_MS }
    }

    suspend fun eventDetails(id: Long): Triple<ItineraryItem, List<Attachment>, List<Reminder>>? = db.withTransaction {
        itemDao.byId(id)?.let { Triple(it, attachmentDao.forItem(id), reminderDao.forItem(id)) }
    }

    private suspend fun archive(bundle: PendingDeletion, plans: List<Trip>? = null) {
        val owners = plans ?: readIds(bundle.items.map { it.tripId }, tripDao::byIds).sortedWith(compareBy({ it.sortOrder }, { it.id }))
        val label = bundle.items.firstOrNull()?.title ?: bundle.tasks.firstOrNull()?.title ?: owners.firstOrNull()?.name ?: "Deleted events"
        deletedDao.insert(DeletedEntry(id = bundle.token, label = if (bundle.items.size > 1) "$label + ${bundle.items.size - 1}" else label,
            payload = DeletedCodec.encode(DeletedContents(owners, bundle.items, bundle.attachments, bundle.reminders, bundle.tasks))))
    }

    suspend fun restoreDeleted(id: String) = changes.withLock { restoreDeletedLocked(id) }

    private suspend fun restoreDeletedLocked(id: String) {
        val restoredTasks = mutableListOf<String>()
        val reminderIds = db.withTransaction {
            val entry = deletedDao.byId(id) ?: return@withTransaction emptyList<Long>()
            check(entry.deletedAt > System.currentTimeMillis() - TRASH_RETENTION_MS) { "The recovery period has expired" }
            val data = DeletedCodec.decode(entry.payload)
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
        _pendingDeletions.value = _pendingDeletions.value.filterNot { it.token == id }
        afterCommit(reminderIds = reminderIds, taskIds = restoredTasks)
    }

    suspend fun permanentlyDelete(id: String) = changes.withLock {
        val files = deletedDao.byId(id)?.let { DeletedCodec.decode(it.payload).storedAttachments.map { a -> a.fileName } }.orEmpty()
        deletedDao.delete(id)
        _pendingDeletions.value = _pendingDeletions.value.filterNot { it.token == id }
        afterCommit(files = files, notify = false)
    }

    private suspend fun purgeExpiredDeleted() {
        val expired = deletedDao.all().filter { it.deletedAt <= System.currentTimeMillis() - TRASH_RETENTION_MS }
        if (expired.isEmpty()) return
        val files = expired.flatMap { DeletedCodec.decode(it.payload).storedAttachments }.map { it.fileName }
        db.withTransaction { expired.forEach { deletedDao.delete(it.id) } }
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
        val (files, reminders) = db.withTransaction {
            val selected = tripDao.all().filter { current -> trips.any { it.id == current.id } }
            if (selected.isEmpty()) return@withTransaction emptyList<String>() to emptyList<Reminder>()
            val candidates = selected.flatMap { attachmentDao.fileNamesForTrip(it.id) }.toSet()
            val alarms = selected.flatMap { reminderDao.forTrip(it.id) }
            val selectedIds = selected.map { it.id }.toSet()
            val events = itemDao.all().filter { it.tripId in selectedIds }
            val eventIds = events.map { it.id }.toSet()
            archive(PendingDeletion(items = events, attachments = attachmentDao.all().filter { it.itemId in eventIds }, reminders = alarms), selected)
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
    ) = changes.withLock {
        ChecklistCodec.validate(item.checklist)
        Bills.validate(item.billAmountMinor, item.billCurrency)
        Payments.validate(item.payments)
        require(item.payments.none { !it.reversed } || item.billAmountMinor != null)
        require(item.bufferBeforeMinutes in 0..1440 && item.bufferAfterMinutes in 0..1440)
        require(item.durationMinutes == null || item.startTime != null && item.durationMinutes in 1..1440)
        MultiDay.validate(item)
        val cancelled = mutableListOf<Reminder>()
        val resetReminders = mutableSetOf<Long>()
        val removedFiles = mutableListOf<String>()
        val scheduled = mutableListOf<Pair<ItineraryItem, List<Reminder>>>()
        val payments = mutableListOf<PendingPayment>()
        db.withTransaction {
            if (options.draftToken != null && itemDao.hasDraftToken(options.draftToken)) return@withTransaction
            if (item.id == 0L && item.linkedTaskId != null) {
                require(taskDao.byId(item.linkedTaskId) != null) { "The linked task was deleted. Restore it before scheduling." }
                require(item.startTime != null && item.durationMinutes != null && item.category != "Bills")
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
                        repeatRule = if (series == null) "NONE" else options.repeat.name) }
                }
                options.entireSeries && original?.seriesId != null -> {
                    val shift = ChronoUnit.DAYS.between(original.date, item.date)
                    val dates = if (options.changeRepeat && options.repeat != RepeatRule.NONE)
                        options.repeat.dates(members.first().date.plusDays(shift), members.size) else members.map { it.date.plusDays(shift) }
                    members.mapIndexed { index, old ->
                        item.startingOn(dates[index]).copy(id = old.id, tripId = old.tripId, paid = if (old.id == item.id) item.paid else old.paid,
                            payments = if (old.id == item.id) item.payments else old.payments, skipped = old.skipped,
                            seriesId = if (options.changeRepeat && options.repeat == RepeatRule.NONE) null else old.seriesId,
                            repeatRule = if (options.changeRepeat) options.repeat.name else old.repeatRule)
                    }
                }
                else -> listOf(item)
            }
            val replaceChildren = options.entireSeries && original?.seriesId != null || item.id != 0L && targets.size > 1
            val attachmentsByItem = if (replaceChildren)
                readIds(targets.filter { it.id != 0L }.map { it.id }, attachmentDao::forItems).groupBy { it.itemId }
                else emptyMap()
            val selectedAttachments = attachmentsByItem[item.id].orEmpty().filter { it !in removed } + added
            val selectedReminders = if (replaceChildren) {
                reminderDao.forItem(item.id).filter { it !in removedReminders } + addedReminders
            } else emptyList()
            for (target in targets) {
                val paymentTotal = Payments.total(target.payments)
                require(target.category != "Bills" || paymentTotal == 0L || target.billAmountMinor != null && paymentTotal <= target.billAmountMinor)
                val previous = previousById[target.id]
                require(previous == null || previous.payments.isEmpty() || target.category != "Bills" || previous.billCurrency == target.billCurrency)
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
                if (replaceChildren) {
                    val oldAttachments = attachmentsByItem[saved.id].orEmpty()
                    oldAttachments.forEach { attachmentDao.delete(it); removedFiles.add(it.fileName) }
                    val oldReminders = reminderDao.forItem(saved.id)
                    oldReminders.forEach { reminderDao.delete(it); cancelled.add(it) }
                    selectedAttachments.forEach { attachmentDao.insert(it.copy(id = 0, itemId = saved.id)) }
                    selectedReminders.forEach { reminderDao.insert(it.copy(id = 0, itemId = saved.id, snoozedUntil = null)) }
                } else {
                    added.forEach { attachmentDao.insert(it.copy(id = 0, itemId = saved.id)) }
                    removed.filter { it.itemId == saved.id }.forEach { attachmentDao.delete(it); removedFiles.add(it.fileName) }
                    addedReminders.forEach { reminderDao.insert(it.copy(id = 0, itemId = saved.id)) }
                    removedReminders.filter { it.itemId == saved.id }.forEach { reminderDao.delete(it); cancelled.add(it) }
                }
                if (previous?.paid != saved.paid) {
                    reminderDao.forItem(saved.id).forEach { reminderDao.snooze(it.id, null) }
                }
                val savedReminders = reminderDao.forItem(saved.id)
                if (original != null && saved.id == original.id && original.category == "Bills" && saved.category == "Bills" && original.paid != saved.paid)
                    payments += PendingPayment(before = original, paid = saved.paid, remindersBefore = originalReminders, remindersAfter = savedReminders, paymentsAfter = saved.payments)
                scheduled.add(saved to savedReminders)
            }
        }
        payments.forEach(::recordPayment)
        afterCommit(removedFiles, cancelled.map { it.id } + scheduled.flatMap { it.second }.map { it.id }, cancelFirst = resetReminders)
    }

    // Every event in [names], in any plan, becomes Other.
    suspend fun removeCategories(names: Collection<String>) = changes.withLock {
        if (names.isEmpty()) return@withLock
        val reminders = db.withTransaction {
            val affected = itemDao.all().filter { it.category in names }.mapTo(hashSetOf()) { it.id }
            itemDao.replaceCategories(names.toList(), Categories.OTHER)
            reminderDao.all().filter { it.itemId in affected }
        }
        afterCommit(reminderIds = reminders.map { it.id }, cancelFirst = reminders.mapTo(hashSetOf()) { it.id })
    }

    suspend fun deleteItem(item: ItineraryItem) = deleteWithUndo(item)

    suspend fun deleteWithUndo(item: ItineraryItem, entireSeries: Boolean = false) = changes.withLock {
        val deleted = db.withTransaction {
            val current = itemDao.byId(item.id) ?: return@withTransaction null
            val selected = if (entireSeries && current.seriesId != null) {
                itemDao.forSeries(current.seriesId).sortedBy { it.id }
            } else listOf(current)
            val ids = selected.mapTo(HashSet()) { it.id }
            val bundle = PendingDeletion(items = selected,
                attachments = readIds(ids, attachmentDao::forItems).sortedBy { it.id },
                reminders = readIds(ids, reminderDao::forItems).sortedBy { it.id })
            archive(bundle)
            selected.forEach { itemDao.delete(it) }
            bundle
        } ?: return@withLock
        _pendingDeletions.value += deleted
        afterCommit(reminderIds = deleted.reminders.map { it.id })
    }

    // Delete exactly these occurrences together, with one Undo bundle for the entire selection.
    suspend fun deleteEventsWithUndo(ids: Set<Long>) = changes.withLock {
        withContext(NonCancellable) {
            val deleted = db.withTransaction {
                val selected = readIds(ids, itemDao::byIds).sortedBy { it.id }
                if (selected.isEmpty()) return@withTransaction null
                val selectedIds = selected.mapTo(hashSetOf()) { it.id }
                val bundle = PendingDeletion(items = selected,
                    attachments = readIds(selectedIds, attachmentDao::forItems).sortedBy { it.id },
                    reminders = readIds(selectedIds, reminderDao::forItems).sortedBy { it.id })
                archive(bundle)
                selected.forEach { itemDao.delete(it) }
                bundle
            } ?: return@withContext
            _pendingDeletions.value += deleted
            afterCommit(reminderIds = deleted.reminders.map { it.id })
        }
    }

    suspend fun undoDeletion(token: String) = changes.withLock {
        restoreDeletedLocked(token)
    }

    suspend fun finishDeletion(token: String) = changes.withLock {
        val bundle = _pendingDeletions.value.find { it.token == token } ?: return@withLock
        _pendingDeletions.value -= bundle
        afterCommit(files = bundle.attachments.map { it.fileName }, notify = false)
    }

    // Change only the date of the latest saved record; one occurrence of a series stays independent.
    suspend fun moveToTomorrow(id: Long, today: java.time.LocalDate = java.time.LocalDate.now()) = changes.withLock {
        val (move, reminders) = db.withTransaction {
            val current = itemDao.byId(id) ?: error("This event no longer exists")
            val tomorrow = today.plusDays(1)
            if (current.date == tomorrow) return@withTransaction null
            itemDao.moveDate(id, tomorrow)
            PendingMove(itemId = id, title = current.title, fromDate = current.date, toDate = tomorrow) to reminderDao.forItem(id)
        } ?: return@withLock
        _pendingMoves.value += move
        afterCommit(reminderIds = reminders.map { it.id }, cancelFirst = reminders.mapTo(hashSetOf()) { it.id })
    }

    // Undo only the date. Preserve edits made since the move, and never recreate a deleted event.
    suspend fun undoMove(token: String): Boolean = changes.withLock {
        val move = _pendingMoves.value.find { it.token == token } ?: return@withLock false
        val (restored, reminders) = db.withTransaction {
            val current = itemDao.byId(move.itemId)
            if (current == null || current.date != move.toDate) false to emptyList<Reminder>()
            else { itemDao.moveDate(current.id, move.fromDate); true to reminderDao.forItem(current.id) }
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
        val reminders = db.withTransaction {
            val current = itemDao.byId(id) ?: error("This event no longer exists")
            require(current.seriesId != null || !skipped) { "Only repeating events can be skipped" }
            itemDao.upsert(current.copy(skipped = skipped))
            reminderDao.forItem(id).also { list -> list.forEach { reminderDao.snooze(it.id, null) } }
        }
        afterCommit(reminderIds = reminders.map { it.id }, cancelFirst = reminders.mapTo(hashSetOf()) { it.id })
    }

    suspend fun setPaid(id: Long, paid: Boolean) = changes.withLock {
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
        if (!item.paid && !item.skipped && (trigger == expected || trigger == 0L && reminder.snoozedUntil == null)) deliver(item, reminder)
    }

    suspend fun deliverTaskReminder(id: String, trigger: Long, deliver: (PlannerTask) -> Unit) = changes.withLock {
        val task = taskDao.byId(id) ?: return@withLock
        if (!task.done && task.reminderAt == trigger && trigger <= System.currentTimeMillis()) deliver(task)
    }

    // Everything in the database, read in one go so the pieces agree with each other (for backups).
    suspend fun snapshot(): DataSnapshot = db.withTransaction {
        DataSnapshot(tripDao.all(), itemDao.all(), reminderDao.all(), attachmentDao.all(), db.templateDao().all(), deletedDao.all(), taskDao.all())
    }

    // Throws away all current data and puts [data] in its place, keeping its ids. The attachment
    // files named in [data] must already be in the store. Old files nothing refers to any more are removed.
    suspend fun replaceAll(data: DataSnapshot) = changes.withLock {
        data.tasks.forEach(Tasks::validate)
        TaskDependencies.validateGraph(data.tasks)
        require(data.tasks.map { it.id }.distinct().size == data.tasks.size)
        val oldTasks = taskDao.all()
        val oldReminders = reminderDao.all()
        val oldFiles = oldTasks.flatMap { it.attachments }.map { it.fileName } + attachmentDao.allFileNames() + deletedDao.all().flatMap { DeletedCodec.decode(it.payload).storedAttachments }.map { it.fileName } + _pendingDeletions.value.flatMap { it.attachments }.map { it.fileName }
        db.withTransaction {
            taskDao.deleteAll()
            taskDao.insertAll(data.tasks)
            deletedDao.deleteAll()
            deletedDao.insertAll(data.deleted)
            db.templateDao().deleteAll()
            db.templateDao().insertAll(data.templates)
            tripDao.deleteAll()
            tripDao.insertAll(data.trips)
            itemDao.insertAll(data.items)
            reminderDao.insertAll(data.reminders)
            attachmentDao.insertAll(data.attachments)
        }
        _pendingDeletions.value = emptyList()
        _pendingMoves.value = emptyList()
        _pendingPayments.value = emptyList()
        paymentUndo.clear()
        afterCommit(oldFiles, (oldReminders + data.reminders).map { it.id },
            cancelFirst = oldReminders.mapTo(hashSetOf()) { it.id }, taskIds = (oldTasks + data.tasks).map { it.id },
            resetTaskIds = oldTasks.mapTo(hashSetOf()) { it.id })
    }

    // After a reboot or update, and whenever the app opens (the exact-alarm permission may have changed).
    suspend fun rescheduleAllReminders() = changes.withLock {
        // Cleanup is independent maintenance: its failure must not prevent scheduling alarms.
        performFollowUp(linkedMapOf("cleanup:expired" to { purgeExpiredDeleted() }, "reminders:reload" to {
            val reminders = reminderDao.all()
            val items = readIds(reminders.map { it.itemId }, itemDao::byIds).associateBy { it.id }
            var failure: Exception? = null
            reminders.forEach { reminder ->
                try { items[reminder.itemId]?.let { if (it.paid || it.skipped) scheduler.cancel(reminder.id) else scheduler.reconcile(it, reminder) } }
                catch (e: Exception) { failure = e }
            }
            taskDao.all().forEach { task ->
                try { scheduler.scheduleTask(task) } catch (e: Exception) { failure = e }
            }
            failure?.let { throw it }
        }))
    }
}
