package com.example.itinerary.data

import java.io.File
import java.net.URLEncoder

/**
 * Report a bug (wish list #6): a new issue on Planner's public GitHub, opened in the browser already filled in. Nothing is
 * sent by Planner itself: the person reads the report in Planner, then submits it on GitHub with their own account. Only
 * what they wrote, the app and Android versions and the phone model go in, and the last crash only if they tick it;
 * never events, tasks, notes or Nextcloud details.
 */
object BugReport {
    const val NEW_ISSUE = "https://github.com/baslawson/Planner/issues/new"
    // Browsers and GitHub refuse very long links; the crash is shortened to keep under this.
    const val MAX_URL = 7_500

    fun title(description: String): String =
        description.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }?.take(80) ?: "Bug report"

    fun body(description: String, version: String, android: String, device: String, crash: String?): String = buildString {
        append(description.trim().ifEmpty { "(No description)" })
        append("\n\n---\nPlanner $version · Android $android · $device")
        if (crash != null) append("\n\nLast crash:\n```\n").append(crash.trim()).append("\n```")
    }

    /** The link that opens the new issue; a crash too long for a link is cut from its end. */
    fun url(description: String, version: String, android: String, device: String, crash: String?): String {
        fun build(c: String?) = NEW_ISSUE + "?title=" + encode(title(description)) + "&body=" + encode(body(description, version, android, device, c))
        var cut = crash
        var link = build(cut)
        while (link.length > MAX_URL && !cut.isNullOrEmpty()) {
            cut = cut.take((cut.length * 3 / 4).coerceAtMost(cut.length - 100).coerceAtLeast(0)).let { if (it.isEmpty()) null else "$it\n…" }
            link = build(cut)
        }
        return link
    }

    private fun encode(text: String) = URLEncoder.encode(text, "UTF-8").replace("+", "%20")
}

/**
 * The last time Planner crashed, kept on this phone only (files/last-crash.txt), for a bug report to include if the
 * person ticks it. Written by an uncaught-exception handler just before Android ends the app; the next crash replaces it.
 */
class CrashLog(private val dir: File) {
    data class Crash(val at: Long, val text: String)

    private val file get() = File(dir, "last-crash.txt")

    fun write(at: Long, version: String, error: Throwable) {
        val trace = android.util.Log.getStackTraceString(error).take(MAX_TEXT)
        runCatching { file.writeText("$at\n$version\n$trace") }
    }

    fun read(): Crash? = runCatching {
        val lines = file.takeIf { it.isFile }?.readText()?.split('\n', limit = 3) ?: return null
        Crash(lines[0].toLong(), "Planner ${lines[1]}\n${lines.getOrElse(2) { "" }}".trim())
    }.getOrNull()

    fun clear() { runCatching { file.delete() } }

    companion object {
        const val MAX_TEXT = 12_000

        /** Keeps the last crash, then lets Android end the app as it would have. */
        fun install(dir: File, version: () -> String) {
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, error ->
                runCatching { CrashLog(dir).write(System.currentTimeMillis(), version(), error) }
                previous?.uncaughtException(thread, error)
            }
        }
    }
}
