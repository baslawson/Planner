package com.example.itinerary.reminders

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.itinerary.ItineraryApp
import com.example.itinerary.R
import java.time.LocalDate
import java.time.LocalTime

// Rings like an alarm clock: looping alarm sound and vibration until the user taps Stop or Snooze.
// It gives up after [MAX_RING_MINUTES] and leaves a "missed" notification, so a forgotten phone stays quiet.
// Reminder sound (bugnotes 7 Oct): a reminder set to ring for a few seconds ([EXTRA_RING_FOR]) rings the same way, then
// stops by itself and leaves its normal notification (not a missed one), as its Stop button does.
class AlarmService : Service() {
    private var player: MediaPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var ringing: Bundle? = null
    // R6-2: the start that brought the ringing alarm. Android keeps every start to deliver again after it ends the
    // process, until that start is stopped by its id: so one that gave way or gave up is never rung again, and stopping it
    // leaves the starts after it (another alarm) to run.
    private var ringingStart = 0
    // A14-4: the newest start seen, so Stop ends only the starts so far (one queued just behind it still runs). A14-1: the
    // newest start set aside while ringing (a stale button, a refused alarm), done with when the ringing one ends.
    private var lastStart = 0
    private var ignoredStart = 0
    private val handler = Handler(Looper.getMainLooper())
    // Its time is up: a timed ring ends as its normal notification, one until stopped as missed.
    private val giveUp = Runnable { if (timed(ringing)) onRangOut() else onGiveUp() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStart = maxOf(lastStart, startId)
        if (intent?.action == ACTION_STOP || intent?.action == ACTION_SNOOZE) {
            if (!RingToken.matches(stopToken, intent.getStringExtra(EXTRA_STOP_ALARM))) {
                // A stale button must neither stop this ring nor change its process-restart policy.
                if (ringing == null) { stopSelf(startId); return START_NOT_STICKY }
                ignoredStart = maxOf(ignoredStart, startId)
                return START_REDELIVER_INTENT
            }
        }
        when (intent?.action) {
            ACTION_STOP -> {
                // D14-1: ringing turned off while it rang: the reminder stays, as its normal notification. So does a timed
                // ring's Stop: it stops the sound, the reminder stays to act on.
                // A15-1: only one that still stands (not completed or deleted meanwhile), checked and posted under the lock
                // that cancelling takes, and without sounding again (A15-3).
                if (intent.getBooleanExtra(EXTRA_KEEP_REMINDER, false)) ringing?.let { extras ->
                    synchronized(OwnedAlarmStarts) { if (OwnedAlarmStarts.isCurrent(this, extras)) showAsNotification(extras, silent = true) }
                }
                stopRinging()
            }
            ACTION_SNOOZE -> {
                ringing?.let { extras ->
                    (application as ItineraryApp).reminderScheduler.snooze(extras, SNOOZE_MINUTES)
                    Toast.makeText(this, "Snoozed for $SNOOZE_MINUTES minutes", Toast.LENGTH_SHORT).show()
                }
                stopRinging()
            }
            else -> {
                val extras = intent?.extras
                val accepted = OwnedAlarmStarts.start(this, extras) {
                    startRinging(extras, startId, redelivered = flags and START_FLAG_REDELIVERY != 0)
                }
                // Turned quiet on its way (D14-1): its reminder is shown as a normal notification instead. Hunt 25 D1: an
                // event's or bill's too (with its buttons); delivered again after a restart, it has had its sound.
                if (!accepted) synchronized(OwnedAlarmStarts) { if (OwnedAlarmStarts.takeQuiet(this, extras))
                    extras?.let { showAsNotification(it, silent = flags and START_FLAG_REDELIVERY != 0) } }
                if (!accepted && ringing != null) ignoredStart = maxOf(ignoredStart, startId)
                if (!accepted && ringing == null) {
                    // Fulfil the foreground-start deadline, then end this cancelled start without playing anything.
                    val cancelled = NotificationCompat.Builder(this, ALARM_CHANNEL_ID)
                        .setSmallIcon(R.drawable.ic_notification).setContentTitle("Reminder cancelled")
                        .setCategory(NotificationCompat.CATEGORY_STATUS).setSilent(true).build()
                    // Hunt 23 P1: refused after a restart from the background (Android 12+), it has no deadline to meet.
                    if (goForeground(cancelled)) ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                }
                // Android ending the process (low memory, seen right after an unlock) must not end the alarm without anyone
                // stopping it: the start is delivered again and it rings on (Stop and Snooze end it for good).
                return START_REDELIVER_INTENT
            }
        }
        return START_NOT_STICKY
    }

    private fun startRinging(extras: Bundle?, startId: Int, redelivered: Boolean = false) {
        // Hunt 22 P1: a timed ring never takes over one ringing until stopped (a wake-up alarm, which would go quiet while the
        // newcomer stopped by itself seconds later): it gives way, as its normal notification with its sound, and the alarm
        // rings on. Its start is set aside, done with when the alarm ends.
        if (extras != null && timed(extras) && ringing?.let { !timed(it) } == true) {
            // Hunt 23: delivered again after a restart, it has had its sound already.
            showAsNotification(extras, silent = redelivered)
            OwnedAlarmStarts.finish(this, extras)
            ignoredStart = maxOf(ignoredStart, startId)
            return
        }
        // A second alarm can arrive while one is ringing; keep the first one as a normal notification.
        val previous = ringing
        val previousStart = ringingStart
        // A14-5: not one completed, deleted or removed meanwhile (its start no longer stands).
        // It has rung already: quietly, under the new one's sound.
        if (previous != null && extras != null && OwnedAlarmStarts.isCurrent(this, previous)) showAsNotification(previous, silent = true)
        // Hunt 24 D1: an event's reserved start is compared too (not the same start delivered again).
        if (OwnedAlarmStarts.owner(previous) != OwnedAlarmStarts.owner(extras))
            OwnedAlarmStarts.finish(this, previous)
        ringing = extras
        ringingStart = startId
        currentReminderId = extras?.takeUnless { it.containsKey(EXTRA_OWNER_KIND) }?.getLong(ReminderScheduler.EXTRA_REMINDER_ID)
        currentOwner = extras?.getString(EXTRA_OWNER_KIND)?.let { kind -> "$kind:${extras.getString(EXTRA_OWNER_ID)}" }
        ringingSince = SystemClock.elapsedRealtime()
        stopToken = RingToken.new()

        // AS-7: read before the notification is built (an unlock is for good), so an unlock landing in between still
        // gets its rebuild below.
        val builtLocked = !DirectBoot.isUnlocked(this)
        // Must be called promptly after startForegroundService, even if there is nothing to ring for.
        // Hunt 23 P1: Android may refuse it after it restarted the service in the background (Android 12+, a start delivered
        // again): rather than crash (and be restarted to crash again), the reminder is left as its normal notification.
        // After a restart it rings for what is left of its time from the reminder's own time (at least a minute), and an
        // alarm long past that is left as missed. A timed one rings only what is left of its seconds; with none left it is
        // its normal notification. Hunt 26 E1: worked out first, so one with nothing left goes into the foreground silently
        // (no ringing card popping up for a moment before it is replaced).
        val ringFor = extras?.let { AlarmRestart.ringFor(redelivered, it.getLong(ReminderScheduler.EXTRA_TRIGGER, 0L), System.currentTimeMillis(), it.getInt(EXTRA_RING_FOR, 0)) }
        if (!goForeground(notification(extras, silent = extras != null && ringFor == null))) {
            // Hunt 24 D3: a timed one delivered again has had its sound already, as one that gives way (above).
            extras?.let { showAsNotification(it, missed = redelivered && !timed(it), silent = redelivered && timed(it)) }
            stopRinging(startId)
            return
        }
        // The one that gave way is a notification now: its start is done with, not to be delivered again.
        if (previous != null && extras != null) stopSelfResult(previousStart)
        if (extras == null) {
            stopRinging()
            return
        }
        // R6-5: started before the first unlock, Mark paid may be left out (addDataAction, Android 11 and lower). Once
        // unlocked, the notification is built again with it.
        if (builtLocked) rebuildAtUnlock()

        if (ringFor == null) { if (timed(extras)) onRangOut() else onGiveUp(); return }
        startSound()
        startVibration()
        handler.removeCallbacks(giveUp)
        handler.postDelayed(giveUp, ringFor)
    }

    // Hunt 23 P1: false when Android won't let it run in the foreground now (ForegroundServiceStartNotAllowedException).
    private fun goForeground(shown: android.app.Notification): Boolean = try {
        ServiceCompat.startForeground(this, NOTIFICATION_ID, shown, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        true
    } catch (e: IllegalStateException) {
        android.util.Log.w("AlarmService", "Couldn't ring in the foreground", e)
        false
    }

    // The ringing notification for [extras]. [quiet]: built again, without popping up a second time.
    private fun notification(extras: Bundle?, quiet: Boolean = false, silent: Boolean = false): android.app.Notification {
        val content = reminderContent(this, extras)
        val timed = timed(extras)
        return NotificationCompat.Builder(this, ALARM_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(content?.title ?: "Alarm")
            // R5 (hunt 21): what it is for, as the reminder's own notification says it: the notes' first line after the time,
            // all of them when opened. R2: none of it on the lock screen (the public version below: title and time).
            .setContentText(contentWithDetails(content?.text.orEmpty(), content?.details.orEmpty()))
            .apply { content?.details?.takeIf { it.isNotBlank() }?.let { setStyle(NotificationCompat.BigTextStyle().bigText("${content.text}\n$it")) } }
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(lockScreen(content?.title ?: "Alarm", content?.whenText.orEmpty(), timed))
            .setSubText(content?.subText.orEmpty())
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setOnlyAlertOnce(quiet)
            .setSilent(silent)
            .setContentIntent(openAndStop(stopToken))
            // Android 14+ lets the user swipe even this ongoing notification away (not on the lock screen). Swiping it is
            // the only thing left to do, so it counts as Stop: otherwise it would ring on with nothing to stop it.
            .setDeleteIntent(serviceAction(ACTION_STOP))
            .apply {
                val owner = extras?.getString(EXTRA_OWNER_ID)
                val kind = extras?.getString(EXTRA_OWNER_KIND)
                val trigger = extras?.getLong(ReminderScheduler.EXTRA_TRIGGER) ?: 0L
                if (owner != null && kind != null) {
                    if (kind == "task") {
                        addDataAction(this@AlarmService, "Done", TaskActionReceiver.done(this@AlarmService, owner, trigger))
                        addAction(0, "Snooze", SnoozeActivity.taskAction(this@AlarmService, owner, trigger))
                    } else {
                        addDataAction(this@AlarmService, "Done", NoteActionReceiver.done(this@AlarmService, owner, trigger))
                        addAction(0, "Snooze", SnoozeActivity.noteAction(this@AlarmService, owner, trigger))
                        setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                        setPublicVersion(lockScreen("Note reminder", "", timed))
                    }
                    return@apply
                }
                val id = extras?.getLong(ReminderScheduler.EXTRA_REMINDER_ID) ?: 0L
                if (id > 0) {
                    val billToken = extras?.getString(ReminderScheduler.EXTRA_BILL_TOKEN)
                    if (billToken != null) addDataAction(this@AlarmService, "Mark paid", BillPaymentReceiver.action(this@AlarmService, id, billToken))
                    // One chooser leaves room for both Mark paid and Stop on bill alarms.
                    extras?.getString(ReminderScheduler.EXTRA_SNOOZE_TOKEN)?.let { token ->
                        addAction(0, "Snooze", SnoozeActivity.action(this@AlarmService, id, token))
                    }
                } else addAction(0, "Snooze $SNOOZE_MINUTES min", serviceAction(ACTION_SNOOZE))
            }
            // A timed ring's Stop leaves the reminder's normal notification, as its end does; a swipe (delete intent) or a tap
            // (opens Planner) is done with it, as with a normal reminder.
            .addAction(0, "Stop", serviceAction(ACTION_STOP, keepReminder = timed))
            .build()
    }

    // R2: what the lock screen shows while it rings, where the phone hides sensitive content: [title] and [text] (its time),
    // no notes, and its Stop, so it can still be stopped without unlocking.
    private fun lockScreen(title: String, text: String, timed: Boolean): android.app.Notification =
        NotificationCompat.Builder(this, ALARM_CHANNEL_ID).setSmallIcon(R.drawable.ic_notification).setContentTitle(title)
            .apply { if (text.isNotBlank()) setContentText(text) }.setCategory(NotificationCompat.CATEGORY_ALARM)
            .addAction(0, "Stop", serviceAction(ACTION_STOP, keepReminder = timed)).build()

    // Rings for a few seconds ([EXTRA_RING_FOR] above 0), not until stopped.
    private fun timed(extras: Bundle?): Boolean = (extras?.getInt(EXTRA_RING_FOR, 0) ?: 0) > 0

    /**
     * [extras]' reminder as its normal notification: a task's or note's (postOwnedAlarm), an event's or bill's with its
     * notes, Snooze and Mark paid. [missed]: it rang unanswered ("Missed alarm: …"). [silent]: it has just rung.
     */
    private fun showAsNotification(extras: Bundle, missed: Boolean = false, silent: Boolean = false) {
        if (postOwnedAlarm(this, extras, missed = missed, silent = silent)) return
        val id = extras.getLong(ReminderScheduler.EXTRA_REMINDER_ID)
        reminderContent(this, extras)?.let {
            postReminderNotification(
                this, id.toInt(), (if (missed) "Missed alarm: " else "") + it.title, it.text, it.subText, id,
                extras.getString(ReminderScheduler.EXTRA_BILL_TOKEN), extras.getString(ReminderScheduler.EXTRA_SNOOZE_TOKEN),
                details = it.details, silent = silent, publicText = it.whenText,
            )
        }
    }

    // A timed ring's seconds are up (or were, before Android restarted the service): the sound stops and the reminder stays
    // as its normal notification, quietly, with its buttons. Like a give-up, it ends its own start only (R6-2).
    private fun onRangOut() {
        val start = ringingStart
        // P6: an event's alarm stopped from elsewhere (stopIfRinging) just now is no longer this one's: nothing to post.
        synchronized(OwnedAlarmStarts) {
            ringing?.takeIf { it.containsKey(EXTRA_OWNER_KIND) || currentReminderId != null }?.let { extras -> showAsNotification(extras, silent = true) }
        }
        stopRinging(start)
    }

    private var unlockWatch: android.content.BroadcastReceiver? = null

    private fun rebuildAtUnlock() {
        if (unlockWatch != null) return
        val watch = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                stopUnlockWatch()
                val extras = ringing ?: return
                runCatching { getSystemService(android.app.NotificationManager::class.java).notify(NOTIFICATION_ID, notification(extras, quiet = true)) }
                    .onFailure { android.util.Log.w("AlarmService", "Couldn't show the alarm's buttons again", it) }
            }
        }
        unlockWatch = watch
        ContextCompat.registerReceiver(this, watch, android.content.IntentFilter(Intent.ACTION_USER_UNLOCKED), ContextCompat.RECEIVER_NOT_EXPORTED)
        // Unlocked in the meantime: the broadcast may have gone before the receiver was there.
        if (DirectBoot.isUnlocked(this)) watch.onReceive(this, Intent(Intent.ACTION_USER_UNLOCKED))
    }

    private fun stopUnlockWatch() {
        unlockWatch?.let { runCatching { unregisterReceiver(it) } }
        unlockWatch = null
    }

    private fun startSound() {
        releasePlayer()
        // D6-1: the first sound that plays, of these (AlarmSound); vibration goes on whatever happens to the sound.
        val unlocked = DirectBoot.isUnlocked(this)
        soundChoices = AlarmSound.choices(unlocked,
            if (unlocked) runCatching { RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM) }.getOrNull() else null,
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
            Uri.parse("android.resource://$packageName/${R.raw.alarm_fallback}"))
        soundRun++
        play(0)
        // Keeps the CPU awake while the screen is off.
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "itinerary:alarm")
            .apply { acquire(MAX_RING_MINUTES * 60_000L + 5_000L) }
    }

    private var soundChoices: List<Uri> = emptyList()
    // Each alarm's sounds are a run of their own: a late error from the previous alarm's player changes nothing.
    private var soundRun = 0

    // Plays [soundChoices] from [index]; a sound that can't be read or played hands over to the next one. If none plays,
    // Android's own beeps (ToneGenerator), so a ringing alarm is never silent.
    private fun play(index: Int) {
        releasePlayer()
        val uri = soundChoices.getOrNull(index) ?: run { startBeeps(); return }
        val run = soundRun
        val next = { why: String ->
            android.util.Log.w("AlarmService", "Couldn't play the alarm sound $uri ($why), trying the next one")
            handler.post { if (ringing != null && run == soundRun) play(index + 1) }
        }
        try {
            player = MediaPlayer().apply {
                // Alarm usage keeps it audible in silent mode and lets it through default Do Not Disturb.
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                setOnErrorListener { mp, what, extra -> if (player === mp) next("error $what/$extra"); true }
                setOnPreparedListener { mp -> if (player === mp) runCatching { mp.start() }.onFailure { next(it.toString()) } }
                setDataSource(this@AlarmService, uri)
                isLooping = true
                prepareAsync()
            }
        } catch (e: Exception) {
            releasePlayer()
            next(e.toString())
        }
    }

    private var tones: ToneGenerator? = null
    private val beep = object : Runnable {
        override fun run() {
            tones?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 1_000)
            handler.postDelayed(this, 1_600L)
        }
    }

    private fun startBeeps() {
        tones = runCatching { ToneGenerator(AudioManager.STREAM_ALARM, ToneGenerator.MAX_VOLUME) }
            .onFailure { android.util.Log.w("AlarmService", "No alarm sound could play", it) }.getOrNull() ?: return
        handler.post(beep)
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

    // Given up on, it ends its own start only: an alarm started after it still rings (R6-2).
    private fun onGiveUp() {
        val start = ringingStart
        // Hunt 25 D4: as onRangOut: an alarm stopped from elsewhere just now (paid, deleted, snoozed, done) is no longer this
        // one's, so no "Missed alarm" goes into the slot its notification was just taken from.
        synchronized(OwnedAlarmStarts) {
            ringing?.takeIf { (it.containsKey(EXTRA_OWNER_KIND) || currentReminderId != null) && OwnedAlarmStarts.isCurrent(this, it) }
                ?.let { extras -> showAsNotification(extras, missed = true) }
        }
        stopRinging(start)
    }

    // [startId]: only that start (and the ones before it) is done with; without it, all of them (Stop, Snooze).
    private fun stopRinging(startId: Int? = null) {
        handler.removeCallbacks(giveUp)
        releasePlayer()
        vibrator?.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        OwnedAlarmStarts.finish(this, ringing)
        ringing = null
        currentReminderId = null
        currentOwner = null
        stopToken = null
        stopUnlockWatch()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        if (startId == null) stopSelf(lastStart) else stopSelf(maxOf(startId, ignoredStart))
    }

    private fun releasePlayer() {
        player?.let {
            player = null
            runCatching { it.stop() }
            it.release()
        }
        handler.removeCallbacks(beep)
        tones?.let { runCatching { it.stopTone() }; it.release() }
        tones = null
    }

    override fun onDestroy() {
        OwnedAlarmStarts.finish(this, ringing)
        currentReminderId = null
        currentOwner = null
        stopToken = null
        stopUnlockWatch()
        handler.removeCallbacks(giveUp)
        releasePlayer()
        vibrator?.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    // [keepReminder]: a Stop that leaves the reminder's notification (a timed ring's button). Its own request code and data,
    // so it doesn't replace the swipe's plain Stop, the same action.
    private fun serviceAction(action: String, keepReminder: Boolean = false): PendingIntent =
        PendingIntent.getService(
            this,
            action.hashCode() + if (keepReminder) 1 else 0,
            alarmAction(this, action, stopToken, keepReminder).putExtra(EXTRA_KEEP_REMINDER, keepReminder),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    // Tapping the notification counts as acknowledging: MainActivity stops the alarm when it sees the extra. A6-7: the
    // extra is this ring's own [token], so another app starting Planner (it's exported) can't stop a ringing alarm.
    private fun openAndStop(token: String?): PendingIntent =
        PendingIntent.getActivity(
            this,
            NOTIFICATION_ID,
            (if (ringing?.getString(EXTRA_OWNER_KIND) == "note")
                NoteReminderReceiver.openIntent(this, ringing!!.getString(EXTRA_OWNER_ID)!!) else openPlannerIntent(this))
                .putExtra(EXTRA_STOP_ALARM, token),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    companion object {
        @Volatile private var currentOwner: String? = null
        fun stopIfRinging(context: Context, kind: String, id: String) {
            synchronized(OwnedAlarmStarts) {
                // A14-2: a store that can't be written must not keep the caller (cancelTask/cancelNote) from the rest.
                runCatching { OwnedAlarmStarts.cancel(context, kind, id) }
                    .onFailure { android.util.Log.w("AlarmService", "Couldn't cancel ringing ownership", it) }
                if (currentOwner == "$kind:$id") requestStop(context, stopToken)
            }
        }
        /** D14-1: "Ring until I stop it" turned off: this owner's alarm stops ringing (or won't start) but stays a notification. */
        fun quietIfRinging(context: Context, kind: String, id: String) {
            synchronized(OwnedAlarmStarts) {
                runCatching { OwnedAlarmStarts.quiet(context, kind, id) }
                    .onFailure { android.util.Log.w("AlarmService", "Couldn't quiet ringing ownership", it) }
                if (currentOwner == "$kind:$id") requestStop(context, stopToken, keepReminder = true)
            }
        }
        @Volatile private var currentReminderId: Long? = null
        @Volatile private var ringingSince = 0L
        @Volatile private var stopToken: String? = null
        fun stopIfRinging(context: Context, id: Long) {
            synchronized(OwnedAlarmStarts) {
                // Hunt 24 D1: its reserved start goes too, so one Android delivers again after ending the process (where
                // currentReminderId, below, is gone) is refused rather than ringing for a reminder dealt with meanwhile.
                runCatching { OwnedAlarmStarts.cancel(context, OwnedAlarmStarts.EVENT, id.toString()) }
                    .onFailure { android.util.Log.w("AlarmService", "Couldn't cancel ringing ownership", it) }
                // Hunt 22 P6: no longer this alarm's, at once, so its time running out meanwhile (onRangOut) doesn't post the
                // notification that was just taken away (snoozed, deleted, paid).
                if (currentReminderId == id) { currentReminderId = null; requestStop(context, stopToken) }
            }
        }
        /**
         * Hunt 22 P2: an event reminder's sound changed to one that doesn't ring: it stops ringing, its notification stays.
         * Hunt 25 D1: its reserved start turns quiet too, as a task's does (D14-1), so one on its way, or delivered again after
         * Android ended the process, shows the notification instead of ringing.
         */
        fun quietIfRinging(context: Context, id: Long) {
            synchronized(OwnedAlarmStarts) {
                runCatching { OwnedAlarmStarts.quiet(context, OwnedAlarmStarts.EVENT, id.toString()) }
                    .onFailure { android.util.Log.w("AlarmService", "Couldn't quiet ringing ownership", it) }
                if (currentReminderId == id) requestStop(context, stopToken, keepReminder = true)
            }
        }

        // Planner opened while an alarm rings without its notification (swiped away on Android 14+ and the delete intent
        // didn't get through): nothing else could stop it, so opening the app does. The first seconds are left alone,
        // while Android may not list the just-posted notification yet.
        fun stopIfUnseen(context: Context) {
            val token = stopToken
            if ((currentReminderId == null && currentOwner == null) || SystemClock.elapsedRealtime() - ringingSince < 3_000L) return
            val shown = runCatching {
                context.getSystemService(android.app.NotificationManager::class.java).activeNotifications.any { it.id == NOTIFICATION_ID }
            }.getOrDefault(true)
            if (!shown) requestStop(context, token)
        }
        /** A tap on the ringing notification ([EXTRA_STOP_ALARM]): stops it only with the ringing alarm's own token. */
        fun stopFromTap(context: Context, token: String?) {
            if (RingToken.matches(stopToken, token)) requestStop(context, token)
        }
        private fun alarmAction(context: Context, action: String, token: String?, keepReminder: Boolean = false): Intent =
            Intent(context, AlarmService::class.java).setAction(action)
                .setData(Uri.Builder().scheme("planner").authority("alarm-action").appendPath(action).appendPath(token.orEmpty())
                    .apply { if (keepReminder) appendPath("keep") }.build())
                .putExtra(EXTRA_STOP_ALARM, token)

        private fun requestStop(context: Context, token: String?, keepReminder: Boolean = false) {
            if (token != null) context.startService(alarmAction(context, ACTION_STOP, token).putExtra(EXTRA_KEEP_REMINDER, keepReminder))
        }
        const val ACTION_STOP = "com.example.itinerary.alarm.STOP"
        const val ACTION_SNOOZE = "com.example.itinerary.alarm.SNOOZE"
        const val EXTRA_STOP_ALARM = "stop_alarm"
        private const val EXTRA_KEEP_REMINDER = "keep_reminder"
        const val SNOOZE_MINUTES = 10L
        const val MAX_RING_MINUTES = 10L
        // Seconds a reminder rings (ringSecondsNow): above 0 a timed ring, 0 (or none) until stopped.
        const val EXTRA_RING_FOR = "ring_for"
        private const val NOTIFICATION_ID = 1_000_000_001

        // Rings a sample alarm so the sound, vibration and buttons can be checked from Settings.
        // False when it can't ring: its notification (the only way to stop it) couldn't show.
        fun startTest(context: Context): Boolean {
            if (!ringingAlarmsEnabled(context)) return false
            val intent = Intent(context, AlarmService::class.java)
                .putExtra(ReminderScheduler.EXTRA_REMINDER_ID, 0L)
                .putExtra(ReminderScheduler.EXTRA_TITLE, "Test alarm")
                .putExtra(ReminderScheduler.EXTRA_LOCATION, "")
                .putExtra(ReminderScheduler.EXTRA_DATE, LocalDate.now().toString())
                .putExtra(ReminderScheduler.EXTRA_TIME, LocalTime.now().withSecond(0).withNano(0).toString())
                .putExtra(ReminderScheduler.EXTRA_OFFSET_LABEL, "Test")
                .putExtra(ReminderScheduler.EXTRA_RING, true)
                .putExtra(EXTRA_RING_FOR, 0)
                .putExtra(ReminderScheduler.EXTRA_TRIGGER, System.currentTimeMillis())
            ContextCompat.startForegroundService(context, intent)
            return true
        }
    }
}

// How long an alarm rings: its full time when it starts; after Android restarted the service, what is left counted from
// the reminder's time (at least a minute), or null (missed) once that is long gone. A trigger of 0 (not known) rings fully.
// [seconds]: a timed ring's length (above 0): it rings that long; after a restart only what is left of it counted from the
// reminder's time, and null (its normal notification, not missed) once none is left.
internal object AlarmRestart {
    fun ringFor(redelivered: Boolean, trigger: Long, now: Long, seconds: Int = 0): Long? {
        if (seconds > 0) {
            val timed = seconds * 1_000L
            if (!redelivered || trigger <= 0L) return timed
            val left = trigger + timed - now
            return left.takeIf { it > 0L }?.coerceAtMost(timed)
        }
        val full = AlarmService.MAX_RING_MINUTES * 60_000L
        if (!redelivered || trigger <= 0L) return full
        val left = trigger + full - now
        return when {
            left < -5 * 60_000L -> null
            else -> left.coerceIn(60_000L, full)
        }
    }
}

// A6-7: what a tap on the ringing notification must carry to stop it, new for each ring, so no other app can guess it.
internal object RingToken {
    fun new(): String = java.util.UUID.randomUUID().toString()
    fun matches(current: String?, given: String?): Boolean = current != null && given == current
}
