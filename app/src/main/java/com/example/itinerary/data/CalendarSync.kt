package com.example.itinerary.data

import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

// A ticked calendar, as a backup stores it: which login and calendar, never its events or the password.
data class CalendarChoice(val account: String, val href: String, val name: String, val color: Int?)

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
) {
    data class State(val running: Boolean = false, val message: String? = null, val error: Boolean = false)

    private val dao = db.outsideDao()
    private val lock = Mutex()
    private var lastAttempt = 0L

    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()
    private val phoneLock = Mutex()
    private val _phoneState = MutableStateFlow(State())
    val phoneState = _phoneState.asStateFlow()

    val sources: Flow<List<CalendarSource>> = dao.observeSources()

    // Ticked calendars' events with their calendar's name and colour, keyed by the id they are shown under.
    val shown: Flow<Map<Long, OutsideInfo>> = combine(dao.observeShown(), dao.observeSources()) { events, sources ->
        val byId = sources.associateBy { it.id }
        events.mapNotNull { event ->
            val source = byId[event.sourceId] ?: return@mapNotNull null
            event.displayId() to OutsideInfo(event, source.name, source.color ?: OutsideCalendars.DEFAULT_COLOR,
                phone = source.kind == OutsideCalendars.KIND_PHONE)
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
        if (now() - lastAttempt < MIN_INTERVAL_MS) return
        if (dao.sources().none { it.enabled && it.kind == OutsideCalendars.KIND_NEXTCLOUD } || !hasAccount()) return
        sync()
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
                if (old == null) dao.insertSource(CalendarSource(account = key, href = calendar.href, name = calendar.name, color = calendar.color))
                else if (old.name != calendar.name || old.color != calendar.color) dao.updateSource(old.copy(name = calendar.name, color = calendar.color))
            }
        }
        val today = java.time.Instant.ofEpochMilli(now()).atZone(zone()).toLocalDate()
        val from = today.minusMonths(MONTHS_BACK).withDayOfMonth(1)
        val until = today.plusMonths(MONTHS_AHEAD + 1).withDayOfMonth(1)
        val window = "$from|${zone().id}"
        var failed = 0
        var skipped = 0
        for (source in dao.sources().filter { it.enabled && it.kind == OutsideCalendars.KIND_NEXTCLOUD }) {
            val calendar = remote[source.href] ?: continue
            if (calendar.ctag != null && calendar.ctag == source.ctag && source.fetchedFor == window && source.lastError == null) continue
            try {
                val data = client.calendarEvents(account, source.href, from.atStartOfDay(zone()).toInstant(), until.atStartOfDay(zone()).toInstant())
                val read = OutsideEventReader.read(data, zone())
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
        db.withTransaction { dao.deleteEventsOfKind(OutsideCalendars.KIND_NEXTCLOUD); dao.deleteSourcesOfKind(OutsideCalendars.KIND_NEXTCLOUD) }
        lastAttempt = 0L
        _state.value = State()
        onChanged()
    }

    // Everything, both kinds (the test runner's clean start).
    suspend fun clearAll() {
        db.withTransaction { dao.deleteAllEvents(); dao.deleteAllSources() }
        lastAttempt = 0L
        _state.value = State(); _phoneState.value = State()
        onChanged()
    }

    // For backups: which Nextcloud calendars are ticked. Phone calendars are left out: another phone numbers them differently.
    suspend fun choices(): List<CalendarChoice> = dao.sources().filter { it.enabled && it.kind == OutsideCalendars.KIND_NEXTCLOUD }
        .map { CalendarChoice(it.account, it.href, it.name, it.color) }

    // After restoring a backup: the ticked Nextcloud calendars come back without events; the next sync downloads them.
    // Phone calendars are left as they are.
    suspend fun restoreChoices(choices: List<CalendarChoice>) {
        db.withTransaction {
            dao.deleteEventsOfKind(OutsideCalendars.KIND_NEXTCLOUD); dao.deleteSourcesOfKind(OutsideCalendars.KIND_NEXTCLOUD)
            choices.distinctBy { it.account to it.href }.forEach {
                dao.insertSource(CalendarSource(account = it.account, href = it.href, name = it.name, color = it.color, enabled = true))
            }
        }
        lastAttempt = 0L
        onChanged()
    }

    companion object {
        const val MIN_INTERVAL_MS = 15 * 60 * 1000L
        const val MONTHS_BACK = 3L
        const val MONTHS_AHEAD = 12L
        const val PERMISSION_NEEDED = "Permission needed: allow Planner to read calendars."
        private const val PHONE_ACCOUNT = "phone"
        private val ORDER = compareBy<OutsideEvent>({ it.date }, { it.startTime }, { it.title }, { it.endDate }, { it.durationMinutes }, { it.location }, { it.notes })
        private fun phoneHref(id: Long) = "calendar/$id"
        private fun calendarId(href: String) = href.removePrefix("calendar/").toLongOrNull()

        // Which login a calendar belongs to: the server address and username, never the password.
        fun accountKey(account: NextcloudAccount): String = "${account.server}|${account.username}"
    }
}
