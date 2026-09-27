package com.example.itinerary.data

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

// Blocking transport; callers run on IO. TLS verification stays enabled, redirects are never followed
// with credentials. Backups are confined to the selected folder under this account's Files root.
class NextcloudClient(client: OkHttpClient = OkHttpClient()) {
    private val http = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS).writeTimeout(45, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.MINUTES).build()

    fun checkConnection(account: NextcloudAccount) {
        val root = account.filesRoot
        val entries = properties(account, root, "0")
        if (entries.none { it.url.encodedPath.trimEnd('/') == root.encodedPath.trimEnd('/') && it.collection }) {
            throw BackupException("The server didn't return a Nextcloud files folder. Check the address and username.")
        }
    }

    fun list(account: NextcloudAccount): List<NextcloudBackup> {
        val entries = properties(account, account.folder, "1", missingIsEmpty = true)
        return entries.mapNotNull { entry ->
            if (entry.collection || entry.url.encodedPath.trimEnd('/') == account.folder.encodedPath.trimEnd('/')) return@mapNotNull null
            val name = entry.url.pathSegments.last()
            if (!validName(name) || entry.url != fileUrl(account, name)) return@mapNotNull null
            NextcloudBackup(name, entry.size, entry.modified, entry.etag)
        }.distinctBy { it.name }.sortedByDescending { it.name }
    }

    fun upload(account: NextcloudAccount, file: File): String {
        // Create parents in order; MKCOL does not create missing intermediate directories.
        var directory = account.filesRoot
        for (segment in account.folderPath.split('/')) {
            directory = directory.newBuilder().addPathSegment(segment).addPathSegment("").build()
            request(account, "MKCOL", directory, EMPTY).use { response ->
                if (response.code != 201 && response.code != 405) fail(response.code)
            }
            // A 405 may mean a file occupies this name. Never overwrite it or continue through it.
            if (properties(account, directory, "0").none { it.collection &&
                    it.url.encodedPath.trimEnd('/') == directory.encodedPath.trimEnd('/') }) {
                throw BackupException("Part of the backup path exists but isn't a folder. Choose another path.")
            }
        }
        val id = UUID.randomUUID().toString()
        val timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss").withZone(ZoneOffset.UTC).format(Instant.now())
        val name = "Planner-backup-${timestamp}_${id}.zip"
        val temporary = fileUrl(account, ".upload-$id")
        try {
            request(account, "PUT", temporary, file.asRequestBody("application/zip".toMediaType()),
                mapOf("If-None-Match" to "*")).use { if (it.code != 201 && it.code != 204) fail(it.code) }
            request(account, "MOVE", temporary, null,
                mapOf("Destination" to fileUrl(account, name).toString(), "Overwrite" to "F"))
                .use { if (it.code != 201 && it.code != 204) fail(it.code) }
            return name
        } finally {
            // Only our random temporary object is eligible for cleanup; completed backups are never deleted.
            runCatching { request(account, "DELETE", temporary).close() }
        }
    }

    fun download(account: NextcloudAccount, backup: NextcloudBackup, target: File) {
        if (!validName(backup.name)) throw BackupException("Invalid backup filename.")
        val headers = backup.etag?.let { mapOf("If-Match" to it) }.orEmpty()
        try {
            request(account, "GET", fileUrl(account, backup.name), headers = headers).use { response ->
                if (response.code != 200) fail(response.code)
                val body = response.body ?: throw BackupException("The backup download was empty.")
                if (body.contentLength() > target.parentFile!!.usableSpace) {
                    throw BackupException("There isn't enough free space on this device to download the backup.")
                }
                val copied = body.byteStream().use { input -> target.outputStream().use { input.copyTo(it) } }
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

    private fun properties(account: NextcloudAccount, url: HttpUrl, depth: String, missingIsEmpty: Boolean = false): List<Entry> {
        request(account, "PROPFIND", url, PROPERTIES.toRequestBody("application/xml; charset=utf-8".toMediaType()),
            mapOf("Depth" to depth)).use { response ->
            if (response.code == 404 && missingIsEmpty) return emptyList()
            if (response.code != 207) fail(response.code)
            val body = response.body ?: throw BackupException("The server returned an empty file list.")
            val bytes = body.byteStream().use { it.readBytesLimited(2 * 1024 * 1024) }
            try {
                val root = parseXml(bytes)
                if (root.namespaceURI != DAV || root.localName != "multistatus") throw IOException("Unexpected XML")
                return root.children("response").mapNotNull { item ->
                    val href = item.children("href").firstOrNull()?.textContent ?: return@mapNotNull null
                    val resolved = url.resolve(href) ?: return@mapNotNull null
                    if (resolved.scheme != url.scheme || resolved.host != url.host || resolved.port != url.port ||
                        resolved.username.isNotEmpty() || resolved.password.isNotEmpty() ||
                        resolved.query != null || resolved.fragment != null) return@mapNotNull null
                    val props = item.children("propstat").filter {
                        it.children("status").firstOrNull()?.textContent?.trim()?.split(Regex("\\s+"))?.getOrNull(1) == "200"
                    }.flatMap { it.children("prop") }
                    fun prop(name: String): XmlNode? = props.firstNotNullOfOrNull { it.children(name).firstOrNull() }
                    if (props.isEmpty()) return@mapNotNull null
                    Entry(resolved, prop("resourcetype")?.children("collection")?.isNotEmpty() == true,
                        prop("getcontentlength")?.textContent?.toLongOrNull()?.takeIf { it >= 0 },
                        prop("getlastmodified")?.textContent, prop("getetag")?.textContent)
                }
            } catch (e: BackupException) {
                throw e
            } catch (_: Exception) {
                throw BackupException("The server returned an invalid file list. Check that this is your Nextcloud address.")
            }
        }
    }

    private class XmlNode(val namespaceURI: String?, val localName: String) {
        val nodes = mutableListOf<XmlNode>()
        val text = StringBuilder()
        val textContent: String get() = text.toString()
        fun children(name: String) = nodes.filter { it.namespaceURI == DAV && it.localName == name }
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
                    val node = XmlNode(parser.namespace, parser.name)
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

    private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = read(buffer)
            if (n == -1) break
            if (output.size() + n > limit) throw BackupException("The backup folder is too large to list. Move older backups to another folder.")
            output.write(buffer, 0, n)
        }
        return output.toByteArray()
    }

    private fun request(account: NextcloudAccount, method: String, url: HttpUrl, body: RequestBody? = null,
                        headers: Map<String, String> = emptyMap()): Response {
        val request = Request.Builder().url(url).method(method, body)
            .header("Authorization", Credentials.basic(account.username, account.password, Charsets.UTF_8))
            .header("User-Agent", "Planner/0.1").header("Cache-Control", "no-store")
        headers.forEach { (name, value) -> request.header(name, value) }
        try {
            return http.newCall(request.build()).execute()
        } catch (_: SSLException) {
            throw BackupException("Couldn't verify the server's HTTPS certificate. Check the address and server certificate.")
        } catch (_: SocketTimeoutException) {
            throw BackupException("Nextcloud timed out. Check your connection and try again.")
        } catch (_: IOException) {
            throw BackupException("Couldn't reach Nextcloud. Check your connection and server address.")
        }
    }

    private fun fail(code: Int): Nothing = throw BackupException(when (code) {
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

    companion object {
        private const val DAV = "DAV:"
        private val EMPTY = ByteArray(0).toRequestBody(null)
        private const val PROPERTIES = """<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getcontentlength/><d:getlastmodified/><d:getetag/></d:prop></d:propfind>"""
        private fun validName(name: String) = name.startsWith("Planner-backup-") && name.endsWith(".zip") &&
            name.length <= 240 && name.none { it == '/' || it == '\\' || it.isISOControl() }
    }
}
