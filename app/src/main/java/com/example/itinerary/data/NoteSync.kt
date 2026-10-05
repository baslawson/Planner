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

    fun delete(account: NextcloudAccount, expected: RemoteNote): NextcloudClient.NoteDelete {
        if (expected.etag == null || expected.readonly) return NextcloudClient.NoteDelete.UNSUPPORTED
        val settings = account.server.newBuilder().addPathSegments("index.php/apps/notes/api/v1/settings").build()
        val (code, body) = call(account, Request.Builder().url(settings).get())
        if (code == 400 || code == 404 || code == 405) return NextcloudClient.NoteDelete.UNSUPPORTED
        if (code != 200) fail(code)
        val path = runCatching { JSONObject(body).getString("notesPath") }.getOrNull()
            ?: return NextcloudClient.NoteDelete.UNSUPPORTED
        // API etags cover metadata too, whereas DAV etags protect the backing file's bytes.
        return try {
            NextcloudClient(http).deleteNoteFile(account, path, expected.category, expected.id, expected.content) {
                val (status, fresh) = call(account, Request.Builder().url(notes(account, expected.id)).get())
                if (status == 404) false
                else {
                    if (status != 200) fail(status)
                    parse(JSONObject(fresh)) == expected
                }
            }
        } catch (_: BackupException) {
            // A Notes-only deployment or refused DAV access leaves only this deletion pending; other notes still sync.
            NextcloudClient.NoteDelete.UNSUPPORTED
        }
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
 * Two-way sync of Planner's notes with the Nextcloud Notes app, on the backup login. A pass reads every note there,
 * then for each linked pair compares both sides with what was last synced: one side changed → the other follows; both →
 * Nextcloud's version wins in the note and Planner's is kept as a "(conflict copy)" (sent up as a new note); deleted on
 * Nextcloud → Recently deleted here unless changed here. Deleted in Planner → conditionally deleted through DAV;
 * a changed version comes back, and a file that cannot be verified waits. Then Nextcloud's new notes come in
 * (linked to an identical one here first), and Planner's new notes go
 * up. Updates use If-Match to protect changes made since the list was read.
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
            }
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
                    val (conflicts, skipped, keptRemote) = withContext(Dispatchers.IO) { pass(account, wrote) }
                    val now = System.currentTimeMillis()
                    prefs.edit().putLong(KEY_LAST, now).apply()
                    _state.value = State(lastSynced = now, conflicts = conflicts, skipped = skipped, keptRemote = keptRemote)
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

    // One pass; returns how many conflict copies it made and how many notes it left alone. [wrote][0]: set once it has
    // changed anything on either side.
    private suspend fun pass(account: NextcloudAccount, wrote: BooleanArray): Triple<Int, Int, Int> {
        val key = CalendarSync.accountKey(account)
        val (list, tag) = api.listTagged(account)
        // What Nextcloud's list was as this pass read it (a pass that writes changes it, so the next check passes once more).
        listTag = tag?.let { key to it }
        val remote = list.associateBy { it.id }
        val pending = pendingDeleted()
        var conflicts = 0
        var skipped = 0
        var keptRemote = 0
        // Links made for another login don't count here.
        rows.all().filter { it.account != key }.forEach { rows.delete(it.noteId) }
        val linked = rows.all()
        var local = store.all().associateBy { it.id }
        val linkedRemote = linked.mapTo(HashSet()) { it.remoteId }

        suspend fun put(note: PlannerNote, expected: PlannerNote?) = store.put(note, expected).also { if (it) wrote[0] = true }
        suspend fun archive(id: String, expected: PlannerNote) = store.archive(id, expected).also { if (it) wrote[0] = true }
        suspend fun push(note: PlannerNote) {
            val made = api.create(account, NoteMapping.remoteTitle(note), note.content, note.notebook, note.pinned)
            wrote[0] = true
            rows.put(NoteMapping.row(note, key, made))
        }
        suspend fun conflictCopy(mine: PlannerNote) {
            val copy = mine.copy(id = java.util.UUID.randomUUID().toString(),
                title = (Notes.label(mine) + " (conflict copy)").take(Notes.MAX_TITLE), reminderAt = null, snoozedUntil = null, ringUntilDismissed = false)
            if (put(copy, null)) conflicts++
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
                // Delete the backing note file conditionally; a changed or unverifiable version waits safely.
                mine == null -> {
                    if (row.noteId in pending) return@each
                    if (theirs == null) { rows.delete(row.noteId); return@each }
                    if (theirs.etag == row.etag && !theirs.readonly) {
                        when (api.delete(account, theirs)) {
                            NextcloudClient.NoteDelete.DELETED -> { wrote[0] = true; rows.delete(row.noteId) }
                            NextcloudClient.NoteDelete.CHANGED -> {} // New version reconciled on the next pass.
                            NextcloudClient.NoteDelete.UNSUPPORTED -> keptRemote++
                        }
                    }
                    // N16-2: shared read-only and unchanged: left there, and its link kept so it isn't imported again.
                    else if (theirs.etag == row.etag) {}
                    else {
                        // Changed there: kept there, and it comes back below as a new note.
                        rows.delete(row.noteId)
                        linkedRemote.remove(theirs.id)
                    }
                }
                // Deleted on Nextcloud: to Recently deleted, unless changed here since (then it goes up again).
                theirs == null -> {
                    rows.delete(row.noteId)
                    if (NoteMapping.fields(mine) == NoteMapping.fields(row)) archive(mine.id, mine) else push(mine)
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
                            if (put(updated, mine)) rows.put(NoteMapping.row(updated, key, theirs))
                        }
                        theirs.readonly && metaOnly -> {
                            val updated = if (theirsChanged) NoteMapping.apply(mine, theirs).copy(notebook = mine.notebook, pinned = mine.pinned) else mine
                            if (updated == mine || put(updated, mine)) rows.put(NoteMapping.row(updated, key, theirs))
                        }
                        !theirsChanged && !theirs.readonly -> when (val result = api.update(account, theirs.id, theirs.etag,
                            NoteMapping.remoteTitle(mine), mine.content, mine.notebook, mine.pinned)) {
                            is NotesApi.Write.Done -> { wrote[0] = true; rows.put(NoteMapping.row(mine, key, result.note)) }
                            // Changed there meanwhile: settled on the next pass, with its new version.
                            NotesApi.Write.Changed -> {}
                            NotesApi.Write.Gone -> { rows.delete(row.noteId); push(mine) }
                        }
                        else -> {
                            // Both changed (or it's read-only there): Nextcloud's version here, then Planner's as a copy
                            // (only once the note itself is updated, so a failed update doesn't make a second copy).
                            val updated = NoteMapping.apply(mine, theirs)
                            if (put(updated, mine)) {
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
            if (!NoteMapping.fits(theirs)) {
                // N16-1: its copy here (same words, or the same title for one grown too long) is neither linked nor sent
                // up again as a second note.
                val copy = free.firstOrNull { NoteMapping.sameText(it.content, theirs.content) }
                    ?: free.takeIf { theirs.content.length > Notes.MAX_CONTENT }?.firstOrNull { NoteMapping.sameTitle(NoteMapping.remoteTitle(it), theirs.title) }
                copy?.let(free::remove)
                skipped++; return@each
            }
            val incoming = NoteMapping.apply(PlannerNote(), theirs)
            val sameWords = free.filter { NoteMapping.sameText(it.content, incoming.content) }
            val twin = sameWords.firstOrNull { NoteMapping.sameTitle(NoteMapping.remoteTitle(it), theirs.title) } ?: sameWords.firstOrNull()
            if (twin != null) {
                // Linked, with Nextcloud's notebook and favourite (Planner's title, colour, tags and so on stay).
                free.remove(twin)
                val linkedNote = twin.copy(notebook = incoming.notebook, pinned = incoming.pinned)
                if (linkedNote == twin || put(linkedNote, twin)) rows.put(NoteMapping.row(linkedNote, key, theirs))
            } else if (put(incoming, null)) rows.put(NoteMapping.row(incoming, key, theirs))
        }

        // Planner's notes not on Nextcloud yet go up.
        for (mine in free) each { push(mine) }
        return Triple(conflicts, skipped, keptRemote)
    }

    companion object {
        const val SEND_DELAY_MS = 3_000L
        private const val KEY_ENABLED = "enabled"
        private const val KEY_LAST = "last_synced"
        fun prefs(context: Context): android.content.SharedPreferences = context.getSharedPreferences("note_sync", Context.MODE_PRIVATE)
    }
}
