package com.example.itinerary

import okhttp3.Credentials
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** A small Nextcloud for the two-way sync tests: calendars "Planner" (kept in sync) and "Work" in [home], event files
 *  with ETags, a ctag that changes with them, calendar-query (from the window start on) and multiget REPORTs, GET, and
 *  PUT/DELETE honouring If-Match / If-None-Match. Files in Work get the fixed ETag "w-orig". [code] fails everything;
 *  [loseReplies] saves that many PUTs and then drops the connection instead of answering (a reply lost on the way).
 *  [queries] records each calendar-query's start date (yyyyMMdd); one starting before [refuseBefore] fails with a 500;
 *  [onQuery] runs first. With [taskList], a third calendar "Tasks" that holds tasks only (as a Nextcloud Tasks list does);
 *  calendar-queries return only the kind they ask for (VEVENT or VTODO). */
class FakeCalDav(private val home: String, private val user: String, private val password: String, private val taskList: Boolean = false) : Dispatcher() {
    val synced = "${home}planner/"
    val other = "${home}work/"
    val tasks = "${home}tasks/"
    val files = ConcurrentHashMap<String, Pair<String, String>>()
    val requests = CopyOnWriteArrayList<Triple<String, String, String?>>()
    @Volatile var code: Int? = null
    @Volatile var loseReplies = 0
    val queries = CopyOnWriteArrayList<String>()
    @Volatile var refuseBefore: String? = null
    // Called with each calendar-query's start date before it's answered (something happening while a sync runs).
    @Volatile var onQuery: ((String) -> Unit)? = null
    // A PUT whose body this returns a code for is answered with it and not stored (Nextcloud refusing one file).
    @Volatile var refuse: ((String) -> Int?)? = null
    @Volatile private var version = 0
    fun bump() { version++ }
    fun put(path: String, body: String) { files[path] = (if (path.startsWith(other)) "\"w-orig\"" else "\"s${++version}\"") to body }
    fun edit(path: String, change: (String) -> String) { files[path] = "\"s${++version}\"" to change(files[path]!!.second) }
    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.requestUrl!!.encodedPath
        val body = request.body.readUtf8()
        requests += Triple(request.method.orEmpty(), path, request.getHeader("If-Match"))
        code?.let { return MockResponse().setResponseCode(it) }
        if (request.getHeader("Authorization") != Credentials.basic(user, password, Charsets.UTF_8)) return MockResponse().setResponseCode(401)
        fun ms(xml: String) = MockResponse().setResponseCode(207).setBody("""<?xml version="1.0"?><d:multistatus xmlns:d="DAV:" xmlns:cal="urn:ietf:params:xml:ns:caldav" xmlns:cs="http://calendarserver.org/ns/">$xml</d:multistatus>""")
        fun entry(p: String, v: Pair<String, String>, data: Boolean) = """<d:response><d:href>$p</d:href><d:propstat><d:prop><d:getetag>${v.first}</d:getetag>""" +
            (if (data) "<cal:calendar-data>${v.second.replace("&", "&amp;").replace("<", "&lt;")}</cal:calendar-data>" else "<d:resourcetype/>") +
            """</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"""
        val current = files[path]
        return when (request.method) {
            "PROPFIND" -> when (path) {
                home -> ms((listOf("planner" to "Planner", "work" to "Work") + listOfNotNull(("tasks" to "Tasks").takeIf { taskList })).joinToString("") { (slug, name) ->
                    val components = if (slug == "tasks") """<cal:supported-calendar-component-set><cal:comp name="VTODO"/></cal:supported-calendar-component-set>""" else ""
                    """<d:response><d:href>$home$slug/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/><cal:calendar/></d:resourcetype><d:displayname>$name</d:displayname><cs:getctag>$slug-$version-${files.size}</cs:getctag>$components<d:current-user-privilege-set><d:privilege><d:write/></d:privilege></d:current-user-privilege-set></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"""
                })
                synced, other, tasks -> ms("""<d:response><d:href>$path</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>""" +
                    files.filterKeys { it.startsWith(path) }.entries.joinToString("") { entry(it.key, it.value, false) })
                else -> if (current != null) ms(entry(path, current, false)) else MockResponse().setResponseCode(404)
            }
            "REPORT" -> {
                val wanted = Regex("<d:href>([^<]+)</d:href>").findAll(body).map { it.groupValues[1] }.toSet()
                // calendar-query: events from the window start on (or repeating); multiget: the listed files.
                val start = Regex("start=\"(\\d{8})").find(body)?.groupValues?.get(1)
                if (start != null) { queries += start; onQuery?.invoke(start); if (refuseBefore?.let { start < it } == true) return MockResponse().setResponseCode(500) }
                val todo = body.contains("name=\"VTODO\"")
                ms(files.filterKeys { it.startsWith(path) }.filter { (p, v) ->
                    if (body.contains("calendar-multiget")) p in wanted
                    // A calendar-query returns only the kind it asks for.
                    else if (todo != v.second.contains("BEGIN:VTODO")) false
                    else todo || v.second.contains("RRULE") || start == null || (Regex("DTSTART[^:]*:(\\d{8})").find(v.second)?.groupValues?.get(1) ?: "0") >= start
                }.entries.joinToString("") { entry(it.key, it.value, true) })
            }
            "GET" -> current?.let { MockResponse().setResponseCode(200).setHeader("ETag", it.first).setBody(it.second) } ?: MockResponse().setResponseCode(404)
            "PUT" -> {
                refuse?.invoke(body)?.let { return MockResponse().setResponseCode(it) }
                val ifMatch = request.getHeader("If-Match"); val ifNone = request.getHeader("If-None-Match")
                when {
                    ifNone == "*" && current != null -> MockResponse().setResponseCode(412)
                    ifMatch != null && current == null -> MockResponse().setResponseCode(404)
                    ifMatch != null && ifMatch != current!!.first -> MockResponse().setResponseCode(412)
                    else -> { val etag = "\"s${++version}\""; files[path] = etag to body
                        if (loseReplies > 0) { loseReplies--; return MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AFTER_REQUEST) }
                        MockResponse().setResponseCode(if (current == null) 201 else 204).setHeader("ETag", etag) }
                }
            }
            "DELETE" -> when {
                current == null -> MockResponse().setResponseCode(404)
                request.getHeader("If-Match") != null && request.getHeader("If-Match") != current.first -> MockResponse().setResponseCode(412)
                else -> { files.remove(path); version++; MockResponse().setResponseCode(204) }
            }
            else -> MockResponse().setResponseCode(405)
        }
    }
}
