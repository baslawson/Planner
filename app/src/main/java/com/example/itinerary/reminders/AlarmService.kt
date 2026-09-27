package com.example.itinerary.reminders

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.itinerary.ItineraryApp
import com.example.itinerary.MainActivity
import com.example.itinerary.R
import java.time.LocalDate
import java.time.LocalTime

// Rings like an alarm clock: looping alarm sound and vibration until the user taps Stop or Snooze.
// It gives up after [MAX_RING_MINUTES] and leaves a "missed" notification, so a forgotten phone stays quiet.
class AlarmService : Service() {
    private var player: MediaPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var ringing: Bundle? = null
    private val handler = Handler(Looper.getMainLooper())
    private val giveUp = Runnable { onGiveUp() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopRinging()
            ACTION_SNOOZE -> {
                ringing?.let { extras ->
                    (application as ItineraryApp).reminderScheduler.snooze(extras, SNOOZE_MINUTES)
                    Toast.makeText(this, "Snoozed for $SNOOZE_MINUTES minutes", Toast.LENGTH_SHORT).show()
                }
                stopRinging()
            }
            else -> startRinging(intent?.extras)
        }
        return START_NOT_STICKY
    }

    private fun startRinging(extras: Bundle?) {
        // A second alarm can arrive while one is ringing; keep the first one as a normal notification.
        val previous = ringing
        if (previous != null && extras != null) {
            reminderContent(this, previous)?.let {
                postReminderNotification(
                    this,
                    previous.getLong(ReminderScheduler.EXTRA_REMINDER_ID).toInt(),
                    it.title, it.text, it.subText, previous.getLong(ReminderScheduler.EXTRA_REMINDER_ID), previous.getString(ReminderScheduler.EXTRA_BILL_TOKEN), previous.getString(ReminderScheduler.EXTRA_SNOOZE_TOKEN),
                )
            }
        }
        ringing = extras
        currentReminderId = extras?.getLong(ReminderScheduler.EXTRA_REMINDER_ID)

        val content = reminderContent(this, extras)
        val notification = NotificationCompat.Builder(this, ALARM_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(content?.title ?: "Alarm")
            .setContentText(content?.text.orEmpty())
            .setSubText(content?.subText.orEmpty())
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setContentIntent(openAndStop())
            .apply {
                val id = extras?.getLong(ReminderScheduler.EXTRA_REMINDER_ID) ?: 0L
                if (id > 0) {
                    val billToken = extras?.getString(ReminderScheduler.EXTRA_BILL_TOKEN)
                    if (billToken != null) addAction(0, "Mark paid", BillPaymentReceiver.action(this@AlarmService, id, billToken))
                    // One chooser leaves room for both Mark paid and Stop on bill alarms.
                    extras?.getString(ReminderScheduler.EXTRA_SNOOZE_TOKEN)?.let { token ->
                        addAction(0, "Snooze", SnoozeActivity.action(this@AlarmService, id, token))
                    }
                } else addAction(0, "Snooze $SNOOZE_MINUTES min", serviceAction(ACTION_SNOOZE))
            }
            .addAction(0, "Stop", serviceAction(ACTION_STOP))
            .build()
        // Must be called promptly after startForegroundService, even if there is nothing to ring for.
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
        if (extras == null) {
            stopRinging()
            return
        }

        startSound()
        startVibration()
        handler.removeCallbacks(giveUp)
        handler.postDelayed(giveUp, MAX_RING_MINUTES * 60_000L)
    }

    private fun startSound() {
        releasePlayer()
        val uri = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        try {
            player = MediaPlayer().apply {
                // Alarm usage keeps it audible in silent mode and lets it through default Do Not Disturb.
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                setDataSource(this@AlarmService, uri)
                isLooping = true
                setOnPreparedListener { it.start() }
                prepareAsync()
            }
        } catch (e: Exception) {
            releasePlayer()
        }
        // Keeps the CPU awake while the screen is off.
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "itinerary:alarm")
            .apply { acquire(MAX_RING_MINUTES * 60_000L + 5_000L) }
    }

    private val vibrator: Vibrator?
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Vibrator::class.java)
        }

    private fun startVibration() {
        // Alarm attributes stop Android from silencing the vibration while the app is in the background.
        val effect = VibrationEffect.createWaveform(longArrayOf(0, 800, 600), 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val attributes = android.os.VibrationAttributes.Builder()
                .setUsage(android.os.VibrationAttributes.USAGE_ALARM)
                .build()
            vibrator?.vibrate(effect, attributes)
        } else {
            val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
            @Suppress("DEPRECATION")
            vibrator?.vibrate(effect, attributes)
        }
    }

    private fun onGiveUp() {
        ringing?.let { extras ->
            reminderContent(this, extras)?.let {
                postReminderNotification(
                    this,
                    extras.getLong(ReminderScheduler.EXTRA_REMINDER_ID).toInt(),
                    "Missed alarm: ${it.title}", it.text, it.subText, extras.getLong(ReminderScheduler.EXTRA_REMINDER_ID), extras.getString(ReminderScheduler.EXTRA_BILL_TOKEN), extras.getString(ReminderScheduler.EXTRA_SNOOZE_TOKEN),
                )
            }
        }
        stopRinging()
    }

    private fun stopRinging() {
        handler.removeCallbacks(giveUp)
        releasePlayer()
        vibrator?.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        ringing = null
        currentReminderId = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun releasePlayer() {
        player?.let {
            runCatching { it.stop() }
            it.release()
        }
        player = null
    }

    override fun onDestroy() {
        currentReminderId = null
        handler.removeCallbacks(giveUp)
        releasePlayer()
        vibrator?.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    private fun serviceAction(action: String): PendingIntent =
        PendingIntent.getService(
            this,
            action.hashCode(),
            Intent(this, AlarmService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    // Tapping the notification counts as acknowledging: MainActivity stops the alarm when it sees the extra.
    private fun openAndStop(): PendingIntent =
        PendingIntent.getActivity(
            this,
            NOTIFICATION_ID,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_STOP_ALARM, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    companion object {
        @Volatile private var currentReminderId: Long? = null
        fun stopIfRinging(context: Context, id: Long) {
            if (currentReminderId == id) context.stopService(Intent(context, AlarmService::class.java))
        }
        const val ACTION_STOP = "com.example.itinerary.alarm.STOP"
        const val ACTION_SNOOZE = "com.example.itinerary.alarm.SNOOZE"
        const val EXTRA_STOP_ALARM = "stop_alarm"
        const val SNOOZE_MINUTES = 10L
        const val MAX_RING_MINUTES = 10L
        private const val NOTIFICATION_ID = 1_000_000_001

        // Rings a sample alarm so the sound, vibration and buttons can be checked from Settings.
        fun startTest(context: Context) {
            val intent = Intent(context, AlarmService::class.java)
                .putExtra(ReminderScheduler.EXTRA_REMINDER_ID, 0L)
                .putExtra(ReminderScheduler.EXTRA_TITLE, "Test alarm")
                .putExtra(ReminderScheduler.EXTRA_LOCATION, "")
                .putExtra(ReminderScheduler.EXTRA_DATE, LocalDate.now().toString())
                .putExtra(ReminderScheduler.EXTRA_TIME, LocalTime.now().withSecond(0).withNano(0).toString())
                .putExtra(ReminderScheduler.EXTRA_OFFSET_LABEL, "Test")
                .putExtra(ReminderScheduler.EXTRA_RING, true)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
