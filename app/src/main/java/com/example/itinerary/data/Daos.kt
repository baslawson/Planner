package com.example.itinerary.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.time.LocalTime

// Counts can be read without loading event titles, notes and other columns into memory.
data class CategoryCount(val category: String, val count: Int)

// Compact agenda/share summary. Full notes and category stay in the database; checklist progress is shown on cards.
data class PlanEvent(
    val tripId: Long,
    val id: Long,
    val date: LocalDate,
    val startTime: LocalTime?, // null = all day
    val title: String,
    val colorIndex: Int,
    val customColor: Int?,
    val durationMinutes: Int? = null,
    val location: String = "",
    val checklist: List<ChecklistEntry> = emptyList(),
    val category: String = Categories.OTHER,
    val paid: Boolean = false,
    val billAmountMinor: Long? = null,
    val billCurrency: String = "AUD",
    val skipped: Boolean = false,
    val seriesId: String? = null,
    val payments: List<BillPayment> = emptyList(),
    val linkedTaskId: String? = null,
    val endDate: LocalDate? = null,
)

@Dao
interface TripDao {
    @Query("SELECT id FROM trips ORDER BY sortOrder, id LIMIT 1")
    suspend fun firstId(): Long?

    @Query("SELECT * FROM trips WHERE id IN (:ids) ORDER BY sortOrder, id")
    suspend fun byIds(ids: List<Long>): List<Trip>

    @Query("SELECT * FROM trips ORDER BY sortOrder, id")
    fun observeTrips(): Flow<List<Trip>>


    // New plans are placed above this, so they land at the top of the list.
    @Query("SELECT COALESCE(MIN(sortOrder), 0) FROM trips")
    suspend fun minOrder(): Int

    @Query("SELECT * FROM trips WHERE id = :id")
    fun observeTrip(id: Long): Flow<Trip?>

    @Query("SELECT * FROM trips ORDER BY sortOrder, id")
    suspend fun all(): List<Trip>

    @Upsert
    suspend fun upsert(trip: Trip): Long

    // Used when restoring a backup; the ids in [trips] are kept as they are.
    @Insert
    suspend fun insertAll(trips: List<Trip>)

    // Activities, reminders and attachments go too, via cascade.
    @Query("DELETE FROM trips")
    suspend fun deleteAll()

    @Delete
    suspend fun delete(trip: Trip)
}

@Dao
interface ItemDao {
    @Query("SELECT EXISTS(SELECT 1 FROM items WHERE draftToken = :token)")
    suspend fun hasDraftToken(token: String): Boolean

    // The first event a save added (a new series shares its token); the editor was showing that one.
    @Query("SELECT id FROM items WHERE draftToken = :token ORDER BY id LIMIT 1")
    suspend fun firstIdForDraftToken(token: String): Long?

    @Query("SELECT * FROM items WHERE id IN (:ids) ORDER BY id")
    suspend fun byIds(ids: List<Long>): List<ItineraryItem>

    // Timed durations are at most 24 hours, so only yesterday's can carry into today; multi-day all-day events can
    // have started any day before and run to their end date.
    @Query("SELECT * FROM items WHERE (date IN (:day, :previousDay) OR date < :day AND endDate >= :day) AND skipped = 0")
    suspend fun dayCandidates(day: LocalDate, previousDay: LocalDate): List<ItineraryItem>

    @Query("SELECT * FROM items WHERE seriesId = :seriesId ORDER BY date, id")
    suspend fun forSeries(seriesId: String): List<ItineraryItem>

    @Query("SELECT EXISTS(SELECT 1 FROM items WHERE linkedTaskId = :taskId)")
    suspend fun hasTimeBlocks(taskId: String): Boolean

    @Query("SELECT * FROM items WHERE category = 'Bills' AND billAmountMinor = :amount AND billCurrency = :currency AND date IN (:dates)")
    suspend fun billCandidates(amount: Long, currency: String, dates: List<LocalDate>): List<ItineraryItem>

    @Query("SELECT * FROM items WHERE id = :id")
    suspend fun byId(id: Long): ItineraryItem?

    // One event as stored, again whenever it changes (null once it's gone): an open editor watching for a sync's update.
    @Query("SELECT * FROM items WHERE id = :id")
    fun observe(id: Long): Flow<ItineraryItem?>

    // A multi-day event keeps its length: the end date moves by the same number of days (SET reads the old row).
    @Query("UPDATE items SET date = :date, endDate = CASE WHEN endDate IS NULL THEN NULL " +
        "ELSE date(endDate, printf('%+d days', CAST(julianday(:date) - julianday(date) AS INTEGER))) END WHERE id = :id")
    suspend fun moveDate(id: Long, date: LocalDate)

    @Query("SELECT category, COUNT(*) AS count FROM items GROUP BY category")
    fun observeCategoryCounts(): Flow<List<CategoryCount>>

    // Null start times (all-day) sort first in SQLite.

    @Query("SELECT * FROM items")
    suspend fun all(): List<ItineraryItem>

    @Query("SELECT * FROM items ORDER BY date, startTime, id")
    fun observeAll(): Flow<List<ItineraryItem>>

    // Every event of every plan, for the agenda and calendar, in date then time order; null start times (all-day)
    // sort first in SQLite.
    @Query(
        "SELECT tripId, id, date, startTime, title, colorIndex, customColor, durationMinutes, location, checklist, category, paid, billAmountMinor, billCurrency, skipped, seriesId, payments, linkedTaskId, endDate FROM items " +
            "ORDER BY date, startTime, id",
    )
    fun observePlanEvents(): Flow<List<PlanEvent>>

    @Upsert
    suspend fun upsert(item: ItineraryItem): Long

    @Insert
    suspend fun insertAll(items: List<ItineraryItem>)

    // Used when categories are removed: their events move to [other].
    @Query("UPDATE items SET category = :other, paid = 0 WHERE category IN (:names)")
    suspend fun replaceCategories(names: List<String>, other: String)

    @Delete
    suspend fun delete(item: ItineraryItem)
}

@Dao
interface ReminderDao {
    @Query("SELECT * FROM reminders WHERE itemId IN (:ids) ORDER BY id")
    suspend fun forItems(ids: List<Long>): List<Reminder>

    @Query("UPDATE reminders SET snoozedUntil = :until WHERE id = :id")
    suspend fun snooze(id: Long, until: Long?)

    @Query("UPDATE reminders SET ringUntilDismissed = :ring, ringSeconds = :seconds WHERE id = :id")
    suspend fun setRing(id: Long, ring: Boolean, seconds: Int)

    @Query("SELECT * FROM reminders WHERE id = :id")
    suspend fun byId(id: Long): Reminder?

    @Query("SELECT * FROM reminders ORDER BY id")
    fun observeAll(): Flow<List<Reminder>>


    @Query("SELECT * FROM reminders WHERE itemId = :itemId ORDER BY id")
    suspend fun forItem(itemId: Long): List<Reminder>

    @Query("SELECT r.* FROM reminders r JOIN items i ON r.itemId = i.id WHERE i.tripId = :tripId")
    suspend fun forTrip(tripId: Long): List<Reminder>

    @Query("SELECT * FROM reminders")
    suspend fun all(): List<Reminder>

    @Insert
    suspend fun insert(reminder: Reminder): Long

    @Insert
    suspend fun insertAll(reminders: List<Reminder>)

    @Delete
    suspend fun delete(reminder: Reminder)

    @Upsert
    suspend fun recordDelivery(delivery: ReminderDelivery)

    @Query("SELECT * FROM reminder_deliveries WHERE reminderId IN (:ids)")
    suspend fun deliveries(ids: List<Long>): List<ReminderDelivery>
}

@Dao
interface AttachmentDao {
    @Query("SELECT * FROM attachments WHERE itemId IN (:ids) ORDER BY id")
    suspend fun forItems(ids: List<Long>): List<Attachment>

    @Query("SELECT * FROM attachments WHERE itemId = :id ORDER BY id")
    suspend fun forItem(id: Long): List<Attachment>

    @Query("SELECT * FROM attachments")
    fun observeAll(): Flow<List<Attachment>>

    @Query("SELECT * FROM attachments ORDER BY id")
    suspend fun all(): List<Attachment>

    // Just the file names, for working out which files nothing refers to any more. Reading the one
    // column avoids building an Attachment for every row when only the name is wanted.
    @Query("SELECT fileName FROM attachments")
    suspend fun allFileNames(): List<String>

    @Insert
    suspend fun insertAll(attachments: List<Attachment>)

    // Rows go away with their item or trip via cascade, so files are looked up first.

    @Query(
        "SELECT a.fileName FROM attachments a JOIN items i ON a.itemId = i.id WHERE i.tripId = :tripId",
    )
    suspend fun fileNamesForTrip(tripId: Long): List<String>

    @Insert
    suspend fun insert(attachment: Attachment): Long

    @Delete
    suspend fun delete(attachment: Attachment)
}
