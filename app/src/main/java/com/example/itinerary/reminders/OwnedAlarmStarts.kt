package com.example.itinerary.reminders

import android.content.Context
import android.os.Bundle
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File

internal const val EXTRA_OWNER_START = "alarm_owner_start"

/** Pending and active starts belong to this phone, survive process death, and are readable before first unlock. */
internal object OwnedAlarmStarts {
    private fun file(context: Context) = AtomicFile(File(context.createDeviceProtectedStorageContext().noBackupFilesDir, "ring-starts"))
    private fun read(context: Context): JSONObject = file(context).let { f ->
        if (!f.baseFile.exists()) JSONObject() else JSONObject(f.readFully().toString(Charsets.UTF_8))
    }
    private fun write(context: Context, value: JSONObject) {
        val f = file(context)
        f.baseFile.parentFile!!.mkdirs()
        val out = f.startWrite()
        try { out.write(value.toString().toByteArray(Charsets.UTF_8)); f.finishWrite(out) }
        catch (e: Exception) { f.failWrite(out); throw e }
    }

    @Synchronized fun reserve(context: Context, kind: String, id: String): String {
        val token = RingToken.new()
        write(context, read(context).put("$kind:$id", token))
        return token
    }

    @Synchronized fun cancel(context: Context, kind: String, id: String, token: String? = null) {
        val value = read(context)
        val key = "$kind:$id"
        if (value.has(key) && (token == null || value.optString(key) == token)) {
            value.remove(key)
            write(context, value)
        }
    }

    @Synchronized fun finish(context: Context, extras: Bundle?) {
        val kind = extras?.getString(EXTRA_OWNER_KIND) ?: return
        val id = extras.getString(EXTRA_OWNER_ID) ?: return
        val token = extras.getString(EXTRA_OWNER_START) ?: return
        runCatching { cancel(context, kind, id, token) }
            .onFailure { android.util.Log.w("OwnedAlarmStarts", "Couldn't finish ringing ownership", it) }
    }

    /** Hold ownership through startup so cancellation cannot land between validation and playback. */
    @Synchronized fun start(context: Context, extras: Bundle?, play: () -> Unit): Boolean {
        val kind = extras?.getString(EXTRA_OWNER_KIND)
        if (kind != null) {
            val id = extras.getString(EXTRA_OWNER_ID) ?: return false
            val token = extras.getString(EXTRA_OWNER_START) ?: return false
            val current = try { read(context).optString("$kind:$id", null) }
            catch (e: Exception) {
                android.util.Log.w("OwnedAlarmStarts", "Couldn't validate ringing owner", e)
                return false
            }
            if (!RingToken.matches(current, token)) return false
        }
        play()
        return true
    }
}
