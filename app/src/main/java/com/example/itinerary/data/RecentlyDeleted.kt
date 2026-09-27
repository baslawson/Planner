package com.example.itinerary.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.UUID

const val TRASH_RETENTION_MS = 30L * 24 * 60 * 60 * 1000

@Entity(tableName = "recently_deleted")
data class DeletedEntry(@PrimaryKey val id: String = UUID.randomUUID().toString(),
    val deletedAt: Long = System.currentTimeMillis(), val label: String, val payload: String)

@Dao
interface DeletedDao {
    @Query("SELECT * FROM recently_deleted ORDER BY deletedAt DESC") fun observe(): Flow<List<DeletedEntry>>
    @Query("SELECT * FROM recently_deleted") suspend fun all(): List<DeletedEntry>
    @Query("SELECT * FROM recently_deleted WHERE id = :id") suspend fun byId(id: String): DeletedEntry?
    @Insert suspend fun insert(entry: DeletedEntry)
    @Insert suspend fun insertAll(entries: List<DeletedEntry>)
    @Query("DELETE FROM recently_deleted WHERE id = :id") suspend fun delete(id: String)
    @Query("DELETE FROM recently_deleted") suspend fun deleteAll()
}

data class DeletedContents(val trips: List<Trip>, val items: List<ItineraryItem>,
    val attachments: List<Attachment>, val reminders: List<Reminder>, val tasks: List<PlannerTask> = emptyList()) {
    val storedAttachments: List<Attachment> get() = attachments + tasks.flatMap { it.attachments }
}

object DeletedCodec {
    fun encode(data: DeletedContents): String = JSONObject().apply {
        put("tasks", TaskCodec.encode(data.tasks))
        put("trips", JSONArray().apply { data.trips.forEach { t -> put(JSONObject()
            .put("id", t.id).put("name", t.name).put("destination", t.destination)
            .put("startDate", t.startDate.toString()).put("endDate", t.endDate.toString())
            .put("sortOrder", t.sortOrder).put("colorIndex", t.colorIndex).put("customColor", t.customColor)) } })
        put("items", JSONArray().apply { data.items.forEach { put(DraftCodec.item(it)) } })
        put("attachments", DraftCodec.attachments(data.attachments))
        put("reminders", DraftCodec.reminders(data.reminders))
    }.toString()

    fun decode(text: String): DeletedContents {
        val root = JSONObject(text)
        val plans = root.getJSONArray("trips")
        val events = root.getJSONArray("items")
        val data = DeletedContents(List(plans.length()) { i -> plans.getJSONObject(i).let { t ->
            Trip(t.getLong("id"), t.getString("name"), t.getString("destination"), LocalDate.parse(t.getString("startDate")),
                LocalDate.parse(t.getString("endDate")), t.getInt("sortOrder"), t.getInt("colorIndex"),
                if (t.has("customColor")) t.getInt("customColor") else null)
        } }, List(events.length()) { DraftCodec.item(events.getJSONObject(it)) },
            DraftCodec.attachments(root.getJSONArray("attachments")), DraftCodec.reminders(root.getJSONArray("reminders")), TaskCodec.decode(if (root.has("tasks")) root.getJSONArray("tasks") else JSONArray()))
        val ids = data.items.map { it.id }.toSet()
        val plansById = data.trips.map { it.id }.toSet()
        require(ids.size == data.items.size && plansById.size == data.trips.size)
        require(data.items.all { it.id > 0 && it.tripId in plansById })
        require(data.trips.all { it.id > 0 })
        data.items.forEach { Bills.validate(it.billAmountMinor, it.billCurrency); Payments.validate(it) }
        require(data.attachments.all { it.id > 0 && it.itemId in ids &&
            (it.url?.let { url -> Links.normalize(url) != null } ?: Regex("[A-Za-z0-9][A-Za-z0-9._-]*").matches(it.fileName)) })
        require(data.reminders.all { it.id > 0 && it.itemId in ids })
        require(data.attachments.map { it.id }.distinct().size == data.attachments.size)
        require(data.reminders.map { it.id }.distinct().size == data.reminders.size)
        return data
    }
}
