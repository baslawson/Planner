package com.example.itinerary.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.itinerary.ItineraryApp
import com.example.itinerary.R
import com.example.itinerary.data.ContactBirthdays
import com.example.itinerary.data.ContactPhones
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

const val BIRTHDAY_CHANNEL_ID = "birthdays"

/**
 * Birthday reminders (user, 15 Oct): the contacts' birthdays have no reminders of their own (they are another calendar's
 * events), so once a day, at the morning summary's time, each birthday the chosen number of days ahead (Settings ›
 * Calendars) gets a notification, with Message and Call (the messaging and phone apps with the contact's number filled
 * in) or, without a number, Open contact. Off until chosen; needs the contacts permission. Its alarm is inexact like the
 * summaries' and set again at the same moments (MorningSummary.schedule's callers).
 */
object BirthdayReminders {
    private const val REQUEST_CODE = 1_000_000_111
    private const val WINDOW_MS = 10 * 60_000L
    // The day a catch-up was set for (once a day, so contacts that fail to answer don't set one every minute).
    @Volatile private var caughtUpOn: LocalDate? = null

    private fun pendingIntent(context: Context) = PendingIntent.getBroadcast(context, REQUEST_CODE,
        Intent(context, BirthdayReminderReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun schedule(context: Context) {
        val settings = (context.applicationContext as ItineraryApp).settings
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        if (settings.birthdayReminderDays.value == ContactBirthdays.REMIND_OFF) { alarms.cancel(pendingIntent(context)); return }
        val zone = ZoneId.systemDefault()
        val now = LocalDateTime.now(zone)
        val shownToday = settings.birthdayRemindersShownOn == LocalDate.now(zone).toString()
        // Bug hunt 29 N7: turned on (or Planner first started) after the time with today's not shown: in a minute,
        // rather than missing a birthday today.
        val catchUp = !shownToday && now.toLocalTime() >= settings.morningSummaryAt.value && caughtUpOn != now.toLocalDate()
        if (catchUp) caughtUpOn = now.toLocalDate()
        val next = if (catchUp) now.plusMinutes(1) else MorningSummary.nextAt(now, settings.morningSummaryAt.value,
            settings.birthdayRemindersShownOn == LocalDate.now(zone).toString())
        runCatching { alarms.setWindow(AlarmManager.RTC_WAKEUP, next.atZone(zone).toInstant().toEpochMilli(), WINDOW_MS, pendingIntent(context)) }
            .onFailure { android.util.Log.w("BirthdayReminders", "Couldn't set the birthday reminders' alarm", it) }
    }

    /** Today's birthday reminders, once a day. */
    fun show(context: Context) {
        val app = context.applicationContext as ItineraryApp
        val settings = app.settings
        val today = LocalDate.now()
        val days = settings.birthdayReminderDays.value
        if (days == ContactBirthdays.REMIND_OFF || settings.birthdayRemindersShownOn == today.toString()) return
        if (!app.phoneCalendars.contactsPermitted()) return
        // Bug hunt 29 N4: marked shown once the contacts have answered, so a failed read doesn't lose the day.
        val due = ContactBirthdays.dueIn(app.phoneCalendars.contactBirthdays(), today, days)
        settings.birthdayRemindersShownOn = today.toString()
        due.forEach { post(context, it, today) }
    }

    private fun post(context: Context, birthday: ContactBirthdays.Upcoming, today: LocalDate) {
        val code = REQUEST_CODE + (birthday.contactId % 100_000).toInt() * 4
        fun activity(intent: Intent, offset: Int) = PendingIntent.getActivity(context, code + offset,
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val number = ContactPhones.number(context, birthday.contactId)
        val contact = Intent(Intent.ACTION_VIEW, android.content.ContentUris.withAppendedId(android.provider.ContactsContract.Contacts.CONTENT_URI, birthday.contactId))
        val builder = NotificationCompat.Builder(context, BIRTHDAY_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(ContactBirthdays.reminderText(birthday, today))
            .setContentText(if (number != null) "Send a message or call." else "No phone number saved for them.")
            .setContentIntent(activity(contact, 0))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
        if (number != null) {
            if (ContactPhones.canOpen(context, ContactPhones.messageIntent(number))) builder.addAction(0, "Message", activity(ContactPhones.messageIntent(number), 1))
            if (ContactPhones.canOpen(context, ContactPhones.callIntent(number))) builder.addAction(0, "Call", activity(ContactPhones.callIntent(number), 2))
        } else builder.addAction(0, "Open contact", activity(contact, 3))
        try { NotificationManagerCompat.from(context).notify("birthday-${birthday.contactId}", 0, builder.build()) }
        catch (e: SecurityException) { android.util.Log.w("BirthdayReminders", "Notifications are off", e) }
    }
}

class BirthdayReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try { BirthdayReminders.show(context) }
            catch (e: Exception) { android.util.Log.w("BirthdayReminders", "Couldn't show the birthday reminders", e) }
            finally {
                BirthdayReminders.schedule(context)
                pending.finish()
            }
        }
    }
}
