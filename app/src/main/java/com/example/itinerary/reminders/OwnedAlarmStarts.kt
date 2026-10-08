package com.example.itinerary.reminders

import android.content.Context
import android.os.Bundle
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File

internal const val EXTRA_OWNER_START = "alarm_owner_start"
// Hunt 24 D1: an event's or bill's ringing start, reserved as "event:<reminder id>" (ReminderReceiver). Not an owner kind,
// which the service takes for a task or note. An alarm without one (the Settings test, one that couldn't reserve) rings as
// before.
internal const val EXTRA_EVENT_START = "alarm_event_start"

/** Pending and active starts belong to this phone, survive process death, and are readable before first unlock. */
internal object OwnedAlarmStarts {
    private fun file(context: Context) = AtomicFile(File(context.createDeviceProtectedStorageContext().noBackupFilesDir, "ring-starts"))
    // A14-2: readFully (not a check of the file itself) so AtomicFile brings back its backup after a write cut short. A file
    // that still can't be read counts as empty: its starts are refused, and the next reservation writes it afresh.
    private fun read(context: Context): JSONObject {
        val bytes = try { file(context).readFully() } catch (_: java.io.FileNotFoundException) { return JSONObject() }
        return try { JSONObject(bytes.toString(Charsets.UTF_8)) }
        catch (e: org.json.JSONException) { android.util.Log.w("OwnedAlarmStarts", "Unreadable ringing ownership, starting again", e); JSONObject() }
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
        if (value.has(key) && (token == null || value.optString(key) == token || value.optString(key) == QUIET + token)) {
            value.remove(key)
            write(context, value)
        }
    }

    /**
     * D14-1: "Ring until I stop it" turned off while this owner's alarm is on its way or ringing. Its start is refused as a
     * cancelled one is, but leaves the reminder's normal notification ([takeQuiet]) instead of nothing.
     */
    @Synchronized fun quiet(context: Context, kind: String, id: String) {
        val value = read(context)
        val key = "$kind:$id"
        val token = value.owner(key) ?: return
        if (!token.startsWith(QUIET)) write(context, value.put(key, QUIET + token))
    }

    /** True once, for a start refused because its ringing was turned off ([quiet]): it is then done with. */
    @Synchronized fun takeQuiet(context: Context, extras: Bundle?): Boolean {
        val kind = extras?.getString(EXTRA_OWNER_KIND) ?: return false
        val id = extras.getString(EXTRA_OWNER_ID) ?: return false
        val token = extras.getString(EXTRA_OWNER_START) ?: return false
        val value = runCatching { read(context) }.getOrNull() ?: return false
        if (value.owner("$kind:$id") != QUIET + token) return false
        runCatching { write(context, value.apply { remove("$kind:$id") }) }
        return true
    }

    /**
     * A14-5: [extras]' reminder still stands, ringing or turned quiet. Hunt 24 D1: an event's too, while its start is reserved;
     * an event's alarm without a reservation always does.
     */
    @Synchronized fun isCurrent(context: Context, extras: Bundle?): Boolean {
        val (kind, id, token) = owner(extras) ?: return extras?.getString(EXTRA_OWNER_KIND) == null
        return runCatching { read(context).owner("$kind:$id").let { it != null && (it == token || it == QUIET + token) } }.getOrDefault(false)
    }

    private const val QUIET = "quiet:"
    private fun JSONObject.owner(key: String): String? = if (has(key)) optString(key) else null

    @Synchronized fun finish(context: Context, extras: Bundle?) {
        val (kind, id, token) = owner(extras) ?: return
        runCatching { cancel(context, kind, id, token) }
            .onFailure { android.util.Log.w("OwnedAlarmStarts", "Couldn't finish ringing ownership", it) }
    }

    /**
     * The reservation [extras] rings under: a task's or note's (its kind, its id, [EXTRA_OWNER_START]), or (Hunt 24 D1) an
     * event's ("event", its reminder id, [EXTRA_EVENT_START]). Null for a task or note start missing a part (refused), and
     * for an event's alarm without a reservation.
     */
    internal fun owner(extras: Bundle?): Triple<String, String, String>? {
        val kind = extras?.getString(EXTRA_OWNER_KIND)
        if (kind != null) return Triple(kind, extras.getString(EXTRA_OWNER_ID) ?: return null, extras.getString(EXTRA_OWNER_START) ?: return null)
        val token = extras?.getString(EXTRA_EVENT_START) ?: return null
        return Triple(EVENT, extras.getLong(ReminderScheduler.EXTRA_REMINDER_ID).toString(), token)
    }
    const val EVENT = "event"

    /**
     * Hold ownership through startup so cancellation cannot land between validation and playback. Hunt 24 D1: an event's
     * start as well, so one Android delivers again after ending the process doesn't ring (or show) a reminder deleted, paid,
     * skipped, snoozed or moved meanwhile: ReminderScheduler.cancel took its reservation away.
     */
    @Synchronized fun start(context: Context, extras: Bundle?, play: () -> Unit): Boolean {
        if (extras?.getString(EXTRA_OWNER_KIND) != null || extras?.getString(EXTRA_EVENT_START) != null) {
            val (kind, id, token) = owner(extras) ?: return false
            val current = try { read(context).owner("$kind:$id") }
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
