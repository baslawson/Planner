package com.example.itinerary.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** What was last synced for one note: its Nextcloud id and version, and the note's fields as they were then (Planner's side). */
@Entity(tableName = "sent_notes")
data class SentNote(
    @PrimaryKey val noteId: String,
    val account: String,
    val remoteId: Long,
    val etag: String?,
    val title: String,
    val content: String,
    val notebook: String,
    val pinned: Boolean,
)

@Dao
interface SentNoteDao {
    @Query("SELECT * FROM sent_notes") suspend fun all(): List<SentNote>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(row: SentNote)
    @Query("DELETE FROM sent_notes WHERE noteId = :noteId") suspend fun delete(noteId: String)
    @Query("DELETE FROM sent_notes") suspend fun deleteAll()
}

/** A note as the Nextcloud Notes app has it. [modified] is in seconds, as the API gives it. */
data class RemoteNote(val id: Long, val etag: String?, val title: String, val content: String, val category: String,
                      val favorite: Boolean, val modified: Long = 0, val readonly: Boolean = false)

class NotesApiException(message: String, val code: Int = 0) : Exception(message)

/** The Nextcloud Notes app's API, version 1 (/index.php/apps/notes/api/v1/notes), with the backup login. */
class NotesApi(private val http: OkHttpClient) {
    private val json = "application/json; charset=utf-8".toMediaType()

    private fun notes(account: NextcloudAccount, id: Long? = null): HttpUrl = account.server.newBuilder()
        .addPathSegments("index.php/apps/notes/api/v1/notes").apply { if (id != null) addPathSegment(id.toString()) }.build()

    private fun call(account: NextcloudAccount, request: Request.Builder): Pair<Int, String> =
        http.newCall(request.header("Authorization", Credentials.basic(account.username, account.password, Charsets.UTF_8))
            .header("Accept", "application/json").header("OCS-APIRequest", "true").build()).execute().use { response ->
            response.code to (response.body?.string().orEmpty())
        }

    private fun fail(code: Int): Nothing = throw NotesApiException(when (code) {
        401, 403 -> "Nextcloud didn't accept the login. Check it in Settings → Nextcloud."
        404 -> "The Notes app isn't enabled on your Nextcloud. Ask its administrator to enable Notes, then try again."
        in 500..599 -> "Nextcloud had a problem ($code). Try again later."
        else -> "Nextcloud refused the request ($code)."
    }, code)

    fun list(account: NextcloudAccount): List<RemoteNote> {
        val (code, body) = call(account, Request.Builder().url(notes(account)).get())
        if (code != 200) fail(code)
        val array = runCatching { JSONArray(body) }.getOrElse { throw NotesApiException("Nextcloud's reply wasn't a list of notes. Is the Notes app enabled?") }
        return List(array.length()) { parse(array.getJSONObject(it)) }
    }

    sealed interface Write {
        data class Done(val note: RemoteNote) : Write
        // Changed on Nextcloud since it was read (412), or gone (404).
        data object Changed : Write
        data object Gone : Write
    }

    fun create(account: NextcloudAccount, title: String, content: String, category: String, favorite: Boolean): RemoteNote {
        val (code, body) = call(account, Request.Builder().url(notes(account)).post(payload(title, content, category, favorite)))
        if (code != 200) fail(code)
        return parse(JSONObject(body))
    }

    fun update(account: NextcloudAccount, id: Long, etag: String?, title: String, content: String, category: String, favorite: Boolean): Write {
        val (code, body) = call(account, Request.Builder().url(notes(account, id)).put(payload(title, content, category, favorite))
            .apply { if (etag != null) header("If-Match", "\"$etag\"") })
        return when (code) {
            200 -> Write.Done(parse(JSONObject(body)))
            412 -> Write.Changed
            404 -> Write.Gone
            else -> fail(code)
        }
    }

    // True once it's gone (also when it already was).
    fun delete(account: NextcloudAccount, id: Long): Boolean {
        val (code, _) = call(account, Request.Builder().url(notes(account, id)).delete())
        if (code != 200 && code != 404) fail(code)
        return true
    }

    private fun payload(title: String, content: String, category: String, favorite: Boolean) = JSONObject()
        .put("title", title).put("content", content).put("category", category).put("favorite", favorite).toString().toRequestBody(json)

    private fun parse(o: JSONObject) = RemoteNote(o.getLong("id"), o.optString("etag").ifBlank { null }, o.optString("title"),
        o.optString("content"), o.optString("category"), o.optBoolean("favorite"), o.optLong("modified"), o.optBoolean("readonly"))
}

/** How Planner's note and Nextcloud's map onto each other. */
object NoteMapping {
    // The first line of text, which the Notes app uses as a title when there is none.
    fun firstLine(content: String) = Markdown.plain(content).lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()

    // Planner's title, or (none set) the note's first line: Nextcloud names its file after it.
    fun remoteTitle(note: PlannerNote) = note.title.trim().ifBlank { firstLine(note.content) }.take(Notes.MAX_TITLE)

    // Nextcloud's title is kept unless it's just the first line again (the card would show it twice), or it's Planner's
    // own title as Nextcloud tidied it (no / \\ : * ? " < > |, cut at 100, " (2)" added): Planner's stays then.
    fun localTitle(remote: RemoteNote, mine: String = "") = when {
        mine.isNotBlank() && sameTitle(mine, remote.title) -> mine
        remote.title.trim() == firstLine(remote.content) -> ""
        else -> remote.title.trim()
    }

    private fun titleKey(title: String) = title.trim().replace(Regex("\\s\\(\\d+\\)$"), "").take(100).lowercase()
        .filter { it.isLetterOrDigit() }
    fun sameTitle(a: String, b: String) = titleKey(a) == titleKey(b)

    // The same words, whatever the line ends and trailing space.
    fun sameText(a: String, b: String) = a.replace("\r\n", "\n").trimEnd() == b.replace("\r\n", "\n").trimEnd()

    fun fields(note: PlannerNote) = listOf(note.title, note.content, note.notebook, note.pinned.toString())
    fun fields(row: SentNote) = listOf(row.title, row.content, row.notebook, row.pinned.toString())

    /** [base] (a new note, or the linked one) changed to what Nextcloud has. */
    fun apply(base: PlannerNote, remote: RemoteNote) = Notes.clean(base.copy(title = localTitle(remote, base.title), content = remote.content,
        notebook = Notes.cleanNotebook(remote.category), pinned = remote.favorite,
        modified = if (remote.modified > 0) remote.modified * 1000 else base.modified))

    // Too long for Planner: such a note stays on Nextcloud only, so it's never cut short there.
    fun fits(remote: RemoteNote) = remote.content.length <= Notes.MAX_CONTENT && remote.title.length <= Notes.MAX_TITLE

    fun row(note: PlannerNote, account: String, remote: RemoteNote) =
        SentNote(note.id, account, remote.id, remote.etag, note.title, note.content, note.notebook, note.pinned)
}

/** What note sync needs from Planner's notes (the app passes its Repository; tests may too). */
interface NoteStore {
    suspend fun all(): List<PlannerNote>
    // Put [note] in place exactly as given, if the note is still as [expected] (null: not there).
    suspend fun put(note: PlannerNote, expected: PlannerNote?): Boolean
    // To Recently deleted without the Undo bar (deleted on Nextcloud), if it's still as [expected].
    suspend fun archive(id: String, expected: PlannerNote): Boolean
}

fun Repository.asNoteStore(): NoteStore = object : NoteStore {
    override suspend fun all() = allNotes()
    override suspend fun put(note: PlannerNote, expected: PlannerNote?) = putSyncedNote(note, expected)
    override suspend fun archive(id: String, expected: PlannerNote) = archiveSyncedNote(id, expected)
}

/**
 * Two-way sync of Planner's notes with the Nextcloud Notes app, on the backup login. A pass reads every note there,
 * then for each linked pair compares both sides with what was last synced: one side changed → the other follows; both →
 * Nextcloud's version wins in the note and Planner's is kept as a "(conflict copy)" (sent up as a new note); deleted on
 * one side and unchanged on the other → deleted there too (Planner's copy to Recently deleted); changed on the other →
 * it comes back. Then Nextcloud's new notes come in (linked to an identical one here first), and Planner's new notes go
 * up. Every write to Nextcloud is conditional (If-Match), so nothing changed there since it was read is overwritten.
 * Colour, tags, attachments, reminders and Archive stay on the phone.
 */
class NoteSync(
    private val db: AppDatabase,
    private val accounts: NextcloudAccountStore,
    internal var api: NotesApi,
    private val store: NoteStore,
    private val prefs: android.content.SharedPreferences,
    // Notes deleted in Planner whose Undo is still on offer: their Nextcloud copies wait until it has passed.
    private val pendingDeleted: () -> Set<String> = { emptySet() },
    private val scope: CoroutineScope? = null,
) {
    // [skipped]: notes the last pass left alone (too long for Planner, or refused by Nextcloud).
    data class State(val running: Boolean = false, val error: String? = null, val lastSynced: Long? = null, val conflicts: Int = 0,
                     val skipped: Int = 0)

    private val rows = db.sentNoteDao()
    private val lock = Mutex()
    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, false))
    val enabled = _enabled.asStateFlow()
    private val _state = MutableStateFlow(State(lastSynced = prefs.getLong(KEY_LAST, 0L).takeIf { it > 0 }))
    val state = _state.asStateFlow()
    // A request waiting out its delay (cancelled by a newer one); a pass itself is never cancelled by a request.
    private var waiting: Job? = null
    // Asked for while a pass ran: one more follow if anything is left to sync.
    @Volatile private var again = false
    // What was still out of step after the last follow-up pass, so a note that can't sync doesn't start a loop.
    private var stuck: Set<String> = emptySet()

    // Turning it on syncs at once; off leaves the notes as they are on both sides, and forgets the links.
    suspend fun setEnabled(on: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, on).apply()
        _enabled.value = on
        if (!on) lock.withLock { rows.deleteAll(); _state.value = State() }
    }

    // After a backup is restored (its notes have new history) or the login changes: the next pass links by content.
    suspend fun forget() = lock.withLock { rows.deleteAll() }

    // A pass a few seconds from now, once changes have settled (after an edit), or at once. A pass already running
    // carries on (a pass's own writes come back here through the repository); one more follows it if needed.
    fun request(delayMs: Long = SEND_DELAY_MS) {
        val scope = scope ?: return
        if (!_enabled.value) return
        synchronized(this) {
            if (lock.isLocked) { again = true; return }
            waiting?.cancel()
            waiting = scope.launch {
                delay(delayMs)
                synchronized(this@NoteSync) { waiting = null }
                runCatching { sync() }
                if (again) {
                    again = false
                    val left = runCatching { outOfStep() }.getOrDefault(emptySet())
                    if (left.isNotEmpty() && left != stuck) { stuck = left; request() } else stuck = emptySet()
                }
            }
        }
    }

    // Planner's notes not as last synced: new, changed, or deleted with the Undo gone.
    private suspend fun outOfStep(): Set<String> {
        val links = rows.all().associateBy { it.noteId }
        val pending = pendingDeleted()
        val local = store.all()
        return local.filter { it.id !in pending && links[it.id]?.let { row -> NoteMapping.fields(it) != NoteMapping.fields(row) } ?: true }
            .mapTo(HashSet()) { it.id } + (links.keys - local.mapTo(HashSet()) { it.id } - pending)
    }

    suspend fun sync(): Boolean = lock.withLock {
        if (!_enabled.value) return@withLock true
        _state.value = _state.value.copy(running = true, error = null)
        // Not cut off half way: a write to Nextcloud and the record of it go together.
        withContext(NonCancellable) {
            try {
                val account = withContext(Dispatchers.IO) { runCatching { accounts.load() }.getOrNull() }
                    ?: throw NotesApiException("Sign in to Nextcloud first, in Settings → Nextcloud.")
                val (conflicts, skipped) = withContext(Dispatchers.IO) { pass(account) }
                val now = System.currentTimeMillis()
                prefs.edit().putLong(KEY_LAST, now).apply()
                _state.value = State(lastSynced = now, conflicts = conflicts, skipped = skipped)
                true
            } catch (e: Exception) {
                _state.value = _state.value.copy(running = false, error = (e as? NotesApiException)?.message
                    ?: if (e is java.io.IOException) "Couldn't reach Nextcloud. Check the connection and try again." else "Notes sync failed. Try again later.")
                false
            }
        }
    }

    // One pass; returns how many conflict copies it made and how many notes it left alone.
    private suspend fun pass(account: NextcloudAccount): Pair<Int, Int> {
        val key = CalendarSync.accountKey(account)
        val remote = api.list(account).associateBy { it.id }
        val pending = pendingDeleted()
        var conflicts = 0
        var skipped = 0
        // Links made for another login don't count here.
        rows.all().filter { it.account != key }.forEach { rows.delete(it.noteId) }
        val linked = rows.all()
        var local = store.all().associateBy { it.id }
        val linkedRemote = linked.mapTo(HashSet()) { it.remoteId }

        suspend fun push(note: PlannerNote) {
            val made = api.create(account, NoteMapping.remoteTitle(note), note.content, note.notebook, note.pinned)
            rows.put(NoteMapping.row(note, key, made))
        }
        suspend fun conflictCopy(mine: PlannerNote) {
            val copy = mine.copy(id = java.util.UUID.randomUUID().toString(),
                title = (Notes.label(mine) + " (conflict copy)").take(Notes.MAX_TITLE), reminderAt = null, snoozedUntil = null)
            if (store.put(copy, null)) conflicts++
        }
        // One note Nextcloud refuses (a shared note it won't let Planner change, say) is left as it is; the rest go on.
        // A login or connection problem still stops the pass.
        suspend fun each(block: suspend () -> Unit) {
            try { block() } catch (e: NotesApiException) { if (e.code in 400..499 && e.code != 401) skipped++ else throw e }
        }

        for (row in linked) each {
            val mine = local[row.noteId]
            val theirs = remote[row.remoteId]
            when {
                // Deleted in Planner (its Undo has passed): deleted there too, unless it changed there since.
                mine == null -> {
                    if (row.noteId in pending) return@each
                    if (theirs == null) { rows.delete(row.noteId); return@each }
                    if (theirs.etag == row.etag && !theirs.readonly) { api.delete(account, theirs.id); rows.delete(row.noteId) }
                    else {
                        // Changed there, or shared read-only: kept there, and (changed) it comes back below as a new note.
                        rows.delete(row.noteId)
                        if (theirs.etag != row.etag) linkedRemote.remove(theirs.id)
                    }
                }
                // Deleted on Nextcloud: to Recently deleted, unless changed here since (then it goes up again).
                theirs == null -> {
                    rows.delete(row.noteId)
                    if (NoteMapping.fields(mine) == NoteMapping.fields(row)) store.archive(mine.id, mine) else push(mine)
                }
                !NoteMapping.fits(theirs) -> skipped++
                else -> {
                    val mineChanged = NoteMapping.fields(mine) != NoteMapping.fields(row)
                    val theirsChanged = theirs.etag != row.etag
                    // Only filed or pinned differently here: a read-only note keeps that on the phone, nothing to send.
                    val metaOnly = mine.title == row.title && mine.content == row.content
                    when {
                        !mineChanged && !theirsChanged -> {}
                        !mineChanged -> {
                            val updated = NoteMapping.apply(mine, theirs)
                            if (store.put(updated, mine)) rows.put(NoteMapping.row(updated, key, theirs))
                        }
                        theirs.readonly && metaOnly -> {
                            val updated = if (theirsChanged) NoteMapping.apply(mine, theirs).copy(notebook = mine.notebook, pinned = mine.pinned) else mine
                            if (updated == mine || store.put(updated, mine)) rows.put(NoteMapping.row(updated, key, theirs))
                        }
                        !theirsChanged && !theirs.readonly -> when (val result = api.update(account, theirs.id, theirs.etag,
                            NoteMapping.remoteTitle(mine), mine.content, mine.notebook, mine.pinned)) {
                            is NotesApi.Write.Done -> rows.put(NoteMapping.row(mine, key, result.note))
                            // Changed there meanwhile: settled on the next pass, with its new version.
                            NotesApi.Write.Changed -> {}
                            NotesApi.Write.Gone -> { rows.delete(row.noteId); push(mine) }
                        }
                        else -> {
                            // Both changed (or it's read-only there): Nextcloud's version here, then Planner's as a copy
                            // (only once the note itself is updated, so a failed update doesn't make a second copy).
                            val updated = NoteMapping.apply(mine, theirs)
                            if (store.put(updated, mine)) {
                                rows.put(NoteMapping.row(updated, key, theirs))
                                if (NoteMapping.fields(updated) != NoteMapping.fields(mine)) conflictCopy(mine)
                            }
                        }
                    }
                }
            }
        }

        // Nextcloud's notes not linked yet: to one here with the same words (a restored backup, the same note added
        // twice; Nextcloud may have tidied its title), or new.
        local = store.all().associateBy { it.id }
        val links = rows.all().associateBy { it.noteId }
        val free = local.values.filter { it.id !in links && it.id !in pending }.toMutableList()
        for (theirs in remote.values.filter { it.id !in linkedRemote }) each {
            if (!NoteMapping.fits(theirs)) { skipped++; return@each }
            val incoming = NoteMapping.apply(PlannerNote(), theirs)
            val sameWords = free.filter { NoteMapping.sameText(it.content, incoming.content) }
            val twin = sameWords.firstOrNull { NoteMapping.sameTitle(NoteMapping.remoteTitle(it), theirs.title) } ?: sameWords.firstOrNull()
            if (twin != null) {
                // Linked, with Nextcloud's notebook and favourite (Planner's title, colour, tags and so on stay).
                free.remove(twin)
                val linkedNote = twin.copy(notebook = incoming.notebook, pinned = incoming.pinned)
                if (linkedNote == twin || store.put(linkedNote, twin)) rows.put(NoteMapping.row(linkedNote, key, theirs))
            } else if (store.put(incoming, null)) rows.put(NoteMapping.row(incoming, key, theirs))
        }

        // Planner's notes not on Nextcloud yet go up.
        for (mine in free) each { push(mine) }
        return conflicts to skipped
    }

    companion object {
        const val SEND_DELAY_MS = 3_000L
        private const val KEY_ENABLED = "enabled"
        private const val KEY_LAST = "last_synced"
        fun prefs(context: Context): android.content.SharedPreferences = context.getSharedPreferences("note_sync", Context.MODE_PRIVATE)
    }
}
