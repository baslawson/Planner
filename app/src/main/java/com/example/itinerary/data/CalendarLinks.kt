package com.example.itinerary.data

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

// Calendar sync step 4: calendars subscribed to by link (public holidays, a club's fixtures, a calendar's "secret
// address"). Only https (webcal:// is the same address over https). A link can contain a private token, so messages
// name only its host, never the whole address.
object CalendarLinks {
    // The address to download, from what the user pasted; throws with a message for the user.
    fun normalize(input: String): HttpUrl {
        val text = input.trim()
        val lower = text.lowercase()
        require(!lower.startsWith("http://")) { "Use an https:// or webcal:// link. Plain http:// links can be read or changed on the way." }
        val https = when {
            lower.startsWith("webcal://") -> "https://" + text.substring("webcal://".length)
            lower.startsWith("webcals://") -> "https://" + text.substring("webcals://".length)
            else -> text
        }
        val url = requireNotNull(https.toHttpUrlOrNull()) { "Paste the calendar's link, starting with https:// or webcal://." }
        require(url.isHttps) { "Use an https:// or webcal:// link." }
        require(url.username.isEmpty() && url.password.isEmpty()) { "Use a link without a username or password in it." }
        return url.newBuilder().fragment(null).build()
    }
}

// Downloads a subscribed calendar. [etag]/[lastModified] from the last download make an unchanged calendar cost almost
// nothing: the server answers "not modified".
class CalendarLinkClient(client: OkHttpClient = OkHttpClient()) {
    sealed class Result {
        object NotModified : Result()
        class Fetched(val text: String, val etag: String?, val lastModified: String?) : Result()
    }

    private val http = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS).callTimeout(2, TimeUnit.MINUTES).build()

    fun fetch(link: HttpUrl, etag: String? = null, lastModified: String? = null): Result {
        var url = link
        val host = link.host
        // Redirects are followed by hand: at most 5, and never away from https.
        repeat(6) { hop ->
            val request = Request.Builder().url(url).get().header("User-Agent", "Planner/0.1").header("Accept", "text/calendar, */*")
            if (hop == 0 || url.host == host) {
                etag?.let { request.header("If-None-Match", it) }
                lastModified?.let { request.header("If-Modified-Since", it) }
            }
            val response = try { http.newCall(request.build()).execute() }
                catch (_: SSLException) { throw BackupException("Couldn't verify $host's HTTPS certificate.") }
                catch (_: SocketTimeoutException) { throw BackupException("$host took too long to answer. Try again later.") }
                catch (_: IOException) { throw BackupException("Couldn't reach $host. Check your connection.") }
            response.use {
                when (it.code) {
                    304 -> return Result.NotModified
                    200 -> {
                        val body = it.body ?: throw BackupException("$host sent an empty calendar.")
                        val bytes = body.byteStream().use { input ->
                            val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                            while (true) {
                                val n = input.read(buffer); if (n < 0) break
                                if (out.size() + n > CalendarFileImport.MAX_BYTES) throw BackupException("This calendar is too large (maximum 10 MB).")
                                out.write(buffer, 0, n)
                            }
                            out.toByteArray()
                        }
                        return Result.Fetched(bytes.toString(body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8),
                            it.header("ETag"), it.header("Last-Modified"))
                    }
                    301, 302, 303, 307, 308 -> {
                        val next = it.header("Location")?.let(url::resolve) ?: throw BackupException("$host sent a broken redirect.")
                        if (!next.isHttps) throw BackupException("$host redirected to an address that isn't https, so it wasn't followed.")
                        url = next
                    }
                    401, 403 -> throw BackupException("$host refused the link. It may be private or no longer valid.")
                    404, 410 -> throw BackupException("$host no longer has this calendar.")
                    else -> throw BackupException("$host couldn't send the calendar (HTTP ${it.code}). Try again later.")
                }
            }
        }
        throw BackupException("$host redirected too many times.")
    }
}
