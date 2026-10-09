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
class NotesApi(client: OkHttpClient) {
    // Bug hunt 19, P7: as NextcloudClient. A note POST isn't sent twice after a dropped reply (two copies on the server),
    // and a redirect (to http:// too) isn't followed with the note's words.
    private val http = client.newBuilder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
    private val json = "application/json; charset=utf-8".toMediaType()

    private fun notes(account: NextcloudAccount, id: Long? = null): HttpUrl = account.server.newBuilder()
        .addPathSegments("index.php/apps/notes/api/v1/notes").apply { if (id != null) addPathSegment(id.toString()) }.build()

    private fun authorised(account: NextcloudAccount, request: Request.Builder): Request =
        request.header("Authorization", Credentials.basic(account.username, account.password, Charsets.UTF_8))
            .header("Accept", "application/json").header("OCS-APIRequest", "true").build()

    private fun call(account: NextcloudAccount, request: Request.Builder): Pair<Int, String> =
        http.newCall(authorised(account, request)).execute().use { response -> response.code to (response.body?.string().orEmpty()) }

    private fun fail(code: Int): Nothing = throw NotesApiException(when (code) {
        401, 403 -> "Nextcloud didn't accept the login. Check it in Settings → Nextcloud."
        404 -> "The Notes app isn't enabled on your Nextcloud. Ask its administrator to enable Notes, then try again."
        in 500..599 -> "Nextcloud had a problem ($code). Try again later."
        else -> "Nextcloud refused the request ($code)."
    }, code)

    fun list(account: NextcloudAccount): List<RemoteNote> = listTagged(account).first

    // The notes and the list's ETag (null if the server sends none), for [unchanged].
    fun listTagged(account: NextcloudAccount): Pair<List<RemoteNote>, String?> {
        val (code, body, tag) = http.newCall(authorised(account, Request.Builder().url(notes(account)).get())).execute().use { response ->
            Triple(response.code, response.body?.string().orEmpty(), response.header("ETag"))
        }
        if (code != 200) fail(code)
        val array = runCatching { JSONArray(body) }.getOrElse { throw NotesApiException("Nextcloud's reply wasn't a list of notes. Is the Notes app enabled?") }
        return List(array.length()) { parse(array.getJSONObject(it)) } to tag
    }

    // Whether the list is still the one with ETag [tag]: one request and, when nothing changed, no notes in the reply (304).
    // True: unchanged; false: changed (200); null: no answer (an error a full pass will report).
    fun unchanged(account: NextcloudAccount, tag: String): Boolean? =
        http.newCall(authorised(account, Request.Builder().url(notes(account)).get().header("If-None-Match", tag))).execute().use {
            when (it.code) { 304 -> true; 200 -> false; else -> null }
        }

    sealed interface Write {
        data class Done(val note: RemoteNote) : Write
        // Changed on Nextcloud since it was read (412), or gone (404).
        data object Changed : Write
        data object Gone : Write
    }

    // [modified]: the note's edit time in seconds, as Quillpad sends it: Nextcloud sets the file's time to it, so both sides
    // then have the same time and the next sync leaves the note alone (0: none, Nextcloud uses now).
    fun create(account: NextcloudAccount, title: String, content: String, category: String, favorite: Boolean, modified: Long = 0): RemoteNote {
        val (code, body) = call(account, Request.Builder().url(notes(account)).post(payload(title, content, category, favorite, modified)))
        if (code != 200) fail(code)
        return parse(JSONObject(body))
    }

    fun update(account: NextcloudAccount, id: Long, etag: String?, title: String, content: String, category: String, favorite: Boolean,
               modified: Long = 0): Write {
        val (code, body) = call(account, Request.Builder().url(notes(account, id)).put(payload(title, content, category, favorite, modified))
            .apply { if (etag != null) header("If-Match", "\"$etag\"") })
        return when (code) {
            200 -> Write.Done(parse(JSONObject(body)))
            412 -> Write.Changed
            404 -> Write.Gone
            else -> fail(code)
        }
    }

    /** Deletes note [id] there, as Nextcloud Notes' own app does (no condition); already gone is fine. */
    fun remove(account: NextcloudAccount, id: Long) {
        val (code, _) = call(account, Request.Builder().url(notes(account, id)).delete())
        if (code != 200 && code != 204 && code != 404) fail(code)
    }

    private fun payload(title: String, content: String, category: String, favorite: Boolean, modified: Long) = JSONObject()
        .put("title", title).put("content", content).put("category", category).put("favorite", favorite)
        .apply { if (modified > 0) put("modified", modified) }.toString().toRequestBody(json)

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
    // N16-3: the first line is compared the same tolerant way, as Nextcloud tidies it too ("- [ ] milk" → "[ ] milk").
    fun localTitle(remote: RemoteNote, mine: String = "") = when {
        mine.isNotBlank() && sameTitle(mine, remote.title) -> mine
        remote.title.trim() == firstLine(remote.content) -> ""
        titleKey(remote.title).isNotEmpty() && sameTitle(remote.title, firstLine(remote.content)) -> ""
        else -> remote.title.trim()
    }

    private fun titleKey(title: String) = title.trim().replace(Regex("\\s\\(\\d+\\)$"), "").take(100).lowercase()
        .filter { it.isLetterOrDigit() }
    fun sameTitle(a: String, b: String) = titleKey(a) == titleKey(b)

    // The same words, whatever the line ends and trailing space.
    fun sameText(a: String, b: String) = a.replace("\r\n", "\n").trimEnd() == b.replace("\r\n", "\n").trimEnd()

    // Planner's unlinked note that is [remote] already: the same words and title (as Nextcloud tidied it), else the same
    // words where one side's title is only the first line (or none). H17-S1: never by words alone when the titles
    // clearly differ, or the text is blank (every title-only note would match). [content] is Nextcloud's text as
    // Planner keeps it (Notes.clean).
    fun twin(free: List<PlannerNote>, remote: RemoteNote,
             content: String = Notes.clean(PlannerNote(content = remote.content)).content): PlannerNote? {
        // Titles with no letters or digits (emoji only) have no tolerant key, so they must match exactly.
        fun same(a: String, b: String) = a.trim() == b.trim() || titleKey(a).isNotEmpty() && sameTitle(a, b)
        val sameWords = free.filter { sameText(it.content, content) }
        sameWords.firstOrNull { same(remoteTitle(it), remote.title) }?.let { return it }
        if (content.isBlank()) return null
        val theirsDerived = remote.title.isBlank() || same(remote.title, firstLine(content))
        return sameWords.firstOrNull { theirsDerived || it.title.isBlank() }
    }

    fun fields(note: PlannerNote) = listOf(note.title, note.content, note.notebook, note.pinned.toString())
    fun fields(row: SentNote) = listOf(row.title, row.content, row.notebook, row.pinned.toString())

    /** [base] (a new note, or the linked one) changed to what Nextcloud has. */
    fun apply(base: PlannerNote, remote: RemoteNote) = Notes.clean(base.copy(title = localTitle(remote, base.title), content = remote.content,
        notebook = Notes.cleanNotebook(remote.category), pinned = remote.favorite,
        modified = if (remote.modified > 0) remote.modified * 1000 else base.modified))

    // Leave notes that cannot map losslessly untouched, including notebooks that cleaning would shorten or rename.
    fun fits(remote: RemoteNote) = remote.content.length <= Notes.MAX_CONTENT && remote.title.length <= Notes.MAX_TITLE &&
        Notes.cleanNotebook(remote.category) == remote.category

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
    // Sync's writes, not changes made in Planner (SyncWrite).
    override suspend fun put(note: PlannerNote, expected: PlannerNote?) = SyncWrite.of { putSyncedNote(note, expected) }
    override suspend fun archive(id: String, expected: PlannerNote) = SyncWrite.of { archiveSyncedNote(id, expected) }
}

/**
 * Two-way sync of Planner's notes with the Nextcloud Notes app, on the backup login, with Quillpad's logic (user, 9 Oct:
 * "learn how quillpad notes sync to nextcloud ... and then just use the same logic"):
 * - An edit goes up on its own, half a second after it ([request] → [sendEdits]): that note only, with If-Match (the
 *   version this phone last saw there) and its edit time, which Nextcloud gives the file, so both sides have the same time.
 * - A sync ([pass]) decides each linked pair by edit time alone: Planner's newer → sent; Nextcloud's newer → taken; within
 *   a second → left as it is. No merges and no conflict copies.
 * - Deleted on one side → deleted on the other (here: to Recently deleted, also when changed here since).
 * - Linking ([SentNote]): with no links yet (a first sync), notes are paired by title; after that a note not linked is
 *   new, and is made on the other side. Links made by the versions before this logic are cleared once ([KEY_RELINKED]),
 *   so a note linked by content to a duplicate there is paired again by its title.
 * Colour, tags, attachments, reminders and Archive stay on the phone. A shared note Nextcloud has read-only: Planner's
 * newer words become a note of its own, "(my copy)".
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
    // [ownCopies]: shared read-only notes edited here, kept as "(my copy)"; [skipped]: notes the last pass left alone (too
    // long for Planner, or refused by Nextcloud); [keptRemote]: notes deleted here that Nextcloud wouldn't delete there.
    data class State(val running: Boolean = false, val error: String? = null, val lastSynced: Long? = null, val ownCopies: Int = 0,
                     val skipped: Int = 0, val keptRemote: Int = 0)

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
    // What was still out of step after the last follow-up pass, so a note that can't sync doesn't start a loop. S5-3: with
    // each note's fields as they were then, so one edited again since is not taken for stuck.
    @Volatile private var stuck: Map<String, List<String>?> = emptyMap()
    // SY-2: a check's full pass that leaves the same notes out of step (refused, too long), fails the same way, or can't
    // be asked about cheaply (no list ETag) waits longer each time. [checked]: what was out of step after the last one.
    private val backoff = SyncBackoff()
    @Volatile private var checked: Map<String, List<String>?>? = null

    // Turning it on syncs at once; off leaves the notes as they are on both sides, and forgets the links.
    suspend fun setEnabled(on: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, on).apply()
        _enabled.value = on
        if (!on) lock.withLock { rows.deleteAll(); _state.value = State() }
    }

    // After a backup is restored (its notes have new history) or the login changes: the next pass links by content.
    suspend fun forget() = lock.withLock { forgetLocked() }

    // SY-1 (as TaskSync.paused): runs [block] with no pass under way, and none starting until it's done. A pass works from
    // the notes and links it read at its start and writes links back by note id: one running across a restore would put
    // links naming the old notes over the restored ones (the next pass then overwrites Nextcloud's newer copy, or deletes
    // a note there that the restore didn't bring back). Inside, use forgetLocked only (the lock isn't reentrant).
    // R5-4: a pass asked for meanwhile (the restore's own notes, while automatic sync is on) only noted it (again), and no
    // pass was running to follow it up: it is asked for once the lock is free, and nothing is left noted for later.
    suspend fun <T> paused(block: suspend () -> T): T {
        try { return lock.withLock { block() } }
        finally {
            val asked = synchronized(this) { again.also { again = false } }
            if (asked) request()
        }
    }

    // forget, inside paused: no links, and nothing remembered about the last list or passes.
    suspend fun forgetLocked() {
        rows.deleteAll()
        listTag = null; stuck = emptyMap(); checked = null; backoff.reset()
    }

    // After an edit (as Quillpad): half a second later, once typing has stopped, the edited notes go up on their own
    // ([sendEdits]); what that can't do alone (a new or deleted note, one changed there meanwhile) is left to a pass. A pass
    // already running carries on (its own writes come back here through the repository); one more follows it if needed.
    fun request(delayMs: Long = SEND_DELAY_MS) {
        val scope = scope ?: return
        if (!_enabled.value) return
        synchronized(this) {
            if (lock.isLocked) { again = true; return }
            waiting?.cancel()
            waiting = scope.launch {
                delay(delayMs)
                synchronized(this@NoteSync) { waiting = null }
                // An edit made while this one was being sent was only noted (again): its turn comes after (followUp).
                runCatching { if (!sendEdits()) sync() else followUp() }
            }
        }
    }

    /**
     * Quillpad's per-note update: each linked note edited here since it was last synced is sent, with If-Match (the version
     * this phone last saw there) and its edit time. False when a pass is needed instead: a note new or deleted here, one
     * changed there meanwhile (412) or gone there, a refusal (a shared read-only note), or a failure.
     */
    suspend fun sendEdits(): Boolean = lock.withLock {
        if (!_enabled.value) return@withLock true
        withContext(NonCancellable) {
            try {
                val account = withContext(Dispatchers.IO) { runCatching { accounts.load() }.getOrNull() } ?: return@withContext false
                val key = CalendarSync.accountKey(account)
                val links = rows.all().filter { it.account == key }.associateBy { it.noteId }
                if (links.isEmpty() || !prefs.getBoolean(KEY_RELINKED, false)) return@withContext false
                val pending = pendingDeleted()
                val local = store.all()
                if (local.any { it.id !in links && it.id !in pending } || links.keys.any { id -> id !in pending && local.none { it.id == id } }) return@withContext false
                var all = true
                for (mine in local) {
                    val row = links[mine.id] ?: continue
                    if (NoteMapping.fields(mine) == NoteMapping.fields(row)) continue
                    val result = withContext(Dispatchers.IO) {
                        try { api.update(account, row.remoteId, row.etag, NoteMapping.remoteTitle(mine), mine.content, mine.notebook, mine.pinned, mine.modified / 1000) }
                        catch (e: NotesApiException) { if (e.code in 400..499 && e.code != 401) null else throw e }
                    }
                    if (result is NotesApi.Write.Done) rows.put(NoteMapping.row(mine, key, result.note)) else all = false
                }
                if (all) {
                    val now = System.currentTimeMillis()
                    prefs.edit().putLong(KEY_LAST, now).apply()
                    _state.value = _state.value.copy(running = false, error = null, lastSynced = now)
                }
                all
            } catch (e: Exception) { false }
        }
    }

    // SY-3: asked for while a pass ran (whoever started it): one more follows, if something is still out of step and it
    // isn't what was left after the last follow-up (a note that can't sync doesn't start a loop). S5-3: the same notes
    // edited again since are not what was left: they get their pass.
    private suspend fun followUp() {
        if (!again) return
        again = false
        val left = runCatching { outOfStep() }.getOrDefault(emptyMap())
        if (left.isNotEmpty() && left != stuck) { stuck = left; request() } else stuck = emptyMap()
    }

    // The list's ETag as the last pass read it, with the login it was read with: lets check() ask "changed?" cheaply.
    @Volatile private var listTag: Pair<String, String>? = null

    // A check of AutoSync's: a pass only when a note changed here, or Nextcloud's list is no longer the one last read.
    // SY-2: a pass only for what the last check's pass already left (the same notes out of step, a failure, no ETag to
    // ask with) waits, longer each time (see SyncBackoff); a change on either side passes at once. [fresh]: the
    // connection is back, so no waiting.
    suspend fun check(fresh: Boolean = false) {
        if (!_enabled.value || lock.isLocked) return
        if (fresh) backoff.reset()
        val account = withContext(Dispatchers.IO) { runCatching { accounts.load() }.getOrNull() } ?: return
        val tag = listTag?.takeIf { it.first == CalendarSync.accountKey(account) }
        val left = outOfStep()
        // Only what the last check's pass left (S5-3: the same notes, unchanged since), and not due yet: a pass only if
        // the list changed there.
        val waiting = left == checked && !backoff.due(System.currentTimeMillis())
        if (left.isEmpty() || waiting) {
            val unchanged = tag?.let { withContext(Dispatchers.IO) { runCatching { api.unchanged(account, it.second) }.getOrNull() } }
            if (unchanged == true && left.isEmpty() || unchanged != false && waiting) return
        }
        val (ok, wrote) = passAndFollow()
        val after = runCatching { outOfStep() }.getOrNull()
        checked = after
        // Settled, or the pass changed something (the next check asks again at once).
        backoff.after(if (wrote || ok && after.isNullOrEmpty() && listTag != null) null
            else listOf(_state.value.error, after, listTag == null), System.currentTimeMillis())
    }

    // Planner's notes not as last synced (new, changed, or deleted with the Undo gone), each with its fields now (null:
    // deleted), so a note changed again since can be told from one left as it was.
    private suspend fun outOfStep(): Map<String, List<String>?> {
        val links = rows.all().associateBy { it.noteId }
        val pending = pendingDeleted()
        val local = store.all()
        val left = HashMap<String, List<String>?>()
        local.forEach { note ->
            val fields = NoteMapping.fields(note)
            if (note.id !in pending && links[note.id]?.let { row -> fields != NoteMapping.fields(row) } != false) left[note.id] = fields
        }
        (links.keys - local.mapTo(HashSet()) { it.id } - pending).forEach { left[it] = null }
        return left
    }

    // A pass asked for (an edit, Sync now, the background run): AutoSync's checks start again from no wait.
    suspend fun sync(): Boolean { backoff.reset(); return passAndFollow().first }

    // One pass, then the follow-up if one was asked for meanwhile. Also whether it wrote anything on either side.
    private suspend fun passAndFollow(): Pair<Boolean, Boolean> {
        val result = lock.withLock {
            if (!_enabled.value) return@withLock true to false
            _state.value = _state.value.copy(running = true, error = null)
            // Not cut off half way: a write to Nextcloud and the record of it go together.
            withContext(NonCancellable) {
                val wrote = booleanArrayOf(false)
                try {
                    val account = withContext(Dispatchers.IO) { runCatching { accounts.load() }.getOrNull() }
                        ?: throw NotesApiException("Sign in to Nextcloud first, in Settings → Nextcloud.")
                    val (ownCopies, skipped, keptRemote) = withContext(Dispatchers.IO) { pass(account, wrote) }
                    val now = System.currentTimeMillis()
                    prefs.edit().putLong(KEY_LAST, now).apply()
                    _state.value = State(lastSynced = now, ownCopies = ownCopies, skipped = skipped, keptRemote = keptRemote)
                    true to wrote[0]
                } catch (e: Exception) {
                    _state.value = _state.value.copy(running = false, error = (e as? NotesApiException)?.message
                        ?: if (e is java.io.IOException) "Couldn't reach Nextcloud. Check the connection and try again." else "Notes sync failed. Try again later.")
                    false to wrote[0]
                }
            }
        }
        followUp()
        return result
    }

    // One pass (Quillpad's synchronize): returns how many "(my copy)" notes it made, how many notes it left alone and how many
    // deletions Nextcloud wouldn't do (see State). [wrote][0]: set once it has changed anything on either side.
    private suspend fun pass(account: NextcloudAccount, wrote: BooleanArray): Triple<Int, Int, Int> {
        val key = CalendarSync.accountKey(account)
        val (list, tag) = api.listTagged(account)
        // What Nextcloud's list was as this pass read it (a pass that writes changes it, so the next check passes once more).
        listTag = tag?.let { key to it }
        val remote = list.associateBy { it.id }
        val pending = pendingDeleted()
        var ownCopies = 0
        var skipped = 0
        var keptRemote = 0
        // Links made for another login don't count here.
        rows.all().filter { it.account != key }.forEach { rows.delete(it.noteId) }
        // E (user, 9 Oct): the links the versions before this logic made (by content, possibly to a duplicate there) go once;
        // this pass pairs the notes again by title.
        if (!prefs.getBoolean(KEY_RELINKED, false)) { rows.deleteAll(); prefs.edit().putBoolean(KEY_RELINKED, true).apply() }
        val linked = rows.all()
        val local = store.all().associateBy { it.id }

        suspend fun put(note: PlannerNote, expected: PlannerNote?) = store.put(note, expected).also { if (it) wrote[0] = true }
        suspend fun archive(id: String, expected: PlannerNote) = store.archive(id, expected).also { if (it) wrote[0] = true }
        suspend fun link(note: PlannerNote, theirs: RemoteNote) = rows.put(NoteMapping.row(note, key, theirs))
        // Sent with its edit time, which Nextcloud gives the file. Should Nextcloud's time come back otherwise, Planner's
        // note takes it, so the next sync doesn't take the two for different versions.
        suspend fun sent(mine: PlannerNote, theirs: RemoteNote) {
            wrote[0] = true
            val updated = if (theirs.modified > 0 && kotlin.math.abs(theirs.modified - mine.modified / 1000) > 1) mine.copy(modified = theirs.modified * 1000) else mine
            if (updated == mine || put(updated, mine)) link(updated, theirs) else link(mine, theirs)
        }
        suspend fun push(note: PlannerNote) =
            sent(note, api.create(account, NoteMapping.remoteTitle(note), note.content, note.notebook, note.pinned, note.modified / 1000))
        // Nextcloud's version here. Edited here meanwhile (put refused: the edit is kept): linked all the same, as it was, so
        // the next sync sees the edit (changed here only) and sends it, rather than pairing the note again and losing it.
        suspend fun take(mine: PlannerNote?, theirs: RemoteNote) {
            val updated = NoteMapping.apply(mine ?: PlannerNote(), theirs)
            if (put(updated, mine)) link(updated, theirs) else if (mine != null) link(mine, theirs)
        }
        // One note Nextcloud refuses (a shared note it won't let Planner change, say) is left as it is; the rest go on.
        // A login or connection problem still stops the pass.
        suspend fun each(block: suspend () -> Unit) {
            try { block() } catch (e: NotesApiException) { if (e.code in 400..499 && e.code != 401) skipped++ else throw e }
        }
        // Both sides have the note: the newer edit wins, to the second (Quillpad: within a second is the same version). Only
        // when both changed since the last sync ([row]; none on a first sync): one changed on one side alone wins whatever
        // the two clocks say (Quillpad's times alone let a phone clock running behind Nextcloud's lose a newer edit).
        // Changed on both within the same second: the phone's goes up (the two would otherwise stay different).
        suspend fun settle(mine: PlannerNote, theirs: RemoteNote, row: SentNote?) {
            if (!NoteMapping.fits(theirs)) { skipped++; return }
            val here = mine.modified / 1000
            val mineChanged = row == null || NoteMapping.fields(mine) != NoteMapping.fields(row)
            val theirsChanged = row == null || theirs.etag != row.etag
            val same = NoteMapping.sameText(mine.content, theirs.content) && NoteMapping.sameTitle(NoteMapping.remoteTitle(mine), theirs.title) &&
                Notes.cleanNotebook(theirs.category) == mine.notebook && theirs.favorite == mine.pinned
            val phoneWins = when {
                !mineChanged && !theirsChanged -> return
                mineChanged && !theirsChanged -> true
                !mineChanged -> false
                kotlin.math.abs(here - theirs.modified) <= 1 -> if (same) { link(mine, theirs); return } else true
                else -> here > theirs.modified
            }
            when {
                !phoneWins -> take(mine, theirs)
                // Shared read-only there: its words can't go up. Only filed or pinned differently here: kept on the phone.
                // Else the edit becomes a note of its own, "(my copy)" (saved first, so the words are never only in the
                // note about to take Nextcloud's), which goes up next pass; the shared one takes Nextcloud's.
                theirs.readonly -> if (NoteMapping.sameTitle(NoteMapping.remoteTitle(mine), theirs.title) && NoteMapping.sameText(mine.content, theirs.content)) link(mine, theirs) else {
                    val copy = mine.copy(id = java.util.UUID.randomUUID().toString(), title = (Notes.label(mine) + " (my copy)").take(Notes.MAX_TITLE),
                        reminderAt = null, snoozedUntil = null, ringUntilDismissed = false, ringSeconds = 0)
                    if (put(copy, null)) { ownCopies++; take(mine, theirs) }
                }
                // If-Match: the version this pass read there. Changed there since (412): left for the next pass to decide.
                else -> when (val result = api.update(account, theirs.id, theirs.etag, NoteMapping.remoteTitle(mine), mine.content,
                    mine.notebook, mine.pinned, here)) {
                    is NotesApi.Write.Done -> sent(mine, result.note)
                    NotesApi.Write.Changed -> {}
                    NotesApi.Write.Gone -> { rows.delete(mine.id); push(mine) }
                }
            }
        }

        val linkedRemote = HashSet<Long>()
        val linkedLocal = HashSet<String>()
        if (linked.isEmpty()) {
            // A first sync (Quillpad's TITLE method): each note here is paired with Nextcloud's note of the same title.
            val unpaired = list.toMutableList()
            for (mine in local.values) {
                if (mine.id in pending) continue
                val title = NoteMapping.remoteTitle(mine)
                // The same title exactly first: Nextcloud names a second note of a title "Shopping (2)", which the tolerant
                // match (for titles Nextcloud tidied) would also take for "Shopping".
                if (title.isBlank()) continue
                val theirs = unpaired.firstOrNull { it.title.trim().equals(title, ignoreCase = true) }
                    ?: unpaired.firstOrNull { NoteMapping.sameTitle(title, it.title) } ?: continue
                unpaired.remove(theirs)
                linkedRemote += theirs.id; linkedLocal += mine.id
                each { settle(mine, theirs, null) }
            }
        } else for (row in linked) {
            linkedRemote += row.remoteId; linkedLocal += row.noteId
            val mine = local[row.noteId]
            val theirs = remote[row.remoteId]
            each {
                when {
                    // Deleted here (its Undo gone): deleted there too.
                    mine == null -> {
                        if (row.noteId in pending) return@each
                        if (theirs == null) { rows.delete(row.noteId); return@each }
                        // A shared read-only note is left there, its link kept so it isn't brought in again; changed there
                        // since, it comes in again as a new note.
                        if (theirs.readonly) {
                            if (theirs.etag == row.etag) keptRemote++ else { rows.delete(row.noteId); linkedRemote.remove(theirs.id) }
                            return@each
                        }
                        // Refused there (not a login problem): kept there and its link with it, tried again next pass, and
                        // said in the sync status as a deletion waiting.
                        try { api.remove(account, theirs.id) }
                        catch (e: NotesApiException) { if (e.code in 400..499 && e.code != 401) { keptRemote++; return@each } else throw e }
                        wrote[0] = true; rows.delete(row.noteId)
                    }
                    // Deleted on Nextcloud: deleted here too (to Recently deleted, where it can be put back), as Quillpad does.
                    theirs == null -> { rows.delete(row.noteId); archive(mine.id, mine) }
                    else -> settle(mine, theirs, row)
                }
            }
        }

        // Nextcloud's notes not linked are new here; Planner's not linked go up as new ones there.
        for (theirs in list) {
            if (theirs.id in linkedRemote) continue
            each { if (NoteMapping.fits(theirs)) take(null, theirs) else skipped++ }
        }
        val nowLinked = rows.all().mapTo(HashSet()) { it.noteId }
        for (mine in store.all()) {
            if (mine.id in linkedLocal || mine.id in pending || mine.id in nowLinked) continue
            each { push(mine) }
        }
        return Triple(ownCopies, skipped, keptRemote)
    }

    companion object {
        // Half a second after an edit, as Quillpad (its RemoteUpdateDebounceTime).
        const val SEND_DELAY_MS = 500L
        private const val KEY_ENABLED = "enabled"
        private const val KEY_LAST = "last_synced"
        // Set once the links made before Quillpad's logic were cleared (E), so notes are paired by title again once.
        internal const val KEY_RELINKED = "relinked_by_title"
        fun prefs(context: Context): android.content.SharedPreferences = context.getSharedPreferences("note_sync", Context.MODE_PRIVATE)
    }
}
