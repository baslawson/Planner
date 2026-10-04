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
    // Its place on the Notes page, set by dragging (lowest first, pinned notes above the rest); stays on the phone.
    // 0 = not placed yet: the repository puts a new note at the top.
    @ColumnInfo(defaultValue = "0") val position: Long = 0,
    // How important it is (Low / Normal / High, as a task's priority); the page can sort by it. Stays on the phone.
    @ColumnInfo(defaultValue = "'NORMAL'") val priority: TaskPriority = TaskPriority.NORMAL,
)

/** How the Notes page orders its cards; pinned notes stay on top in every one. */
/** How a saved note opens in its editor (Settings → Notes). */
enum class NoteOpenView(val label: String) {
    LAST("The way I left it"), EDIT("Edit"), PREVIEW("Preview");

    /**
     * Whether a note opens in Preview. A new note, a recovered draft or an empty note opens in Edit, ready to type, whatever
     * the setting. [leftInPreview]: how this note was last left, or null when it never was; then a note with words opens
     * in Preview, as before the setting.
     */
    fun opensInPreview(creating: Boolean, recovered: Boolean, hasText: Boolean, leftInPreview: Boolean?): Boolean = when {
        creating || recovered || !hasText -> false
        this == EDIT -> false
        this == PREVIEW -> true
        else -> leftInPreview ?: true
    }
}

enum class NoteSort(val label: String) {
    MY_ORDER("My order"), IMPORTANCE("Importance"), CHANGED("Recently changed"), CREATED("Newest first"), TITLE("Title A–Z"), COLOUR("Colour"),
}

/** When the note's reminder is due now: the snooze if there is one, else [PlannerNote.reminderAt]. */
val PlannerNote.activeReminderAt: Long? get() = reminderAt?.let { snoozedUntil ?: it }

/** The snooze time while it is still ahead of [now]. */
fun PlannerNote.snoozedAt(now: Long): Long? = snoozedUntil?.takeIf { reminderAt != null && it > now }

/** After a time-zone change the reminder keeps its clock time, as a task's does (see PlannerTask.inTimeZone). */
fun PlannerNote.inTimeZone(from: java.time.ZoneId, to: java.time.ZoneId, now: Long): PlannerNote {
    val at = reminderAt ?: return this
    val moved = java.time.Instant.ofEpochMilli(at).atZone(from).toLocalDateTime().atZone(to).toInstant().toEpochMilli()
    if (moved == at) return this
    val keep = snoozedUntil != null || at <= now || moved <= now
    return copy(reminderAt = moved, snoozedUntil = if (keep) snoozedUntil ?: at else null)
}

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes ORDER BY id") fun observe(): Flow<List<PlannerNote>>
    @Query("SELECT * FROM notes ORDER BY id") suspend fun all(): List<PlannerNote>
    @Query("SELECT * FROM notes WHERE id = :id") suspend fun byId(id: String): PlannerNote?
    @Query("SELECT * FROM notes WHERE id = :id") fun observe(id: String): Flow<PlannerNote?>
    @Insert suspend fun insert(note: PlannerNote)
    @Insert suspend fun insertAll(notes: List<PlannerNote>)
    @Update suspend fun update(note: PlannerNote)
    // Room for a note placed at [from] (Duplicate): every note there or after it moves down one place, or [by] places.
    @Query("UPDATE notes SET position = position + 1 WHERE position >= :from") suspend fun makeRoomAt(from: Long)
    @Query("UPDATE notes SET position = position + :by WHERE position >= :from") suspend fun makeRoomAt(from: Long, by: Long)
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

    /** The card colours on offer, beside "no colour"; each card's text is white or black, whichever reads better on it. */
    val colors: List<Int> = listOf(0xFF1B5E20, 0xFF00695C, 0xFF0D47A1, 0xFF4A148C, 0xFF880E4F, 0xFFB71C1C, 0xFFE65100, 0xFF5D4037)
        .map { it.toInt() }

    // Pinned first, then the order dragged into (new notes at the top).
    val order: Comparator<PlannerNote> = compareByDescending<PlannerNote> { it.pinned }.thenBy { it.position }
        .thenByDescending { it.modified }.thenBy { it.id }

    /**
     * A copy of [note] to keep as a new one (Duplicate): the same words, notebook, tags, colour, importance and attachments
     * (the files are shared; a file stays while any note uses it), titled "… (copy)" so the two cards tell apart. Not
     * the reminder (no second alert) and not the pin (it doesn't jump to the top); an archived note's copy stays archived.
     */
    fun copyOf(note: PlannerNote, now: Long = System.currentTimeMillis()): PlannerNote =
        note.copy(id = UUID.randomUUID().toString(), title = copyTitle(note), pinned = false, reminderAt = null, snoozedUntil = null,
            created = now, modified = now, position = 0)

    fun copyTitle(note: PlannerNote): String {
        val base = note.title.trim().ifBlank { label(note).takeUnless { it == "Untitled note" }.orEmpty() }
        val suffix = " (copy)"
        return if (base.isEmpty()) "Copy" else base.take(MAX_TITLE - suffix.length) + suffix
    }

    // Where a copy of [original] goes among [notes]: right after it, or for a pinned note (the copy isn't pinned) at the top
    // of the unpinned notes. 0 never comes back (it means "not placed").
    fun copyPosition(original: PlannerNote, notes: Collection<PlannerNote>): Long =
        if (original.pinned) topPosition(notes) else (original.position + 1).let { if (it == 0L) 1L else it }

    // N6-3: the order Duplicate makes copies in. A pinned note's copy goes above every note, so those are made last and
    // last first, and so show in the order of [ids] like the others.
    fun duplicateOrder(ids: List<String>, pinned: (String) -> Boolean): List<String> =
        ids.filterNot(pinned) + ids.filter(pinned).asReversed()

    // N6-4: the notes at [original]'s place that show after it (a restored note keeps its old place, which another can
    // hold by then), in the order they show: a copy put right after it goes before them too.
    fun tiedAfter(original: PlannerNote, notes: Collection<PlannerNote>): List<PlannerNote> =
        notes.filter { it.id != original.id && it.position == original.position && order.compare(original, it) < 0 }.sortedWith(order)

    /** The place for a note going to the top: above every note there is. */
    fun topPosition(notes: Collection<PlannerNote>): Long = (notes.minOfOrNull { it.position } ?: 0L).coerceAtMost(0L) - 1

    /**
     * New places after [shown] (as on the page) was dragged into [newOrder]: the notes shown take each other's places, so
     * notes not shown (another notebook, the archive) keep theirs. Only the notes whose place changes are returned.
     */
    fun reorder(shown: List<PlannerNote>, newOrder: List<String>): Map<String, Long> {
        val byId = shown.associateBy { it.id }
        // The order was taken when the drag began: a note that left the page since (deleted or moved by sync) is skipped,
        // and one that joined keeps its place, so only notes in both trade places.
        val moving = newOrder.distinct().mapNotNull { byId[it] }
        val slots = moving.map { it.position }.sorted()
        // Places must differ for the order to hold: equal ones (never dragged) are spread out first.
        val distinct = if (slots.distinct().size == slots.size) slots else slots.indices.map { slots.first() + it }
        return moving.mapIndexedNotNull { index, note -> if (note.position != distinct[index]) note.id to distinct[index] else null }.toMap()
    }

    /**
     * The order after the note [id] in [order] (as the page shows it) moves one place earlier ([by] = -1) or later (1),
     * for screen readers, which can't drag; null at either end, and pinned and unpinned notes stay apart.
     */
    fun moved(order: List<PlannerNote>, id: String, by: Int): List<String>? {
        val from = order.indexOfFirst { it.id == id }
        val to = from + by
        if (from < 0 || to !in order.indices || order[to].pinned != order[from].pinned) return null
        return order.map { it.id }.toMutableList().apply { add(to, removeAt(from)) }
    }

    /** [order] for [sort]: pinned first, then the sort's own key, then the dragged order. */
    fun order(sort: NoteSort): Comparator<PlannerNote> {
        val pinnedFirst = compareByDescending<PlannerNote> { it.pinned }
        val key: Comparator<PlannerNote>? = when (sort) {
            NoteSort.MY_ORDER -> null
            NoteSort.IMPORTANCE -> compareByDescending { it.priority.rank }
            NoteSort.CHANGED -> compareByDescending { it.modified }
            NoteSort.CREATED -> compareByDescending { it.created }
            // Each note's name worked out once per sort (an untitled note's is read from its text), not per comparison.
            NoteSort.TITLE -> java.util.IdentityHashMap<PlannerNote, String>().let { names -> compareBy { names.getOrPut(it) { Search.normalize(label(it)) } } }
            // The card colours in their order, then custom colours by hue, then plain cards.
            NoteSort.COLOUR -> compareBy { note -> note.color?.let { c -> colors.indexOf(c).takeIf { it >= 0 }?.toFloat() ?: (100f + hue(c)) }
                ?: Float.MAX_VALUE }
        }
        return (key?.let { pinnedFirst.then(it) } ?: pinnedFirst).then(order)
    }

    // An ARGB colour's hue, 0–360 (0 for greys).
    private fun hue(argb: Int): Float {
        val r = (argb shr 16 and 0xFF) / 255f; val g = (argb shr 8 and 0xFF) / 255f; val b = (argb and 0xFF) / 255f
        val max = maxOf(r, g, b); val min = minOf(r, g, b); val d = max - min
        if (d == 0f) return 0f
        val h = when (max) { r -> ((g - b) / d) % 6f; g -> (b - r) / d + 2f; else -> (r - g) / d + 4f } * 60f
        return if (h < 0) h + 360f else h
    }

    /** A note's searched fields, normalised (Search.normalize). Each is matched on its own, so a match never spans two. */
    class SearchFields(val title: String, val content: String, val notebook: String, val tags: List<String>) {
        fun contain(needle: String) = title.contains(needle) || content.contains(needle) || notebook.contains(needle) ||
            tags.any { it.contains(needle) }
    }
    fun searchFields(note: PlannerNote) = SearchFields(Search.normalize(note.title), Search.normalize(note.content),
        Search.normalize(note.notebook), note.tags.map(Search::normalize))
    /** [searchFields] for each of [notes] by id, worked out once per list rather than per letter typed (UI-3). */
    fun searchFields(notes: List<PlannerNote>): Map<String, SearchFields> = notes.associate { it.id to searchFields(it) }

    // [fields]: the notes' searchFields made beforehand; any note missing there is normalised here.
    fun visible(notes: List<PlannerNote>, filter: NoteFilter, query: String, sort: NoteSort = NoteSort.MY_ORDER,
                fields: Map<String, SearchFields> = emptyMap()): List<PlannerNote> {
        val needle = Search.normalize(query.trim())
        return notes.filter { note ->
            when (filter) {
                NoteFilter.All -> !note.archived
                is NoteFilter.Notebook -> !note.archived && note.notebook == filter.name
                is NoteFilter.Tag -> !note.archived && filter.name in note.tags
                NoteFilter.Archive -> note.archived
            } && (needle.isEmpty() || (fields[note.id] ?: searchFields(note)).contain(needle))
        }.sortedWith(order(sort))
    }

    fun notebooks(notes: List<PlannerNote>): List<String> =
        notes.map { it.notebook }.filter { it.isNotBlank() }.distinct().sortedBy { Search.normalize(it) }

    fun tags(notes: List<PlannerNote>): List<String> =
        notes.flatMap { it.tags }.distinct().sortedBy { Search.normalize(it) }

    /** The name a note goes by: its title, else its first line of text, else "Untitled note". */
    fun label(note: PlannerNote): String = label(note) { Markdown.plain(note.content) }
    // [plain]: the note's Markdown.plain text, asked for only when it has no title (a card has it already, UI-4).
    fun label(note: PlannerNote, plain: () -> String): String = note.title.trim().ifBlank {
        plain().lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }?.take(80) ?: ""
    }.ifBlank { "Untitled note" }

    fun clean(note: PlannerNote): PlannerNote = note.copy(
        title = note.title.replace('\n', ' ').trim().take(MAX_TITLE),
        // Windows (CRLF) and old Mac (CR) line ends become plain ones, so lists and checklists read the same.
        content = note.content.replace("\r\n", "\n").replace('\r', '\n').trimEnd().take(MAX_CONTENT),
        notebook = cleanNotebook(note.notebook),
        tags = note.tags.map(::cleanTag).filter { it.isNotEmpty() }.distinct().take(MAX_TAGS),
        // Recognised text beyond a task's limit is cut (marked partly read), as Tasks.capText does.
        attachments = Tasks.capText(PlannerTask(title = "x", attachments = note.attachments)).attachments,
    )

    // Words, a file or a photo: a note the user saves has something in it.
    fun hasContent(note: PlannerNote) = note.title.isNotBlank() || note.content.isNotBlank() || note.attachments.isNotEmpty()

    fun cleanNotebook(name: String) = name.replace('\n', ' ').trim().trim('/').take(MAX_NOTEBOOK)
    fun cleanTag(name: String) = name.replace('\n', ' ').trim().removePrefix("#").trim().take(MAX_TAG)

    /**
     * Existing [names] for what is being typed, capitals ignored: those starting with it first, then those containing it,
     * leaving out [taken]. With nothing typed, all of them.
     */
    fun suggest(names: List<String>, typed: String, taken: Collection<String> = emptyList()): List<String> {
        val t = typed.trim()
        val free = names.filter { name -> taken.none { it.equals(name, ignoreCase = true) } }
        if (t.isEmpty()) return free
        return free.filter { it.startsWith(t, ignoreCase = true) } +
            free.filter { !it.startsWith(t, ignoreCase = true) && it.contains(t, ignoreCase = true) }
    }

    /** [typed] spelt as an existing name when only capitals differ ("home" → "Home"), so no near-duplicate is made. */
    fun existingSpelling(names: List<String>, typed: String): String = names.firstOrNull { it.equals(typed, ignoreCase = true) } ?: typed

    fun validate(note: PlannerNote) {
        require(note.id.isNotBlank() && note.id.length <= 100) { "A note needs an id" }
        require(note.title.length <= MAX_TITLE && note.content.length <= MAX_CONTENT) { "This note is too long" }
        require(note.notebook.length <= MAX_NOTEBOOK && '\n' !in note.notebook) { "Invalid notebook name" }
        require(note.tags.size <= MAX_TAGS && note.tags.all { it.isNotBlank() && it.length <= MAX_TAG }) { "Invalid tags" }
        // As a task's: stored files only, with plain names (a backup can't point outside the attachment store).
        require(note.attachments.size <= 100 && note.attachments.map { it.fileName }.distinct().size == note.attachments.size) { "Invalid attachments" }
        require(note.attachments.all { it.url == null && Regex("[A-Za-z0-9][A-Za-z0-9._-]*").matches(it.fileName) && it.name.isNotBlank() &&
            it.name.length <= 500 && it.mimeType.length <= 200 }) { "Invalid attachments" }
        // As a task's: recognised text stays well inside a database row's 2 MB window.
        require(note.attachments.sumOf { it.recognizedText.length } <= Tasks.MAX_TEXT) { "Invalid attachments" }
        require(note.reminderAt == null || note.reminderAt in 1..253402300799999L) { "Invalid reminder" }
        require(note.snoozedUntil == null || note.snoozedUntil in 1..253402300799999L) { "Invalid reminder" }
        // An empty note is only refused where one is written (saveNote): a backup or Recently deleted entry whose only
        // attachment file has gone stays readable rather than failing as a whole.
    }
}

/** Saving a note found it changed since the editor read it; [latest] is how it is now. */
class NoteChangedException(val latest: PlannerNote) : IllegalStateException("This note changed elsewhere")

/**
 * Three-way merge of a note: [mine] and [theirs] both started from [base]. Each field takes whichever side changed it;
 * null when both changed the same field differently (the user decides). The snooze and the place follow theirs (never edited here).
 */
fun mergeNotes(base: PlannerNote, mine: PlannerNote, theirs: PlannerNote): PlannerNote? {
    fun <T> pick(get: (PlannerNote) -> T): Pair<T, Boolean> {
        val b = get(base); val m = get(mine); val t = get(theirs)
        return when { m == b -> t to true; t == b || m == t -> m to true; else -> m to false }
    }
    val title = pick { it.title }; val content = pick { it.content }; val notebook = pick { it.notebook }
    val color = pick { it.color }; val pinned = pick { it.pinned }; val tags = pick { it.tags }
    val attachments = pick { it.attachments }; val reminder = pick { it.reminderAt }; val archived = pick { it.archived }
    val priority = pick { it.priority }
    if (!listOf(title, content, notebook, color, pinned, tags, attachments, reminder, archived, priority).all { it.second }) return null
    return theirs.copy(title = title.first, content = content.first, notebook = notebook.first, color = color.first,
        pinned = pinned.first, tags = tags.first, attachments = attachments.first, reminderAt = reminder.first, archived = archived.first,
        priority = priority.first)
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
            .put("snoozedUntil", note.snoozedUntil ?: JSONObject.NULL)
            .put("position", note.position).put("priority", note.priority.name)) }
    }

    fun decode(array: JSONArray): List<PlannerNote> = decodeLenient(array).onEach(Notes::validate)
        .also { notes -> require(notes.map { it.id }.distinct().size == notes.size) }

    // As read, cleaned but not checked: a draft may be empty or half done.
    fun decodeLenient(array: JSONArray): List<PlannerNote> = List(array.length()) { index ->
        val value = array.getJSONObject(index)
        fun time(name: String): Long? = if (!value.has(name) || value.isNull(name)) null else value.strictLong(name) { "Invalid note time" }
        val created = time("created") ?: 0L
        PlannerNote(value.getString("id"), value.optString("title"), value.optString("content"), value.optString("notebook"),
            if (!value.has("color") || value.isNull("color")) null else value.getInt("color"),
            value.optBoolean("pinned"), value.optBoolean("archived"), created, time("modified") ?: created,
            StringListCodec.decode(value.optJSONArray("tags") ?: JSONArray()),
            DraftCodec.attachments(value.optJSONArray("attachments")), time("reminderAt"), time("snoozedUntil"),
            // Older files have no place: most recently changed first, as the page sorted them then.
            time("position") ?: -(time("modified") ?: created),
            runCatching { TaskPriority.valueOf(value.optString("priority", "NORMAL")) }.getOrDefault(TaskPriority.NORMAL))
            // A note saved by a later version with longer text is cut rather than refusing the whole file.
            .let(Notes::clean)
    }
}
