package com.example.itinerary.data

import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

// A ticked calendar, as a backup stores it: which login and calendar, never its events or the password.
data class CalendarChoice(val account: String, val href: String, val name: String, val color: Int?, val enabled: Boolean = true)

// Read-only calendar sync. Nextcloud (step 1): lists the calendars on the backup login, downloads the ticked ones and keeps
// their events in their own tables; it only ever reads from the server, when the app opens (at most every 15 minutes)
// and on "Sync now". The phone's own calendars (step 3): read from Android's calendar storage when the app opens, on
// "Sync now" and whenever they change while the app is open. There is no background work. Each kind has its own
// calendars: syncing, disconnecting or restoring one never touches the other.
class CalendarSync(
    private val db: AppDatabase,
    private val accounts: NextcloudAccountStore,
    // A var only so UI tests can point the app at a local test server.
    internal var client: NextcloudClient = NextcloudClient(),
    private val onChanged: () -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    // The phone's calendars; null where there is no phone to read (tests of the Nextcloud part).
    private val phone: PhoneCalendarReader? = null,
    // Downloads subscribed calendars; a var only so UI tests can point the app at a local test server.
    internal var linkClient: CalendarLinkClient = CalendarLinkClient(),
    // Step 5: events deleted in Planner whose Undo is still on offer; their Nextcloud copies wait until it has passed.
    private val pendingDeleted: () -> Set<Long> = { emptySet() },
    // For sending a few seconds after a change (requestSend); null in tests, which call send() themselves.
    private val scope: kotlinx.coroutines.CoroutineScope? = null,
    // Planner's own events, for two-way sync (step 6); null leaves the synced calendar one-way (Planner → Nextcloud).
    private val planner: PlannerStore? = null,
) {
    data class State(val running: Boolean = false, val message: String? = null, val error: Boolean = false)

    private val dao = db.outsideDao()
    private val lock = Mutex()
    // Task sync with a Nextcloud task list, read and sent alongside (set by the app; null in tests of events only).
    internal var tasks: TaskSync? = null
    private var lastAttempt = 0L

    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()
    private val sendLock = Mutex()
    private val _sendState = MutableStateFlow(State())
    val sendState = _sendState.asStateFlow()
    val sent: Flow<List<SentEvent>> = db.sentDao().observe()
    private val debounce = SendDebounce()
    // E-9: what the last pull of the synced calendar couldn't do (too many files to list them all); shown with the send state.
    @Volatile private var partial: String? = null
    private val linkLock = Mutex()
    private var lastLinkAttempt = 0L
    private val _linkState = MutableStateFlow(State())
    val linkState = _linkState.asStateFlow()
    private val phoneLock = Mutex()
    private val _phoneState = MutableStateFlow(State())
    val phoneState = _phoneState.asStateFlow()

    val sources: Flow<List<CalendarSource>> = dao.observeSources()

    // Planner's events on Nextcloud (two-way), by event id, for the cards to mark.
    // DA-6: the calendars are narrowed to what each map needs, and an unchanged list stops there: refreshing a calendar
    // writes its lastSynced (every app open for the phone's), which needn't rebuild the maps.
    val syncMarks: Flow<Map<Long, SyncMark>> = combine(db.sentDao().observeMarks(),
        dao.observeSources().map { SyncMark.syncedCalendars(it) }.distinctUntilChanged()) { rows, synced -> SyncMark.forCards(rows, synced) }
        .distinctUntilChanged()

    // Ticked calendars' events with their calendar's name and colour, keyed by the id they are shown under.
    val shown: Flow<Map<Long, OutsideInfo>> = combine(dao.observeShown().distinctUntilChanged(),
        dao.observeSources().map { sources -> sources.associate { it.id to Label(it.name, it.color, it.kind) } }.distinctUntilChanged()) { events, byId ->
        events.mapNotNull { event ->
            val source = byId[event.sourceId] ?: return@mapNotNull null
            event.displayId() to OutsideInfo(event, source.name, source.color ?: OutsideCalendars.DEFAULT_COLOR, source.kind)
        }.toMap(LinkedHashMap())
    }.distinctUntilChanged()
    private data class Label(val name: String, val color: Int?, val kind: String)

    suspend fun hasAccount(): Boolean = withContext(Dispatchers.IO) { runCatching { accounts.load() != null }.getOrDefault(false) }

    suspend fun widgetItems(day: LocalDate): List<ItineraryItem> {
        val colors = dao.sources().associate { it.id to (it.color ?: OutsideCalendars.DEFAULT_COLOR) }
        return eventsOnDay(dao.dayCandidates(day, day.minusDays(1)).map { it.toItem(colors[it.sourceId] ?: OutsideCalendars.DEFAULT_COLOR) }, day)
    }

    // When the app opens: the phone's calendars (quick, no network), then Nextcloud if it's due — nothing without a
    // login or a ticked calendar, and at most once every 15 minutes.
    // "Sync now" (Settings → Calendars, or a tap on the sync icon): phone calendars and links, then two-way Nextcloud both ways.
    suspend fun syncNow() {
        checkBackoff.reset()
        refreshPhone(); refreshLinks()
        if (hasAccount()) { sync(); send(); tasks?.send() }
    }

    // [nextcloud] false: only the phone's calendars and links (Nextcloud is then AutoSync's, or waits for Sync now).
    suspend fun syncIfDue(nextcloud: Boolean = true) {
        refreshPhone()
        if (now() - lastLinkAttempt >= LINK_INTERVAL_MS && dao.sources().any { it.enabled && it.kind == OutsideCalendars.KIND_LINK }) refreshLinks()
        if (!nextcloud || now() - lastAttempt < MIN_INTERVAL_MS) return
        if (dao.sources().any { (it.enabled || it.tasksHere) && it.kind == OutsideCalendars.KIND_NEXTCLOUD } && hasAccount()) sync()
        send(); tasks?.send()
    }

    // A background run (see CalendarBackground): everything that has something to sync — phone calendars, links (at most
    // hourly), Nextcloud (reading and the synced calendar both ways) — then Planner's own changes. Cheap when there's
    // nothing to do.
    suspend fun backgroundSync() {
        refreshPhone()
        if (dao.sources().any { it.enabled && it.kind == OutsideCalendars.KIND_LINK }) refreshLinks()
        if (dao.sources().any { it.kind == OutsideCalendars.KIND_NEXTCLOUD && (it.enabled || it.sendHere || it.tasksHere) } && hasAccount()) sync()
        send(); tasks?.send()
    }

    // A check of AutoSync's: Nextcloud's changes (a calendar or task list is only downloaded when it changed there) and
    // whatever of Planner's is still to send, with no 15-minute wait. Quiet: no syncing cloud for a check (SY-4: its send
    // included). SY-2: while a check leaves the same problem (a calendar that won't download, a file Nextcloud refuses),
    // the next ones only ask what changed: retrying the failed download, and the send, wait longer each time (see
    // SyncBackoff). A calendar changed since it failed is still tried; Planner's own changes are sent by requestSend, and
    // by the next check whatever the backoff (S5-2: made with the switch off, or while Planner was away).
    // The send only runs when something may be waiting (sendDue). [fresh]: the connection is back, so no waiting.
    suspend fun check(fresh: Boolean = false) {
        if (!hasAccount()) return
        if (fresh) checkBackoff.reset()
        val full = checkBackoff.due(now())
        if (dao.sources().any { it.kind == OutsideCalendars.KIND_NEXTCLOUD && (it.enabled || it.sendHere || it.tasksHere) }) sync(quiet = true, retry = full)
        // Only when something may be waiting (see sendDue): a pass works out every event's fingerprint.
        // S5-2: a change made in Planner since the last send is sent whatever the backoff (made with the switch off, or
        // while Planner was away); the backoff only holds back repeating what was already tried.
        if ((full || unsentChange()) && sendDue()) send(quiet = true)
        tasks?.let { if ((full || it.unsentChange()) && it.sendDue()) it.send(quiet = true) }
        val left = problems()
        if (full) checkBackoff.after(left, now()) else checkBackoff.light(left)
    }

    // What is still wrong after a check (null: nothing): calendars or the task list that failed, and the last sync's or
    // send's error.
    private suspend fun problems(): Any? {
        val failing = dao.sources().filter { it.kind == OutsideCalendars.KIND_NEXTCLOUD && (it.lastError != null || it.taskError != null) }
            .map { Triple(it.id, it.lastError, it.taskError) }
        val errors = listOf(_state.value, _sendState.value, tasks?.state?.value).map { it?.takeIf { s -> s.error }?.message }
        return if (failing.isEmpty() && errors.all { it == null }) null else failing to errors
    }

    // SY-2: AutoSync's checks backing off (see check); and each failed calendar's ctag when it failed, so one that changed
    // there since is tried again at once.
    private val checkBackoff = SyncBackoff()
    private val failedAt = java.util.concurrent.ConcurrentHashMap<Long, String>()

    // Whether AutoSync's check has anything to send: a change in Planner since the last send that went through cleanly
    // (markChanged, requestSend), a send that failed or left a problem, or a pull that read something; and, as a safety
    // net, a pass at least every 15 minutes. Pending from the start. Sync now, syncIfDue and the background run send as
    // before, whatever this says.
    private val changes = java.util.concurrent.atomic.AtomicLong(1)
    @Volatile private var sentUpTo = 0L
    @Volatile private var lastSendPass: Long? = null

    // S5-2: Planner's own changes (markChanged) apart from the rest: one made since the last send started is sent by
    // the next check even while it backs off.
    private val local = java.util.concurrent.atomic.AtomicLong(0)
    @Volatile private var localSent = 0L

    // Something in Planner changed (the app calls this for every change, automatic sync on or off; tasks too).
    internal fun markChanged() { changes.incrementAndGet(); local.incrementAndGet(); tasks?.markChanged() }

    internal fun unsentChange(): Boolean = local.get() != localSent

    internal fun sendDue(): Boolean = changes.get() != sentUpTo || lastSendPass.let { it == null || now() - it >= SEND_SAFETY_MS }

    fun phonePermitted(): Boolean = phone?.permitted() == true

    // Lists the phone's visible calendars (new ones start unticked) and reads the ticked ones' dates in the window.
    // Without permission the phone's events leave Planner and its calendars say so. Returns false when already running.
    suspend fun refreshPhone(): Boolean {
        val reader = phone ?: return false
        if (!phoneLock.tryLock()) return false
        try {
            _phoneState.value = State(running = true)
            _phoneState.value = try {
                withContext(Dispatchers.IO) { refreshPhoneLocked(reader) }
            } catch (e: CancellationException) {
                _phoneState.value = State()
                throw e
            } catch (_: Exception) {
                State(message = "Couldn't read the phone's calendars.", error = true)
            }
            return true
        } finally {
            phoneLock.unlock()
        }
    }

    private suspend fun refreshPhoneLocked(reader: PhoneCalendarReader): State {
        val mine = dao.sources().filter { it.kind == OutsideCalendars.KIND_PHONE }
        if (!reader.permitted()) {
            if (mine.isEmpty()) return State()
            db.withTransaction {
                dao.deleteEventsOfKind(OutsideCalendars.KIND_PHONE)
                mine.forEach { dao.updateSource(it.copy(lastError = PERMISSION_NEEDED, lastSynced = null)) }
            }
            onChanged()
            return State(message = "Planner may no longer read this phone's calendars.", error = true)
        }
        // H17-S3: throws when the phone gives no answer, before the reconcile below could drop every (ticked) calendar.
        val calendars = reader.calendars().associateBy { phoneHref(it.id) }
        var changed = false
        db.withTransaction {
            val existing = dao.sources().filter { it.kind == OutsideCalendars.KIND_PHONE }
            existing.filter { it.href !in calendars }.forEach { dao.deleteSource(it.id); changed = true }
            val kept = existing.associateBy { it.href }
            calendars.forEach { (href, calendar) ->
                val old = kept[href]
                val detail = PhoneEvents.detail(calendar)
                if (old == null) dao.insertSource(CalendarSource(account = PHONE_ACCOUNT, href = href, name = calendar.name, color = calendar.color,
                    kind = OutsideCalendars.KIND_PHONE, detail = detail))
                else if (old.name != calendar.name || old.color != calendar.color || old.detail != detail || old.lastError != null)
                    dao.updateSource(old.copy(name = calendar.name, color = calendar.color, detail = detail, lastError = null))
            }
        }
        val ticked = dao.sources().filter { it.kind == OutsideCalendars.KIND_PHONE && it.enabled }
        val today = java.time.Instant.ofEpochMilli(now()).atZone(zone()).toLocalDate()
        val from = today.minusMonths(MONTHS_BACK).withDayOfMonth(1).atStartOfDay(zone()).toInstant()
        val until = today.plusMonths(MONTHS_AHEAD + 1).withDayOfMonth(1).atStartOfDay(zone()).toInstant()
        val byCalendar = if (ticked.isEmpty()) emptyMap()
            else reader.instances(ticked.mapNotNull { calendarId(it.href) }, from, until).groupBy { it.calendarId }
        for (source in ticked) {
            val events = byCalendar[calendarId(source.href)].orEmpty().mapNotNull { PhoneEvents.toEvent(it, zone()) }.distinct()
                .map { it.copy(sourceId = source.id) }
            db.withTransaction {
                val current = dao.source(source.id) ?: return@withTransaction
                // Unchanged: leave the rows (and every screen showing them) alone.
                if (current.enabled && dao.eventsFor(source.id).map { it.copy(id = 0) }.sortedWith(ORDER) != events.sortedWith(ORDER)) {
                    dao.deleteEvents(source.id)
                    dao.insertEvents(events)
                    changed = true
                }
                dao.updateSource(current.copy(lastSynced = now(), lastError = null))
            }
        }
        if (changed) onChanged()
        return State(message = when {
            calendars.isEmpty() -> "No calendars on this phone."
            ticked.isEmpty() -> "Tick the phone calendars to show in Planner."
            else -> null
        })
    }

    // ---- Keeping Planner in sync with one Nextcloud calendar (steps 5–6) ----
    // Only that calendar and its event files, and every write conditional (If-None-Match / If-Match), so nothing Planner
    // hasn't seen is replaced. sent_events remembers, per Planner event, its file, version and what was last synced; each
    // pass compares both sides with that. Planner → Nextcloud a few seconds after a change (send); Nextcloud → Planner
    // when calendars sync (pull: on open at most every 15 minutes, and Sync now). Changed on both sides = a conflict the
    // user settles (resolve). Events Planner can't hold exactly (repeating, timed over a day) stay read-only there.

    enum class Resolution { PLANNER, NEXTCLOUD, BOTH }

    val conflicts: Flow<List<SentEvent>> = sent.map { rows -> rows.filter { it.problem == SentEvent.CONFLICT } }

    // Keeps Planner in sync with [id] (a writable Nextcloud calendar), or stops (null; the copies stay on Nextcloud).
    // Upcoming events are sent; past ones are noted and sent once edited; the calendar's own events come into Planner.
    // Choosing another calendar leaves the old one as it is.
    suspend fun setSendTarget(id: Long?) {
        // Not while a send, pull or choice is under way (see paused); released before the sync that follows.
        sendLock.withLock {
            // S6-6: a refusal by the calendar left isn't about the new one (inside the lock: no send can set it again).
            lastRefused = null
            db.withTransaction {
                val sources = dao.sources().filter { it.kind == OutsideCalendars.KIND_NEXTCLOUD }
                sources.filter { it.sendHere && it.id != id }.forEach { dao.deleteEvents(it.id); dao.updateSource(it.copy(sendHere = false, enabled = false, ctag = null, fetchedFor = null, note = null)) }
                takeUp(sources.firstOrNull { it.id == id && it.writable && it.events } ?: return@withTransaction)
            }
            _sendState.value = State()
        }
        onChanged()
        // R18-S2: waits for a quiet check holding the lock, so the chosen calendar is read now, not by a later sync.
        if (id != null) { sync(wait = true); send() }
    }

    // Runs [block] with no send, pull or conflict choice under way, and none starting until it's done. Those work from
    // the events and the record of what was sent as they read them at the start, and write rows back by event: one
    // running across a change of either (a restore, a new synced calendar) would put rows naming the old events back
    // over the new record (wrong files overwritten, duplicates, false conflicts). Inside, use the *Locked functions only
    // (the lock isn't reentrant).
    suspend fun <T> paused(block: suspend () -> T): T = sendLock.withLock { block() }

    // In a transaction: [target] becomes the synced calendar and is read before anything new is sent there; past events
    // without a record are only noted.
    private suspend fun takeUp(target: CalendarSource) {
        val sentDao = db.sentDao()
        // Its read-only events show like a ticked calendar's; its other events become Planner events.
        dao.updateSource(target.copy(sendHere = true, enabled = true, ctag = null, fetchedFor = null, lastError = null))
        sentDao.deleteOtherCalendars(target.account, target.href)
        val known = sentDao.itemIds().toHashSet()
        val today = java.time.Instant.ofEpochMilli(now()).atZone(zone()).toLocalDate()
        db.itemDao().all().filter { sendable(it) && it.id !in known && it.lastDay < today }.forEach {
            sentDao.put(SentEvent(itemId = it.id, account = target.account, calendar = target.href, uid = null, fingerprint = fingerprint(it),
                ics = notedDays(it)))
        }
    }

    // After restoring a backup that doesn't say what was sent (made before backups kept that): the old record named
    // other events, so none is kept, and the synced calendar is taken up again as when choosing it. The next pull links
    // its files to the restored events by content; nothing is overwritten or deleted on the strength of an old record.
    // Called inside paused (backup restore).
    suspend fun forgetSentLocked() {
        lastRefused = null // a new record of what was sent: no refusal carried over
        db.withTransaction {
            db.sentDao().deleteAll()
            dao.sources().firstOrNull { it.kind == OutsideCalendars.KIND_NEXTCLOUD && it.sendHere }?.let { takeUp(it) }
        }
        _sendState.value = State(); changes.incrementAndGet()
    }

    // Sends a few seconds after a change in Planner, so a burst of edits is one pass. A send already under way is never
    // cancelled (a write that reached Nextcloud would go unrecorded and come back as a conflict with itself): it
    // finishes, and another pass follows it a few seconds later.
    fun requestSend() {
        markChanged()
        val scope = scope ?: return
        checkBackoff.reset()
        debounce.launch(scope, SEND_DELAY_MS, send = { send() }, sendAgain = ::requestSend)
    }

    // One pass Planner → Nextcloud: creates what's new, updates what changed (only what Planner manages in the file),
    // deletes what Planner no longer has (not while its Undo is on offer). A write that finds the server copy changed or
    // gone leaves it and marks it for the next pull. Offline, the rest waits for the next pass.
    // [quiet]: AutoSync's check; no syncing cloud (SY-4: it used to rain every minute, with nothing to send).
    suspend fun send(quiet: Boolean = false): Boolean = sendLock.withLock {
        // Planner's changes up to here are this pass's (sent, or tried: a failure waits for the backoff); with nowhere to
        // send them, there is nothing to send.
        localSent = local.get()
        val target = dao.sources().firstOrNull { it.kind == OutsideCalendars.KIND_NEXTCLOUD && it.sendHere } ?: return@withLock true
        val account = withContext(Dispatchers.IO) { runCatching { accounts.load() }.getOrNull() } ?: return@withLock true
        if (target.account != accountKey(account)) return@withLock true
        if (!quiet) _sendState.value = State(running = true)
        // Read before the pass reads the events: a change made during it is still to send afterwards.
        val seen = changes.get()
        lastSendPass = now()
        _sendState.value = try {
            withContext(Dispatchers.IO) { sendLocked(account, target) }
        } catch (e: CancellationException) {
            _sendState.value = State()
            throw e
        } catch (e: Exception) {
            State(message = ((e as? BackupException)?.message ?: "Couldn't send events to Nextcloud.") + " The rest will be sent later.", error = true)
        }
        if (!_sendState.value.error) sentUpTo = seen
        true
    }

    private suspend fun sendLocked(account: NextcloudAccount, target: CalendarSource): State {
        val sentDao = db.sentDao()
        val items = db.itemDao().all().filter { it.id > 0 }
        val present = items.mapTo(HashSet()) { it.id }
        val rows = sentDao.forCalendar(target.account, target.href).associateByTo(HashMap()) { it.itemId }
        val waiting = pendingDeleted()
        // Two-way, new files wait until the calendar has been read since it was chosen: a reconnect first links the
        // files already there to their Planner events (pullLocked) instead of sending every event a second time.
        val checked = planner == null || target.fetchedFor != null
        // Past events before the pull window are only noted then; one edited since may have a file there already. E16-1:
        // also one moved since into the window or later (its file is still on the days it was noted on).
        if (checked && planner != null) { val start = linkWindow().first
            val (edited, unedited) = items.filter { item -> rows[item.id]?.let { it.uid == null && it.problem == null && sendable(item) &&
                (item.lastDay < start || notedDays(it)?.let { (_, last) -> last < start } == true) } == true }
                .partition { !inSync(rows.getValue(it.id).fingerprint, it) }
            rows += linkEdited(account, target, rows, edited, unedited)
        }
        // E-1 (as T1 for tasks): a file Nextcloud refuses (not the login or the connection) holds up only its own event: the
        // row stays as it was, so the next pass tries again, and the others go on.
        val refused = mutableListOf<RefusedException>()
        for (item in items) {
            var row = rows[item.id]
            val pending = row?.problem == SentEvent.PENDING
            if (row?.problem != null && !pending) continue // settled by the next pull or by the user
            if (!sendable(item)) {
                // A bill now, or a skipped date: its copy goes, as for an event deleted in Planner (below): changed on
                // Nextcloud first, the row stays for the next pull to make it a conflict, not a new event.
                if (row != null) {
                    val kept = try { afterDelete(row, removeCopy(account, target, row)).takeIf { planner != null } }
                        catch (e: RefusedException) { refused += e; continue }
                    if (kept == null) sentDao.delete(row.id) else if (kept != row) sentDao.put(kept)
                }
                continue
            }
            val print = fingerprint(item)
            // A row from before this version (see inSync) still describing the event: brought up to date, nothing sent.
            if (row != null && row.fingerprint != print && inSync(row.fingerprint, item, zone())) row = row.copy(fingerprint = print).also { sentDao.put(it) }
            try { when {
                pending || row == null || row.uid == null && row.fingerprint != print -> {
                    if (!pending && !checked) continue
                    val uid = row?.uid?.takeIf { pending } ?: newEventUid()
                    val body = CalendarExport.encode(item, uid, zone(), java.time.Instant.ofEpochMilli(now()))
                    // Noted before writing, so a reply lost on the way back leaves this uid to retry, not a second file.
                    val noted = SentEvent(id = row?.id ?: 0, itemId = item.id, account = target.account, calendar = target.href,
                        uid = uid, fingerprint = print, problem = SentEvent.PENDING)
                    val fresh = noted.copy(id = sentDao.put(noted))
                    when (val result = client.putEvent(account, target.href, uid, body, null)) {
                        is WriteResult.Ok -> sentDao.put(fresh.copy(etag = result.etag, ics = body, problem = null))
                        // Already there: the earlier try arrived after all.
                        WriteResult.Changed -> client.getFile(account, target.href, hrefOf(target, fresh))?.let { adopt(fresh, it, item) }
                            ?: sentDao.put(fresh.copy(problem = SentEvent.CHANGED))
                        WriteResult.Missing -> throw BackupException("The calendar Planner syncs with wasn't found on Nextcloud.")
                    }
                }
                row.uid != null && row.fingerprint != print -> {
                    val href = hrefOf(target, row)
                    // Change only what Planner manages in the file as last synced. Fetched if unknown, or if its version
                    // is (E-7: a write without one would go over whatever is there now).
                    if (row.ics == null || row.etag == null) {
                        val file = client.getFile(account, target.href, href)
                        if (file == null) { sentDao.put(row.copy(problem = SentEvent.DELETED)); continue }
                        val (next, send) = fetchedBase(row, file, item, zone())
                        if (!send) { sentDao.put(next); continue }
                        row = next
                    }
                    val base = row.ics!!
                    val body = ServerEvents.patch(base, item, zone(), java.time.Instant.ofEpochMilli(now()))
                    when (val result = client.putFile(account, target.href, href, body, row.etag!!)) {
                        is WriteResult.Ok -> sentDao.put(row.copy(etag = result.etag, ics = body, fingerprint = print))
                        // Changed there: unless it already is Planner's event as it is now (an earlier write whose reply
                        // never got recorded), the next pull compares.
                        // The check is a safety net only: if it can't be made, the row is CHANGED as before (the send goes on).
                        WriteResult.Changed -> sentDao.put(try { client.getFile(account, target.href, href)?.let { alreadyThere(row, it, item, zone()) } }
                            catch (e: CancellationException) { throw e } catch (_: Exception) { null }
                            ?: row.copy(problem = SentEvent.CHANGED))
                        WriteResult.Missing -> sentDao.put(row.copy(problem = SentEvent.DELETED))
                    }
                }
            } } catch (e: RefusedException) { refused += e }
        }
        // Deleted in Planner (and past its Undo): the copy goes too, unless it was changed on Nextcloud meanwhile; then the
        // row stays for the next pull to make it a conflict, rather than the file coming back as a new event (one-way, with
        // no pull, it goes as before).
        for (row in rows.values) if (row.itemId !in present && row.itemId !in waiting && row.problem != SentEvent.CONFLICT) {
            val kept = try { afterDelete(row, removeCopy(account, target, row)).takeIf { planner != null } }
                catch (e: RefusedException) { refused += e; continue }
            if (kept == null) sentDao.delete(row.id) else if (kept != row) sentDao.put(kept)
        }
        lastRefused = if (refused.isEmpty()) null else refusedMessage(refused.size, refused.first().code)
        return summary(target)
    }

    // What the last send pass couldn't send (Nextcloud refused files), kept in the summary until a pass sends them, so a
    // pull in between (Planner reading its own writes back) doesn't make the refusal vanish from the sync icon.
    @Volatile private var lastRefused: String? = null

    private suspend fun summary(target: CalendarSource): State {
        val conflicts = db.sentDao().countWith(target.href, listOf(SentEvent.CONFLICT))
        val waiting = db.sentDao().countWith(target.href, listOf(SentEvent.CHANGED, SentEvent.DELETED))
        val state = conflictSummary("event", conflicts, waiting) ?: State(message = partial)
        return lastRefused?.let { State(message = listOfNotNull(it, state.message).joinToString(" "), error = true) } ?: state
    }

    // See removeSyncedCopy.
    private fun removeCopy(account: NextcloudAccount, target: CalendarSource, row: SentEvent): WriteResult? =
        removeSyncedCopy({ client }, account, target.href, { hrefOf(target, row) }, row.uid, row.problem, row.etag, row.ics)

    // The files of [edited] events (noted past ones, edited since) looked for around their dates only, and linked (see
    // editedFiles, linkedForSend): their rows now. The pull doesn't read the whole history for them. The [noted] past
    // events (unedited) whose days lie in the ranges read take part, so an edited one never gets a neighbour's file (a
    // series); their own rows stay as they are. A lookup that fails (a reply too large, a server error) is skipped, and
    // an event whose file isn't found exactly is sent as a new file, as before there was a lookup.
    private suspend fun linkEdited(account: NextcloudAccount, target: CalendarSource, rows: Map<Long, SentEvent>, edited: List<ItineraryItem>,
                                   noted: List<ItineraryItem>): Map<Long, SentEvent> {
        if (edited.isEmpty()) return emptyMap()
        val known = rows.values.filter { it.uid != null }.mapTo(HashSet()) { hrefOf(target, it) }
        val ranges = lookupRanges(edited, edited.mapNotNull { notedDays(rows.getValue(it.id)) })
        val neighbours = noted.filter { item -> ranges.any { (start, end) -> item.date < end && item.lastDay >= start } }
        val found = ranges.flatMap { (start, end) ->
            try { filesBetween(account, target, start, end) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { android.util.Log.w("CalendarSync", "Skipped looking for past events' files ($start to $end)", e); emptyList() }
        }.filter { it.href !in known }.associateBy { it.href }
        val parsed = found.mapValues { ServerEvents.parse(it.value.data, zone()) }
        val byId = edited.associateBy { it.id }
        return editedFiles(parsed.mapNotNull { (href, p) -> p.item?.let { Triple(href, p.uid, it) } }, edited, neighbours,
            (edited + neighbours).associate { it.id to rows.getValue(it.id).fingerprint }, zone()).entries.associate { (href, id) ->
            val p = parsed.getValue(href)
            id to linkedForSend(rows.getValue(id), href, p.uid ?: href.substringAfterLast('/').removeSuffix(".ics"), found.getValue(href),
                p.item!!, byId.getValue(id), zone()).also { db.sentDao().put(it) }
        }
    }

    private suspend fun adopt(row: SentEvent, file: ServerFile, item: ItineraryItem?) { db.sentDao().put(adopted(row, file, item, zone())) }

    // The event files of [target] from [start] to [end] (exclusive). E-9: a range whose reply is too large to read at once
    // is read in halves (down to a single day), so a calendar with many events still syncs. [budget]: how many too-large
    // replies to take before giving up (one that stays too large however it's split, such as many repeating events, isn't
    // downloaded over and over).
    private fun filesBetween(account: NextcloudAccount, target: CalendarSource, start: LocalDate, end: LocalDate,
                             budget: IntArray = intArrayOf(SPLIT_BUDGET)): List<ServerFile> =
        try { client.calendarFiles(account, target.href, start.atStartOfDay(zone()).toInstant(), end.atStartOfDay(zone()).toInstant()) }
        catch (e: ReplyTooLargeException) {
            val halves = splitRange(start, end)?.takeIf { budget[0]-- > 0 } ?: throw e
            halves.flatMap { (from, until) -> filesBetween(account, target, from, until, budget) }.distinctBy { it.href }
        }

    // The file of [row]: its own name for events that came from Nextcloud, "<uid>.ics" for ones Planner created.
    private fun hrefOf(target: CalendarSource, row: SentEvent): String = row.href ?: "${target.href}${row.uid}.ics"

    // One pass Nextcloud → Planner for the synced calendar: nothing when its change marker says nothing changed (false).
    private suspend fun pullLocked(account: NextcloudAccount, target: CalendarSource, ctag: String?): Boolean {
        val store = planner ?: return false
        val sentDao = db.sentDao()
        val (from, until, key) = linkWindow()
        // After a failed pull (lastError) the next one always reads, so the error clears only once one works. Asked before
        // any row is read: an unchanged calendar (most checks) reads none.
        if (ctag != null && ctag == target.ctag && target.fetchedFor == key && target.lastError == null &&
            !sentDao.unsettled(target.account, target.href)) return false
        val rows = sentDao.forCalendar(target.account, target.href)
        // E-9: a calendar with too many files to list at once is still synced, all but noticing deletions there of events
        // nothing is waiting on (see gone); the window is read in parts when it's too large in one.
        val listing = try { client.eventEtags(account, target.href) } catch (_: ReplyTooLargeException) { null }
        partial = if (listing == null) "${target.name} has too many events for Planner to list them all, so events deleted there " +
            "stay in Planner until you change them. Everything else syncs." else null
        val inWindow = filesBetween(account, target, from, until.plusDays(1)).associateBy { it.href }
        val synced = rows.filter { it.uid != null }.associateBy { hrefOf(target, it) }
        // Synced events outside the window that changed there are fetched too; without the listing, the ones waiting on a
        // check (a write found them changed or gone).
        val older = if (listing != null) synced.filter { (href, row) -> href in listing && !NextcloudClient.sameEtag(listing[href], row.etag) && href !in inWindow }.keys
            else synced.filter { (href, row) -> href !in inWindow && (row.problem == SentEvent.CHANGED || row.problem == SentEvent.DELETED) }.keys
        val files = inWindow + client.multiget(account, target.href, older).associateBy { it.href }
        // No longer on Nextcloud: not in the listing; without it, one looked for by name and not found.
        fun gone(href: String) = if (listing != null) href !in listing else href in older && href !in files
        val items = db.itemDao().all().associateBy { it.id }
        val waiting = pendingDeleted()
        val readOnly = mutableListOf<OutsideEvent>()
        // R18-S1: files whose repeats Planner can't follow (BYSETPOS, BYDAY=1MO,3MO…), with what Planner can show of them;
        // read below as the server expands them, as the other Nextcloud calendars are.
        val unread = mutableMapOf<String, List<OutsideEvent>>()
        fun shown(file: ServerFile): List<OutsideEvent> {
            val window = CalendarFileImport.window(file.data, zone(), from, until)
            if (window.unreadRepeats == 0) return window.events
            unread[file.href] = window.events; return emptyList()
        }
        // Files Planner has no row for that are its own events already (after reconnecting, or choosing this calendar
        // again): linked to them, not brought in as copies. Planner events with a row only noting them (past) count too.
        val linkedIds = rows.filter { it.uid != null || it.problem != null }.mapTo(HashSet()) { it.itemId }
        val noted = rows.filter { it.uid == null && it.problem == null }.associateBy { it.itemId }
        val unlinked = items.values.filter { sendable(it) && it.id !in linkedIds && it.id !in waiting }
        // Events outside the window may have files too (send would write them again): those are read only to link them.
        val outside = linkRanges(unlinked, until).flatMap { (start, end) -> filesBetween(account, target, start, end) }.filter { it.href !in synced && it.href !in files }.associateBy { it.href }
        val parsedFiles = (files + outside).mapValues { ServerEvents.parse(it.value.data, zone()) }
        val relinked = relink(parsedFiles.filterKeys { it !in synced }.mapNotNull { (href, p) -> p.item?.let { Triple(href, p.uid, it) } }, unlinked)
        fun uidOf(href: String) = parsedFiles[href]?.uid ?: href.substringAfterLast('/').removeSuffix(".ics")
        // What Nextcloud has counts as synced; if Planner's event differs, the user chooses which to keep.
        suspend fun link(href: String, file: ServerFile, server: ItineraryItem, item: ItineraryItem) {
            val print = fingerprint(ServerEvents.apply(item, server))
            val same = print == fingerprint(item)
            sentDao.put(SentEvent(id = noted[item.id]?.id ?: 0, itemId = item.id, account = target.account, calendar = target.href,
                uid = uidOf(href), href = href, etag = file.etag, ics = file.data, fingerprint = print,
                problem = if (same) null else SentEvent.CONFLICT, conflict = if (same) null else file.data))
        }
        for ((href, file) in outside) { val item = relinked[href]?.let { items[it] } ?: continue; link(href, file, parsedFiles.getValue(href).item ?: continue, item) }
        for ((href, file) in files) {
            val parsed = parsedFiles.getValue(href)
            val row = synced[href]
            if (row == null) {
                val server = parsed.item
                if (server == null) { readOnly += runCatching { shown(file) }.getOrDefault(emptyList()); continue }
                val uid = uidOf(href)
                val own = relinked[href]?.let { items[it] }
                if (own != null) { link(href, file, server, own); continue }
                // New on Nextcloud: a Planner event from now on.
                val id = store.add(server)
                val added = db.itemDao().byId(id) ?: continue
                sentDao.put(SentEvent(itemId = id, account = target.account, calendar = target.href,
                    uid = uid, href = href, etag = file.etag, ics = file.data,
                    fingerprint = fingerprint(added)))
                continue
            }
            // Nextcloud's side of a conflict, kept up to date; read again after a restore (backups keep the conflict, not it).
            if (row.problem == SentEvent.CONFLICT) { if (!NextcloudClient.sameEtag(file.etag, row.etag) || row.conflict == null) sentDao.put(row.copy(conflict = file.data)); continue }
            if (row.problem == SentEvent.PENDING) { adopt(row, file, items[row.itemId]); continue }
            val detached = row.problem == SentEvent.DETACHED
            if (detached && parsed.item == null) {
                // Still read-only: shown from Nextcloud as before, and remembered so it's neither sent nor brought in.
                readOnly += runCatching { shown(file) }.getOrDefault(emptyList())
                if (!NextcloudClient.sameEtag(file.etag, row.etag)) sentDao.put(row.copy(etag = file.etag, ics = file.data))
                continue
            }
            // Unchanged there. One a write found changed or gone is there as synced after all (its version read in another
            // form, see NextcloudClient.etag): the next send tries again.
            if (NextcloudClient.sameEtag(file.etag, row.etag) && !detached) {
                if (row.ics == null || row.problem != null) sentDao.put(row.copy(ics = row.ics ?: file.data, problem = null)); continue }
            // Changed on Nextcloud (or a detached one Planner can hold again).
            val server = parsed.item
            val item = items[row.itemId]
            when {
                // Now repeating, or otherwise more than Planner can hold: it's read-only from now on; Planner's event stays.
                server == null -> { sentDao.put(row.copy(etag = file.etag, ics = file.data, problem = SentEvent.DETACHED, conflict = null))
                    readOnly += runCatching { shown(file) }.getOrDefault(emptyList()) }
                // Deleted in Planner meanwhile, or no longer on Nextcloud (a bill now, a skipped date).
                item == null || row.itemId in waiting || !sendable(item) -> sentDao.put(row.copy(problem = SentEvent.CONFLICT, conflict = file.data))
                // Unchanged in Planner since the last sync: take Nextcloud's version (Planner-only details stay). Made to the
                // event as it is when saved: one edited or deleted in Planner while this pull ran is compared as below.
                inSync(row.fingerprint, item) -> {
                    val saved = store.update(item.id) { pulled(row, it, server, zone()) }
                    sentDao.put(if (saved != null) row.copy(etag = file.etag, ics = file.data, fingerprint = fingerprint(saved), problem = null, conflict = null)
                        else db.itemDao().byId(item.id)?.let { alreadyThere(row, file, it, zone()) } ?: row.copy(problem = SentEvent.CONFLICT, conflict = file.data))
                }
                // Changed in Planner too, but Nextcloud already has it as Planner has it now (Planner's own write, not
                // recorded): nothing to choose.
                else -> sentDao.put(alreadyThere(row, file, item, zone()) ?: row.copy(problem = SentEvent.CONFLICT, conflict = file.data))
            }
        }
        // Gone from Nextcloud: to Recently deleted, unless it was changed in Planner meanwhile.
        // A pending one isn't there yet: the next send writes it. A restored conflict learns again that it's gone there.
        for ((href, row) in synced) if (gone(href) && row.problem == SentEvent.CONFLICT && row.conflict == null) sentDao.put(row.copy(conflict = ""))
        for ((href, row) in synced) if (gone(href) && row.problem != SentEvent.CONFLICT && row.problem != SentEvent.PENDING) {
            val item = items[row.itemId]
            when {
                // E-11: deleted in Planner too, with its Undo still on offer: the row stays, marked gone there. Undone, the
                // next pull handles it as any event deleted on Nextcloud (to Recently deleted, or a conflict if changed),
                // instead of the next send making it again as a new file; not undone, the send forgets the row.
                row.itemId in waiting -> if (row.problem != SentEvent.DELETED) sentDao.put(row.copy(problem = SentEvent.DELETED))
                // Gone, or its copy was to go anyway (a bill now, a skipped date): the event itself stays as it is.
                item == null || !sendable(item) -> sentDao.delete(row.id)
                // Only if still unchanged when it's moved: one edited while this pull ran is a conflict too.
                inSync(row.fingerprint, item) && store.archive(item.id) { inSync(row.fingerprint, it) } -> sentDao.delete(row.id)
                db.itemDao().byId(item.id) == null -> sentDao.delete(row.id)
                else -> sentDao.put(row.copy(problem = SentEvent.CONFLICT, conflict = ""))
            }
        }
        // R18-S1: expanded by the server; a file it doesn't send expanded keeps what Planner can show (its first date, in
        // the window), and the calendar's note says so.
        var unshown = 0
        if (unread.isNotEmpty()) {
            val expanded = try {
                client.multiget(account, target.href, unread.keys,
                    expand = from.atStartOfDay(zone()).toInstant() to until.plusDays(1).atStartOfDay(zone()).toInstant()).associateBy { it.href }
            } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyMap() }
            for ((href, own) in unread) {
                // Sent back as it is (a server that doesn't expand here): not expanded.
                val events = expanded[href]?.data?.takeUnless { Regex("(?im)^RRULE[:;]").containsMatchIn(it) }
                    ?.let { OutsideEventReader.read(listOf(it), zone()).events }
                if (events != null) readOnly += events else { readOnly += own; unshown++ }
            }
        }
        db.withTransaction {
            dao.deleteEvents(target.id)
            dao.insertEvents(readOnly.distinct().map { it.copy(id = 0, sourceId = target.id) })
            dao.source(target.id)?.let { dao.updateSource(it.copy(ctag = ctag, fetchedFor = key, lastSynced = now(), lastError = null,
                note = unreadRepeatsNote(unshown))) }
        }
        onChanged()
        return true
    }

    // Settles a conflict: keep Planner's version (written to Nextcloud, or Nextcloud's copy deleted if it was deleted in
    // Planner), Nextcloud's (taken into Planner, or Planner's event to Recently deleted if it was deleted there), or both
    // (Nextcloud's becomes a second Planner event; Planner's is sent as a new file). Throws with a message for the user.
    suspend fun resolve(rowId: Long, choice: Resolution) {
        sendLock.withLock {
            withContext(Dispatchers.IO) {
                val store = planner ?: return@withContext
                val sentDao = db.sentDao()
                val account = accounts.load() ?: throw BackupException("Connect to Nextcloud first.")
                val row = sentDao.byId(rowId)?.takeIf { it.problem == SentEvent.CONFLICT } ?: return@withContext
                val target = dao.sources().firstOrNull { it.kind == OutsideCalendars.KIND_NEXTCLOUD && it.sendHere && it.href == row.calendar }
                    ?: throw BackupException("Choose the calendar to keep in sync with first.")
                val href = hrefOf(target, row)
                val stored = db.itemDao().byId(row.itemId)
                // One that is a bill now, or a skipped date, has no copy on Nextcloud any more: settled as if deleted in
                // Planner (Planner's: the file goes; Nextcloud's: it comes in as an event of its own). The bill stays.
                val item = stored?.takeIf { sendable(it) }
                val current = client.getFile(account, target.href, href)
                val stamp = java.time.Instant.ofEpochMilli(now())
                fun changedAgain(): Nothing = throw BackupException("It changed on Nextcloud again. Check it and choose once more.")
                when (choice) {
                    Resolution.PLANNER -> when {
                        item == null -> { if (current != null && client.deleteFile(account, target.href, href, current.etag ?: changedAgain()) == WriteResult.Changed) changedAgain(); sentDao.delete(row.id) }
                        current == null -> {
                            val uid = newEventUid()
                            val body = CalendarExport.encode(item, uid, zone(), stamp)
                            // Noted first (see sendLocked): a lost reply is retried under this uid.
                            sentDao.put(row.copy(uid = uid, href = null, etag = null, ics = null, fingerprint = fingerprint(item), problem = SentEvent.PENDING, conflict = null))
                            val result = client.putEvent(account, target.href, uid, body, null) as? WriteResult.Ok ?: changedAgain()
                            sentDao.put(row.copy(uid = uid, href = null, etag = result.etag, ics = body, fingerprint = fingerprint(item), problem = null, conflict = null))
                        }
                        // E16-3: repeating or cancelled there since (more than Planner can hold, as for Keep Nextcloud's):
                        // patched, Planner's event would become that series, or stay cancelled. Nothing is written; the
                        // conflict stays for the user to choose again.
                        ServerEvents.parse(current.data, zone()).item == null -> throw BackupException(PLANNERS_WONT_FIT)
                        else -> {
                            val body = ServerEvents.patch(current.data, item, zone(), stamp)
                            val result = client.putFile(account, target.href, href, body, current.etag ?: changedAgain()) as? WriteResult.Ok ?: changedAgain()
                            sentDao.put(row.copy(etag = result.etag, ics = body, fingerprint = fingerprint(item), problem = null, conflict = null))
                        }
                    }
                    Resolution.NEXTCLOUD -> {
                        val server = current?.let { ServerEvents.parse(it.data, zone()).item }
                        when {
                            current == null -> { item?.let { store.archive(setOf(it.id)) }; sentDao.delete(row.id) }
                            // Read-only there now; Planner's event stays as it is, and isn't sent again.
                            server == null -> sentDao.put(row.copy(etag = current.etag, ics = current.data, problem = SentEvent.DETACHED, conflict = null))
                            else -> {
                                val id = if (item != null) { store.update(ServerEvents.apply(item, server)); item.id } else store.add(server)
                                val saved = db.itemDao().byId(id) ?: return@withContext
                                sentDao.put(row.copy(itemId = id, etag = current.etag, ics = current.data, fingerprint = fingerprint(saved), problem = null, conflict = null))
                            }
                        }
                    }
                    Resolution.BOTH -> {
                        val server = current?.let { ServerEvents.parse(it.data, zone()).item }
                        require(stored != null && current != null && server != null) { "Both versions are needed to keep both." }
                        // Nextcloud's file now belongs to a new Planner event; Planner's own event is sent as a new file.
                        val id = store.add(server)
                        val saved = db.itemDao().byId(id) ?: return@withContext
                        sentDao.put(row.copy(itemId = id, etag = current.etag, ics = current.data, fingerprint = fingerprint(saved), problem = null, conflict = null))
                    }
                }
            }
        }
        onChanged()
        send()
    }

    private fun inSync(stored: String, item: ItineraryItem) = inSync(stored, item, zone())

    // For backups: which calendar Planner keeps in sync with and what it synced, so a restore doesn't send everything
    // again. Conflicts are kept, without Nextcloud's side (the next pull reads it again): left out, an event from
    // Nextcloud changed on both sides would match no file by content and come back as a second event.
    suspend fun sendSnapshot(): Pair<CalendarChoice, List<SentEvent>>? {
        val target = dao.sources().firstOrNull { it.kind == OutsideCalendars.KIND_NEXTCLOUD && it.sendHere } ?: return null
        return CalendarChoice(target.account, target.href, target.name, target.color, target.enabled) to
            db.sentDao().forCalendar(target.account, target.href)
    }

    suspend fun restoreSend(target: CalendarChoice?, rows: List<SentEvent>) = paused { restoreSendLocked(target, rows) }

    // restoreSend, inside paused.
    suspend fun restoreSendLocked(target: CalendarChoice?, rows: List<SentEvent>) {
        lastRefused = null // a new record of what was sent: no refusal carried over
        db.withTransaction {
            db.sentDao().deleteAll()
            dao.sources().filter { it.sendHere }.forEach { dao.updateSource(it.copy(sendHere = false, note = null)) }
            if (target == null) return@withTransaction
            val existing = dao.sources().firstOrNull { it.kind == OutsideCalendars.KIND_NEXTCLOUD && it.account == target.account && it.href == target.href }
            if (existing != null) dao.updateSource(existing.copy(sendHere = true, enabled = true, ctag = null, fetchedFor = null))
            else dao.insertSource(CalendarSource(account = target.account, href = target.href, name = target.name, color = target.color,
                enabled = true, sendHere = true))
            db.sentDao().insertAll(rows.filter { it.account == target.account && it.calendar == target.href }.map { it.copy(id = 0) })
        }
        _sendState.value = State(); changes.incrementAndGet()
    }

    // ---- Calendars subscribed to by link (step 4) ----

    // Subscribes to [input] (https or webcal) after downloading it once to check it's a calendar. [name] overrides the
    // calendar's own name. Throws with a message for the user.
    suspend fun addLink(input: String, name: String?) = withContext(Dispatchers.IO) {
        val url = CalendarLinks.normalize(input)
        val href = url.toString()
        require(dao.sources().none { it.kind == OutsideCalendars.KIND_LINK && it.href == href }) { "You're already subscribed to this calendar." }
        val fetched = linkClient.fetch(url) as? CalendarLinkClient.Result.Fetched ?: throw BackupException("${url.host} sent no calendar.")
        val (from, until, key) = linkWindow()
        val read = CalendarFileImport.window(fetched.text, zone(), from, until)
        val used = dao.sources().filter { it.kind == OutsideCalendars.KIND_LINK }.mapNotNull { it.color }
        db.withTransaction {
            val id = dao.insertSource(CalendarSource(account = LINK_ACCOUNT, href = href, kind = OutsideCalendars.KIND_LINK,
                name = name?.trim()?.takeIf { it.isNotEmpty() }?.take(200) ?: read.name ?: url.host, detail = url.host,
                color = read.color ?: OutsideCalendars.LINK_COLORS.minBy { c -> used.count { it == c } },
                enabled = true, ctag = validator(fetched), fetchedFor = key, lastSynced = now(), note = unreadRepeatsNote(read.unreadRepeats)))
            dao.insertEvents(read.events.map { it.copy(sourceId = id) })
        }
        onChanged()
    }

    // Downloads the ticked subscriptions again ([only] = just that one). The server says when nothing changed, so an
    // unchanged calendar costs almost nothing. A failed one keeps its last events and shows why on its row.
    // Returns false when already running.
    suspend fun refreshLinks(only: Long? = null): Boolean {
        if (!linkLock.tryLock()) return false
        try {
            lastLinkAttempt = now()
            _linkState.value = State(running = true)
            _linkState.value = try {
                withContext(Dispatchers.IO) { refreshLinksLocked(only) }
            } catch (e: CancellationException) {
                _linkState.value = State()
                throw e
            }
            return true
        } finally {
            linkLock.unlock()
        }
    }

    private suspend fun refreshLinksLocked(only: Long?): State {
        val (from, until, key) = linkWindow()
        var changed = false
        var failed = 0
        for (source in dao.sources().filter { it.kind == OutsideCalendars.KIND_LINK && it.enabled && (only == null || it.id == only) }) {
            try {
                // A new month or time zone downloads in full; otherwise only if the calendar changed.
                val (etag, modified) = if (source.fetchedFor == key) validators(source.ctag) else null to null
                when (val result = linkClient.fetch(CalendarLinks.normalize(source.href), etag, modified)) {
                    is CalendarLinkClient.Result.NotModified ->
                        db.withTransaction { dao.source(source.id)?.let { dao.updateSource(it.copy(lastSynced = now(), lastError = null)) } }
                    is CalendarLinkClient.Result.Fetched -> {
                        val read = CalendarFileImport.window(result.text, zone(), from, until)
                        db.withTransaction {
                            val current = dao.source(source.id) ?: return@withTransaction
                            dao.deleteEvents(source.id)
                            if (!current.enabled) return@withTransaction
                            dao.insertEvents(read.events.map { it.copy(sourceId = source.id) })
                            // R18-S1: repeating events it can't follow are noted on its row.
                            dao.updateSource(current.copy(ctag = validator(result), fetchedFor = key, lastSynced = now(), lastError = null,
                                note = unreadRepeatsNote(read.unreadRepeats)))
                        }
                        changed = true
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed++
                val message = (e as? BackupException)?.message ?: (e as? IllegalArgumentException)?.message ?: "Couldn't update this calendar."
                db.withTransaction { dao.source(source.id)?.let { dao.updateSource(it.copy(lastError = message)) } }
            }
        }
        if (changed) onChanged()
        return if (failed > 0) State(message = "$failed subscribed calendar${if (failed == 1) "" else "s"} couldn't be updated.", error = true) else State()
    }

    // Unsubscribes: the link and its events leave the phone.
    suspend fun removeLink(id: Long) {
        db.withTransaction {
            if (dao.source(id)?.kind != OutsideCalendars.KIND_LINK) return@withTransaction
            dao.deleteEvents(id); dao.deleteSource(id)
        }
        onChanged()
    }

    // Ticking a subscription downloads it straight away.
    suspend fun setLinkEnabled(id: Long, enabled: Boolean) {
        setEnabled(id, enabled)
        if (enabled) refreshLinks(only = id)
    }

    // The same window as Nextcloud: from the first of the month 3 months back to the end of the month 12 ahead.
    private fun linkWindow(): Triple<LocalDate, LocalDate, String> {
        val today = java.time.Instant.ofEpochMilli(now()).atZone(zone()).toLocalDate()
        val from = today.minusMonths(MONTHS_BACK).withDayOfMonth(1)
        return Triple(from, today.plusMonths(MONTHS_AHEAD + 1).withDayOfMonth(1).minusDays(1), "$from|${zone().id}")
    }

    // What the server said about this version (ETag, Last-Modified), kept in ctag, one per line.
    private fun validator(result: CalendarLinkClient.Result.Fetched): String? =
        listOfNotNull(result.etag?.let { "E:$it" }, result.lastModified?.let { "L:$it" }).joinToString("\n").ifEmpty { null }

    private fun validators(ctag: String?): Pair<String?, String?> {
        val lines = ctag?.lines().orEmpty()
        return lines.firstOrNull { it.startsWith("E:") }?.drop(2) to lines.firstOrNull { it.startsWith("L:") }?.drop(2)
    }

    // Ticking a phone calendar reads it straight away (it's on the phone).
    suspend fun setPhoneEnabled(id: Long, enabled: Boolean) {
        setEnabled(id, enabled)
        refreshPhone()
    }

    // Lists the calendars on the server (new ones start unticked) and downloads each ticked calendar that changed since
    // its last download. Returns false when another sync was already running; with [wait], waits for it and then syncs
    // (a choice just made must be read, not left to the next sync). [retry] false (a check backing off): a calendar
    // whose download failed is tried again only if it changed there since.
    suspend fun sync(wait: Boolean = false, quiet: Boolean = false, retry: Boolean = true): Boolean {
        if (wait) lock.lock() else if (!lock.tryLock()) return false
        try {
            lastAttempt = now()
            if (!quiet) _state.value = State(running = true, message = "Syncing calendars…")
            _state.value = try {
                State(message = withContext(Dispatchers.IO) { syncLocked(retry) })
            } catch (e: CancellationException) {
                _state.value = State()
                throw e
            } catch (e: Exception) {
                State(message = (e as? BackupException)?.message ?: "Couldn't sync calendars. Try again later.", error = true)
            }
            return true
        } finally {
            lock.unlock()
        }
    }

    private suspend fun syncLocked(retry: Boolean = true): String {
        val account = accounts.load() ?: throw BackupException("Connect to Nextcloud first.")
        val key = accountKey(account)
        val remote = client.calendars(account).associateBy { it.href }
        // B3: whether this sync wrote what the widget shows (calendars, their events), so it is refreshed only then. The
        // synced calendar's and task list's pulls refresh it themselves when they read, and Planner's own events and
        // tasks through the Repository; the widget's own schedule (midnight, the date or zone changing) does the rest.
        var wrote = false
        // Calendars of another login, and ones no longer on the server, go with their events.
        db.withTransaction {
            val existing = dao.sources().filter { it.kind == OutsideCalendars.KIND_NEXTCLOUD }
            existing.filter { it.account != key || it.href !in remote }.forEach { dao.deleteSource(it.id); wrote = true }
            val kept = existing.filter { it.account == key && it.href in remote }.associateBy { it.href }
            remote.values.forEach { calendar ->
                val old = kept[calendar.href]
                if (old == null) { wrote = true; dao.insertSource(CalendarSource(account = key, href = calendar.href, name = calendar.name, color = calendar.color,
                    writable = calendar.writable, events = calendar.events, tasks = calendar.tasks)) }
                else if (old.name != calendar.name || old.color != calendar.color || old.writable != calendar.writable ||
                    old.events != calendar.events || old.tasks != calendar.tasks)
                    dao.updateSource(old.copy(name = calendar.name, color = calendar.color, writable = calendar.writable,
                        events = calendar.events, tasks = calendar.tasks,
                        enabled = old.enabled && calendar.events, sendHere = old.sendHere && calendar.writable && calendar.events,
                        tasksHere = old.tasksHere && calendar.writable && calendar.tasks)).also { wrote = true }
            }
        }
        val today = java.time.Instant.ofEpochMilli(now()).atZone(zone()).toLocalDate()
        val from = today.minusMonths(MONTHS_BACK).withDayOfMonth(1)
        val until = today.plusMonths(MONTHS_AHEAD + 1).withDayOfMonth(1)
        val window = "$from|${zone().id}"
        var failed = 0
        var skipped = 0
        for (source in dao.sources().filter { it.enabled && it.events && it.kind == OutsideCalendars.KIND_NEXTCLOUD && !it.sendHere }) {
            val calendar = remote[source.href] ?: continue
            if (calendar.ctag != null && calendar.ctag == source.ctag && source.fetchedFor == window && source.lastError == null) continue
            if (!retry && source.lastError != null && failedAt[source.id] == calendar.ctag.orEmpty()) { failed++; continue }
            try {
                val data = client.calendarEvents(account, source.href, from.atStartOfDay(zone()).toInstant(), until.atStartOfDay(zone()).toInstant())
                // Planner's own events (sent from this phone, step 5) are already shown as Planner events.
                val own = db.sentDao().uidsOf(key).toHashSet()
                val read = OutsideEventReader.read(data, zone(), skipUids = own)
                skipped += read.skipped
                db.withTransaction {
                    // Unticked or removed while downloading: keep nothing.
                    val current = dao.source(source.id) ?: return@withTransaction
                    dao.deleteEvents(source.id)
                    if (!current.enabled) return@withTransaction
                    dao.insertEvents(read.events.map { it.copy(id = 0, sourceId = source.id) })
                    // The server expands every repeat here: nothing for a note (R18-S1) to say.
                    dao.updateSource(current.copy(ctag = calendar.ctag, fetchedFor = window, lastSynced = now(), lastError = null, note = null))
                }
                wrote = true
                failedAt.remove(source.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed++
                failedAt[source.id] = calendar.ctag.orEmpty()
                val message = (e as? BackupException)?.message ?: "Couldn't download this calendar."
                db.withTransaction { dao.source(source.id)?.let { dao.updateSource(it.copy(lastError = message)) } }
            }
        }
        // The calendar kept in sync both ways (step 6). E-5: which one is read inside the lock, so a restore or a new choice
        // made while waiting for it is what's pulled, never the calendar from before.
        var target: CalendarSource? = null
        var targetCtag: String? = null
        var read = false
        try {
            sendLock.withLock {
                val current = dao.sources().firstOrNull { it.kind == OutsideCalendars.KIND_NEXTCLOUD && it.sendHere && it.account == key }
                val calendar = current?.let { remote[it.href] } ?: return@withLock
                if (!retry && current.lastError != null && failedAt[current.id] == calendar.ctag.orEmpty()) { failed++; return@withLock }
                target = current; targetCtag = calendar.ctag
                read = pullLocked(account, current, calendar.ctag)
                // What it read may leave something to send (new files wait for the first read of a calendar just chosen).
                if (read) changes.incrementAndGet()
                failedAt.remove(current.id)
            }
            // A check that sends nothing (backing off) keeps what the last send said (a file refused, say) unless the pull
            // read something.
            target?.let { if (retry || read) _sendState.value = summary(it) }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            // Kept on the calendar (a send right after doesn't clear it, as it did the send state): the sync icon
            // and Sync now show it until a pull works again (on a calendar just chosen, nothing new is sent until then).
            failed++
            val message = (e as? BackupException)?.message ?: "Couldn't download this calendar. Planner tries again at the next sync."
            target?.let { pulled -> failedAt[pulled.id] = targetCtag.orEmpty()
                db.withTransaction { dao.source(pulled.id)?.let { dao.updateSource(it.copy(lastError = message)) } } }
        }
        // The task list kept in sync (its own state says how it went).
        val tasksFailed = tasks?.pull(account, remote, retry) == false
        if (wrote) onChanged()
        val ticked = dao.sources().count { it.enabled && it.kind == OutsideCalendars.KIND_NEXTCLOUD }
        return buildString {
            append(when {
                remote.isEmpty() -> "No calendars found on Nextcloud."
                ticked == 0 && dao.sources().none { it.tasksHere } -> "Tick the calendars to show in Planner."
                failed > 0 -> "Synced, but $failed calendar${if (failed == 1) "" else "s"} couldn't be downloaded." +
                    if (tasksFailed) " The task list couldn't be downloaded either." else ""
                tasksFailed -> "Synced, but the task list couldn't be downloaded."
                else -> "Calendars are up to date."
            })
            if (skipped > 0) append(" $skipped event${if (skipped == 1) "" else "s"} couldn't be read and ${if (skipped == 1) "was" else "were"} left out.")
        }
    }

    // Ticking downloads the calendar at the next sync; unticking removes its events from the phone straight away.
    suspend fun setEnabled(id: Long, enabled: Boolean) {
        db.withTransaction {
            val source = dao.source(id) ?: return@withTransaction
            if (!enabled) dao.deleteEvents(id)
            dao.updateSource(source.copy(enabled = enabled, ctag = null, fetchedFor = null, lastError = null,
                lastSynced = if (enabled) source.lastSynced else null))
        }
        onChanged()
    }

    // After disconnecting Nextcloud: its calendars and every downloaded event leave the phone. Phone calendars stay.
    suspend fun clearNextcloud() {
        lastRefused = null // a new record of what was sent: no refusal carried over
        // What Planner sent stays on Nextcloud; the record of it goes with the login.
        db.withTransaction { dao.deleteEventsOfKind(OutsideCalendars.KIND_NEXTCLOUD); dao.deleteSourcesOfKind(OutsideCalendars.KIND_NEXTCLOUD); db.sentDao().deleteAll() }
        tasks?.clear()
        _sendState.value = State()
        lastAttempt = 0L
        _state.value = State()
        onChanged()
    }

    // Everything, both kinds (the test runner's clean start).
    suspend fun clearAll() {
        lastRefused = null // a new record of what was sent: no refusal carried over
        db.withTransaction { dao.deleteAllEvents(); dao.deleteAllSources(); db.sentDao().deleteAll() }
        tasks?.clear()
        _sendState.value = State()
        lastAttempt = 0L; lastLinkAttempt = 0L
        _state.value = State(); _phoneState.value = State(); _linkState.value = State()
        onChanged()
    }

    // For backups: the ticked Nextcloud calendars and every subscribed link (ticked or not). Phone calendars are left
    // out: another phone numbers them differently.
    suspend fun choices(): List<CalendarChoice> = dao.sources()
        .filter { it.kind == OutsideCalendars.KIND_NEXTCLOUD && it.enabled || it.kind == OutsideCalendars.KIND_LINK }
        .map { CalendarChoice(it.account, it.href, it.name, it.color, it.enabled) }

    // After restoring a backup: its Nextcloud calendars and subscribed links come back without events; the next sync
    // downloads them. Phone calendars are left as they are. E-10: for a backup from before it said which calendar ([keepSend])
    // or task list ([keepTasks]) is kept in sync, this phone's stays so, as forgetSentLocked and TaskSync.forget promise; a backup
    // that does say sets its own right after (restoreSend, TaskSync.restore).
    suspend fun restoreChoices(choices: List<CalendarChoice>, keepSend: Boolean = false, keepTasks: Boolean = false) {
        db.withTransaction {
            val synced = dao.sources().filter { it.kind == OutsideCalendars.KIND_NEXTCLOUD && (keepSend && it.sendHere || keepTasks && it.tasksHere) }
                .map { it.copy(sendHere = keepSend && it.sendHere, tasksHere = keepTasks && it.tasksHere) }
            listOf(OutsideCalendars.KIND_NEXTCLOUD, OutsideCalendars.KIND_LINK).forEach { dao.deleteEventsOfKind(it); dao.deleteSourcesOfKind(it) }
            choices.distinctBy { it.account to it.href }.forEach {
                val link = it.account == LINK_ACCOUNT
                if (link && runCatching { CalendarLinks.normalize(it.href) }.isFailure) return@forEach
                dao.insertSource(CalendarSource(account = it.account, href = it.href, name = it.name, color = it.color, enabled = it.enabled,
                    kind = if (link) OutsideCalendars.KIND_LINK else OutsideCalendars.KIND_NEXTCLOUD,
                    detail = if (link) runCatching { CalendarLinks.normalize(it.href).host }.getOrNull() else null))
            }
            val restored = dao.sources().filter { it.kind == OutsideCalendars.KIND_NEXTCLOUD }.associateBy { it.account to it.href }
            synced.forEach { old ->
                val kept = old.copy(ctag = null, fetchedFor = null, lastSynced = null, lastError = null, taskCtag = null, taskError = null)
                val same = restored[old.account to old.href]
                if (same == null) dao.insertSource(kept.copy(id = 0, enabled = old.sendHere))
                else dao.updateSource(kept.copy(id = same.id, name = same.name, color = same.color, enabled = same.enabled || old.sendHere))
            }
        }
        lastAttempt = 0L; lastLinkAttempt = 0L
        onChanged()
    }

    companion object {
        const val MIN_INTERVAL_MS = 15 * 60 * 1000L
        const val MONTHS_BACK = 3L
        const val MONTHS_AHEAD = 12L
        const val PERMISSION_NEEDED = "Permission needed: allow Planner to read calendars."

        // R18-S1: the note on a calendar's row for [count] repeating events whose rule Planner can't follow (null: none).
        internal fun unreadRepeatsNote(count: Int): String? = when (count) {
            0 -> null
            1 -> "1 repeating event repeats in a way Planner can't show."
            else -> "$count repeating events repeat in a way Planner can't show."
        }
        private const val PHONE_ACCOUNT = "phone"
        const val LINK_ACCOUNT = "link"
        const val LINK_INTERVAL_MS = 60 * 60 * 1000L
        const val SEND_DELAY_MS = 5_000L
        // AutoSync's check sends at least this often even with nothing known to be waiting (see sendDue).
        const val SEND_SAFETY_MS = 15 * 60 * 1000L
        private val ORDER = compareBy<OutsideEvent>({ it.date }, { it.startTime }, { it.title }, { it.endDate }, { it.durationMinutes }, { it.location }, { it.notes })
        private fun phoneHref(id: Long) = "calendar/$id"
        private fun calendarId(href: String) = href.removePrefix("calendar/").toLongOrNull()

        // What Planner syncs for [item], as a short fingerprint: a change in any synced detail changes it. Worked out in
        // UTC, so a new phone time zone on its own (travelling) changes nothing and sends nothing; an event edited
        // afterwards is still written with the zone of the moment. Prefixed, so a row saved before (without it) is told
        // apart: see inSync.
        internal fun fingerprint(item: ItineraryItem): String = PRINT + zonedFingerprint(item, java.time.ZoneOffset.UTC)
        private const val PRINT = "u1:"
        // For tests: how many event fingerprints were worked out (AutoSync's idle check makes none).
        internal val fingerprints = java.util.concurrent.atomic.AtomicLong()

        // The hash alone, as fingerprints were until 0.0.9 (in the phone's zone until 0.0.8, in UTC in 0.0.9).
        internal fun zonedFingerprint(item: ItineraryItem, zone: ZoneId): String {
            fingerprints.incrementAndGet()
            val text = CalendarExport.encode(item, "planner", zone, java.time.Instant.EPOCH)
            return CalendarExport.shortHash(text)
        }

        // Not a bill or a skipped date, and within the years a calendar file can hold.
        internal fun sendable(item: ItineraryItem) = item.id > 0 && item.category != "Bills" && !item.skipped && CalendarExport.exportable(item)

        // True when [stored] (a row's fingerprint) still describes [item]: unchanged in Planner since the last sync. A row
        // saved before fingerprints had their prefix may match the old ways too (UTC in 0.0.9, the phone's zone until
        // 0.0.8); send then rewrites it, so that is only ever once.
        internal fun inSync(stored: String, item: ItineraryItem, zone: ZoneId) = CalendarExport.exportable(item) &&
            (stored == fingerprint(item) || !stored.startsWith(PRINT) && (stored == zonedFingerprint(item, java.time.ZoneOffset.UTC) || stored == zonedFingerprint(item, zone)))

        // [file] is the one a pending [row] wrote (its reply was lost): the row takes it. What it holds counts as synced, so
        // an edit made since is sent at the next pass; a file Planner can't read is left for the next pull to check.
        internal fun adopted(row: SentEvent, file: ServerFile, item: ItineraryItem?, zone: ZoneId): SentEvent {
            val server = ServerEvents.parse(file.data, zone).item
            return when {
                // Deleted in Planner meanwhile: still pending, now with the file's version, so send deletes exactly that file.
                item == null -> row.copy(etag = file.etag, problem = SentEvent.PENDING)
                server == null -> row.copy(problem = SentEvent.CHANGED)
                else -> row.copy(etag = file.etag, ics = file.data, fingerprint = fingerprint(ServerEvents.apply(item, server)), problem = null)
            }
        }

        // [file] (the event's file, at a version [row] doesn't know) already holds [item] as Planner has it now: the row
        // takes it as synced (its version, content and fingerprint), clearing any problem. Null otherwise, or when Planner
        // can't hold the file. What only one side has isn't compared: [item]'s Planner-only details, the file's extras.
        internal fun alreadyThere(row: SentEvent, file: ServerFile, item: ItineraryItem, zone: ZoneId): SentEvent? {
            val server = ServerEvents.parse(file.data, zone).item ?: return null
            val print = fingerprint(item)
            if (fingerprint(ServerEvents.apply(item, server)) != print) return null
            return row.copy(etag = file.etag, ics = file.data, fingerprint = print, problem = null, conflict = null)
        }

        // After the pull window, the date range (end exclusive) whose files are read only to link them to [unlinked]
        // events (send would write those again), when one lies there. Never before it: past events are only noted, and
        // one edited since is looked for around its own date when it is sent (lookupRanges), so a pull never depends on
        // the size of the calendar's history.
        internal fun linkRanges(unlinked: List<ItineraryItem>, until: LocalDate): List<Pair<LocalDate, LocalDate>> =
            listOfNotNull((until.plusDays(1) to until.plusYears(100)).takeIf { unlinked.any { it.lastDay > until } })

        // Where to look for the files of [events] (end exclusive): each one's days and a day either side (a file written in
        // another time zone), ranges less than a month apart taken together. E16-1: [noted] (first and last day) are the
        // days the events were on when noted, where their files are even after a move.
        internal fun lookupRanges(events: List<ItineraryItem>, noted: List<Pair<LocalDate, LocalDate>> = emptyList()): List<Pair<LocalDate, LocalDate>> =
            (events.map { it.date to it.lastDay } + noted).map { (first, last) -> first.minusDays(1) to last.plusDays(2) }
                .sortedBy { it.first }.fold(mutableListOf()) { ranges, next ->
                    val last = ranges.lastOrNull()
                    if (last != null && next.first <= last.second.plusMonths(1)) ranges[ranges.lastIndex] = last.first to maxOf(last.second, next.second)
                    else ranges += next
                    ranges
                }

        // E16-1: what a noted row keeps of its event (in [SentEvent.ics], as "first/last" day): the days it was on when
        // noted, so an edit that moves it far is still looked for where its file is. Null for a row noted before this was
        // kept (an edit is then looked for around its new days only, as before), or one that isn't only noted.
        internal fun notedDays(item: ItineraryItem): String = "${item.date}/${item.lastDay}"
        internal fun notedDays(row: SentEvent): Pair<LocalDate, LocalDate>? = if (row.uid == null) notedDays(row.ics) else null
        internal fun notedDays(text: String?): Pair<LocalDate, LocalDate>? = text?.split('/')?.takeIf { it.size == 2 }?.let { (first, last) ->
            runCatching { LocalDate.parse(first) to LocalDate.parse(last) }.getOrNull()?.takeIf { (from, to) -> !to.isBefore(from) } }

        // [row] (a noted past event, edited since) linked to [file], its file on Nextcloud: counts as synced as Nextcloud
        // has it, so Planner's edit is then sent as an update of that file; changed there too since it was noted, it's a
        // conflict for the user, as when the pull links.
        internal fun linkedForSend(row: SentEvent, href: String, uid: String, file: ServerFile, server: ItineraryItem, item: ItineraryItem, zone: ZoneId): SentEvent {
            val theirs = ServerEvents.apply(item, server)
            val clash = fingerprint(theirs) != fingerprint(item) && !inSync(row.fingerprint, theirs, zone)
            return row.copy(uid = uid, href = href, etag = file.etag, ics = file.data, fingerprint = fingerprint(theirs),
                problem = if (clash) SentEvent.CONFLICT else null, conflict = if (clash) file.data else null)
        }

        // The pull's change to a Planner event whose file changed on Nextcloud, made to [now] (the event as it is when
        // saved): Nextcloud's version with Planner-only details kept; null when it changed in Planner since [row] was synced.
        internal fun pulled(row: SentEvent, now: ItineraryItem, server: ItineraryItem, zone: ZoneId): ItineraryItem? =
            if (inSync(row.fingerprint, now, zone)) ServerEvents.apply(now, server) else null

        // What the user is told when Nextcloud refused [count] events (the first with HTTP [code]); see TaskSync.refusedMessage.
        internal fun refusedMessage(count: Int, code: Int, noun: String = "event") =
            "Nextcloud refused ${if (count == 1) "1 $noun" else "$count ${noun}s"} (HTTP $code). The others were sent; Planner tries again at the next sync."

        // What a send tells the user when [conflicts] [noun]s ("event", "task") were changed in both places, or [waiting]
        // were changed on Nextcloud; null when neither.
        internal fun conflictSummary(noun: String, conflicts: Int, waiting: Int): State? = when {
            conflicts > 0 -> State(message = "$conflicts $noun${if (conflicts == 1) " was" else "s were"} changed in both places. " +
                "Choose which version to keep.", error = true)
            waiting > 0 -> State(message = "$waiting $noun${if (waiting == 1) " was" else "s were"} changed on Nextcloud; " +
                "Planner will check at the next sync.")
            else -> null
        }

        // How many too-large replies one pull takes while reading a range in parts (see filesBetween): enough for a window
        // sixteen times the size of one reply.
        internal const val SPLIT_BUDGET = 15

        // [start] to [end] (exclusive) in two halves; null for a single day.
        internal fun splitRange(start: LocalDate, end: LocalDate): List<Pair<LocalDate, LocalDate>>? {
            val days = java.time.temporal.ChronoUnit.DAYS.between(start, end)
            if (days < 2) return null
            val middle = start.plusDays(days / 2)
            return listOf(start to middle, middle to end)
        }

        // An update of [row]'s event (edited in Planner) whose file had to be fetched ([file], as it is now: the row knew
        // its text or its version no longer, after a restore or a lost reply). The row to send on, and true, when Nextcloud
        // still has what was last synced: the same version, or one changed only outside what Planner manages (another
        // app's extras, an alarm acknowledged). Otherwise the row as settled without sending, and false: in sync when
        // Nextcloud already has Planner's version (E-2), CHANGED for the next pull when it differs, or has no version to
        // write against (E-7).
        internal fun fetchedBase(row: SentEvent, file: ServerFile, item: ItineraryItem, zone: ZoneId): Pair<SentEvent, Boolean> {
            if (NextcloudClient.sameEtag(file.etag, row.etag)) return row.copy(etag = file.etag, ics = file.data) to true
            alreadyThere(row, file, item, zone)?.let { return it to false }
            val server = ServerEvents.parse(file.data, zone).item
            return if (file.etag != null && server != null && inSync(row.fingerprint, ServerEvents.apply(item, server), zone))
                row.copy(etag = file.etag, ics = file.data) to true
            else row.copy(problem = SentEvent.CHANGED) to false
        }

        // The version to delete a copy at when its row doesn't know it (E-7), from [file] as it is now: a pending one's
        // (Planner's own file, its reply lost), or one still exactly as last synced ([ics]). Null when that can't be told:
        // the delete waits for the next pull to compare.
        internal fun deleteVersion(problem: String?, ics: String?, file: ServerFile): String? = when {
            file.etag == null -> null
            problem == SentEvent.PENDING -> file.etag
            ics != null && ics == file.data -> file.etag
            else -> null
        }

        // Deletes Planner's copy of a synced event or task (file [href] in [calendar]), only if Nextcloud still has the
        // version Planner synced. A copy changed on Nextcloud meanwhile stays there (the server refuses the delete:
        // Changed). Null when nothing was tried (see deletable). A row that doesn't know its version (E-7) learns it
        // first: never a delete without one.
        internal fun removeSyncedCopy(client: () -> NextcloudClient, account: NextcloudAccount, calendar: String, href: () -> String,
                                      uid: String?, problem: String?, etag: String?, ics: String?): WriteResult? {
            if (!deletable(uid, problem)) return null
            val file = href()
            val version = etag ?: run {
                val current = client().getFile(account, calendar, file) ?: return WriteResult.Missing
                deleteVersion(problem, ics, current) ?: return WriteResult.Changed
            }
            return client().deleteFile(account, calendar, file, version)
        }

        // Whether the copy of [row] may be deleted on Nextcloud: sent, and with nothing to settle first.
        internal fun deletable(row: SentEvent) = deletable(row.uid, row.problem)
        internal fun deletable(uid: String?, problem: String?) = uid != null && (problem == null || problem == SentEvent.PENDING)

        // The row of an event deleted in Planner once its copy's delete was tried ([result]; null = not tried): forgotten
        // (null), or kept as CHANGED when the copy changed on Nextcloud first, so the next pull makes it a conflict
        // ("deleted in Planner, changed on Nextcloud") instead of bringing the file in as a new event.
        internal fun afterDelete(row: SentEvent, result: WriteResult?): SentEvent? = when {
            result == WriteResult.Changed -> row.copy(problem = SentEvent.CHANGED)
            result == null && row.uid != null && row.problem == SentEvent.CHANGED -> row
            else -> null
        }

        // Planner's own file names for events it created (see sendLocked).
        internal fun isPlannerUid(uid: String?) = uid != null && uid.startsWith("planner-") && uid.endsWith("@planner")
        internal fun newEventUid() = "planner-${java.util.UUID.randomUUID()}@planner"

        // Which files ([href], uid, the event as Planner would hold it) are [candidates] already (Planner events without
        // a file), by file: first exactly (title, dates, time, length); then, for files Planner itself created, the one
        // event with that title, or else the one at that date and time (edited on one side while disconnected; the
        // difference becomes a conflict to choose). Each event is used once; anything unsure is left alone.
        internal fun relink(files: List<Triple<String, String?, ItineraryItem>>, candidates: List<ItineraryItem>): Map<String, Long> {
            fun key(it: ItineraryItem) = relinkKey(it)
            val free = candidates.sortedBy { it.id }.toMutableList()
            val result = LinkedHashMap<String, Long>()
            for ((href, _, server) in files) free.firstOrNull { key(it) == key(server) }?.let { result[href] = it.id; free.remove(it) }
            for ((href, uid, server) in files) {
                if (href in result || !isPlannerUid(uid)) continue
                val match = free.filter { key(it)[0] == key(server)[0] }.singleOrNull()
                    ?: free.filter { it.date == server.date && it.startTime == server.startTime }.singleOrNull() ?: continue
                result[href] = match.id; free.remove(match)
            }
            return result
        }

        // What makes a file exactly an event for linking: the same title, dates, time and length.
        private fun relinkKey(it: ItineraryItem) = listOf(it.title.trim().take(500).ifEmpty { "(No title)" }, it.date, it.lastDay, it.startTime,
            it.durationMinutes?.takeIf { d -> it.startTime != null && d > 0 })

        // Which files ([href], uid, the event as Planner would hold it) are the [edited] events' (noted past ones, edited
        // since), looked up together with their [neighbours] (noted past ones near them, unedited): first the file exactly
        // as an event was noted ([noted]: each one's fingerprint then), so an edited one finds its own file whatever the
        // edit; then a file exactly like an event now (see relink). Nothing looser: a series' occurrences differ only by
        // date, and a guess by title or time would give an edited one a neighbour's file (a false conflict). Neighbours
        // only claim their own files so no edited event takes them; only the edited events' files are returned.
        internal fun editedFiles(files: List<Triple<String, String?, ItineraryItem>>, edited: List<ItineraryItem>, neighbours: List<ItineraryItem>,
                                 noted: Map<Long, String>, zone: ZoneId): Map<String, Long> {
            val free = (edited + neighbours).sortedBy { it.id }.toMutableList()
            val claimed = LinkedHashMap<String, Long>()
            for ((href, _, server) in files) free.firstOrNull { event -> noted[event.id]?.let { inSync(it, ServerEvents.apply(event, server), zone) } == true }
                ?.let { claimed[href] = it.id; free.remove(it) }
            for ((href, _, server) in files) if (href !in claimed) free.firstOrNull { relinkKey(it) == relinkKey(server) }
                ?.let { claimed[href] = it.id; free.remove(it) }
            val ids = edited.mapTo(HashSet()) { it.id }
            return claimed.filterValues { it in ids }
        }

        // E16-3: why Keep Planner's can't settle a conflict whose Nextcloud side Planner can't hold any more.
        internal const val PLANNERS_WONT_FIT = "On Nextcloud this event now repeats or was cancelled, so Planner's version can't be " +
            "written over it. Choose Keep Nextcloud's: Planner's event stays as it is, and Nextcloud's is left alone."

        // Which login a calendar belongs to: the server address and username, never the password.
        fun accountKey(account: NextcloudAccount): String = "${account.server}|${account.username}"
    }
}

// When Planner's changes are sent (CalendarSync.requestSend): a few seconds after the last change, and never by cancelling
// a pass under way — a change during one is sent by another pass right after it. Called under its own lock.
internal class SendDebounce {
    private var sending = false
    private var again = false
    private var job: Job? = null

    // Runs [send] after [delayMs] unless another change restarts the wait first; a change while it sends runs
    // [sendAgain] once it is over (see request, started, finished).
    fun launch(scope: CoroutineScope, delayMs: Long, send: suspend () -> Unit, sendAgain: () -> Unit) {
        synchronized(this) {
            if (!request()) return
            job?.cancel()
            job = scope.launch {
                delay(delayMs)
                // From here on no request cancels this job; one that came first already has.
                synchronized(this@SendDebounce) { ensureActive(); started() }
                try { send() } finally { if (synchronized(this@SendDebounce) { finished() }) sendAgain() }
            }
        }
    }

    // A change in Planner. True: (re)start the wait now. False: a pass is sending; another one follows it.
    fun request(): Boolean { if (sending) again = true; return !sending }

    // The wait is over and the pass begins; from now on requests queue behind it.
    fun started() { sending = true; again = false }

    // The pass is over (sent, failed or stopped). True: changes came in meanwhile, so wait and send again.
    fun finished(): Boolean { sending = false; return again.also { again = false } }
}
