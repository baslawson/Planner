package com.example.itinerary.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit

// Calendars kept somewhere else (Nextcloud, or the calendars on this phone) that Planner shows but never changes. Their events live in their
// own table, so they stay out of backups, Recently deleted, reminders and every editor. On screen they become ordinary
// ItineraryItem/PlanEvent rows with negative ids (see OutsideEvent.toItem); the repository refuses to change those.

@Entity(tableName = "calendar_sources", indices = [Index(value = ["account", "href"], unique = true)])
data class CalendarSource(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    // The login it belongs to (see CalendarSync.accountKey). A calendar of another login is removed at the next sync.
    val account: String,
    // The calendar's path on the server, such as /remote.php/dav/calendars/user/personal/
    val href: String,
    val name: String,
    // Opaque ARGB, from the server; null = the default outside-calendar colour.
    val color: Int? = null,
    // Ticked in Settings → Calendars. Only ticked calendars are downloaded and shown.
    val enabled: Boolean = false,
    // The server's change marker when this calendar was last downloaded; null = download it at the next sync.
    val ctag: String? = null,
    // The window and time zone of that download ("2026-06-01|Australia/Perth"): a new month or zone downloads again.
    val fetchedFor: String? = null,
    val lastSynced: Long? = null,
    // Why the last download of this calendar failed, in words for the user; null when it worked.
    val lastError: String? = null,
    // Where it lives: KIND_NEXTCLOUD, KIND_PHONE (Android's calendar storage: Google, Samsung, DAVx⁵…) or KIND_LINK (a
    // calendar subscribed to by its https link; href is the link). Each kind is synced, cleared and backed up on its own.
    @ColumnInfo(defaultValue = "'NEXTCLOUD'") val kind: String = OutsideCalendars.KIND_NEXTCLOUD,
    // A second line in Settings → Calendars (a phone calendar's account); null = none.
    val detail: String? = null,
    // Nextcloud only: whether this login may add events to it, and whether Planner sends its own events here (step 5;
    // at most one calendar).
    @ColumnInfo(defaultValue = "1") val writable: Boolean = true,
    @ColumnInfo(defaultValue = "0") val sendHere: Boolean = false,
)

// Steps 5–6: one Planner event kept in sync with a file in the Nextcloud calendar, so each pass can tell what changed on
// which side. No foreign key on purpose: a row whose event is gone is how a deletion in Planner is noticed. [uid] null =
// a past event that was only noted, not sent (it's sent once it's edited). [href]: the file on the server (null = the
// uid's own name, as Planner names what it creates). [ics]: that file as last synced, so an update changes only what
// Planner manages and keeps the rest (attendees, alarms…). [problem]: CHANGED/DELETED — a write found the server copy
// changed or gone (the next check sorts it out); CONFLICT — changed on both sides, [conflict] holds Nextcloud's version
// ("" = deleted there) until the user chooses.
@Entity(tableName = "sent_events", indices = [Index(value = ["itemId"], unique = true)])
data class SentEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val itemId: Long,
    val account: String,
    val calendar: String,
    val uid: String?,
    val etag: String? = null,
    val fingerprint: String,
    val problem: String? = null,
    val href: String? = null,
    val ics: String? = null,
    val conflict: String? = null,
) {
    companion object {
        const val CHANGED = "CHANGED"
        const val DELETED = "DELETED"
        const val CONFLICT = "CONFLICT"
    }
}

// What two-way sync needs from Planner's own events: save a change (reminders follow), add one, and move some to
// Recently deleted. The app passes its Repository (see asPlannerStore); tests may too.
interface PlannerStore {
    suspend fun update(item: ItineraryItem)
    suspend fun add(item: ItineraryItem): Long
    suspend fun archive(ids: Set<Long>)
}

fun Repository.asPlannerStore(): PlannerStore = object : PlannerStore {
    override suspend fun update(item: ItineraryItem) = saveItem(item)
    override suspend fun add(item: ItineraryItem): Long = importEvents(listOf(item)).single()
    override suspend fun archive(ids: Set<Long>) = archiveEvents(ids)
}

@Dao
interface SentDao {
    @Query("SELECT * FROM sent_events ORDER BY itemId")
    suspend fun all(): List<SentEvent>

    @Query("SELECT * FROM sent_events ORDER BY itemId")
    fun observe(): Flow<List<SentEvent>>

    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun put(row: SentEvent): Long

    @Query("DELETE FROM sent_events WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM sent_events WHERE calendar != :calendar OR account != :account")
    suspend fun deleteOtherCalendars(account: String, calendar: String)

    @Query("DELETE FROM sent_events")
    suspend fun deleteAll()

    @Insert
    suspend fun insertAll(rows: List<SentEvent>)
}

@Entity(
    tableName = "outside_events",
    foreignKeys = [ForeignKey(entity = CalendarSource::class, parentColumns = ["id"], childColumns = ["sourceId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("sourceId"), Index("date")],
)
data class OutsideEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceId: Long,
    val date: LocalDate,
    val startTime: LocalTime?, // null = all day
    val durationMinutes: Int? = null, // timed events up to 24 hours
    val endDate: LocalDate? = null, // last day of an event covering several days (inclusive); null = one day
    // A timed event longer than 24 hours is shown across its days like an all-day event; these are its real clock
    // times. It ends on endDate at timedEnd, or at the midnight after endDate when timedEnd is 00:00.
    val timedStart: LocalTime? = null,
    val timedEnd: LocalTime? = null,
    val title: String,
    val location: String = "",
    val notes: String = "",
)

// An outside event with the calendar it came from, for labels and the read-only view.
data class OutsideInfo(val event: OutsideEvent, val calendar: String, val color: Int, val kind: String = OutsideCalendars.KIND_NEXTCLOUD)

object OutsideCalendars {
    // Every outside event shown in the app belongs to this made-up plan id; no real plan has a negative id.
    const val TRIP_ID = -1L

    // Muted blue, for a calendar the server gives no colour.
    val DEFAULT_COLOR: Int = 0xFF5B7DB1L.toInt()

    fun isOutside(id: Long): Boolean = id < 0

    const val READ_ONLY = "Events from other calendars can't be changed in Planner."

    const val KIND_NEXTCLOUD = "NEXTCLOUD"
    const val KIND_PHONE = "PHONE"
    const val KIND_LINK = "LINK"

    // For subscribed calendars whose file names no colour: the least used of these.
    val LINK_COLORS: List<Int> = listOf(0xFF2E7D6BL, 0xFF8E5BB5L, 0xFFC0632DL, 0xFF3F7FBFL, 0xFFA83E5CL, 0xFF6B8E23L).map { it.toInt() }
}

fun OutsideEvent.displayId(): Long = -id

fun OutsideEvent.toItem(color: Int): ItineraryItem = ItineraryItem(id = displayId(), tripId = OutsideCalendars.TRIP_ID,
    date = date, startTime = startTime, title = title, location = location, notes = notes, category = Categories.OTHER,
    customColor = color, durationMinutes = durationMinutes, endDate = endDate)

fun OutsideEvent.toPlanEvent(color: Int): PlanEvent = PlanEvent(tripId = OutsideCalendars.TRIP_ID, id = displayId(),
    date = date, startTime = startTime, title = title, colorIndex = 0, customColor = color,
    durationMinutes = durationMinutes, location = location, endDate = endDate)

// When a long timed event really ends.
fun OutsideEvent.timedEndAt(): LocalDateTime? {
    val end = timedEnd ?: return null
    val last = endDate ?: date
    return if (end == LocalTime.MIDNIGHT) last.plusDays(1).atStartOfDay() else last.atTime(end)
}

// For Find free time: a long timed event is busy from its real start to its real end, not for whole days.
fun OutsideEvent.busyItem(color: Int): ItineraryItem {
    val start = timedStart ?: return toItem(color)
    val end = timedEndAt() ?: return toItem(color)
    return toItem(color).copy(startTime = start, endDate = null,
        durationMinutes = ChronoUnit.MINUTES.between(date.atTime(start), end).toInt())
}

// A copy for the event editor ("Copy to Planner"): a new, ordinary Planner event. A long timed event, which Planner
// can't hold as timed, becomes an all-day event over the same days.
fun OutsideEvent.plannerCopy(): ItineraryItem = ItineraryItem(tripId = 0, date = date, startTime = startTime, title = title,
    location = location, notes = notes, durationMinutes = durationMinutes, endDate = endDate)

@Dao
interface OutsideDao {
    @Query("SELECT * FROM calendar_sources ORDER BY name COLLATE NOCASE, id")
    fun observeSources(): Flow<List<CalendarSource>>

    @Query("SELECT * FROM calendar_sources ORDER BY name COLLATE NOCASE, id")
    suspend fun sources(): List<CalendarSource>

    @Query("SELECT * FROM calendar_sources WHERE id = :id")
    suspend fun source(id: Long): CalendarSource?

    @Insert
    suspend fun insertSource(source: CalendarSource): Long

    @Update
    suspend fun updateSource(source: CalendarSource)

    @Query("DELETE FROM calendar_sources WHERE id = :id")
    suspend fun deleteSource(id: Long)

    @Query("DELETE FROM calendar_sources")
    suspend fun deleteAllSources()

    @Query("DELETE FROM calendar_sources WHERE kind = :kind")
    suspend fun deleteSourcesOfKind(kind: String)

    @Query("DELETE FROM outside_events WHERE sourceId IN (SELECT id FROM calendar_sources WHERE kind = :kind)")
    suspend fun deleteEventsOfKind(kind: String)

    @Query("SELECT * FROM outside_events WHERE sourceId = :sourceId ORDER BY date, startTime, id")
    suspend fun eventsFor(sourceId: Long): List<OutsideEvent>

    // Events of ticked calendars only, in date then time order.
    @Query("SELECT e.* FROM outside_events e JOIN calendar_sources s ON s.id = e.sourceId WHERE s.enabled = 1 ORDER BY e.date, e.startTime, e.id")
    fun observeShown(): Flow<List<OutsideEvent>>

    // Same as ItemDao.dayCandidates, for the widget.
    @Query("SELECT e.* FROM outside_events e JOIN calendar_sources s ON s.id = e.sourceId WHERE s.enabled = 1 AND " +
        "(e.date IN (:day, :previousDay) OR e.date < :day AND e.endDate >= :day)")
    suspend fun dayCandidates(day: LocalDate, previousDay: LocalDate): List<OutsideEvent>

    @Query("SELECT COUNT(*) FROM outside_events WHERE sourceId = :sourceId")
    suspend fun countFor(sourceId: Long): Int

    @Query("DELETE FROM outside_events WHERE sourceId = :sourceId")
    suspend fun deleteEvents(sourceId: Long)

    @Query("DELETE FROM outside_events")
    suspend fun deleteAllEvents()

    @Insert
    suspend fun insertEvents(events: List<OutsideEvent>)
}
