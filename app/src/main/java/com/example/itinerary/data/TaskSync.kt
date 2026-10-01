package com.example.itinerary.data

import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId

// What task sync needs from Planner's tasks: add one, change one as it is at that moment, and move one to Recently
// deleted. The app passes its Repository (see asTaskStore); tests may too.
interface TaskStore {
    suspend fun add(task: PlannerTask): String
    suspend fun update(id: String, change: (PlannerTask) -> PlannerTask?): PlannerTask?
    suspend fun archive(id: String, still: (PlannerTask) -> Boolean): Boolean
}

fun Repository.asTaskStore(): TaskStore = object : TaskStore {
    override suspend fun add(task: PlannerTask): String { saveTask(task); return task.id }
    override suspend fun update(id: String, change: (PlannerTask) -> PlannerTask?) = saveTaskIf(id, change)
    override suspend fun archive(id: String, still: (PlannerTask) -> Boolean) = archiveTask(id, still)
}

// Two-way task sync with one Nextcloud task list, the way CalendarSync keeps events in sync with one calendar: every write
// conditional (If-None-Match / If-Match), so nothing Planner hasn't seen is replaced; sent_tasks remembers, per Planner
// task, its file, version and what was last synced, and each pass compares both sides with that. Planner → Nextcloud a
// few seconds after a change (send); Nextcloud → Planner when calendars sync (pull, from CalendarSync.sync). Changed on
// both sides = a conflict the user settles (resolve).
// Which tasks: Planner's open tasks go to Nextcloud (a done one only once it was synced); Nextcloud's open tasks come into
// Planner. Tasks already done on Nextcloud, and repeating ones (Planner can't hold their rule), stay there only.
class TaskSync(
    private val db: AppDatabase,
    private val accounts: NextcloudAccountStore,
    // CalendarSync's client, so UI tests that point it at a local server move both.
    private val client: () -> NextcloudClient,
    private val store: TaskStore,
    // Tasks deleted in Planner whose Undo is still on offer: their Nextcloud copies wait until it has passed.
    private val pendingDeleted: () -> Set<String> = { emptySet() },
    // For sending a few seconds after a change (requestSend); null in tests, which call send() themselves.
    private val scope: CoroutineScope? = null,
    private val now: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val onChanged: () -> Unit = {},
) {
    private val dao = db.outsideDao()
    private val rowsDao = db.sentTaskDao()
    private val lock = Mutex()
    private val _state = MutableStateFlow(CalendarSync.State())
    val state = _state.asStateFlow()
    // Repeating tasks on Nextcloud at the last read, which Planner leaves there.
    private val _hidden = MutableStateFlow(0)
    val hidden = _hidden.asStateFlow()
    val rows: Flow<List<SentTask>> = rowsDao.observe()
    val conflicts: Flow<List<SentTask>> = rows.map { rows -> rows.filter { it.problem == SentEvent.CONFLICT } }
    private var sendJob: Job? = null
    private val debounce = SendDebounce()

    private suspend fun target(): CalendarSource? = dao.sources().firstOrNull { it.kind == OutsideCalendars.KIND_NEXTCLOUD && it.tasksHere }

    // Keeps Planner's tasks in sync with list [id], or stops (null; the tasks stay in both places). Choosing another list
    // leaves the old one as it is. The list is read before anything is sent there, so a list chosen again links its tasks
    // to Planner's instead of sending them twice.
    suspend fun setTarget(id: Long?) {
        db.withTransaction {
            val sources = dao.sources().filter { it.kind == OutsideCalendars.KIND_NEXTCLOUD }
            sources.filter { it.tasksHere && it.id != id }.forEach { dao.updateSource(it.copy(tasksHere = false, taskCtag = null, taskError = null)) }
            val chosen = sources.firstOrNull { it.id == id && it.tasks && it.writable } ?: return@withTransaction
            rowsDao.all().filter { it.account != chosen.account || it.list != chosen.href }.forEach { rowsDao.delete(it.id) }
            dao.updateSource(chosen.copy(tasksHere = true, taskCtag = null, taskError = null))
        }
        _state.value = CalendarSync.State(); _hidden.value = 0
        onChanged()
    }

    // After restoring a backup that doesn't say what was synced: nothing is kept, and the next read links the list's files
    // to the restored tasks by content; nothing is overwritten or deleted on the strength of an old record.
    suspend fun forget() {
        db.withTransaction {
            rowsDao.deleteAll()
            target()?.let { dao.updateSource(it.copy(taskCtag = null, taskError = null)) }
        }
        _state.value = CalendarSync.State()
    }

    // After disconnecting Nextcloud (its calendars go with it) or the test runner's clean start.
    suspend fun clear() {
        rowsDao.deleteAll()
        _state.value = CalendarSync.State(); _hidden.value = 0
    }

    // See CalendarSync.requestSend.
    fun requestSend() {
        val scope = scope ?: return
        synchronized(debounce) {
            if (!debounce.request()) return
            sendJob?.cancel()
            sendJob = scope.launch {
                kotlinx.coroutines.delay(CalendarSync.SEND_DELAY_MS)
                synchronized(debounce) { ensureActive(); debounce.started() }
                try { send() } finally { if (synchronized(debounce) { debounce.finished() }) requestSend() }
            }
        }
    }

    // One pass Planner → Nextcloud: creates what's new, updates what changed (only what Planner manages in the file),
    // deletes what Planner no longer has (not while its Undo is on offer). A write that finds the server copy changed or
    // gone leaves it and marks it for the next pull. Offline, the rest waits for the next pass.
    suspend fun send(): Boolean = lock.withLock {
        val target = target() ?: return@withLock true
        val account = withContext(Dispatchers.IO) { runCatching { accounts.load() }.getOrNull() } ?: return@withLock true
        if (target.account != CalendarSync.accountKey(account)) return@withLock true
        _state.value = CalendarSync.State(running = true)
        _state.value = try {
            withContext(Dispatchers.IO) { sendLocked(account, target) }
        } catch (e: CancellationException) {
            _state.value = CalendarSync.State(); throw e
        } catch (e: Exception) {
            CalendarSync.State(message = ((e as? BackupException)?.message ?: "Couldn't send tasks to Nextcloud.") + " The rest will be sent later.", error = true)
        }
        true
    }

    private fun hrefOf(target: CalendarSource, row: SentTask) = row.href ?: "${target.href}${row.uid}.ics"
    private fun stamp() = Instant.ofEpochMilli(now())

    private suspend fun sendLocked(account: NextcloudAccount, target: CalendarSource): CalendarSync.State {
        val tasks = db.taskDao().all()
        val present = tasks.mapTo(HashSet()) { it.id }
        val rows = rowsDao.all().filter { it.account == target.account && it.list == target.href }.associateByTo(HashMap()) { it.taskId }
        val waiting = pendingDeleted()
        // New files wait until the list has been read since it was chosen (see setTarget).
        val checked = target.taskCtag != null
        for (task in tasks) {
            val row = rows[task.id]
            val pending = row?.problem == SentEvent.PENDING
            if (row?.problem != null && !pending) continue // settled by the next pull or by the user
            val print = ServerTasks.fingerprint(task)
            when {
                pending || row == null -> {
                    if (!pending && (!checked || task.done)) continue
                    val uid = row?.uid?.takeIf { pending } ?: "planner-task-${java.util.UUID.randomUUID()}@planner"
                    val body = ServerTasks.encode(task, uid, stamp())
                    // Noted before writing, so a reply lost on the way back leaves this uid to retry, not a second file.
                    val noted = SentTask(id = row?.id ?: 0, taskId = task.id, account = target.account, list = target.href, uid = uid,
                        fingerprint = print, problem = SentEvent.PENDING)
                    val fresh = noted.copy(id = rowsDao.put(noted))
                    when (val result = client().putEvent(account, target.href, uid, body, null)) {
                        is WriteResult.Ok -> rowsDao.put(fresh.copy(etag = result.etag, ics = body, problem = null))
                        WriteResult.Changed -> client().getFile(account, target.href, hrefOf(target, fresh))?.let { rowsDao.put(adopted(fresh, it, task, zone())) }
                            ?: rowsDao.put(fresh.copy(problem = SentEvent.CHANGED))
                        WriteResult.Missing -> throw BackupException("The task list Planner syncs with wasn't found on Nextcloud.")
                    }
                }
                row.fingerprint != print -> {
                    val href = hrefOf(target, row)
                    val base = row.ics ?: client().getFile(account, target.href, href)?.let { file ->
                        if (file.etag == row.etag) file.data
                        else { rowsDao.put(alreadyThere(row, file, task, zone()) ?: row.copy(problem = SentEvent.CHANGED)); null }
                    }
                    if (base == null) { if (rowsDao.all().none { it.id == row.id && it.problem != null }) rowsDao.put(row.copy(problem = SentEvent.DELETED)); continue }
                    val body = ServerTasks.patch(base, task, zone(), stamp())
                    when (val result = client().putFile(account, target.href, href, body, row.etag)) {
                        is WriteResult.Ok -> rowsDao.put(row.copy(etag = result.etag, ics = body, fingerprint = print))
                        WriteResult.Changed -> rowsDao.put(try { client().getFile(account, target.href, href)?.let { alreadyThere(row, it, task, zone()) } }
                            catch (e: CancellationException) { throw e } catch (_: Exception) { null }
                            ?: row.copy(problem = SentEvent.CHANGED))
                        WriteResult.Missing -> rowsDao.put(row.copy(problem = SentEvent.DELETED))
                    }
                }
            }
        }
        // Deleted in Planner (and past its Undo): the copy goes too, unless it was changed on Nextcloud meanwhile; then the
        // row stays for the next pull to make it a conflict.
        for (row in rows.values) if (row.taskId !in present && row.taskId !in waiting && row.problem != SentEvent.CONFLICT) {
            val result = if (CalendarSync.deletable(row.uid, row.problem)) client().deleteFile(account, target.href, hrefOf(target, row), row.etag) else null
            val kept = afterDelete(row, result)
            if (kept == null) rowsDao.delete(row.id) else if (kept != row) rowsDao.put(kept)
        }
        return summary(target)
    }

    private suspend fun summary(target: CalendarSource): CalendarSync.State {
        val rows = rowsDao.all().filter { it.list == target.href }
        val conflicts = rows.count { it.problem == SentEvent.CONFLICT }
        val waiting = rows.count { it.problem == SentEvent.CHANGED || it.problem == SentEvent.DELETED }
        return when {
            conflicts > 0 -> CalendarSync.State(message = "$conflicts task${if (conflicts == 1) " was" else "s were"} changed in both places. " +
                "Choose which version to keep.", error = true)
            waiting > 0 -> CalendarSync.State(message = "$waiting task${if (waiting == 1) " was" else "s were"} changed on Nextcloud; " +
                "Planner will check at the next sync.")
            else -> CalendarSync.State()
        }
    }

    // Called by CalendarSync.sync with the lists the server has (it has just brought the calendar rows up to date). False
    // when reading the list failed; the reason stays on the list (taskError) until a read works.
    internal suspend fun pull(account: NextcloudAccount, remote: Map<String, RemoteCalendar>): Boolean {
        val target = target()?.takeIf { it.account == CalendarSync.accountKey(account) } ?: return true
        val list = remote[target.href] ?: return true
        return try {
            lock.withLock { pullLocked(account, target, list.ctag) }
            _state.value = summary(target)
            true
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            val message = (e as? BackupException)?.message ?: "Couldn't download the task list. Planner tries again at the next sync."
            db.withTransaction { dao.source(target.id)?.let { dao.updateSource(it.copy(taskError = message)) } }
            false
        }
    }

    // One pass Nextcloud → Planner: nothing when the list's change marker says nothing changed.
    private suspend fun pullLocked(account: NextcloudAccount, target: CalendarSource, ctag: String?) {
        val rows = rowsDao.all().filter { it.account == target.account && it.list == target.href }
        val unsettled = rows.any { it.problem == SentEvent.CHANGED || it.problem == SentEvent.DELETED }
        if (ctag != null && ctag == target.taskCtag && !unsettled && target.taskError == null) return
        val files = client().taskFiles(account, target.href).associateBy { it.href }
        val parsed = files.mapValues { ServerTasks.parse(it.value.data, zone()) }
        val tasks = db.taskDao().all().associateBy { it.id }
        val waiting = pendingDeleted()
        val synced = rows.filter { it.uid != null }.associateBy { hrefOf(target, it) }
        val linkedIds = rows.mapTo(HashSet()) { it.taskId }
        // Planner's open tasks without a file: one already on Nextcloud (after reconnecting, or choosing the list again)
        // is linked to its file, not brought in as a copy.
        val unlinked = tasks.values.filter { !it.done && it.id !in linkedIds && it.id !in waiting }
        val relinked = relink(parsed.filterKeys { it !in synced }.mapNotNull { (href, p) -> p.fields?.let { Triple(href, p.uid, it) } }, unlinked)
        fun uidOf(href: String) = parsed[href]?.uid ?: href.substringAfterLast('/').removeSuffix(".ics")
        var hidden = 0
        for ((href, file) in files) {
            val p = parsed.getValue(href)
            val row = synced[href]
            if (row == null) {
                val server = p.fields
                if (server == null) { if (p.repeating) hidden++; continue }
                val own = relinked[href]?.let { tasks[it] }
                if (own != null) { rowsDao.put(linked(target, uidOf(href), file, server, own)); continue }
                // Done on Nextcloud and never in Planner: it stays there only.
                if (server.done) continue
                val id = store.add(ServerTasks.apply(PlannerTask(), server))
                val added = db.taskDao().byId(id) ?: continue
                rowsDao.put(SentTask(taskId = id, account = target.account, list = target.href, uid = uidOf(href), href = href,
                    etag = file.etag, ics = file.data, fingerprint = ServerTasks.fingerprint(added)))
                continue
            }
            if (row.problem == SentEvent.CONFLICT) { if (file.etag != row.etag) rowsDao.put(row.copy(conflict = file.data)); continue }
            if (row.problem == SentEvent.PENDING) { rowsDao.put(adopted(row, file, tasks[row.taskId], zone())); continue }
            val detached = row.problem == SentEvent.DETACHED
            if (detached && p.fields == null) { if (file.etag != row.etag) rowsDao.put(row.copy(etag = file.etag, ics = file.data)); continue }
            if (file.etag == row.etag && !detached) { if (row.ics == null) rowsDao.put(row.copy(ics = file.data)); continue }
            // Changed on Nextcloud (or a detached one Planner can hold again).
            val server = p.fields
            val task = tasks[row.taskId]
            when {
                // Now repeating, or otherwise more than Planner can hold: Planner's task stays as it is and isn't sent again.
                server == null -> rowsDao.put(row.copy(etag = file.etag, ics = file.data, problem = SentEvent.DETACHED, conflict = null))
                // Deleted in Planner meanwhile.
                task == null || row.taskId in waiting -> rowsDao.put(row.copy(problem = SentEvent.CONFLICT, conflict = file.data))
                // Unchanged in Planner since the last sync: take Nextcloud's version, made to the task as it is when saved.
                ServerTasks.inSync(row.fingerprint, task) -> {
                    val saved = store.update(task.id) { if (ServerTasks.inSync(row.fingerprint, it)) ServerTasks.apply(it, server) else null }
                    rowsDao.put(if (saved != null) row.copy(etag = file.etag, ics = file.data, fingerprint = ServerTasks.fingerprint(saved), problem = null, conflict = null)
                        else db.taskDao().byId(task.id)?.let { alreadyThere(row, file, it, zone()) } ?: row.copy(problem = SentEvent.CONFLICT, conflict = file.data))
                }
                // Changed in Planner too, unless Nextcloud already has it as Planner has it now.
                else -> rowsDao.put(alreadyThere(row, file, task, zone()) ?: row.copy(problem = SentEvent.CONFLICT, conflict = file.data))
            }
        }
        // Gone from Nextcloud: to Recently deleted, unless it was changed in Planner meanwhile. A pending one isn't there
        // yet: the next send writes it.
        for ((href, row) in synced) if (href !in files && row.problem != SentEvent.CONFLICT && row.problem != SentEvent.PENDING) {
            val task = tasks[row.taskId]
            when {
                task == null -> rowsDao.delete(row.id)
                ServerTasks.inSync(row.fingerprint, task) && store.archive(task.id) { ServerTasks.inSync(row.fingerprint, it) } -> rowsDao.delete(row.id)
                db.taskDao().byId(task.id) == null -> rowsDao.delete(row.id)
                else -> rowsDao.put(row.copy(problem = SentEvent.CONFLICT, conflict = ""))
            }
        }
        db.withTransaction { dao.source(target.id)?.let { dao.updateSource(it.copy(taskCtag = ctag ?: "", taskError = null)) } }
        _hidden.value = hidden
        onChanged()
    }

    enum class Resolution { PLANNER, NEXTCLOUD, BOTH }

    // Settles a conflict: keep Planner's version (written to Nextcloud, or Nextcloud's copy deleted if it was deleted in
    // Planner), Nextcloud's (taken into Planner, or Planner's task to Recently deleted if it was deleted there), or both
    // (Nextcloud's becomes a second Planner task; Planner's is sent as a new file). Throws with a message for the user.
    suspend fun resolve(rowId: Long, choice: Resolution) {
        lock.withLock {
            withContext(Dispatchers.IO) {
                val account = accounts.load() ?: throw BackupException("Connect to Nextcloud first.")
                val row = rowsDao.all().firstOrNull { it.id == rowId && it.problem == SentEvent.CONFLICT } ?: return@withContext
                val target = target()?.takeIf { it.href == row.list } ?: throw BackupException("Choose the task list to keep in sync with first.")
                val href = hrefOf(target, row)
                val task = db.taskDao().byId(row.taskId)
                val current = client().getFile(account, target.href, href)
                fun changedAgain(): Nothing = throw BackupException("It changed on Nextcloud again. Check it and choose once more.")
                when (choice) {
                    Resolution.PLANNER -> when {
                        task == null -> { if (current != null && client().deleteFile(account, target.href, href, current.etag) == WriteResult.Changed) changedAgain(); rowsDao.delete(row.id) }
                        current == null -> {
                            val uid = "planner-task-${java.util.UUID.randomUUID()}@planner"
                            val body = ServerTasks.encode(task, uid, stamp())
                            rowsDao.put(row.copy(uid = uid, href = null, etag = null, ics = null, fingerprint = ServerTasks.fingerprint(task), problem = SentEvent.PENDING, conflict = null))
                            val result = client().putEvent(account, target.href, uid, body, null) as? WriteResult.Ok ?: changedAgain()
                            rowsDao.put(row.copy(uid = uid, href = null, etag = result.etag, ics = body, fingerprint = ServerTasks.fingerprint(task), problem = null, conflict = null))
                        }
                        else -> {
                            val body = ServerTasks.patch(current.data, task, zone(), stamp())
                            val result = client().putFile(account, target.href, href, body, current.etag) as? WriteResult.Ok ?: changedAgain()
                            rowsDao.put(row.copy(etag = result.etag, ics = body, fingerprint = ServerTasks.fingerprint(task), problem = null, conflict = null))
                        }
                    }
                    Resolution.NEXTCLOUD -> {
                        val server = current?.let { ServerTasks.parse(it.data, zone()).fields }
                        when {
                            current == null -> { task?.let { store.archive(it.id) { true } }; rowsDao.delete(row.id) }
                            server == null -> rowsDao.put(row.copy(etag = current.etag, ics = current.data, problem = SentEvent.DETACHED, conflict = null))
                            else -> {
                                val saved = if (task != null) store.update(task.id) { ServerTasks.apply(it, server) }
                                    else store.add(ServerTasks.apply(PlannerTask(), server)).let { db.taskDao().byId(it) }
                                saved ?: return@withContext
                                rowsDao.put(row.copy(taskId = saved.id, etag = current.etag, ics = current.data, fingerprint = ServerTasks.fingerprint(saved), problem = null, conflict = null))
                            }
                        }
                    }
                    Resolution.BOTH -> {
                        val server = current?.let { ServerTasks.parse(it.data, zone()).fields }
                        require(task != null && current != null && server != null) { "Both versions are needed to keep both." }
                        // Nextcloud's file now belongs to a new Planner task; Planner's own task is sent as a new file.
                        val saved = db.taskDao().byId(store.add(ServerTasks.apply(PlannerTask(), server))) ?: return@withContext
                        rowsDao.put(row.copy(taskId = saved.id, etag = current.etag, ics = current.data, fingerprint = ServerTasks.fingerprint(saved), problem = null, conflict = null))
                    }
                }
            }
        }
        onChanged()
        send()
    }

    // For backups: which list Planner keeps its tasks in sync with and what it synced. Conflicts aren't kept (checked again).
    suspend fun snapshot(): Pair<CalendarChoice, List<SentTask>>? {
        val target = target() ?: return null
        return CalendarChoice(target.account, target.href, target.name, target.color, false) to
            rowsDao.all().filter { it.account == target.account && it.list == target.href && it.problem != SentEvent.CONFLICT }
    }

    // After restoring a backup (after CalendarSync's own restore, which rebuilds the calendar rows): its list and record,
    // or none.
    suspend fun restore(target: CalendarChoice?, rows: List<SentTask>) {
        db.withTransaction {
            rowsDao.deleteAll()
            dao.sources().filter { it.tasksHere }.forEach { dao.updateSource(it.copy(tasksHere = false, taskCtag = null, taskError = null)) }
            if (target == null) return@withTransaction
            val existing = dao.sources().firstOrNull { it.kind == OutsideCalendars.KIND_NEXTCLOUD && it.account == target.account && it.href == target.href }
            if (existing != null) dao.updateSource(existing.copy(tasks = true, tasksHere = true, taskCtag = null, taskError = null))
            else dao.insertSource(CalendarSource(account = target.account, href = target.href, name = target.name, color = target.color,
                events = false, tasks = true, tasksHere = true))
            rowsDao.insertAll(rows.filter { it.account == target.account && it.list == target.href }.map { it.copy(id = 0) })
        }
        _state.value = CalendarSync.State()
    }

    companion object {
        internal fun isPlannerUid(uid: String?) = uid != null && uid.startsWith("planner-task-") && uid.endsWith("@planner")

        // [file] is the one a pending [row] wrote (its reply was lost): the row takes it (see CalendarSync.adopted).
        internal fun adopted(row: SentTask, file: ServerFile, task: PlannerTask?, zone: ZoneId): SentTask {
            val server = ServerTasks.parse(file.data, zone).fields
            return when {
                task == null -> row.copy(etag = file.etag, problem = SentEvent.PENDING)
                server == null -> row.copy(problem = SentEvent.CHANGED)
                else -> row.copy(etag = file.etag, ics = file.data, fingerprint = ServerTasks.fingerprint(ServerTasks.apply(task, server)), problem = null)
            }
        }

        // [file] already holds [task] as Planner has it now: the row takes it as synced. Null otherwise.
        internal fun alreadyThere(row: SentTask, file: ServerFile, task: PlannerTask, zone: ZoneId): SentTask? {
            val server = ServerTasks.parse(file.data, zone).fields ?: return null
            val print = ServerTasks.fingerprint(task)
            if (ServerTasks.fingerprint(ServerTasks.apply(task, server)) != print) return null
            return row.copy(etag = file.etag, ics = file.data, fingerprint = print, problem = null, conflict = null)
        }

        // A file found for an unlinked Planner task: what Nextcloud has counts as synced; if Planner's task differs, the
        // user chooses which to keep.
        internal fun linked(target: CalendarSource, uid: String, file: ServerFile, server: ServerTasks.Fields, task: PlannerTask): SentTask {
            val print = ServerTasks.fingerprint(ServerTasks.apply(task, server))
            val same = print == ServerTasks.fingerprint(task)
            return SentTask(taskId = task.id, account = target.account, list = target.href, uid = uid, href = file.href, etag = file.etag,
                ics = file.data, fingerprint = print, problem = if (same) null else SentEvent.CONFLICT, conflict = if (same) null else file.data)
        }

        // The row of a task deleted in Planner once its copy's delete was tried (see CalendarSync.afterDelete).
        internal fun afterDelete(row: SentTask, result: WriteResult?): SentTask? = when {
            result == WriteResult.Changed -> row.copy(problem = SentEvent.CHANGED)
            result == null && row.uid != null && row.problem == SentEvent.CHANGED -> row
            else -> null
        }

        // Which files ([href], uid, the task as Planner would hold it) are [candidates] already (Planner's open tasks without
        // a file): first exactly (title and due date); then, for files Planner itself created, the one task with that title.
        // Each task is used once; anything unsure is left alone.
        internal fun relink(files: List<Triple<String, String?, ServerTasks.Fields>>, candidates: List<PlannerTask>): Map<String, String> {
            fun key(title: String, due: java.time.LocalDate?) = title.trim().take(ServerTasks.MAX_TITLE).ifEmpty { "(No title)" } to due
            val free = candidates.sortedBy { it.id }.toMutableList()
            val result = LinkedHashMap<String, String>()
            for ((href, _, server) in files) free.firstOrNull { key(it.title, it.dueDate) == key(server.title, server.dueDate) }
                ?.let { result[href] = it.id; free.remove(it) }
            for ((href, uid, server) in files) {
                if (href in result || !isPlannerUid(uid)) continue
                val match = free.filter { key(it.title, null) == key(server.title, null) }.singleOrNull() ?: continue
                result[href] = match.id; free.remove(match)
            }
            return result
        }
    }
}
