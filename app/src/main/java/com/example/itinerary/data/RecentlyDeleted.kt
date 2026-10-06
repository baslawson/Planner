package com.example.itinerary.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
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

// One database row bigger than Android's 2 MB cursor window can't be read back, and every read of this table (the list,
// Undo, backups, restores) would then fail. So a big bundle (an undone calendar import, a series whose documents carry
// long recognised text) is kept in a file of its own and its row holds only "file:<name>". Small ones stay in the row.
class DeletedPayloads(private val dir: File) {
    // What the row keeps for [payload]. The file is written and synced before the row, so a saved row never points at nothing.
    fun store(payload: String): String {
        if (payload.length <= INLINE_LIMIT) return payload
        dir.mkdirs()
        val name = "${UUID.randomUUID()}.json"
        val temp = File(dir, "$name.tmp")
        try {
            FileOutputStream(temp).use { out -> out.write(payload.toByteArray(Charsets.UTF_8)); out.fd.sync() }
            check(temp.renameTo(File(dir, name))) { "Couldn't keep the deleted items" }
        } catch (e: Throwable) { temp.delete(); throw e }
        return PREFIX + name
    }
    // The bundle's JSON, whether it is in the row (as before) or in a file.
    fun read(stored: String): String = fileOf(stored)?.readText(Charsets.UTF_8) ?: stored
    fun delete(stored: String) { fileOf(stored)?.delete() }
    private fun fileOf(stored: String): File? {
        if (!stored.startsWith(PREFIX)) return null // a bundle's JSON starts with "{"
        val name = stored.removePrefix(PREFIX)
        require(NAME.matches(name)) { "Unreadable deleted items" }
        return File(dir, name)
    }
    companion object {
        // Characters: at most about 600 KB as UTF-8, well inside the window.
        const val INLINE_LIMIT = 200_000
        private const val PREFIX = "file:"
        private val NAME = Regex("[0-9a-f-]{36}\\.json")
    }
}

data class DeletedContents(val trips: List<Trip>, val items: List<ItineraryItem>,
    val attachments: List<Attachment>, val reminders: List<Reminder>, val tasks: List<PlannerTask> = emptyList(),
    val notes: List<PlannerNote> = emptyList()) {
    val storedAttachments: List<Attachment> get() = attachments + tasks.flatMap { it.attachments } + notes.flatMap { it.attachments }
}

// H17-D3: Recently deleted bundles read one at a time, null for one that can't be read (a damaged row or file), which
// goes to [unreadable]: one such bundle mustn't stop cleanup, purging and backups for good.
fun <T> readEachDeleted(entries: List<T>, read: (T) -> DeletedContents, unreadable: (T, Exception) -> Unit): List<DeletedContents?> =
    entries.map { entry -> try { read(entry) } catch (e: Exception) { unreadable(entry, e); null } }

object DeletedCodec {
    fun encode(data: DeletedContents): String = JSONObject().apply {
        put("tasks", TaskCodec.encode(data.tasks))
        put("notes", NoteCodec.encode(data.notes))
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
            DraftCodec.attachments(root.getJSONArray("attachments")), DraftCodec.reminders(root.getJSONArray("reminders")), TaskCodec.decode(if (root.has("tasks")) root.getJSONArray("tasks") else JSONArray()),
            NoteCodec.decode(root.optJSONArray("notes") ?: JSONArray()))
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
