package com.example.itinerary.data

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import java.time.Instant

// PhoneCalendarReader on Android's calendar storage. Only reads; every query is refused (and returns nothing) without
// READ_CALENDAR, which the user grants in Settings → Calendars.
class AndroidPhoneCalendars(context: Context) : PhoneCalendarReader {
    private val context = context.applicationContext

    override fun permitted(): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    // Only calendars the phone's calendar app shows (hidden ones such as switched-off Holidays stay out).
    override fun calendars(): List<PhoneCalendar> {
        if (!permitted()) return emptyList()
        return context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME, CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.Calendars.CALENDAR_COLOR),
            "${CalendarContract.Calendars.VISIBLE} = 1", null, null)?.use { row ->
            buildList {
                while (row.moveToNext()) add(PhoneCalendar(row.getLong(0), row.getString(1)?.takeIf { it.isNotBlank() } ?: "Calendar",
                    row.getString(2).orEmpty(), row.getString(3).orEmpty(), if (row.isNull(4)) null else row.getInt(4) or 0xFF000000.toInt()))
            }
        // H17-S3: no answer (calendar storage unavailable) isn't "no calendars": that would drop every ticked one.
        } ?: throw IllegalStateException("The phone's calendar storage didn't answer.")
    }

    override fun instances(calendarIds: Collection<Long>, from: Instant, until: Instant): List<PhoneInstance> {
        if (!permitted() || calendarIds.isEmpty()) return emptyList()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, from.toEpochMilli()); ContentUris.appendId(it, until.toEpochMilli())
        }.build()
        return context.contentResolver.query(uri, arrayOf(CalendarContract.Instances.CALENDAR_ID, CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.TITLE,
            CalendarContract.Instances.EVENT_LOCATION, CalendarContract.Instances.DESCRIPTION, CalendarContract.Instances.STATUS,
            CalendarContract.Instances.SELF_ATTENDEE_STATUS),
            "${CalendarContract.Instances.CALENDAR_ID} IN (${calendarIds.joinToString(",") { "?" }})",
            calendarIds.map { it.toString() }.toTypedArray(), "${CalendarContract.Instances.BEGIN} ASC")?.use { row ->
            buildList {
                while (row.moveToNext()) add(PhoneInstance(row.getLong(0), row.getLong(1), row.getLong(2), row.getInt(3) == 1,
                    row.getString(4), row.getString(5), row.getString(6),
                    cancelled = !row.isNull(7) && row.getInt(7) == CalendarContract.Events.STATUS_CANCELED,
                    declined = !row.isNull(8) && row.getInt(8) == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED))
            }
        // H17-S3: likewise, not "no dates" (the ticked calendars' events would be cleared).
        } ?: throw IllegalStateException("The phone's calendar storage didn't answer.")
    }

    // Calls [onChange] (on the main thread, at most once every 2 seconds) when the phone's calendars change, until the
    // returned function is called. Null without permission: nothing is watched.
    fun watch(onChange: () -> Unit): (() -> Unit)? {
        if (!permitted()) return null
        val handler = Handler(Looper.getMainLooper())
        val fire = Runnable(onChange)
        val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) { handler.removeCallbacks(fire); handler.postDelayed(fire, 2000) }
        }
        return runCatching {
            context.contentResolver.registerContentObserver(CalendarContract.CONTENT_URI, true, observer)
            val stop: () -> Unit = { handler.removeCallbacks(fire); context.contentResolver.unregisterContentObserver(observer) }
            stop
        }.getOrNull()
    }
}
