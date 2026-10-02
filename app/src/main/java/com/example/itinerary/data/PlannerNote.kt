package com.example.itinerary.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * A note, Quillpad style: a title and a Markdown body ([content]), filed in a [notebook] ("" = none), with a colour, pinned
 * to the top or archived out of the way. [tags], [attachments] and a reminder ([reminderAt], [snoozedUntil]) stay on the
 * phone; with Nextcloud Notes sync, the notebook is its category and pinned its favourite.
 */
@Entity(tableName = "notes")
data class PlannerNote(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val title: String = "",
    val content: String = "",
    val notebook: String = "",
    // ARGB, or null for the plain card.
    val color: Int? = null,
    val pinned: Boolean = false,
    val archived: Boolean = false,
    val created: Long = System.currentTimeMillis(),
    val modified: Long = created,
    val tags: List<String> = emptyList(),
    val attachments: List<Attachment> = emptyList(),
    val reminderAt: Long? = null,
    val snoozedUntil: Long? = null,
)

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes ORDER BY id") fun observe(): Flow<List<PlannerNote>>
    @Query("SELECT * FROM notes ORDER BY id") suspend fun all(): List<PlannerNote>
    @Query("SELECT * FROM notes WHERE id = :id") suspend fun byId(id: String): PlannerNote?
    @Query("SELECT * FROM notes WHERE id = :id") fun observe(id: String): Flow<PlannerNote?>
    @Insert suspend fun insert(note: PlannerNote)
    @Insert suspend fun insertAll(notes: List<PlannerNote>)
    @Update suspend fun update(note: PlannerNote)
    @Query("DELETE FROM notes WHERE id = :id") suspend fun delete(id: String)
    @Query("DELETE FROM notes") suspend fun deleteAll()
}

/** Which notes the Notes page shows: everything not archived, one notebook or tag of those, or the archive. */
sealed interface NoteFilter {
    data object All : NoteFilter
    data class Notebook(val name: String) : NoteFilter
    data class Tag(val name: String) : NoteFilter
    data object Archive : NoteFilter
}

object Notes {
    const val MAX_TITLE = 500
    // Long enough for any note; well inside a database row's 2 MB window.
    const val MAX_CONTENT = 100_000
    const val MAX_NOTEBOOK = 100
    const val MAX_TAG = 50
    const val MAX_TAGS = 30

    /** The card colours on offer, beside "no colour": readable with light text on the dark theme and dark text on light. */
    val colors: List<Int> = listOf(0xFF1B5E20, 0xFF00695C, 0xFF0D47A1, 0xFF4A148C, 0xFF880E4F, 0xFFB71C1C, 0xFFE65100, 0xFF5D4037)
        .map { it.toInt() }

    // Pinned first, then the most recently changed.
    val order: Comparator<PlannerNote> = compareByDescending<PlannerNote> { it.pinned }.thenByDescending { it.modified }.thenBy { it.id }

    fun visible(notes: List<PlannerNote>, filter: NoteFilter, query: String): List<PlannerNote> {
        val needle = Search.normalize(query.trim())
        return notes.filter { note ->
            when (filter) {
                NoteFilter.All -> !note.archived
                is NoteFilter.Notebook -> !note.archived && note.notebook == filter.name
                is NoteFilter.Tag -> !note.archived && filter.name in note.tags
                NoteFilter.Archive -> note.archived
            } && (needle.isEmpty() || listOf(note.title, note.content, note.notebook).any { Search.normalize(it).contains(needle) } ||
                note.tags.any { Search.normalize(it).contains(needle) })
        }.sortedWith(order)
    }

    fun notebooks(notes: List<PlannerNote>): List<String> =
        notes.map { it.notebook }.filter { it.isNotBlank() }.distinct().sortedBy { Search.normalize(it) }

    fun tags(notes: List<PlannerNote>): List<String> =
        notes.flatMap { it.tags }.distinct().sortedBy { Search.normalize(it) }

    /** The name a note goes by: its title, else its first line of text, else "Untitled note". */
    fun label(note: PlannerNote): String = note.title.trim().ifBlank {
        Markdown.plain(note.content).lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }?.take(80) ?: ""
    }.ifBlank { "Untitled note" }

    fun clean(note: PlannerNote): PlannerNote = note.copy(
        title = note.title.replace('\n', ' ').trim().take(MAX_TITLE),
        content = note.content.trimEnd().take(MAX_CONTENT),
        notebook = cleanNotebook(note.notebook),
        tags = note.tags.map(::cleanTag).filter { it.isNotEmpty() }.distinct().take(MAX_TAGS),
    )

    fun cleanNotebook(name: String) = name.replace('\n', ' ').trim().trim('/').take(MAX_NOTEBOOK)
    fun cleanTag(name: String) = name.replace('\n', ' ').trim().removePrefix("#").trim().take(MAX_TAG)

    fun validate(note: PlannerNote) {
        require(note.id.isNotBlank()) { "A note needs an id" }
        require(note.title.length <= MAX_TITLE && note.content.length <= MAX_CONTENT) { "This note is too long" }
        require(note.notebook.length <= MAX_NOTEBOOK && '\n' !in note.notebook) { "Invalid notebook name" }
        require(note.tags.size <= MAX_TAGS && note.tags.all { it.isNotBlank() && it.length <= MAX_TAG }) { "Invalid tags" }
        require(note.title.isNotBlank() || note.content.isNotBlank() || note.attachments.isNotEmpty()) { "An empty note can't be saved" }
    }
}

object NoteCodec {
    fun encode(notes: List<PlannerNote>): JSONArray = JSONArray().apply {
        notes.forEach { note -> put(JSONObject().put("id", note.id).put("title", note.title).put("content", note.content)
            .put("notebook", note.notebook).put("color", note.color ?: JSONObject.NULL)
            .put("pinned", note.pinned).put("archived", note.archived)
            .put("created", note.created).put("modified", note.modified)
            .put("tags", JSONArray(note.tags))
            .put("attachments", DraftCodec.attachments(note.attachments))
            .put("reminderAt", note.reminderAt ?: JSONObject.NULL)
            .put("snoozedUntil", note.snoozedUntil ?: JSONObject.NULL)) }
    }

    fun decode(array: JSONArray): List<PlannerNote> = List(array.length()) { index ->
        val value = array.getJSONObject(index)
        fun time(name: String): Long? = if (!value.has(name) || value.isNull(name)) null else {
            val number = value.get(name)
            require(number is Long || number is Int) { "Invalid note time" }
            (number as Number).toLong()
        }
        val created = time("created") ?: 0L
        PlannerNote(value.getString("id"), value.optString("title"), value.optString("content"), value.optString("notebook"),
            if (!value.has("color") || value.isNull("color")) null else value.getInt("color"),
            value.optBoolean("pinned"), value.optBoolean("archived"), created, time("modified") ?: created,
            StringListCodec.decode(value.optJSONArray("tags") ?: JSONArray()),
            DraftCodec.attachments(value.optJSONArray("attachments")), time("reminderAt"), time("snoozedUntil"))
            // A note saved by a later version with longer text is cut rather than refusing the whole file.
            .let(Notes::clean).also(Notes::validate)
    }.also { notes -> require(notes.map { it.id }.distinct().size == notes.size) }
}
