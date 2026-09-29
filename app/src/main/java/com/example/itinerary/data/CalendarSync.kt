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

// Read-only calendar sync with Nextcloud (step 1): lists the calendars on the backup login, downloads the ticked ones and
// keeps their events in their own tables. It only ever reads from the server. Runs when the app opens (at most every
// 15 minutes) and on "Sync now"; there is no background work.
class CalendarSync(
    private val db: AppDatabase,
    private val accounts: NextcloudAccountStore,
    // A var only so UI tests can point the app at a local test server.
    internal var client: NextcloudClient = NextcloudClient(),
    private val onChanged: () -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    data class State(val running: Boolean = false, val message: String? = null, val error: Boolean = false)

    private val dao = db.outsideDao()
    private val lock = Mutex()
    private var lastAttempt = 0L

    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()

    val sources: Flow<List<CalendarSource>> = dao.observeSources()

    // Ticked calendars' events with their calendar's name and colour, keyed by the id they are shown under.
    val shown: Flow<Map<Long, OutsideInfo>> = combine(dao.observeShown(), dao.observeSources()) { events, sources ->
        val byId = sources.associateBy { it.id }
        events.mapNotNull { event ->
            val source = byId[event.sourceId] ?: return@mapNotNull null
            event.displayId() to OutsideInfo(event, source.name, source.color ?: OutsideCalendars.DEFAULT_COLOR)
        }.toMap(LinkedHashMap())
    }.distinctUntilChanged()

    suspend fun hasAccount(): Boolean = withContext(Dispatchers.IO) { runCatching { accounts.load() != null }.getOrDefault(false) }

    suspend fun widgetItems(day: LocalDate): List<ItineraryItem> {
        val colors = dao.sources().associate { it.id to (it.color ?: OutsideCalendars.DEFAULT_COLOR) }
        return eventsOnDay(dao.dayCandidates(day, day.minusDays(1)).map { it.toItem(colors[it.sourceId] ?: OutsideCalendars.DEFAULT_COLOR) }, day)
    }

    // When the app opens: nothing without a login or a ticked calendar, and at most once every 15 minutes.
    suspend fun syncIfDue() {
        if (now() - lastAttempt < MIN_INTERVAL_MS) return
        if (dao.sources().none { it.enabled } || !hasAccount()) return
        sync()
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
            val existing = dao.sources()
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
        for (source in dao.sources().filter { it.enabled }) {
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
        val ticked = dao.sources().count { it.enabled }
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

    // After disconnecting Nextcloud: the calendar list and every downloaded event leave the phone.
    suspend fun clearAll() {
        db.withTransaction { dao.deleteAllEvents(); dao.deleteAllSources() }
        lastAttempt = 0L
        _state.value = State()
        onChanged()
    }

    // For backups: which calendars are ticked.
    suspend fun choices(): List<CalendarChoice> = dao.sources().filter { it.enabled }.map { CalendarChoice(it.account, it.href, it.name, it.color) }

    // After restoring a backup: the ticked calendars come back without events; the next sync downloads them.
    suspend fun restoreChoices(choices: List<CalendarChoice>) {
        db.withTransaction {
            dao.deleteAllEvents(); dao.deleteAllSources()
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

        // Which login a calendar belongs to: the server address and username, never the password.
        fun accountKey(account: NextcloudAccount): String = "${account.server}|${account.username}"
    }
}
