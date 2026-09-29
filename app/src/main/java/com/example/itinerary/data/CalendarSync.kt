package com.example.itinerary.data

import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private var lastAttempt = 0L

    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()
    private val sendLock = Mutex()
    private val _sendState = MutableStateFlow(State())
    val sendState = _sendState.asStateFlow()
    val sent: Flow<List<SentEvent>> = db.sentDao().observe()
    private var sendJob: kotlinx.coroutines.Job? = null
    private val linkLock = Mutex()
    private var lastLinkAttempt = 0L
    private val _linkState = MutableStateFlow(State())
    val linkState = _linkState.asStateFlow()
    private val phoneLock = Mutex()
    private val _phoneState = MutableStateFlow(State())
    val phoneState = _phoneState.asStateFlow()

    val sources: Flow<List<CalendarSource>> = dao.observeSources()

    // Ticked calendars' events with their calendar's name and colour, keyed by the id they are shown under.
    val shown: Flow<Map<Long, OutsideInfo>> = combine(dao.observeShown(), dao.observeSources()) { events, sources ->
        val byId = sources.associateBy { it.id }
        events.mapNotNull { event ->
            val source = byId[event.sourceId] ?: return@mapNotNull null
            event.displayId() to OutsideInfo(event, source.name, source.color ?: OutsideCalendars.DEFAULT_COLOR, source.kind)
        }.toMap(LinkedHashMap())
    }.distinctUntilChanged()

    suspend fun hasAccount(): Boolean = withContext(Dispatchers.IO) { runCatching { accounts.load() != null }.getOrDefault(false) }

    suspend fun widgetItems(day: LocalDate): List<ItineraryItem> {
        val colors = dao.sources().associate { it.id to (it.color ?: OutsideCalendars.DEFAULT_COLOR) }
        return eventsOnDay(dao.dayCandidates(day, day.minusDays(1)).map { it.toItem(colors[it.sourceId] ?: OutsideCalendars.DEFAULT_COLOR) }, day)
    }

    // When the app opens: the phone's calendars (quick, no network), then Nextcloud if it's due — nothing without a
    // login or a ticked calendar, and at most once every 15 minutes.
    suspend fun syncIfDue() {
        refreshPhone()
        if (now() - lastLinkAttempt >= LINK_INTERVAL_MS && dao.sources().any { it.enabled && it.kind == OutsideCalendars.KIND_LINK }) refreshLinks()
        if (now() - lastAttempt < MIN_INTERVAL_MS) return
        if (dao.sources().any { it.enabled && it.kind == OutsideCalendars.KIND_NEXTCLOUD } && hasAccount()) sync()
        send()
    }

    // A background run (see CalendarBackground): everything that has something to sync — phone calendars, links (at most
    // hourly), Nextcloud (reading and the synced calendar both ways) — then Planner's own changes. Cheap when there's
    // nothing to do.
    suspend fun backgroundSync() {
        refreshPhone()
        if (dao.sources().any { it.enabled && it.kind == OutsideCalendars.KIND_LINK }) refreshLinks()
        if (dao.sources().any { it.kind == OutsideCalendars.KIND_NEXTCLOUD && (it.enabled || it.sendHere) } && hasAccount()) sync()
        send()
    }

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
        val sentDao = db.sentDao()
        db.withTransaction {
            val sources = dao.sources().filter { it.kind == OutsideCalendars.KIND_NEXTCLOUD }
            sources.filter { it.sendHere && it.id != id }.forEach { dao.deleteEvents(it.id); dao.updateSource(it.copy(sendHere = false, enabled = false, ctag = null, fetchedFor = null)) }
            val target = sources.firstOrNull { it.id == id && it.writable } ?: return@withTransaction
            // Its read-only events show like a ticked calendar's; its other events become Planner events.
            dao.updateSource(target.copy(sendHere = true, enabled = true, ctag = null, fetchedFor = null))
            sentDao.deleteOtherCalendars(target.account, target.href)
            val known = sentDao.all().mapTo(HashSet()) { it.itemId }
            val today = java.time.Instant.ofEpochMilli(now()).atZone(zone()).toLocalDate()
            db.itemDao().all().filter { sendable(it) && it.id !in known && it.lastDay < today }.forEach {
                sentDao.put(SentEvent(itemId = it.id, account = target.account, calendar = target.href, uid = null, fingerprint = fingerprint(it)))
            }
        }
        _sendState.value = State()
        onChanged()
        if (id != null) { sync(); send() }
    }

    // Sends a few seconds after a change in Planner, so a burst of edits is one pass.
    fun requestSend() {
        val scope = scope ?: return
        sendJob?.cancel()
        sendJob = scope.launch { kotlinx.coroutines.delay(SEND_DELAY_MS); send() }
    }

    // One pass Planner → Nextcloud: creates what's new, updates what changed (only what Planner manages in the file),
    // deletes what Planner no longer has (not while its Undo is on offer). A write that finds the server copy changed or
    // gone leaves it and marks it for the next pull. Offline, the rest waits for the next pass.
    suspend fun send(): Boolean = sendLock.withLock {
        val target = dao.sources().firstOrNull { it.kind == OutsideCalendars.KIND_NEXTCLOUD && it.sendHere } ?: return@withLock true
        val account = withContext(Dispatchers.IO) { runCatching { accounts.load() }.getOrNull() } ?: return@withLock true
        if (target.account != accountKey(account)) return@withLock true
        _sendState.value = State(running = true)
        _sendState.value = try {
            withContext(Dispatchers.IO) { sendLocked(account, target) }
        } catch (e: CancellationException) {
            _sendState.value = State()
            throw e
        } catch (e: Exception) {
            State(message = ((e as? BackupException)?.message ?: "Couldn't send events to Nextcloud.") + " The rest will be sent later.", error = true)
        }
        true
    }

    private suspend fun sendLocked(account: NextcloudAccount, target: CalendarSource): State {
        val sentDao = db.sentDao()
        val items = db.itemDao().all().filter { it.id > 0 }
        val present = items.mapTo(HashSet()) { it.id }
        val rows = sentDao.all().filter { it.account == target.account && it.calendar == target.href }.associateBy { it.itemId }
        val waiting = pendingDeleted()
        for (item in items) {
            val row = rows[item.id]
            if (row?.problem != null) continue // settled by the next pull or by the user
            if (!sendable(item)) {
                // A bill now, or a skipped date: its copy goes.
                if (row != null) { removeCopy(account, target, row); sentDao.delete(row.id) }
                continue
            }
            val print = fingerprint(item)
            when {
                row == null || row.uid == null && row.fingerprint != print -> {
                    val uid = "planner-${java.util.UUID.randomUUID()}@planner"
                    val body = CalendarExport.encode(item, uid, zone(), java.time.Instant.ofEpochMilli(now()))
                    val fresh = SentEvent(id = row?.id ?: 0, itemId = item.id, account = target.account, calendar = target.href,
                        uid = uid, fingerprint = print, ics = body)
                    when (val result = client.putEvent(account, target.href, uid, body, null)) {
                        is WriteResult.Ok -> sentDao.put(fresh.copy(etag = result.etag))
                        WriteResult.Changed -> sentDao.put(fresh.copy(ics = null, problem = SentEvent.CHANGED))
                        WriteResult.Missing -> throw BackupException("The calendar Planner syncs with wasn't found on Nextcloud.")
                    }
                }
                row.uid != null && row.fingerprint != print -> {
                    val href = hrefOf(target, row)
                    // Change only what Planner manages in the file as last synced (fetched once if unknown).
                    val base = row.ics ?: client.getFile(account, target.href, href)?.let { file ->
                        if (file.etag != row.etag) { sentDao.put(row.copy(problem = SentEvent.CHANGED)); null } else file.data
                    }
                    if (base == null) { if (sentDao.all().none { it.id == row.id && it.problem != null }) sentDao.put(row.copy(problem = SentEvent.DELETED)); continue }
                    val body = ServerEvents.patch(base, item, zone(), java.time.Instant.ofEpochMilli(now()))
                    when (val result = client.putFile(account, target.href, href, body, row.etag)) {
                        is WriteResult.Ok -> sentDao.put(row.copy(etag = result.etag, ics = body, fingerprint = print))
                        WriteResult.Changed -> sentDao.put(row.copy(problem = SentEvent.CHANGED))
                        WriteResult.Missing -> sentDao.put(row.copy(problem = SentEvent.DELETED))
                    }
                }
            }
        }
        // Deleted in Planner (and past its Undo): the copy goes too, unless it was changed on Nextcloud meanwhile.
        for (row in rows.values) if (row.itemId !in present && row.itemId !in waiting && row.problem != SentEvent.CONFLICT) {
            removeCopy(account, target, row)
            sentDao.delete(row.id)
        }
        return summary(target)
    }

    private suspend fun summary(target: CalendarSource): State {
        val rows = db.sentDao().all().filter { it.calendar == target.href }
        val conflicts = rows.count { it.problem == SentEvent.CONFLICT }
        val waiting = rows.count { it.problem == SentEvent.CHANGED || it.problem == SentEvent.DELETED }
        return when {
            conflicts > 0 -> State(message = "$conflicts event${if (conflicts == 1) " was" else "s were"} changed in both places. " +
                "Choose which version to keep.", error = true)
            waiting > 0 -> State(message = "$waiting event${if (waiting == 1) " was" else "s were"} changed on Nextcloud; " +
                "Planner will check at the next sync.")
            else -> State()
        }
    }

    // Deletes Planner's copy of [row], only if Nextcloud still has the version Planner synced. A copy changed on Nextcloud
    // meanwhile stays there (the server refuses the delete); either way Planner then forgets the row.
    private fun removeCopy(account: NextcloudAccount, target: CalendarSource, row: SentEvent) {
        if (row.uid == null || row.problem != null) return
        client.deleteFile(account, target.href, hrefOf(target, row), row.etag)
    }

    // The file of [row]: its own name for events that came from Nextcloud, "<uid>.ics" for ones Planner created.
    private fun hrefOf(target: CalendarSource, row: SentEvent): String = row.href ?: "${target.href}${row.uid}.ics"

    // One pass Nextcloud → Planner for the synced calendar: nothing when its change marker says nothing changed.
    private suspend fun pullLocked(account: NextcloudAccount, target: CalendarSource, ctag: String?) {
        val store = planner ?: return
        val sentDao = db.sentDao()
        val (from, until, key) = linkWindow()
        val rows = sentDao.all().filter { it.account == target.account && it.calendar == target.href }
        val unsettled = rows.any { it.problem == SentEvent.CHANGED || it.problem == SentEvent.DELETED }
        if (ctag != null && ctag == target.ctag && target.fetchedFor == key && !unsettled) return
        val listing = client.eventEtags(account, target.href)
        val inWindow = client.calendarFiles(account, target.href, from.atStartOfDay(zone()).toInstant(),
            until.plusDays(1).atStartOfDay(zone()).toInstant()).associateBy { it.href }
        val synced = rows.filter { it.uid != null }.associateBy { hrefOf(target, it) }
        // Synced events outside the window that changed there are fetched too.
        val older = synced.filter { (href, row) -> href in listing && listing[href] != row.etag && href !in inWindow }.keys
        val files = inWindow + client.multiget(account, target.href, older).associateBy { it.href }
        val items = db.itemDao().all().associateBy { it.id }
        val waiting = pendingDeleted()
        val readOnly = mutableListOf<OutsideEvent>()
        fun shown(file: ServerFile) = CalendarFileImport.window(file.data, zone(), from, until).events
        for ((href, file) in files) {
            val parsed = ServerEvents.parse(file.data, zone())
            val row = synced[href]
            if (row == null) {
                val server = parsed.item
                if (server == null) { readOnly += runCatching { shown(file) }.getOrDefault(emptyList()); continue }
                // New on Nextcloud: a Planner event from now on.
                val id = store.add(server)
                val added = db.itemDao().byId(id) ?: continue
                sentDao.put(SentEvent(itemId = id, account = target.account, calendar = target.href,
                    uid = parsed.uid ?: href.substringAfterLast('/').removeSuffix(".ics"), href = href, etag = file.etag, ics = file.data,
                    fingerprint = fingerprint(added)))
                continue
            }
            if (row.problem == SentEvent.CONFLICT) { if (file.etag != row.etag) sentDao.put(row.copy(conflict = file.data)); continue }
            if (file.etag == row.etag) { if (row.ics == null) sentDao.put(row.copy(ics = file.data)); continue }
            // Changed on Nextcloud.
            val server = parsed.item
            val item = items[row.itemId]
            when {
                // Now repeating, or otherwise more than Planner can hold: it's read-only from now on; Planner's event stays.
                server == null -> { sentDao.delete(row.id); readOnly += runCatching { shown(file) }.getOrDefault(emptyList()) }
                // Deleted in Planner meanwhile.
                item == null || row.itemId in waiting -> sentDao.put(row.copy(problem = SentEvent.CONFLICT, conflict = file.data))
                // Unchanged in Planner since the last sync: take Nextcloud's version (Planner-only details stay).
                fingerprint(item) == row.fingerprint -> {
                    store.update(ServerEvents.apply(item, server))
                    val saved = db.itemDao().byId(item.id) ?: continue
                    sentDao.put(row.copy(etag = file.etag, ics = file.data, fingerprint = fingerprint(saved), problem = null, conflict = null))
                }
                else -> sentDao.put(row.copy(problem = SentEvent.CONFLICT, conflict = file.data))
            }
        }
        // Gone from Nextcloud: to Recently deleted, unless it was changed in Planner meanwhile.
        for ((href, row) in synced) if (href !in listing && row.problem != SentEvent.CONFLICT) {
            val item = items[row.itemId]
            when {
                item == null -> sentDao.delete(row.id)
                fingerprint(item) == row.fingerprint -> { store.archive(setOf(item.id)); sentDao.delete(row.id) }
                else -> sentDao.put(row.copy(problem = SentEvent.CONFLICT, conflict = ""))
            }
        }
        db.withTransaction {
            dao.deleteEvents(target.id)
            dao.insertEvents(readOnly.distinct().map { it.copy(id = 0, sourceId = target.id) })
            dao.source(target.id)?.let { dao.updateSource(it.copy(ctag = ctag, fetchedFor = key, lastSynced = now(), lastError = null)) }
        }
        onChanged()
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
                val row = sentDao.all().firstOrNull { it.id == rowId && it.problem == SentEvent.CONFLICT } ?: return@withContext
                val target = dao.sources().firstOrNull { it.kind == OutsideCalendars.KIND_NEXTCLOUD && it.sendHere && it.href == row.calendar }
                    ?: throw BackupException("Choose the calendar to keep in sync with first.")
                val href = hrefOf(target, row)
                val item = db.itemDao().byId(row.itemId)
                val current = client.getFile(account, target.href, href)
                val stamp = java.time.Instant.ofEpochMilli(now())
                fun changedAgain(): Nothing = throw BackupException("It changed on Nextcloud again. Check it and choose once more.")
                when (choice) {
                    Resolution.PLANNER -> when {
                        item == null -> { if (current != null && client.deleteFile(account, target.href, href, current.etag) == WriteResult.Changed) changedAgain(); sentDao.delete(row.id) }
                        current == null -> {
                            val uid = "planner-${java.util.UUID.randomUUID()}@planner"
                            val body = CalendarExport.encode(item, uid, zone(), stamp)
                            val result = client.putEvent(account, target.href, uid, body, null) as? WriteResult.Ok ?: changedAgain()
                            sentDao.put(row.copy(uid = uid, href = null, etag = result.etag, ics = body, fingerprint = fingerprint(item), problem = null, conflict = null))
                        }
                        else -> {
                            val body = ServerEvents.patch(current.data, item, zone(), stamp)
                            val result = client.putFile(account, target.href, href, body, current.etag) as? WriteResult.Ok ?: changedAgain()
                            sentDao.put(row.copy(etag = result.etag, ics = body, fingerprint = fingerprint(item), problem = null, conflict = null))
                        }
                    }
                    Resolution.NEXTCLOUD -> {
                        val server = current?.let { ServerEvents.parse(it.data, zone()).item }
                        when {
                            current == null -> { item?.let { store.archive(setOf(it.id)) }; sentDao.delete(row.id) }
                            server == null -> sentDao.delete(row.id) // read-only there now; Planner's event stays as it is
                            else -> {
                                val id = if (item != null) { store.update(ServerEvents.apply(item, server)); item.id } else store.add(server)
                                val saved = db.itemDao().byId(id) ?: return@withContext
                                sentDao.put(row.copy(itemId = id, etag = current.etag, ics = current.data, fingerprint = fingerprint(saved), problem = null, conflict = null))
                            }
                        }
                    }
                    Resolution.BOTH -> {
                        val server = current?.let { ServerEvents.parse(it.data, zone()).item }
                        require(item != null && current != null && server != null) { "Both versions are needed to keep both." }
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

    private fun sendable(item: ItineraryItem) = item.id > 0 && item.category != "Bills" && !item.skipped

    // What Planner syncs for [item], as a short fingerprint: a change in any synced detail (or the phone's time zone,
    // which moves the times) changes it.
    private fun fingerprint(item: ItineraryItem): String {
        val text = CalendarExport.encode(item, "planner", zone(), java.time.Instant.EPOCH)
        return java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }.take(32)
    }

    // For backups: which calendar Planner keeps in sync with and what it synced, so a restore doesn't send everything
    // again. Conflicts aren't kept (they're checked again).
    suspend fun sendSnapshot(): Pair<CalendarChoice, List<SentEvent>>? {
        val target = dao.sources().firstOrNull { it.kind == OutsideCalendars.KIND_NEXTCLOUD && it.sendHere } ?: return null
        return CalendarChoice(target.account, target.href, target.name, target.color, target.enabled) to
            db.sentDao().all().filter { it.account == target.account && it.calendar == target.href }
    }

    suspend fun restoreSend(target: CalendarChoice?, rows: List<SentEvent>) {
        db.withTransaction {
            db.sentDao().deleteAll()
            dao.sources().filter { it.sendHere }.forEach { dao.updateSource(it.copy(sendHere = false)) }
            if (target == null) return@withTransaction
            val existing = dao.sources().firstOrNull { it.kind == OutsideCalendars.KIND_NEXTCLOUD && it.account == target.account && it.href == target.href }
            if (existing != null) dao.updateSource(existing.copy(sendHere = true, enabled = true, ctag = null, fetchedFor = null))
            else dao.insertSource(CalendarSource(account = target.account, href = target.href, name = target.name, color = target.color,
                enabled = true, sendHere = true))
            db.sentDao().insertAll(rows.filter { it.account == target.account && it.calendar == target.href }.map { it.copy(id = 0) })
        }
        _sendState.value = State()
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
                enabled = true, ctag = validator(fetched), fetchedFor = key, lastSynced = now()))
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
                            dao.updateSource(current.copy(ctag = validator(result), fetchedFor = key, lastSynced = now(), lastError = null))
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
    // its last download. Returns false when another sync was already running.
    suspend fun sync(): Boolean {
        if (!lock.tryLock()) return false
        try {
            lastAttempt = now()
            _state.value = State(running = true, message = "Syncing calendars…")
            _state.value = try {
                State(message = withContext(Dispatchers.IO) { syncLocked() })
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

    private suspend fun syncLocked(): String {
        val account = accounts.load() ?: throw BackupException("Connect to Nextcloud first.")
        val key = accountKey(account)
        val remote = client.calendars(account).associateBy { it.href }
        // Calendars of another login, and ones no longer on the server, go with their events.
        db.withTransaction {
            val existing = dao.sources().filter { it.kind == OutsideCalendars.KIND_NEXTCLOUD }
            existing.filter { it.account != key || it.href !in remote }.forEach { dao.deleteSource(it.id) }
            val kept = existing.filter { it.account == key && it.href in remote }.associateBy { it.href }
            remote.values.forEach { calendar ->
                val old = kept[calendar.href]
                if (old == null) dao.insertSource(CalendarSource(account = key, href = calendar.href, name = calendar.name, color = calendar.color,
                    writable = calendar.writable))
                else if (old.name != calendar.name || old.color != calendar.color || old.writable != calendar.writable)
                    dao.updateSource(old.copy(name = calendar.name, color = calendar.color, writable = calendar.writable,
                        sendHere = old.sendHere && calendar.writable))
            }
        }
        val today = java.time.Instant.ofEpochMilli(now()).atZone(zone()).toLocalDate()
        val from = today.minusMonths(MONTHS_BACK).withDayOfMonth(1)
        val until = today.plusMonths(MONTHS_AHEAD + 1).withDayOfMonth(1)
        val window = "$from|${zone().id}"
        var failed = 0
        var skipped = 0
        for (source in dao.sources().filter { it.enabled && it.kind == OutsideCalendars.KIND_NEXTCLOUD && !it.sendHere }) {
            val calendar = remote[source.href] ?: continue
            if (calendar.ctag != null && calendar.ctag == source.ctag && source.fetchedFor == window && source.lastError == null) continue
            try {
                val data = client.calendarEvents(account, source.href, from.atStartOfDay(zone()).toInstant(), until.atStartOfDay(zone()).toInstant())
                // Planner's own events (sent from this phone, step 5) are already shown as Planner events.
                val own = db.sentDao().all().filter { it.account == key }.mapNotNullTo(HashSet()) { it.uid }
                val read = OutsideEventReader.read(data, zone(), skipUids = own)
                skipped += read.skipped
                db.withTransaction {
                    // Unticked or removed while downloading: keep nothing.
                    val current = dao.source(source.id) ?: return@withTransaction
                    dao.deleteEvents(source.id)
                    if (!current.enabled) return@withTransaction
                    dao.insertEvents(read.events.map { it.copy(id = 0, sourceId = source.id) })
                    dao.updateSource(current.copy(ctag = calendar.ctag, fetchedFor = window, lastSynced = now(), lastError = null))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed++
                val message = (e as? BackupException)?.message ?: "Couldn't download this calendar."
                db.withTransaction { dao.source(source.id)?.let { dao.updateSource(it.copy(lastError = message)) } }
            }
        }
        // The calendar kept in sync both ways (step 6).
        dao.sources().firstOrNull { it.kind == OutsideCalendars.KIND_NEXTCLOUD && it.sendHere && it.account == key }?.let { target ->
            remote[target.href]?.let { calendar ->
                try { sendLock.withLock { pullLocked(account, target, calendar.ctag) } }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { failed++; _sendState.value = State(message = (e as? BackupException)?.message ?: "Couldn't check the synced calendar.", error = true) }
                if (failed == 0) _sendState.value = summary(target)
            }
        }
        onChanged()
        val ticked = dao.sources().count { it.enabled && it.kind == OutsideCalendars.KIND_NEXTCLOUD }
        return buildString {
            append(when {
                remote.isEmpty() -> "No calendars found on Nextcloud."
                ticked == 0 -> "Tick the calendars to show in Planner."
                failed > 0 -> "Synced, but $failed calendar${if (failed == 1) "" else "s"} couldn't be downloaded."
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
        // What Planner sent stays on Nextcloud; the record of it goes with the login.
        db.withTransaction { dao.deleteEventsOfKind(OutsideCalendars.KIND_NEXTCLOUD); dao.deleteSourcesOfKind(OutsideCalendars.KIND_NEXTCLOUD); db.sentDao().deleteAll() }
        _sendState.value = State()
        lastAttempt = 0L
        _state.value = State()
        onChanged()
    }

    // Everything, both kinds (the test runner's clean start).
    suspend fun clearAll() {
        db.withTransaction { dao.deleteAllEvents(); dao.deleteAllSources(); db.sentDao().deleteAll() }
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
    // downloads them. Phone calendars are left as they are.
    suspend fun restoreChoices(choices: List<CalendarChoice>) {
        db.withTransaction {
            listOf(OutsideCalendars.KIND_NEXTCLOUD, OutsideCalendars.KIND_LINK).forEach { dao.deleteEventsOfKind(it); dao.deleteSourcesOfKind(it) }
            choices.distinctBy { it.account to it.href }.forEach {
                val link = it.account == LINK_ACCOUNT
                if (link && runCatching { CalendarLinks.normalize(it.href) }.isFailure) return@forEach
                dao.insertSource(CalendarSource(account = it.account, href = it.href, name = it.name, color = it.color, enabled = it.enabled,
                    kind = if (link) OutsideCalendars.KIND_LINK else OutsideCalendars.KIND_NEXTCLOUD,
                    detail = if (link) runCatching { CalendarLinks.normalize(it.href).host }.getOrNull() else null))
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
        private const val PHONE_ACCOUNT = "phone"
        const val LINK_ACCOUNT = "link"
        const val LINK_INTERVAL_MS = 60 * 60 * 1000L
        const val SEND_DELAY_MS = 5_000L
        private val ORDER = compareBy<OutsideEvent>({ it.date }, { it.startTime }, { it.title }, { it.endDate }, { it.durationMinutes }, { it.location }, { it.notes })
        private fun phoneHref(id: Long) = "calendar/$id"
        private fun calendarId(href: String) = href.removePrefix("calendar/").toLongOrNull()

        // Which login a calendar belongs to: the server address and username, never the password.
        fun accountKey(account: NextcloudAccount): String = "${account.server}|${account.username}"
    }
}
