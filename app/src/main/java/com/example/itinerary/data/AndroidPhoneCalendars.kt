package com.example.itinerary.data

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import android.provider.ContactsContract
import java.time.Instant

// PhoneCalendarReader on Android's calendar storage. Only reads; every query is refused (and returns nothing) without
// READ_CALENDAR, which the user grants in Settings → Calendars. With READ_CONTACTS (granted there too, separately) the
// contacts' birthdays are one more calendar (ContactBirthdays).
class AndroidPhoneCalendars(context: Context) : PhoneCalendarReader {
    private val context = context.applicationContext

    override fun calendarsPermitted(): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
    override fun contactsPermitted(): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    override fun permitted(): Boolean = calendarsPermitted() || contactsPermitted()

    override fun calendars(): List<PhoneCalendar> =
        (if (calendarsPermitted()) androidCalendars() else emptyList()) +
            listOfNotNull(PhoneCalendar(ContactBirthdays.CALENDAR_ID, ContactBirthdays.NAME, "Contacts on this phone", "", null)
                .takeIf { contactsPermitted() })

    // Only calendars the phone's calendar app shows (hidden ones such as switched-off Holidays stay out).
    private fun androidCalendars(): List<PhoneCalendar> {
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

    override fun instances(calendarIds: Collection<Long>, from: Instant, until: Instant): List<PhoneInstance> =
        androidInstances(calendarIds - ContactBirthdays.CALENDAR_ID, from, until) +
            if (ContactBirthdays.CALENDAR_ID in calendarIds && contactsPermitted()) ContactBirthdays.instances(contactBirthdays().map { it.second }, from, until) else emptyList()

    // Every contact's birthday, once per contact and date, with the contact's id.
    fun contactBirthdays(): List<Pair<Long, ContactBirthdays.Birthday>> {
        val startDate = ContactsContract.CommonDataKinds.Event.START_DATE
        return context.contentResolver.query(ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.Data.CONTACT_ID, ContactsContract.Data.DISPLAY_NAME, startDate),
            "${ContactsContract.Data.MIMETYPE} = ? AND ${ContactsContract.CommonDataKinds.Event.TYPE} = ?",
            arrayOf(ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE, ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY.toString()), null)?.use { row ->
            buildList {
                while (row.moveToNext()) ContactBirthdays.parse(row.getString(1).orEmpty(), row.getString(2))?.let { add(row.getLong(0) to it) }
            }.distinct()
        // H17-S3: as for the calendars, no answer isn't "no birthdays".
        } ?: throw IllegalStateException("The phone's contacts didn't answer.")
    }

    private fun androidInstances(calendarIds: Collection<Long>, from: Instant, until: Instant): List<PhoneInstance> {
        if (!calendarsPermitted() || calendarIds.isEmpty()) return emptyList()
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

    // Calls [onChange] (on the main thread, at most once every 2 seconds) when the phone's calendars (or contacts, for their birthdays) change, until the
    // returned function is called. Null without permission: nothing is watched.
    fun watch(onChange: () -> Unit): (() -> Unit)? {
        if (!permitted()) return null
        val handler = Handler(Looper.getMainLooper())
        val fire = Runnable(onChange)
        val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) { handler.removeCallbacks(fire); handler.postDelayed(fire, 2000) }
        }
        return runCatching {
            if (calendarsPermitted()) context.contentResolver.registerContentObserver(CalendarContract.CONTENT_URI, true, observer)
            if (contactsPermitted()) context.contentResolver.registerContentObserver(ContactsContract.Contacts.CONTENT_URI, true, observer)
            val stop: () -> Unit = { handler.removeCallbacks(fire); context.contentResolver.unregisterContentObserver(observer) }
            stop
        }.getOrNull()
    }
}

/**
 * Reaching a contact from their birthday (user, 15 Oct): the first phone number saved for them, by contact id or (from a
 * birthday event, which keeps only the name) by their name as shown. Null without the contacts permission or a number.
 */
object ContactPhones {
    fun number(context: Context, contactId: Long): String? = query(context,
        "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?", arrayOf(contactId.toString()))

    fun numberByName(context: Context, name: String): String? = query(context,
        "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} = ?", arrayOf(name))


    /**
     * The number for the contact whose birthday a "Sam's birthday" event on [date] is (bug hunt 29 N5): found among the
     * contacts' birthdays by name and day, so two contacts named the same aren't mixed up and a name saved with spaces
     * around it still matches; then by name alone.
     */
    fun numberForBirthday(context: Context, name: String, date: java.time.LocalDate): String? {
        val day = java.time.MonthDay.from(date)
        val ids = runCatching { AndroidPhoneCalendars(context).contactBirthdays() }.getOrNull().orEmpty()
            .filter { (_, b) -> b.name == name && (b.day == day || b.day.atYear(date.year) == date) }.map { it.first }.distinct()
        return ids.firstNotNullOfOrNull { number(context, it) } ?: numberByName(context, name)
    }

    /** Whether some app on the phone can take [intent] (a phone with no messaging app gets no Message button). */
    fun canOpen(context: Context, intent: Intent): Boolean = context.packageManager.resolveActivity(intent, 0) != null
    private fun query(context: Context, where: String, args: Array<String>): String? {
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        return runCatching {
            context.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY), where, args,
                "${ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY} DESC")?.use { row ->
                if (row.moveToFirst()) row.getString(0)?.takeIf { it.isNotBlank() } else null
            }
        }.getOrNull()
    }

    /** A text message to [number], in the phone's messaging app. */
    fun messageIntent(number: String): Intent = Intent(Intent.ACTION_SENDTO, android.net.Uri.fromParts("smsto", number, null))
    /** The phone app with [number] filled in (it calls only when the user presses call). */
    fun callIntent(number: String): Intent = Intent(Intent.ACTION_DIAL, android.net.Uri.fromParts("tel", number, null))
}
