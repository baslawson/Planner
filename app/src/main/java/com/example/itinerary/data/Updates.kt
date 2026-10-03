package com.example.itinerary.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

// Versions as Planner writes them ("0.0.14", tags "v0.0.14"): numbers compared part by part, so 0.0.14 is after 0.0.9.
object AppVersion {
    fun parts(version: String): List<Int>? = version.trim().removePrefix("v").removePrefix("V").split('.')
        .map { it.toIntOrNull() ?: return null }.takeIf { it.isNotEmpty() && it.all { n -> n >= 0 } }

    // Whether [candidate] is a later version than [installed]; anything unreadable is not.
    fun newer(candidate: String, installed: String): Boolean {
        val a = parts(candidate) ?: return false
        val b = parts(installed) ?: return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    // A ".sha256" file as the releases carry it ("<64 hex>  Planner.apk"): the hash, lower case, or null.
    fun sha256Of(file: String): String? = file.trim().split(Regex("\\s+")).firstOrNull()?.lowercase()
        ?.takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }
}

// A release on GitHub with an APK and its SHA-256 file.
data class AppRelease(val version: String, val notes: String, val apkUrl: String, val shaUrl: String, val page: String)

class UpdateException(message: String) : Exception(message)

// GitHub's public API for Planner's releases: the latest one, and its files. No login.
class ReleaseApi(private val http: OkHttpClient, private val base: HttpUrl = "https://api.github.com/repos/baslawson/Planner/".toHttpUrl()) {
    // The latest published release (GitHub leaves drafts and pre-releases out), or null when it has no Planner.apk
    // with its .sha256.
    fun latest(): AppRelease? {
        val request = Request.Builder().url(base.resolve("releases/latest")!!).header("Accept", "application/vnd.github+json").build()
        val body = http.newCall(request).execute().use { response ->
            when (response.code) {
                200 -> response.body?.string().orEmpty()
                404 -> return null // no release yet
                403, 429 -> throw UpdateException("GitHub is busy. Try again later.")
                else -> throw UpdateException("GitHub couldn't be reached (${response.code}). Try again later.")
            }
        }
        return parse(body)
    }

    fun text(url: String): String = http.newCall(Request.Builder().url(url).build()).execute().use { response ->
        if (response.code != 200) throw UpdateException("The update couldn't be downloaded (${response.code}).")
        response.body?.string().orEmpty()
    }

    // Streams [url] into [to], reporting the share done (0..1, or -1 when the size isn't known).
    suspend fun download(url: String, to: File, progress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (response.code != 200) throw UpdateException("The update couldn't be downloaded (${response.code}).")
            val body = response.body ?: throw UpdateException("The update couldn't be downloaded.")
            val total = body.contentLength()
            to.outputStream().use { out ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024); var done = 0L
                    while (true) {
                        ensureActive()
                        val n = input.read(buffer); if (n < 0) break
                        out.write(buffer, 0, n); done += n
                        progress(if (total > 0) done.toFloat() / total else -1f)
                    }
                }
            }
        }
    }

    companion object {
        fun parse(json: String): AppRelease? {
            val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
            if (o.optBoolean("draft") || o.optBoolean("prerelease")) return null
            val version = o.optString("tag_name").takeIf { AppVersion.parts(it) != null } ?: return null
            val assets = o.optJSONArray("assets") ?: return null
            fun url(name: String) = (0 until assets.length()).map { assets.getJSONObject(it) }
                .firstOrNull { it.optString("name") == name }?.optString("browser_download_url")?.takeIf { it.startsWith("https://") }
            return AppRelease(version.removePrefix("v").removePrefix("V"), o.optString("body").take(20_000),
                url("Planner.apk") ?: return null, url("Planner.apk.sha256") ?: return null, o.optString("html_url"))
        }
    }
}

// "Check for updates" (Settings → Updates): at most once a day when Planner starts, or on Check now. A newer release
// is only offered: nothing downloads until Update now (or, with "Download updates automatically", in the background),
// and nothing installs without Android's own confirmation. Only the release app updates itself; the debug and test
// builds are other apps that a GitHub APK can't replace.
class Updates(
    private val prefs: SharedPreferences,
    // A var only so UI tests can point it at a local test server.
    @Volatile internal var api: ReleaseApi,
    private val installed: String,
    private val folder: File,
    // Whether this build can update itself from GitHub (the release app); a var so tests can turn it on.
    @Volatile internal var supported: Boolean,
    private val now: () -> Long = System::currentTimeMillis,
) {
    sealed interface State {
        data object Idle : State
        data object Checking : State
        data class UpToDate(val at: Long) : State
        data class Available(val release: AppRelease) : State
        data class Downloading(val release: AppRelease, val progress: Float, val asked: Boolean) : State
        data class Ready(val release: AppRelease, val file: File) : State
        data class Failed(val message: String, val release: AppRelease? = null) : State
    }

    // A download already checked survives Planner being restarted (Android does that when "install unknown apps" is
    // allowed for it): it's offered to install again, without downloading it a second time.
    private val _state = MutableStateFlow<State>(restored() ?: State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()
    // The pop-up was closed with Later (until Planner is started again) or after a check from Settings.
    private val _dismissed = MutableStateFlow(false)
    val dismissed: StateFlow<Boolean> = _dismissed.asStateFlow()

    private val _checkOnStart = MutableStateFlow(prefs.getBoolean(KEY_ON_START, true))
    val checkOnStart: StateFlow<Boolean> = _checkOnStart.asStateFlow()
    private val _autoDownload = MutableStateFlow(prefs.getBoolean(KEY_AUTO_DOWNLOAD, false))
    val autoDownload: StateFlow<Boolean> = _autoDownload.asStateFlow()
    val lastChecked: Long get() = prefs.getLong(KEY_LAST, 0L)
    val installedVersion: String get() = installed

    private val lock = Mutex()

    fun setCheckOnStart(on: Boolean) { prefs.edit().putBoolean(KEY_ON_START, on).apply(); _checkOnStart.value = on }
    fun setAutoDownload(on: Boolean) { prefs.edit().putBoolean(KEY_AUTO_DOWNLOAD, on).apply(); _autoDownload.value = on }

    // When Planner starts: a check if it's switched on and the last one was a day or more ago.
    suspend fun checkIfDue() {
        if (!supported || !_checkOnStart.value || now() - lastChecked < DAY_MS) return
        check(fromSettings = false)
    }

    // [fromSettings]: Check now — a skipped version is offered again and "up to date" is said.
    suspend fun check(fromSettings: Boolean = true) {
        if (!supported) return
        if (!lock.tryLock()) return
        try {
            if (_state.value is State.Downloading) return
            _dismissed.value = false
            _state.value = State.Checking
            val release = try {
                withContext(Dispatchers.IO) { api.latest() }
            } catch (e: CancellationException) { _state.value = State.Idle; throw e }
            catch (e: Exception) {
                // At start-up a failed check stays quiet (no pop-up); the next start tries again.
                _state.value = if (fromSettings) State.Failed((e as? UpdateException)?.message ?: "Couldn't check for updates. Check the connection.") else State.Idle
                return
            }
            prefs.edit().putLong(KEY_LAST, now()).apply()
            if (release == null || !AppVersion.newer(release.version, installed) ||
                (!fromSettings && prefs.getString(KEY_SKIPPED, null) == release.version)) {
                _state.value = State.UpToDate(now()); cleanUp(); return
            }
            _state.value = State.Available(release)
        } finally { lock.unlock() }
        (_state.value as? State.Available)?.let { if (_autoDownload.value) download(it.release, asked = false) }
    }

    // Downloads the APK, then compares it with the release's SHA-256: a file that doesn't match is deleted, never offered.
    // [asked]: Update now was tapped (the pop-up shows the progress); otherwise a background download, which only asks
    // once it's ready to install.
    suspend fun download(release: AppRelease, asked: Boolean = true) {
        if (!lock.tryLock()) return
        try {
            _state.value = State.Downloading(release, 0f, asked)
            folder.mkdirs()
            val file = File(folder, "Planner-${release.version}.apk")
            val part = File(folder, "Planner-${release.version}.apk.part")
            try {
                val expected = AppVersion.sha256Of(withContext(Dispatchers.IO) { api.text(release.shaUrl) })
                    ?: throw UpdateException("The update's checksum couldn't be read, so it wasn't downloaded.")
                api.download(release.apkUrl, part) { p -> _state.value = State.Downloading(release, p, asked) }
                if (sha256(part) != expected) { part.delete(); throw UpdateException("The download didn't match the release's checksum, so it was deleted. Try again.") }
                file.delete()
                if (!part.renameTo(file)) throw UpdateException("The update couldn't be saved.")
                prefs.edit().putString(KEY_READY, JSONObject().put("version", release.version).put("notes", release.notes)
                    .put("apk", release.apkUrl).put("sha", release.shaUrl).put("page", release.page).put("hash", expected).toString()).apply()
                _state.value = State.Ready(release, file)
            } catch (e: CancellationException) {
                part.delete(); _state.value = State.Available(release); throw e
            } catch (e: Exception) {
                part.delete()
                _state.value = State.Failed((e as? UpdateException)?.message ?: "The update couldn't be downloaded. Check the connection and try again.", release)
            }
        } finally { lock.unlock() }
    }

    fun later() { _dismissed.value = true }

    fun skip(release: AppRelease) {
        prefs.edit().putString(KEY_SKIPPED, release.version).apply()
        _dismissed.value = true
        _state.value = State.Idle
        cleanUp()
    }

    // Downloads of older (or already installed) versions are of no use.
    private fun cleanUp() {
        val keep = (_state.value as? State.Ready)?.file
        if (keep == null) prefs.edit().remove(KEY_READY).apply()
        folder.listFiles()?.forEach { if (it != keep) it.delete() }
    }

    // The checked download from before a restart, if it's still there and still newer than what's installed (after the
    // update itself, it isn't: then it's deleted).
    private fun restored(): State? {
        val o = prefs.getString(KEY_READY, null)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
        val release = AppRelease(o.optString("version"), o.optString("notes"), o.optString("apk"), o.optString("sha"), o.optString("page"))
        val file = File(folder, "Planner-${release.version}.apk")
        if (!supported || !AppVersion.newer(release.version, installed) || !file.isFile) {
            prefs.edit().remove(KEY_READY).apply(); folder.listFiles()?.forEach { it.delete() }; return null
        }
        return State.Ready(release, file)
    }

    private suspend fun sha256(file: File): String = withContext(Dispatchers.IO) {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(64 * 1024); while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val DAY_MS = 24 * 60 * 60 * 1000L
        private const val KEY_ON_START = "check_on_start"
        private const val KEY_AUTO_DOWNLOAD = "auto_download"
        private const val KEY_LAST = "last_checked"
        private const val KEY_SKIPPED = "skipped_version"
        private const val KEY_READY = "ready"
        // The release app's id: only it is the app the GitHub APK updates.
        const val RELEASE_ID = "io.github.baslawson.planner"
        fun prefs(context: Context): SharedPreferences = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
    }
}
