package com.example.itinerary.data

import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
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

// Sync's writes, not changes made in Planner (SyncWrite).
fun Repository.asTaskStore(): TaskStore = object : TaskStore {
    override suspend fun add(task: PlannerTask): String { SyncWrite.of { saveTask(task) }; return task.id }
    override suspend fun update(id: String, change: (PlannerTask) -> PlannerTask?) = SyncWrite.of { saveTaskIf(id, change) }
    override suspend fun archive(id: String, still: (PlannerTask) -> Boolean) = SyncWrite.of { archiveTask(id, still) }
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
    private val debounce = SendDebounce()
    // SY-2: the list and its ctag when its read last failed (see pull).
    @Volatile private var failedAt: Pair<Long, String>? = null

    private suspend fun target(): CalendarSource? = dao.sources().firstOrNull { it.kind == OutsideCalendars.KIND_NEXTCLOUD && it.tasksHere }

    // Keeps Planner's tasks in sync with list [id], or stops (null; the tasks stay in both places). Choosing another list
    // leaves the old one as it is. The list is read before anything is sent there, so a list chosen again links its tasks
    // to Planner's instead of sending them twice.
    // E-4: not while a send, pull or choice is under way (see paused).
    suspend fun setTarget(id: Long?) {
        lock.withLock {
            // A new record of what was sent: no refusal carried over. S6-6: inside the lock, so a send still under way
            // can't set it again afterwards.
            lastRefused = null
            db.withTransaction {
                val sources = dao.sources().filter { it.kind == OutsideCalendars.KIND_NEXTCLOUD }
                sources.filter { it.tasksHere && it.id != id }.forEach { dao.updateSource(it.copy(tasksHere = false, taskCtag = null, taskError = null)) }
                val chosen = sources.firstOrNull { it.id == id && it.tasks && it.writable } ?: return@withTransaction
                rowsDao.all().filter { it.account != chosen.account || it.list != chosen.href }.forEach { rowsDao.delete(it.id) }
                dao.updateSource(chosen.copy(tasksHere = true, taskCtag = null, taskError = null))
            }
            _state.value = CalendarSync.State(); _hidden.value = 0
            markChanged()
        }
        onChanged()
    }

    // E-4, as CalendarSync.paused: runs [block] with no task send, pull or conflict choice under way, and none starting until
    // it's done (one running across a restore would write rows for the old tasks back over the restored record, or delete
    // the file of a task the restore brought back). Inside, use the *Locked functions only (the lock isn't reentrant).
    suspend fun <T> paused(block: suspend () -> T): T = lock.withLock { block() }

    // After restoring a backup that doesn't say what was synced: nothing is kept, and the next read links the list's files
    // to the restored tasks by content; nothing is overwritten or deleted on the strength of an old record.
    suspend fun forget() = paused { forgetLocked() }

    // forget, inside paused.
    suspend fun forgetLocked() {
        lastRefused = null // a new record of what was sent: no refusal carried over
        db.withTransaction {
            rowsDao.deleteAll()
            target()?.let { dao.updateSource(it.copy(taskCtag = null, taskError = null)) }
        }
        _state.value = CalendarSync.State(); markChanged()
    }

    // After disconnecting Nextcloud (its calendars go with it) or the test runner's clean start.
    suspend fun clear() = paused {
        lastRefused = null // a new record of what was sent: no refusal carried over
        rowsDao.deleteAll()
        _state.value = CalendarSync.State(); _hidden.value = 0
    }

    // Whether AutoSync's check has anything to send, as CalendarSync.sendDue: a change in Planner since the last clean
    // send, a send that failed or left a problem, a read that read something, or 15 minutes since the last pass.
    private val changes = java.util.concurrent.atomic.AtomicLong(1)
    @Volatile private var sentUpTo = 0L
    @Volatile private var lastSendPass: Long? = null

    // S5-2, as CalendarSync's: Planner's own changes apart from the rest, sent by the next check even while it backs off.
    private val local = java.util.concurrent.atomic.AtomicLong(0)
    @Volatile private var localSent = 0L

    internal fun markChanged() { changes.incrementAndGet(); local.incrementAndGet() }

    internal fun unsentChange(): Boolean = local.get() != localSent

    internal fun sendDue(): Boolean = changes.get() != sentUpTo || lastSendPass.let { it == null || now() - it >= CalendarSync.SEND_SAFETY_MS }

    // See CalendarSync.requestSend.
    fun requestSend() {
        markChanged()
        val scope = scope ?: return
        debounce.launch(scope, CalendarSync.SEND_DELAY_MS, send = { send() }, sendAgain = ::requestSend)
    }

    // One pass Planner → Nextcloud: creates what's new, updates what changed (only what Planner manages in the file),
    // deletes what Planner no longer has (not while its Undo is on offer). A write that finds the server copy changed or
    // gone leaves it and marks it for the next pull. Offline, the rest waits for the next pass.
    // [quiet]: AutoSync's check, as CalendarSync.send (SY-4).
    suspend fun send(quiet: Boolean = false): Boolean = lock.withLock {
        localSent = local.get()
        val target = target() ?: return@withLock true
        val account = withContext(Dispatchers.IO) { runCatching { accounts.load() }.getOrNull() } ?: return@withLock true
        if (target.account != CalendarSync.accountKey(account)) return@withLock true
        if (!quiet) _state.value = CalendarSync.State(running = true)
        // Read before the pass reads the tasks: a change made during it is still to send afterwards.
        val seen = changes.get()
        lastSendPass = now()
        _state.value = try {
            withContext(Dispatchers.IO) { sendLocked(account, target) }
        } catch (e: CancellationException) {
            _state.value = CalendarSync.State(); throw e
        } catch (e: Exception) {
            CalendarSync.State(message = ((e as? BackupException)?.message ?: "Couldn't send tasks to Nextcloud.") + " The rest will be sent later.", error = true)
        }
        if (!_state.value.error) sentUpTo = seen
        true
    }

    private fun hrefOf(target: CalendarSource, row: SentTask) = row.href ?: "${target.href}${row.uid}.ics"
    private fun stamp() = Instant.ofEpochMilli(now())

    private suspend fun sendLocked(account: NextcloudAccount, target: CalendarSource): CalendarSync.State {
        val tasks = db.taskDao().all()
        val present = tasks.mapTo(HashSet()) { it.id }
        val rows = rowsDao.forList(target.account, target.href).associateByTo(HashMap()) { it.taskId }
        val waiting = pendingDeleted()
        // New files wait until the list has been read since it was chosen (see setTarget).
        val checked = target.taskCtag != null
        // T1: a file Nextcloud refuses (not the login or the connection) holds up only its own task: the row stays as it
        // was, so the next pass tries again, and the others go on.
        val refused = mutableListOf<RefusedException>()
        for (task in tasks) {
            val row = rows[task.id]
            val pending = row?.problem == SentEvent.PENDING
            if (row?.problem != null && !pending) continue // settled by the next pull or by the user
            val print = ServerTasks.fingerprint(task)
            try { when {
                pending || row == null -> {
                    if (!pending && (!checked || task.done)) continue
                    val uid = row?.uid?.takeIf { pending } ?: newTaskUid()
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
                    // As for events (CalendarSync.fetchedBase): fetched if the text or the version is unknown.
                    val synced = if (row.ics != null && row.etag != null) row else {
                        val file = client().getFile(account, target.href, href)
                        if (file == null) { rowsDao.put(row.copy(problem = SentEvent.DELETED)); continue }
                        val (next, send) = fetchedBase(row, file, task, zone())
                        if (!send) { rowsDao.put(next); continue }
                        next
                    }
                    // The due date as last synced stays exactly as written unless it was changed in Planner (a UTC one
                    // read after travelling would otherwise move a day).
                    val body = ServerTasks.patch(synced.ics!!, task, zone(), stamp(), synced.fingerprint)
                    when (val result = client().putFile(account, target.href, href, body, synced.etag!!)) {
                        is WriteResult.Ok -> rowsDao.put(synced.copy(etag = result.etag, ics = body, fingerprint = print))
                        WriteResult.Changed -> rowsDao.put(try { client().getFile(account, target.href, href)?.let { alreadyThere(synced, it, task, zone()) } }
                            catch (e: CancellationException) { throw e } catch (_: Exception) { null }
                            ?: synced.copy(problem = SentEvent.CHANGED))
                        WriteResult.Missing -> rowsDao.put(synced.copy(problem = SentEvent.DELETED))
                    }
                }
            } } catch (e: RefusedException) { refused += e }
        }
        // Deleted in Planner (and past its Undo): the copy goes too, unless it was changed on Nextcloud meanwhile; then the
        // row stays for the next pull to make it a conflict.
        for (row in rows.values) if (row.taskId !in present && row.taskId !in waiting && row.problem != SentEvent.CONFLICT) {
            val result = try { removeCopy(account, target, row) }
                catch (e: RefusedException) { refused += e; continue }
            val kept = afterDelete(row, result)
            if (kept == null) rowsDao.delete(row.id) else if (kept != row) rowsDao.put(kept)
        }
        lastRefused = if (refused.isEmpty()) null else refusedMessage(refused.size, refused.first().code)
        return summary(target)
    }

    // See CalendarSync.lastRefused.
    @Volatile private var lastRefused: String? = null

    // See CalendarSync.removeSyncedCopy: only at the version Planner synced, learnt first when the row doesn't know it (E-7).
    private fun removeCopy(account: NextcloudAccount, target: CalendarSource, row: SentTask): WriteResult? =
        CalendarSync.removeSyncedCopy(client, account, target.href, { hrefOf(target, row) }, row.uid, row.problem, row.etag, row.ics)

    private suspend fun summary(target: CalendarSource): CalendarSync.State {
        val conflicts = rowsDao.countWith(target.href, listOf(SentEvent.CONFLICT))
        val waiting = rowsDao.countWith(target.href, listOf(SentEvent.CHANGED, SentEvent.DELETED))
        val state = CalendarSync.conflictSummary("task", conflicts, waiting) ?: CalendarSync.State()
        return lastRefused?.let { CalendarSync.State(message = listOfNotNull(it, state.message).joinToString(" "), error = true) } ?: state
    }

    // Called by CalendarSync.sync with the lists the server has (it has just brought the calendar rows up to date). False
    // when reading the list failed; the reason stays on the list (taskError) until a read works. [retry] false (SY-2, a
    // check backing off): a list that failed is read again only if it changed there since.
    internal suspend fun pull(account: NextcloudAccount, remote: Map<String, RemoteCalendar>, retry: Boolean = true): Boolean {
        // E-5: the list is read inside the lock, so a restore or a new choice made while waiting is what's pulled.
        var target: CalendarSource? = null
        var ctag: String? = null
        var read = false
        return try {
            lock.withLock {
                val current = target()?.takeIf { it.account == CalendarSync.accountKey(account) }
                val list = current?.let { remote[it.href] } ?: return@withLock
                if (!retry && current.taskError != null && failedAt == current.id to list.ctag.orEmpty()) return false
                target = current; ctag = list.ctag
                read = pullLocked(account, current, list.ctag)
                // What it read may leave something to send (new files wait for the first read of a list just chosen);
                // not a change in Planner, so it waits for a check backing off (S5-2). The pull's own writes to Planner's
                // tasks aren't reported as changes either (asTaskStore, S6-5), so they don't reset that backoff.
                if (read) changes.incrementAndGet()
                failedAt = null
            }
            // As CalendarSync's: a check backing off keeps what the last send said unless the pull read something.
            target?.let { if (retry || read) _state.value = summary(it) }
            true
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            val message = (e as? BackupException)?.message ?: "Couldn't download the task list. Planner tries again at the next sync."
            target?.let { pulled -> failedAt = pulled.id to ctag.orEmpty()
                db.withTransaction { dao.source(pulled.id)?.let { dao.updateSource(it.copy(taskError = message)) } } }
            false
        }
    }

    // One pass Nextcloud → Planner: nothing when the list's change marker says nothing changed.
    private suspend fun pullLocked(account: NextcloudAccount, target: CalendarSource, ctag: String?): Boolean {
        // Asked before any row is read: an unchanged list (most checks) reads none.
        if (ctag != null && ctag == target.taskCtag && target.taskError == null && !rowsDao.unsettled(target.account, target.href)) return false
        val rows = rowsDao.forList(target.account, target.href)
        // T4: what is gone is judged from the full listing (as for events): the task query leaves out any file it returns
        // without its content.
        val listing = client().eventEtags(account, target.href)
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
            // Nextcloud's side of a conflict, kept up to date; read again after a restore (E-3: backups keep the conflict, not it).
            if (row.problem == SentEvent.CONFLICT) { if (!NextcloudClient.sameEtag(file.etag, row.etag) || row.conflict == null) rowsDao.put(row.copy(conflict = file.data)); continue }
            if (row.problem == SentEvent.PENDING) { rowsDao.put(adopted(row, file, tasks[row.taskId], zone())); continue }
            val detached = row.problem == SentEvent.DETACHED
            if (detached && p.fields == null) { if (!NextcloudClient.sameEtag(file.etag, row.etag)) rowsDao.put(row.copy(etag = file.etag, ics = file.data)); continue }
            // Unchanged there; one a write found changed or gone is there as synced after all (see CalendarSync.pullLocked).
            if (NextcloudClient.sameEtag(file.etag, row.etag) && !detached) {
                if (row.ics == null || row.problem != null) rowsDao.put(row.copy(ics = row.ics ?: file.data, problem = null)); continue }
            // Changed on Nextcloud (or a detached one Planner can hold again).
            val server = p.fields
            val task = tasks[row.taskId]
            when {
                // Now repeating, or otherwise more than Planner can hold: Planner's task stays as it is and isn't sent again.
                server == null -> rowsDao.put(row.copy(etag = file.etag, ics = file.data, problem = SentEvent.DETACHED, conflict = null))
                // Deleted in Planner meanwhile.
                task == null || row.taskId in waiting -> rowsDao.put(row.copy(problem = SentEvent.CONFLICT, conflict = file.data))
                // Unchanged in Planner since the last sync: take Nextcloud's version, made to the task as it is when saved
                // (a due date written as it was last synced stays Planner's, see syncedDue).
                ServerTasks.inSync(row.fingerprint, task) -> {
                    val saved = store.update(task.id) { if (ServerTasks.inSync(row.fingerprint, it)) ServerTasks.apply(it, syncedDue(server, file.data, row, it, zone())) else null }
                    rowsDao.put(if (saved != null) row.copy(etag = file.etag, ics = file.data, fingerprint = ServerTasks.fingerprint(saved), problem = null, conflict = null)
                        else db.taskDao().byId(task.id)?.let { alreadyThere(row, file, it, zone()) } ?: row.copy(problem = SentEvent.CONFLICT, conflict = file.data))
                }
                // Changed in Planner too, unless Nextcloud already has it as Planner has it now.
                else -> rowsDao.put(alreadyThere(row, file, task, zone()) ?: row.copy(problem = SentEvent.CONFLICT, conflict = file.data))
            }
        }
        // Gone from Nextcloud: to Recently deleted, unless it was changed in Planner meanwhile. A pending one isn't there
        // yet: the next send writes it.
        for ((href, row) in synced) if (href !in listing && row.problem != SentEvent.CONFLICT && row.problem != SentEvent.PENDING) {
            val task = tasks[row.taskId]
            when {
                // E-11, as for events (CalendarSync.pullLocked): deleted in Planner too, Undo still on offer.
                row.taskId in waiting -> if (row.problem != SentEvent.DELETED) rowsDao.put(row.copy(problem = SentEvent.DELETED))
                task == null -> rowsDao.delete(row.id)
                ServerTasks.inSync(row.fingerprint, task) && store.archive(task.id) { ServerTasks.inSync(row.fingerprint, it) } -> rowsDao.delete(row.id)
                db.taskDao().byId(task.id) == null -> rowsDao.delete(row.id)
                else -> rowsDao.put(row.copy(problem = SentEvent.CONFLICT, conflict = ""))
            }
        }
        // A conflict whose file has gone since: Nextcloud's side is now "deleted" (not the version it had); deleted on both
        // sides, nothing is left to choose.
        for ((href, row) in synced) if (href !in listing && row.problem == SentEvent.CONFLICT && row.conflict != "") {
            if (tasks[row.taskId] == null && row.taskId !in waiting) rowsDao.delete(row.id) else rowsDao.put(row.copy(conflict = ""))
        }
        db.withTransaction { dao.source(target.id)?.let { dao.updateSource(it.copy(taskCtag = ctag ?: "", taskError = null)) } }
        _hidden.value = hidden
        onChanged()
        return true
    }

    enum class Resolution { PLANNER, NEXTCLOUD, BOTH }

    // Settles a conflict: keep Planner's version (written to Nextcloud, or Nextcloud's copy deleted if it was deleted in
    // Planner), Nextcloud's (taken into Planner, or Planner's task to Recently deleted if it was deleted there), or both
    // (Nextcloud's becomes a second Planner task; Planner's is sent as a new file). Throws with a message for the user.
    suspend fun resolve(rowId: Long, choice: Resolution) {
        lock.withLock {
            withContext(Dispatchers.IO) {
                val account = accounts.load() ?: throw BackupException("Connect to Nextcloud first.")
                val row = rowsDao.byId(rowId)?.takeIf { it.problem == SentEvent.CONFLICT } ?: return@withContext
                val target = target()?.takeIf { it.href == row.list } ?: throw BackupException("Choose the task list to keep in sync with first.")
                val href = hrefOf(target, row)
                val task = db.taskDao().byId(row.taskId)
                val current = client().getFile(account, target.href, href)
                fun changedAgain(): Nothing = throw BackupException("It changed on Nextcloud again. Check it and choose once more.")
                when (choice) {
                    Resolution.PLANNER -> when {
                        task == null -> { if (current != null && client().deleteFile(account, target.href, href, current.etag ?: changedAgain()) == WriteResult.Changed) changedAgain(); rowsDao.delete(row.id) }
                        current == null -> {
                            val uid = newTaskUid()
                            val body = ServerTasks.encode(task, uid, stamp())
                            rowsDao.put(row.copy(uid = uid, href = null, etag = null, ics = null, fingerprint = ServerTasks.fingerprint(task), problem = SentEvent.PENDING, conflict = null))
                            val result = client().putEvent(account, target.href, uid, body, null) as? WriteResult.Ok ?: changedAgain()
                            rowsDao.put(row.copy(uid = uid, href = null, etag = result.etag, ics = body, fingerprint = ServerTasks.fingerprint(task), problem = null, conflict = null))
                        }
                        else -> {
                            val body = ServerTasks.patch(current.data, task, zone(), stamp())
                            val result = client().putFile(account, target.href, href, body, current.etag ?: changedAgain()) as? WriteResult.Ok ?: changedAgain()
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
                        // Planner's task was synced, so it is sent even when done (a done task without a row never is).
                        rowsDao.put(SentTask(taskId = task.id, account = target.account, list = target.href,
                            uid = newTaskUid(), fingerprint = ServerTasks.fingerprint(task), problem = SentEvent.PENDING))
                    }
                }
            }
        }
        onChanged()
        send()
    }

    // For backups: which list Planner keeps its tasks in sync with and what it synced. E-3: conflicts are kept, without
    // Nextcloud's side (the next pull reads it again), as for events: left out, a task from Nextcloud changed on both sides
    // would match no file by content and come back as a second task in both places.
    suspend fun snapshot(): Pair<CalendarChoice, List<SentTask>>? {
        val target = target() ?: return null
        return CalendarChoice(target.account, target.href, target.name, target.color, false) to
            rowsDao.forList(target.account, target.href)
    }

    suspend fun restore(target: CalendarChoice?, rows: List<SentTask>) = paused { restoreLocked(target, rows) }

    // After restoring a backup (after CalendarSync's own restore, which rebuilds the calendar rows): its list and record,
    // or none. Inside paused.
    suspend fun restoreLocked(target: CalendarChoice?, rows: List<SentTask>) {
        lastRefused = null // a new record of what was sent: no refusal carried over
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
        _state.value = CalendarSync.State(); markChanged()
    }

    companion object {
        // What the user is told when Nextcloud refused [count] tasks (the first with HTTP [code]).
        internal fun refusedMessage(count: Int, code: Int) = CalendarSync.refusedMessage(count, code, "task")

        internal fun isPlannerUid(uid: String?) = uid != null && uid.startsWith("planner-task-") && uid.endsWith("@planner")
        internal fun newTaskUid() = "planner-task-${java.util.UUID.randomUUID()}@planner"

        // [file] is the one a pending [row] wrote (its reply was lost): the row takes it (see CalendarSync.adopted).
        internal fun adopted(row: SentTask, file: ServerFile, task: PlannerTask?, zone: ZoneId): SentTask {
            val server = ServerTasks.parse(file.data, zone).fields
            return when {
                task == null -> row.copy(etag = file.etag, problem = SentEvent.PENDING)
                server == null -> row.copy(problem = SentEvent.CHANGED)
                else -> row.copy(etag = file.etag, ics = file.data, fingerprint = ServerTasks.fingerprint(ServerTasks.apply(task, server)), problem = null)
            }
        }

        // [file] already holds [task] as Planner has it now: the row takes it as synced. Null otherwise. The due date is
        // compared as in syncedDue, so Planner's own write is recognised in any time zone. (adopted needs no such care: a
        // pending file is one Planner wrote new, whose due is a plain date.)
        internal fun alreadyThere(row: SentTask, file: ServerFile, task: PlannerTask, zone: ZoneId): SentTask? {
            val server = syncedDue(ServerTasks.parse(file.data, zone).fields ?: return null, file.data, row, task, zone)
            val print = ServerTasks.fingerprint(task)
            if (ServerTasks.fingerprint(ServerTasks.apply(task, server)) != print) return null
            return row.copy(etag = file.etag, ics = file.data, fingerprint = print, problem = null, conflict = null)
        }

        // [server] (what file [text] says) with [task]'s due date when the file's DUE is exactly the one last synced
        // ([row]'s text) and the due date hasn't changed in Planner since: a UTC due time ("…Z") counts on the phone's day
        // (see ServerTasks.dueDate), so after travelling the same DUE reads as another day, which isn't a change on either
        // side. Otherwise [server] as it is.
        internal fun syncedDue(server: ServerTasks.Fields, text: String, row: SentTask, task: PlannerTask, zone: ZoneId): ServerTasks.Fields {
            val synced = row.ics ?: return server
            if (server.dueDate == task.dueDate) return server
            val due = ServerTasks.dueLine(text) ?: return server
            return if (due == ServerTasks.dueLine(synced) && ServerTasks.dueUnchanged(synced, row.fingerprint, task, zone)) server.copy(dueDate = task.dueDate) else server
        }

        // See CalendarSync.fetchedBase: the row to send [task]'s update on, and true, when [file] (fetched: the text or
        // version was unknown) still has what was last synced; otherwise the row as settled, and false.
        internal fun fetchedBase(row: SentTask, file: ServerFile, task: PlannerTask, zone: ZoneId): Pair<SentTask, Boolean> {
            if (NextcloudClient.sameEtag(file.etag, row.etag)) return row.copy(etag = file.etag, ics = file.data) to true
            alreadyThere(row, file, task, zone)?.let { return it to false }
            val server = ServerTasks.parse(file.data, zone).fields
            return if (file.etag != null && server != null && ServerTasks.inSync(row.fingerprint, ServerTasks.apply(task, server)))
                row.copy(etag = file.etag, ics = file.data) to true
            else row.copy(problem = SentEvent.CHANGED) to false
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
        // Each task is used once; anything unsure is left alone. Files already done on Nextcloud stay there only (T3: an
        // old completed one is never a new open task's file).
        internal fun relink(all: List<Triple<String, String?, ServerTasks.Fields>>, candidates: List<PlannerTask>): Map<String, String> {
            fun key(title: String, due: java.time.LocalDate?) = title.trim().take(ServerTasks.MAX_TITLE).ifEmpty { "(No title)" } to due
            val files = all.filter { !it.third.done }
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
