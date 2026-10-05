package com.example.itinerary.data

import okhttp3.Call
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

data class NextcloudBackup(val name: String, val size: Long?, val modified: String?, val etag: String?)

// A calendar in the account's calendar home. [href] is its path on the server; [ctag] changes whenever its events do;
// [writable]: this login may add events to it. [events] / [tasks]: it can hold events / tasks (a Nextcloud Tasks list
// often holds tasks only).
data class RemoteCalendar(val href: String, val name: String, val color: Int?, val ctag: String?, val writable: Boolean = true,
                          val events: Boolean = true, val tasks: Boolean = false)

// What happened to a write (step 5). Changed: the copy on the server isn't the one Planner last wrote (or, for a new one,
// something already has that name), so nothing was written. Missing: it's no longer on the server.
// An event file in a calendar (step 6): its path on the server, version marker and text.
data class ServerFile(val href: String, val etag: String?, val data: String)

// A reply larger than Planner reads at once (see NextcloudClient.multistatus): the caller may ask for less at a time.
class ReplyTooLargeException(message: String) : BackupException(message)

sealed class WriteResult {
    class Ok(val etag: String?) : WriteResult()
    object Changed : WriteResult()
    object Missing : WriteResult()
}

// S6-1: lets the person stop a backup upload or download. cancel() ends the request under way at once (Call.cancel)
// and refuses any that would follow; the upload or download then fails with TransferCancelledException.
class BackupTransfer {
    @Volatile var cancelled = false
        private set
    private var call: Call? = null
    fun cancel() = synchronized(this) { cancelled = true; call?.cancel() }
    internal fun begin(next: Call) = synchronized(this) { call = next; if (cancelled) next.cancel() }
    fun check() { if (cancelled) throw TransferCancelledException() }
}

class TransferCancelledException : BackupException("Cancelled.")

// Blocking transport; callers run on IO. TLS verification stays enabled, redirects are never followed
// with credentials. Backups are confined to the selected folder under this account's Files root. Calendar sync reads
// (PROPFIND and REPORT) inside the account's calendar home; the only writes (step 5) are PUT and DELETE of Planner's own
// event files in the calendar the user chose, always conditional (If-None-Match / If-Match) so nothing else is replaced.
class NextcloudClient(client: OkHttpClient = OkHttpClient(), callTimeoutMs: Long = TimeUnit.MINUTES.toMillis(5),
                      private val transferTimeoutMs: Long = TimeUnit.MINUTES.toMillis(30),
                      private val minBytesPerSecond: Long = 32 * 1024) {
    private val http = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS).writeTimeout(45, TimeUnit.SECONDS)
        .callTimeout(callTimeoutMs, TimeUnit.MILLISECONDS).build()
    // R5-2: a backup's own upload (PUT) and download (GET) take as long as the file and the link need, within a generous
    // limit on the whole call (S6-1, so a link that trickles can't hold it for ever; Cancel stops it sooner). AS-1: the
    // limit grows with the file (see [transferLimitMs]), so a large backup on a modest link isn't refused every time. A
    // stall ends it sooner too (the connect, read and write timeouts above still apply).
    // AB-5: a size no server really has (over about 9 PB) can't overflow it.
    internal fun transferLimitMs(bytes: Long?): Long =
        transferTimeoutMs + (bytes ?: 0).coerceIn(0, Long.MAX_VALUE / 2000) * 1000 / minBytesPerSecond

    fun checkConnection(account: NextcloudAccount) {
        val root = account.filesRoot
        val entries = properties(account, root, "0")
        if (entries.none { samePath(it.url, root) && it.collection }) {
            throw BackupException("The server didn't return a Nextcloud files folder. Check the address and username.")
        }
    }

    fun list(account: NextcloudAccount): List<NextcloudBackup> {
        val entries = properties(account, account.folder, "1", missingIsEmpty = true)
        return entries.mapNotNull { entry ->
            if (entry.collection || samePath(entry.url, account.folder)) return@mapNotNull null
            val name = entry.url.pathSegments.last()
            if (!validName(name) || !samePath(entry.url, fileUrl(account, name))) return@mapNotNull null
            NextcloudBackup(name, entry.size, entry.modified, entry.etag)
        }.distinctBy { it.name }.sortedByDescending { it.name }
    }

    // [cancel]: see BackupTransfer. The temporary file is still removed after a Cancel.
    fun upload(account: NextcloudAccount, file: File, cancel: BackupTransfer? = null): String {
        // Create parents in order; MKCOL does not create missing intermediate directories.
        var directory = account.filesRoot
        for (segment in account.folderPath.split('/')) {
            directory = directory.newBuilder().addPathSegment(segment).addPathSegment("").build()
            request(account, "MKCOL", directory, EMPTY, cancel = cancel).use { response ->
                if (response.code != 201 && response.code != 405) fail(response.code)
            }
            // A 405 may mean a file occupies this name. Never overwrite it or continue through it.
            if (properties(account, directory, "0").none { it.collection &&
                    samePath(it.url, directory) }) {
                throw BackupException("Part of the backup path exists but isn't a folder. Choose another path.")
            }
        }
        val id = UUID.randomUUID().toString()
        val timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss").withZone(ZoneOffset.UTC).format(Instant.now())
        val name = "Planner-backup-${timestamp}_${id}.zip"
        val temporary = fileUrl(account, ".upload-$id")
        try {
            request(account, "PUT", temporary, file.asRequestBody("application/zip".toMediaType()),
                mapOf("If-None-Match" to "*"), limitMs = transferLimitMs(file.length()), cancel = cancel).use { if (it.code != 201 && it.code != 204) fail(it.code) }
            request(account, "MOVE", temporary, null,
                mapOf("Destination" to fileUrl(account, name).toString(), "Overwrite" to "F"), cancel = cancel)
                .use { if (it.code != 201 && it.code != 204) fail(it.code) }
            return name
        } finally {
            // Only our random temporary object is eligible for cleanup; completed backups are never deleted.
            runCatching { request(account, "DELETE", temporary).close() }
        }
    }

    // [cancel]: see BackupTransfer. Whatever stops it, no part of the file is left at [target].
    fun download(account: NextcloudAccount, backup: NextcloudBackup, target: File, cancel: BackupTransfer? = null) {
        if (!validName(backup.name)) throw BackupException("Invalid backup filename.")
        val headers = backup.etag?.let { mapOf("If-Match" to it) }.orEmpty()
        val started = System.nanoTime()
        val limitMs = transferLimitMs(backup.size)
        try {
            request(account, "GET", fileUrl(account, backup.name), headers = headers, limitMs = limitMs, cancel = cancel).use { response ->
                if (response.code != 200) fail(response.code)
                val body = response.body ?: throw BackupException("The backup download was empty.")
                if (body.contentLength() > target.parentFile!!.usableSpace) {
                    throw BackupException("There isn't enough free space on this device to download the backup.")
                }
                // The body arrives here, after request(): its errors get the same plain messages.
                val copied = try {
                    body.byteStream().use { input -> target.outputStream().use { input.copyTo(it) } }
                } catch (e: IOException) {
                    cancel?.check()
                    if (overLimit(started, limitMs)) throw BackupException(TOO_LONG)
                    if (e is SocketTimeoutException) throw BackupException("Nextcloud timed out during the backup download. Check your connection and try again.")
                    throw BackupException(if (target.parentFile!!.usableSpace < 1024 * 1024)
                        "There isn't enough free space on this device to download the backup."
                    else "The backup download was interrupted. Check your connection and try again.")
                }
                cancel?.check()
                if (body.contentLength() >= 0 && copied != body.contentLength()) {
                    throw BackupException("The backup download was interrupted. Try again.")
                }
            }
        } catch (e: Exception) {
            target.delete()
            throw e
        }
    }

    private fun fileUrl(account: NextcloudAccount, name: String): HttpUrl =
        account.folder.newBuilder().addPathSegment(name).build()

    private data class Entry(val url: HttpUrl, val collection: Boolean, val size: Long?, val modified: String?, val etag: String?)

    private fun properties(account: NextcloudAccount, url: HttpUrl, depth: String, missingIsEmpty: Boolean = false): List<Entry> =
        multistatus(account, "PROPFIND", url, PROPERTIES, depth, missingIsEmpty = missingIsEmpty,
            tooLarge = "The backup folder is too large to list. Move older backups to another folder.",
            invalid = "The server returned an invalid file list. Check that this is your Nextcloud address.",
            empty = "The server returned an empty file list.").map { (resolved, prop) ->
            Entry(resolved, prop("resourcetype")?.children("collection")?.isNotEmpty() == true,
                prop("getcontentlength")?.textContent?.toLongOrNull()?.takeIf { it >= 0 },
                prop("getlastmodified")?.textContent, prop("getetag")?.textContent)
        }

    // Every calendar in the account's calendar home that can hold events or tasks. Inbox, outbox, trash bin and
    // subscriptions aren't calendars of this kind, so they are left out.
    fun calendars(account: NextcloudAccount): List<RemoteCalendar> {
        val home = account.calendarsRoot
        return multistatus(account, "PROPFIND", home, CALENDAR_PROPERTIES, "1", calendar = true,
            tooLarge = "Nextcloud listed too many calendars to read.",
            invalid = "The server returned an invalid calendar list. Check that this is your Nextcloud address.",
            empty = "The server returned an empty calendar list.").mapNotNull { (resolved, prop) ->
            val path = resolved.encodedPath
            if (!inside(resolved, home)) return@mapNotNull null
            if (prop("resourcetype")?.children("calendar", CALDAV)?.isNotEmpty() != true) return@mapNotNull null
            val components = prop("supported-calendar-component-set", CALDAV)?.children("comp", CALDAV).orEmpty()
            // A server that doesn't say takes both.
            fun holds(kind: String) = components.isEmpty() || components.any { it.attributes["name"].equals(kind, ignoreCase = true) }
            if (!holds("VEVENT") && !holds("VTODO")) return@mapNotNull null
            val name = prop("displayname")?.textContent?.trim()?.takeIf { it.isNotEmpty() }
                ?: resolved.pathSegments.lastOrNull { it.isNotEmpty() } ?: return@mapNotNull null
            // Without a privilege list, assume it can be written; the server still refuses a write it doesn't allow.
            val privileges = prop("current-user-privilege-set")?.children("privilege")?.flatMap { it.nodes }?.map { it.localName }
            RemoteCalendar(path.let { if (it.endsWith('/')) it else "$it/" }, name.take(200),
                prop("calendar-color", APPLE_ICAL)?.textContent?.let(::parseColor),
                (prop("getctag", CALENDARSERVER) ?: prop("sync-token"))?.textContent?.trim()?.takeIf { it.isNotEmpty() },
                writable = privileges == null || privileges.any { it in setOf("all", "write", "write-content", "bind") },
                events = holds("VEVENT"), tasks = holds("VTODO"))
        }.distinctBy { it.href }
    }

    // The raw iCalendar text of every event in [calendar] between [from] and [until], with repeats expanded by the
    // server into single dates.
    fun calendarEvents(account: NextcloudAccount, calendar: String, from: Instant, until: Instant): List<String> {
        val home = account.calendarsRoot
        val url = account.server.newBuilder().encodedPath(calendar).build()
        require(inside(url, home) &&
            url.pathSegments.none { it == "." || it == ".." }) { "Calendar outside the calendar home" }
        val stamp = RANGE_STAMP
        val range = "start=\"${stamp.format(from)}\" end=\"${stamp.format(until)}\""
        val query = """<?xml version="1.0" encoding="utf-8"?><c:calendar-query xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">""" +
            """<d:prop><d:getetag/><c:calendar-data><c:expand $range/></c:calendar-data></d:prop>""" +
            """<c:filter><c:comp-filter name="VCALENDAR"><c:comp-filter name="VEVENT"><c:time-range $range/></c:comp-filter></c:comp-filter></c:filter></c:calendar-query>"""
        return multistatus(account, "REPORT", url, query, "1", calendar = true, limit = 16 * 1024 * 1024,
            tooLarge = "This calendar has too many events to download.",
            invalid = "The server returned an invalid calendar. Try again later.",
            empty = "The server returned an empty calendar.").mapNotNull { (_, prop) ->
            prop("calendar-data", CALDAV)?.textContent?.takeIf { it.isNotBlank() }
        }
    }

    enum class NoteDelete { DELETED, CHANGED, UNSUPPORTED }

    // Notes API DELETE ignores If-Match. Resolve its backing file id through DAV and delete only that version.
    fun deleteNoteFile(account: NextcloudAccount, notesPath: String, category: String, id: Long,
                       content: String, stillCurrent: () -> Boolean): NoteDelete {
        fun parts(path: String, emptyAllowed: Boolean): List<String>? {
            if (path.isEmpty()) return if (emptyAllowed) emptyList() else null
            val parts = path.trim('/').split('/')
            if (parts.any { it.isEmpty() || it == "." || it == ".." || it.contains('\\') ||
                    it.any { c -> c.isISOControl() } || Regex("(?i)%2f|%5c|%2e").containsMatchIn(it) }) return null
            return parts
        }
        val path = parts(notesPath, false) ?: return NoteDelete.UNSUPPORTED
        val sub = parts(category, true) ?: return NoteDelete.UNSUPPORTED
        val folder = account.filesRoot.newBuilder().apply { (path + sub).forEach { addPathSegment(it) }; addPathSegment("") }.build()
        if (!inside(folder, account.filesRoot)) return NoteDelete.UNSUPPORTED
        val entries = multistatus(account, "PROPFIND", folder, NOTE_PROPERTIES, "1", missingIsEmpty = true,
            tooLarge = "The notes folder is too large to check safely.", invalid = "Nextcloud returned an invalid notes file list.",
            empty = "Nextcloud returned an empty notes file list.")
        val matching = entries.filter { (url, prop) ->
            inside(url, folder) && segments(url).size == segments(folder).size + 1 &&
                url.pathSegments.none { it == "." || it == ".." || '/' in it || '\\' in it } &&
                prop("resourcetype")?.children("collection")?.isNotEmpty() != true &&
                prop("fileid", "http://owncloud.org/ns")?.textContent?.trim()?.toLongOrNull() == id
        }
        if (matching.size != 1) return NoteDelete.UNSUPPORTED
        val (url, prop) = matching.single()
        val rawTag = prop("getetag")?.textContent?.trim() ?: return NoteDelete.UNSUPPORTED
        if (rawTag.startsWith("W/") || !rawTag.startsWith('"') || !rawTag.endsWith('"')) return NoteDelete.UNSUPPORTED
        val tag = etag(rawTag) ?: return NoteDelete.UNSUPPORTED
        request(account, "GET", url, headers = mapOf("If-Match" to tag)).use { response ->
            if (response.code == 404 || response.code == 412) return NoteDelete.CHANGED
            if (response.code != 200) fail(response.code)
            val bytes = response.body?.byteStream()?.use { it.readBytesLimited(Notes.MAX_CONTENT * 4 + 16, "The note file is too large to check safely.") }
                ?: return NoteDelete.UNSUPPORTED
            val decoded = runCatching { Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString() }.getOrNull()
                ?: return NoteDelete.UNSUPPORTED
            val text = decoded.replace("\uFEFF", "").replace("\uFFFE", "")
            if (text != content) return NoteDelete.CHANGED
        }
        if (!stillCurrent()) return NoteDelete.CHANGED
        request(account, "DELETE", url, headers = mapOf("If-Match" to tag)).use { response ->
            return when (response.code) {
                200, 204, 404 -> NoteDelete.DELETED
                412 -> NoteDelete.CHANGED
                else -> fail(response.code)
            }
        }
    }

    // Step 5: creates ([etag] null, only if nothing has that name) or replaces (only if the server still has version
    // [etag]) Planner's event [uid] in [calendar].
    fun putEvent(account: NextcloudAccount, calendar: String, uid: String, body: String, etag: String?): WriteResult =
        putFile(account, calendar, eventUrl(account, calendar, uid).encodedPath, body, etag)

    // Step 6: the same for any event file [href] in [calendar] (one created on Nextcloud keeps its own name).
    fun putFile(account: NextcloudAccount, calendar: String, href: String, body: String, etag: String?): WriteResult {
        val url = fileUrl(account, calendar, href)
        request(account, "PUT", url, body.toRequestBody("text/calendar; charset=utf-8".toMediaType()),
            if (etag == null) mapOf("If-None-Match" to "*") else mapOf("If-Match" to etag)).use { response ->
            return when (response.code) {
                200, 201, 204 -> WriteResult.Ok(etag(response.header("ETag")) ?: currentEtag(account, url))
                412 -> WriteResult.Changed
                404 -> WriteResult.Missing
                else -> fail(response.code, calendar = true, write = true)
            }
        }
    }

    // Always conditional (E-7): a caller that doesn't know the version fetches the file first.
    fun deleteFile(account: NextcloudAccount, calendar: String, href: String, etag: String): WriteResult {
        val url = fileUrl(account, calendar, href)
        request(account, "DELETE", url, null, mapOf("If-Match" to etag)).use { response ->
            return when (response.code) {
                200, 204 -> WriteResult.Ok(null)
                412 -> WriteResult.Changed
                404 -> WriteResult.Missing
                else -> fail(response.code, calendar = true, write = true)
            }
        }
    }

    // Step 6: every event file in [calendar] with its version marker (any date), to notice changes and deletions there.
    // E-9: only the two properties needed, so a large calendar fits (about twice as many files as with the four of a file
    // list); the limit stays, since the whole reply is held as a tree. Beyond it: ReplyTooLargeException.
    fun eventEtags(account: NextcloudAccount, calendar: String): Map<String, String?> {
        val folder = calendarUrl(account, calendar)
        return multistatus(account, "PROPFIND", folder, ETAG_PROPERTIES, "1", calendar = true, limit = 8 * 1024 * 1024,
            tooLarge = "This calendar has too many events to check.", invalid = "The server returned an invalid calendar. Try again later.",
            empty = "The server returned an empty calendar.").mapNotNull { (resolved, prop) ->
            val path = resolved.encodedPath
            if (!path.startsWith(folder.encodedPath) || path == folder.encodedPath || prop("resourcetype")?.children("collection")?.isNotEmpty() == true) null
            else path to etag(prop("getetag")?.textContent)
        }.toMap()
    }

    // Step 6: the event files in [calendar] between [from] and [until] as they are (repeats not expanded).
    fun calendarFiles(account: NextcloudAccount, calendar: String, from: Instant, until: Instant): List<ServerFile> {
        val stamp = RANGE_STAMP
        val range = "start=\"${stamp.format(from)}\" end=\"${stamp.format(until)}\""
        return files(account, calendar, """<?xml version="1.0" encoding="utf-8"?><c:calendar-query xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">""" +
            """<d:prop><d:getetag/><c:calendar-data/></d:prop><c:filter><c:comp-filter name="VCALENDAR"><c:comp-filter name="VEVENT">""" +
            """<c:time-range $range/></c:comp-filter></c:comp-filter></c:filter></c:calendar-query>""")
    }

    // Task sync: every task file in [list] (any date, done or not) as it is.
    fun taskFiles(account: NextcloudAccount, list: String): List<ServerFile> =
        files(account, list, tooLarge = "This task list has too many tasks for Planner to download.", query = """<?xml version="1.0" encoding="utf-8"?><c:calendar-query xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">""" +
            """<d:prop><d:getetag/><c:calendar-data/></d:prop><c:filter><c:comp-filter name="VCALENDAR"><c:comp-filter name="VTODO"/>""" +
            """</c:comp-filter></c:filter></c:calendar-query>""")

    // Step 6: these event files of [calendar], in one request.
    fun multiget(account: NextcloudAccount, calendar: String, hrefs: Collection<String>): List<ServerFile> {
        if (hrefs.isEmpty()) return emptyList()
        hrefs.forEach { fileUrl(account, calendar, it) }
        val list = hrefs.joinToString("") { "<d:href>${it.replace("&", "&amp;").replace("<", "&lt;")}</d:href>" }
        return files(account, calendar, """<?xml version="1.0" encoding="utf-8"?><c:calendar-multiget xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">""" +
            """<d:prop><d:getetag/><c:calendar-data/></d:prop>$list</c:calendar-multiget>""")
    }

    // Step 6: one event file as it is now; null when it's no longer there. Its version as the listings give it (see etag),
    // asked for separately when the reply has none.
    fun getFile(account: NextcloudAccount, calendar: String, href: String): ServerFile? {
        val url = fileUrl(account, calendar, href)
        val (header, bytes) = request(account, "GET", url).use { response ->
            if (response.code == 404) return null
            if (response.code != 200) fail(response.code, calendar = true)
            response.header("ETag") to (response.body ?: return null).byteStream().use { it.readBytesLimited(2 * 1024 * 1024, "This event is too large.") }
        }
        return ServerFile(url.encodedPath, etag(header) ?: currentEtag(account, url), bytes.toString(Charsets.UTF_8))
    }

    private fun files(account: NextcloudAccount, calendar: String, query: String,
                      tooLarge: String = "This calendar has too many events to download."): List<ServerFile> {
        val folder = calendarUrl(account, calendar)
        return multistatus(account, "REPORT", folder, query, "1", calendar = true, limit = 16 * 1024 * 1024,
            tooLarge = tooLarge, invalid = "The server returned an invalid calendar. Try again later.",
            empty = "The server returned an empty calendar.").mapNotNull { (resolved, prop) ->
            val data = prop("calendar-data", CALDAV)?.textContent?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            if (!resolved.encodedPath.startsWith(folder.encodedPath)) null else ServerFile(resolved.encodedPath, etag(prop("getetag")?.textContent), data)
        }
    }

    // A calendar inside this account's calendar home.
    private fun calendarUrl(account: NextcloudAccount, calendar: String): HttpUrl {
        val home = account.calendarsRoot
        val folder = account.server.newBuilder().encodedPath(calendar).build()
        require(inside(folder, home) &&
            folder.pathSegments.none { it == "." || it == ".." }) { "Calendar outside the calendar home" }
        return folder
    }

    // An event file directly inside [calendar].
    private fun fileUrl(account: NextcloudAccount, calendar: String, href: String): HttpUrl {
        val folder = calendarUrl(account, calendar)
        val url = account.server.newBuilder().encodedPath(href).build()
        require(url.encodedPath.startsWith(folder.encodedPath) && url.encodedPath != folder.encodedPath &&
            url.encodedPath.removePrefix(folder.encodedPath).none { it == '/' } && url.pathSegments.none { it == "." || it == ".." }) {
            "Event outside the calendar"
        }
        return url
    }

    // A server that doesn't return the new version marker with the write is asked for it.
    private fun currentEtag(account: NextcloudAccount, url: HttpUrl): String? = runCatching {
        etag(multistatus(account, "PROPFIND", url, ETAG_PROPERTIES, "0", tooLarge = "", invalid = "", empty = "", calendar = true)
            .firstOrNull()?.second?.invoke("getetag")?.textContent)
    }.getOrNull()

    private fun eventUrl(account: NextcloudAccount, calendar: String, uid: String): HttpUrl {
        require(uid.matches(EVENT_NAME)) { "Invalid event name" }
        return calendarUrl(account, calendar).newBuilder().addPathSegment("$uid.ics").build()
    }

    // One WebDAV request answered with a multistatus: each response's address (on this server only) with a lookup for
    // the properties the server returned with status 200.
    private fun multistatus(account: NextcloudAccount, method: String, url: HttpUrl, body: String, depth: String,
                            tooLarge: String, invalid: String, empty: String, missingIsEmpty: Boolean = false,
                            calendar: Boolean = false, limit: Int = 2 * 1024 * 1024): List<Pair<HttpUrl, (String, String) -> XmlNode?>> {
        request(account, method, url, body.toRequestBody("application/xml; charset=utf-8".toMediaType()),
            mapOf("Depth" to depth)).use { response ->
            if (response.code == 404 && missingIsEmpty) return emptyList()
            if (response.code != 207) fail(response.code, calendar)
            val responseBody = response.body ?: throw BackupException(empty)
            val bytes = responseBody.byteStream().use { it.readBytesLimited(limit, tooLarge) }
            try {
                val root = parseXml(bytes)
                if (root.namespaceURI != DAV || root.localName != "multistatus") throw IOException("Unexpected XML")
                return root.children("response").mapNotNull { item ->
                    val href = item.children("href").firstOrNull()?.textContent ?: return@mapNotNull null
                    val resolved = url.resolve(href.trim()) ?: return@mapNotNull null
                    if (resolved.scheme != url.scheme || resolved.host != url.host || resolved.port != url.port ||
                        resolved.username.isNotEmpty() || resolved.password.isNotEmpty() ||
                        resolved.query != null || resolved.fragment != null) return@mapNotNull null
                    val props = item.children("propstat").filter {
                        it.children("status").firstOrNull()?.textContent?.trim()?.split(Regex("\\s+"))?.getOrNull(1) == "200"
                    }.flatMap { it.children("prop") }
                    if (props.isEmpty()) return@mapNotNull null
                    resolved to { name: String, namespace: String -> props.firstNotNullOfOrNull { it.children(name, namespace).firstOrNull() } }
                }
            } catch (e: BackupException) {
                throw e
            } catch (_: Exception) {
                throw BackupException(invalid)
            }
        }
    }

    private operator fun ((String, String) -> XmlNode?).invoke(name: String): XmlNode? = this(name, DAV)

    private class XmlNode(val namespaceURI: String?, val localName: String, val attributes: Map<String, String> = emptyMap()) {
        val nodes = mutableListOf<XmlNode>()
        val text = StringBuilder()
        val textContent: String get() = text.toString()
        fun children(name: String, namespace: String = DAV) = nodes.filter { it.namespaceURI == namespace && it.localName == name }
    }

    private fun parseXml(bytes: ByteArray): XmlNode {
        // Android's pull parser works across our supported APIs. Never process DTDs or external entities.
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
            setInput(ByteArrayInputStream(bytes), null)
        }
        val stack = ArrayDeque<XmlNode>()
        var root: XmlNode? = null
        while (true) {
            when (parser.nextToken()) {
                XmlPullParser.END_DOCUMENT -> break
                XmlPullParser.DOCDECL -> throw IOException("DTD not allowed")
                XmlPullParser.START_TAG -> {
                    if (stack.size >= 32) throw IOException("XML nesting limit")
                    val node = XmlNode(parser.namespace, parser.name,
                        (0 until parser.attributeCount).associate { parser.getAttributeName(it) to parser.getAttributeValue(it) })
                    if (stack.isEmpty()) {
                        if (root != null) throw IOException("Multiple roots")
                        root = node
                    } else stack.last().nodes.add(node)
                    stack.addLast(node)
                }
                XmlPullParser.END_TAG -> stack.removeLast()
                XmlPullParser.TEXT, XmlPullParser.CDSECT, XmlPullParser.ENTITY_REF -> {
                    val text = parser.text ?: throw IOException("Unresolved entity")
                    stack.lastOrNull()?.text?.append(text)
                }
            }
        }
        if (stack.isNotEmpty()) throw IOException("Incomplete XML")
        return root ?: throw IOException("Empty XML")
    }

    private fun java.io.InputStream.readBytesLimited(limit: Int, tooLarge: String): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = read(buffer)
            if (n == -1) break
            if (output.size() + n > limit) throw ReplyTooLargeException(tooLarge)
            output.write(buffer, 0, n)
        }
        return output.toByteArray()
    }

    private fun request(account: NextcloudAccount, method: String, url: HttpUrl, body: RequestBody? = null,
                        headers: Map<String, String> = emptyMap(), limitMs: Long? = null, cancel: BackupTransfer? = null): Response {
        val request = Request.Builder().url(url).method(method, body)
            .header("Authorization", Credentials.basic(account.username, account.password, Charsets.UTF_8))
            .header("User-Agent", "Planner/0.1").header("Cache-Control", "no-store")
        headers.forEach { (name, value) -> request.header(name, value) }
        cancel?.check()
        val started = System.nanoTime()
        try {
            // [limitMs]: a backup transfer's own limit on the whole call, in place of the usual one.
            val call = http.newCall(request.build()).apply { if (limitMs != null) timeout().timeout(limitMs, TimeUnit.MILLISECONDS) }
            cancel?.begin(call)
            return call.execute()
        } catch (e: IOException) {
            // Cancelled, or over the transfer limit: said as such, not as a connection problem.
            cancel?.check()
            if (limitMs != null && overLimit(started, limitMs)) throw BackupException(TOO_LONG)
            throw BackupException(when (e) {
                is SSLException -> "Couldn't verify the server's HTTPS certificate. Check the address and server certificate."
                is SocketTimeoutException -> "Nextcloud timed out. Check your connection and try again."
                else -> "Couldn't reach Nextcloud. Check your connection and server address."
            })
        }
    }

    private fun overLimit(started: Long, limitMs: Long) = System.nanoTime() - started >= TimeUnit.MILLISECONDS.toNanos(limitMs)

    // [write]: a calendar file's PUT or DELETE, which Nextcloud may refuse for that file alone (RefusedException). A 403
    // there too (E-1: a private event of the owner in a shared calendar): a calendar this login can't write to at all
    // shows in the calendar list already, so it is that one file.
    private fun fail(code: Int, calendar: Boolean = false, write: Boolean = false): Nothing {
        if (write && (refused(code) || code == 403)) throw RefusedException(code, "Nextcloud refused a change (HTTP $code).")
        failWith(code, calendar)
    }

    private fun failWith(code: Int, calendar: Boolean): Nothing = throw BackupException(if (calendar) when (code) {
        401 -> "Nextcloud rejected the login. Check your username and app password."
        403 -> "Nextcloud denied access to your calendars (or doesn't allow adding events to this one)."
        404 -> "The calendar wasn't found on Nextcloud. It may have been deleted."
        in 300..399 -> "Nextcloud redirected the request. Enter its final HTTPS address."
        else -> "Nextcloud couldn't send the calendars (HTTP $code). Try again later."
    } else when (code) {
        401 -> "Nextcloud rejected the login. Check your username and app password."
        403 -> "Nextcloud denied access. Check this account's file permissions."
        404 -> "The Nextcloud folder or backup wasn't found. Check the address and username, or refresh the backup list."
        409 -> "The destination folder is missing or has changed. Try again."
        412 -> "The file changed on Nextcloud. Refresh the backup list and try again."
        413 -> "The backup is larger than your server's upload limit."
        423 -> "Nextcloud has locked the file. Try again shortly."
        507 -> "Nextcloud doesn't have enough available storage for this backup."
        in 300..399 -> "Nextcloud redirected the request. Enter its final HTTPS address."
        else -> "Nextcloud couldn't complete the request (HTTP $code). Try again."
    })

    // "#RRGGBB" or "#RRGGBBAA" (Nextcloud's calendar colours) as an opaque ARGB int.
    private fun parseColor(text: String): Int? = Regex("#([0-9A-Fa-f]{6})([0-9A-Fa-f]{2})?").matchEntire(text.trim())
        ?.let { (0xFF000000L or it.groupValues[1].toLong(16)).toInt() }

    companion object {
        internal const val TOO_LONG = "The backup took too long to transfer for its size and was stopped. Try again on a faster connection."
        private const val DAV = "DAV:"
        private const val CALDAV = "urn:ietf:params:xml:ns:caldav"
        private const val APPLE_ICAL = "http://apple.com/ns/ical/"
        private const val CALENDARSERVER = "http://calendarserver.org/ns/"
        private const val CALENDAR_PROPERTIES = """<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav" xmlns:a="http://apple.com/ns/ical/" xmlns:cs="http://calendarserver.org/ns/"><d:prop><d:resourcetype/><d:displayname/><a:calendar-color/><cs:getctag/><d:sync-token/><c:supported-calendar-component-set/><d:current-user-privilege-set/></d:prop></d:propfind>"""
        private val EMPTY = ByteArray(0).toRequestBody(null)
        // Compiled once (each sync asks for time ranges and names event files).
        private val RANGE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
        private val EVENT_NAME = Regex("[A-Za-z0-9@._-]{1,200}")
        private const val NOTE_PROPERTIES = """<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns"><d:prop><d:resourcetype/><d:getetag/><oc:fileid/></d:prop></d:propfind>"""
        private const val PROPERTIES = """<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getcontentlength/><d:getlastmodified/><d:getetag/></d:prop></d:propfind>"""
        private const val ETAG_PROPERTIES = """<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getetag/></d:prop></d:propfind>"""

        // E-6: a version marker as the calendar's listings give it. A front end that compresses a reply changes the one in
        // its ETag header (Apache's mod_deflate adds "-gzip", nginx makes it weak: W/"…") but never the one inside a
        // listing, and Nextcloud compares If-Match with its own. Taken off here, so every marker Planner keeps and sends
        // back is in the server's own form. Null for none.
        internal fun etag(raw: String?): String? {
            var text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            if (text.startsWith("W/")) text = text.drop(2)
            val quoted = text.length >= 2 && text.startsWith('"') && text.endsWith('"')
            var core = if (quoted) text.substring(1, text.length - 1) else text
            COMPRESSED.firstOrNull { core.endsWith(it) && core.length > it.length }?.let { core = core.removeSuffix(it) }
            return if (quoted) "\"$core\"" else core
        }
        private val COMPRESSED = listOf("-gzip", "-br", "-deflate")

        // Whether two markers name the same version (quotes, weakness and a compression suffix aside). Unknown is never
        // the same: a write needs a version to be conditional.
        internal fun sameEtag(a: String?, b: String?): Boolean {
            val x = etag(a)?.removeSurrounding("\"") ?: return false
            return x == etag(b)?.removeSurrounding("\"")
        }
        // Paths compared by their decoded segments: Nextcloud writes some characters percent-encoded that OkHttp leaves
        // as they are (an apostrophe in a username is %27 there, ' here), and both name the same folder.
        // A reply that refuses this one request (what was sent), not the login, the calendar, or the server as a whole:
        // a client error other than login (401), access (403), not found (404), method (405), proxy login (407), timeout
        // (408), version (412), lock (423) and too many requests (429).
        internal fun refused(code: Int) = code in 400..499 && code !in setOf(401, 403, 404, 405, 407, 408, 412, 423, 429)

        private fun segments(url: HttpUrl) = url.pathSegments.dropLastWhile { it.isEmpty() }
        internal fun samePath(url: HttpUrl, other: HttpUrl) = segments(url) == segments(other)
        // [url] is somewhere inside the folder [folder] (not the folder itself).
        internal fun inside(url: HttpUrl, folder: HttpUrl): Boolean {
            val base = segments(folder); val path = segments(url)
            return path.size > base.size && path.subList(0, base.size) == base
        }
        private fun validName(name: String) = name.startsWith("Planner-backup-") && name.endsWith(".zip") &&
            name.length <= 240 && name.none { it == '/' || it == '\\' || it.isISOControl() }
    }
}
