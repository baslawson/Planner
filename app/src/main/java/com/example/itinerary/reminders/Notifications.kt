package com.example.itinerary.reminders

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.itinerary.ItineraryApp
import com.example.itinerary.MainActivity
import com.example.itinerary.R
import com.example.itinerary.ui.label
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

const val REMINDER_CHANNEL_ID = "reminders"

// Reminder ids start at 1, so 0 can never collide with a real reminder's notification.
private const val TEST_NOTIFICATION_ID = 0

// A6-4: a notification tap opens Planner as the widget and shortcuts do: in its open window (onNewIntent), clearing
// the App lock screen above it (MainActivity locks again on start), never as one more Planner window on top.
// AS-6: accepted, the same as the widget's: it also ends a camera or file picker Planner opened in its own task (a photo
// being framed is lost). The PendingIntent is made when the reminder posts, so it can't tell what will be on top then.
internal const val OPEN_PLANNER_FLAGS = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP

// Marks a notification tap, which opens Planner where it already was (Agenda on a fresh start), never on the chosen
// start screen: that one is only for opening Planner itself (SR-1).
internal const val EXTRA_FROM_NOTIFICATION = "from_notification"

/** What tapping an event, task, missed-reminders or ringing-alarm notification opens. */
internal fun openPlannerIntent(context: Context): Intent =
    Intent(context, MainActivity::class.java).addFlags(OPEN_PLANNER_FLAGS).putExtra(EXTRA_FROM_NOTIFICATION, true)

// Ringing alarms play their own looping sound from AlarmService, so the channel itself is silent.
const val ALARM_CHANNEL_ID = "alarms"

fun createReminderChannel(context: Context) {
    val manager = context.getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(
        NotificationChannel(REMINDER_CHANNEL_ID, "Reminders", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Reminders for your events, tasks and bills"
        },
    )
    manager.createNotificationChannel(
        NotificationChannel(ALARM_CHANNEL_ID, "Ringing alarms", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Alarms that keep ringing until you stop them"
            setSound(null, null)
            enableVibration(false)
        },
    )
}

class ReminderContent(val title: String, val text: String, val subText: String)

// Builds what a reminder says from the extras that ReminderScheduler put in its intent.
fun reminderContent(context: Context, extras: Bundle?): ReminderContent? {
    extras ?: return null
    val title = extras.getString(ReminderScheduler.EXTRA_TITLE) ?: return null
    val location = extras.getString(ReminderScheduler.EXTRA_LOCATION).orEmpty()
    val date = extras.getString(ReminderScheduler.EXTRA_DATE)?.let(LocalDate::parse) ?: return null
    val time = extras.getString(ReminderScheduler.EXTRA_TIME)
        ?.takeIf { it.isNotEmpty() }
        ?.let(LocalTime::parse)

    val timeFormat = reminderTimeFormat(context)
    val dayText = date.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()))
    val whenText = if (extras.getBoolean(ReminderScheduler.EXTRA_BILL, false))
        "Due $dayText${time?.let { ", ${it.label(timeFormat, context)}" }.orEmpty()}"
    else "$dayText, ${time?.label(timeFormat, context) ?: "all day"}"
    val text = if (location.isBlank()) whenText else "$whenText · $location"
    return ReminderContent(title, text, extras.getString(ReminderScheduler.EXTRA_OFFSET_LABEL).orEmpty())
}

// False when the permission was refused, notifications are off for the app, or the user muted our channel.
fun notificationsEnabled(context: Context): Boolean {
    if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
    val channel = context.getSystemService(NotificationManager::class.java).getNotificationChannel(REMINDER_CHANNEL_ID)
    if (channel != null && channel.importance == NotificationManager.IMPORTANCE_NONE) return false
    return true
}

// A ringing alarm is stopped only from its notification, so it rings only when that notification can show: otherwise it
// would ring for ten minutes with no way to stop it. The reminder then falls back to a normal notification.
internal fun ringsAsAlarm(appNotifications: Boolean, alarmChannelImportance: Int?): Boolean =
    appNotifications && alarmChannelImportance != NotificationManager.IMPORTANCE_NONE

fun ringingAlarmsEnabled(context: Context): Boolean = ringsAsAlarm(
    NotificationManagerCompat.from(context).areNotificationsEnabled(),
    context.getSystemService(NotificationManager::class.java).getNotificationChannel(ALARM_CHANNEL_ID)?.importance)

const val COULD_NOT_RING = "Android didn't let this ring as an alarm. Allow Alarms & reminders for Planner so it can."

// Returns false if it could not be shown because notifications are off.
fun postReminderNotification(
    context: Context,
    notificationId: Int,
    title: String,
    text: String,
    subText: String,
    reminderId: Long? = null,
    billToken: String? = null,
    snoozeToken: String? = null,
    // A "Ring until I stop it" reminder that Android didn't let ring (ReminderReceiver).
    couldNotRing: Boolean = false,
    // Shown again (D6-10: with its Mark paid, after the unlock), without sounding a second time.
    quiet: Boolean = false,
): Boolean {
    if (!notificationsEnabled(context)) return false
    val open = PendingIntent.getActivity(
        context,
        notificationId,
        openPlannerIntent(context),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val notification = NotificationCompat.Builder(context, REMINDER_CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(title)
        .setContentText(text)
        .setSubText(subText)
        .setCategory(NotificationCompat.CATEGORY_REMINDER)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setAutoCancel(true)
        .setContentIntent(open)
        .setOnlyAlertOnce(quiet)
        .apply {
            if (reminderId != null && reminderId > 0) {
                if (billToken != null) addDataAction(context, "Mark paid", BillPaymentReceiver.action(context, reminderId, billToken))
                if (snoozeToken != null) addAction(0, "Snooze", SnoozeActivity.action(context, reminderId, snoozeToken))
            }
            if (couldNotRing) {
                setStyle(NotificationCompat.BigTextStyle().bigText("$text\n$COULD_NOT_RING"))
                if (android.os.Build.VERSION.SDK_INT >= 31) addAction(0, "Allow alarms", PendingIntent.getActivity(context, 0,
                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.fromParts("package", context.packageName, null)),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            }
        }
        .build()
        // Its sound repeats until the notification is opened or dismissed: the nearest to ringing without the alarm.
        .apply { if (couldNotRing) flags = flags or android.app.Notification.FLAG_INSISTENT }
    return try {
        NotificationManagerCompat.from(context).notify(notificationId, notification)
        true
    } catch (e: SecurityException) {
        false
    }
}

fun sendTestNotification(context: Context): Boolean =
    postReminderNotification(
        context,
        TEST_NOTIFICATION_ID,
        title = "Test notification",
        text = "Your reminders will look like this.",
        subText = "Just now",
    )

fun openNotificationSettings(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

// Where battery use ("Unrestricted") can be changed for this app.
fun openAppSettings(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}
