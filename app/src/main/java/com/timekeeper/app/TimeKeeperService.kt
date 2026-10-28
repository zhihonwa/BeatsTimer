package com.timekeeper.app

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.media.MediaMetadata
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import java.util.Calendar

class TimeKeeperService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var ttsManager: TtsManager
    private lateinit var mediaSession: MediaSession
    private lateinit var musicPlayer: MusicPlayer
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile
    private var intervalMinutes = DEFAULT_INTERVAL

    @Volatile
    private var running = false

    @Volatile
    private var lastSpeakAtMillis = 0L

    private var nextReportSecond = 0

    private val countdownRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            checkAndReport()
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        intervalMinutes = prefs.getInt(KEY_INTERVAL, DEFAULT_INTERVAL)
            .coerceIn(MIN_INTERVAL, MAX_INTERVAL)
        musicPlayer = MusicPlayer(this).apply {
            setVolume(prefs.getInt(KEY_VOLUME, DEFAULT_VOLUME_PERCENT) / 100f)
            setMusicUri(prefs.getString(KEY_MUSIC_URI, null)?.let { Uri.parse(it) })
        }
        ttsManager = TtsManager(this).apply {
            listener = TtsManager.Listener { message ->
                Toast.makeText(this@TimeKeeperService, message, Toast.LENGTH_LONG).show()
            }
        }
        mediaSession = MediaSession(this, "TimeKeeperSession").apply {
            setCallback(mediaSessionCallback)
            isActive = true
        }
        updateMediaSession(PlaybackState.STATE_PAUSED)
        createNotificationChannel()
    }

    private val mediaSessionCallback = object : MediaSession.Callback() {
        override fun onPlay() {
            startReporting()
        }

        override fun onPause() {
            pauseReporting()
        }

        override fun onStop() {
            pauseReporting()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getIntExtra(EXTRA_INTERVAL_MINUTES, intervalMinutes)?.let { value ->
            intervalMinutes = value.coerceIn(MIN_INTERVAL, MAX_INTERVAL)
        }
        when (intent?.action) {
            ACTION_PAUSE -> pauseReporting()

            ACTION_PAUSE_TIMER -> pauseTimer()

            ACTION_RESUME_TIMER -> resumeTimer()

            ACTION_TICK -> {
                if (!running && isReportingRequested()) {
                    startReporting()
                } else {
                    handleFallbackTick()
                }
            }

            ACTION_SET_VOLUME -> musicPlayer.setVolume(
                (intent?.getIntExtra(EXTRA_VOLUME_PERCENT, DEFAULT_VOLUME_PERCENT)
                    ?: DEFAULT_VOLUME_PERCENT) / 100f
            )

            ACTION_SET_MUSIC -> applyMusicSource(intent?.getStringExtra(EXTRA_MUSIC_URI))

            null -> if (isReportingRequested()) startReporting() else pauseReporting()

            else -> startReporting()
        }
        startForegroundWithNotification()
        return START_STICKY
    }

    private fun startReporting() {
        if (running) return
        running = true
        isReporting = true
        isPaused = false
        setReportingRequested(true)
        syncMusicSettingsFromPrefs()
        acquireWakeLock()
        musicPlayer.start()
        intervalSeconds = intervalMinutes * 60
        resetTimerOrigin()
        nextReportSecond = intervalMinutes * 60
        updateMediaSession(PlaybackState.STATE_PLAYING)
        updateNotification()
        notifyStateChanged()
        speakCurrentTime(0)
        scheduleFallbackAlarm()
        handler.postDelayed(countdownRunnable, 1000L)
    }

    private fun pauseReporting() {
        val wasActive = running || isPaused || isReporting
        running = false
        isReporting = false
        isPaused = false
        nextReportSecond = 0
        resetTimerOrigin()
        handler.removeCallbacks(countdownRunnable)
        cancelFallbackAlarm()
        musicPlayer.stop()
        releaseWakeLock()
        updateMediaSession(PlaybackState.STATE_PAUSED)
        setReportingRequested(false)
        if (wasActive) {
            updateNotification()
            notifyStateChanged()
        }
    }

    private fun pauseTimer() {
        if (!running) return
        running = false
        isPaused = true
        pauseStartedRealtimeMillis = SystemClock.elapsedRealtime()
        handler.removeCallbacks(countdownRunnable)
        cancelFallbackAlarm()
        musicPlayer.pause()
        releaseWakeLock()
        updateMediaSession(PlaybackState.STATE_PAUSED)
        updateNotification()
        notifyStateChanged()
    }

    private fun resumeTimer() {
        if (running || !isReporting) return
        running = true
        isPaused = false
        if (pauseStartedRealtimeMillis > 0L) {
            pausedAccumMillis += SystemClock.elapsedRealtime() - pauseStartedRealtimeMillis
            pauseStartedRealtimeMillis = 0L
        }
        acquireWakeLock()
        musicPlayer.start()
        updateMediaSession(PlaybackState.STATE_PLAYING)
        updateNotification()
        notifyStateChanged()
        scheduleFallbackAlarm()
        handler.postDelayed(countdownRunnable, 1000L)
    }

    private fun handleFallbackTick() {
        checkAndReport()
    }

    private fun checkAndReport() {
        if (!running) return
        val elapsedSec = computeElapsedSeconds()
        var elapsedMinutes = -1
        while (nextReportSecond > 0 && elapsedSec >= nextReportSecond) {
            elapsedMinutes = nextReportSecond / 60
            nextReportSecond += intervalMinutes * 60
        }
        if (elapsedMinutes >= 0) {
            speakCurrentTime(elapsedMinutes)
            scheduleFallbackAlarm()
        }
    }

    private fun resetTimerOrigin() {
        startedRealtimeMillis = SystemClock.elapsedRealtime()
        pausedAccumMillis = 0L
        pauseStartedRealtimeMillis = 0L
    }

    private fun syncMusicSettingsFromPrefs() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        musicPlayer.setMusicUri(prefs.getString(KEY_MUSIC_URI, null)?.let { Uri.parse(it) })
        musicPlayer.setVolume(prefs.getInt(KEY_VOLUME, DEFAULT_VOLUME_PERCENT) / 100f)
    }

    private fun applyMusicSource(uriString: String?) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putString(KEY_MUSIC_URI, uriString)
            .apply()
        musicPlayer.setMusicUri(uriString?.let { Uri.parse(it) })
        when {
            running -> {
                musicPlayer.stop()
                musicPlayer.start()
            }

            isPaused -> musicPlayer.stop()
        }
    }

    private fun isReportingRequested(): Boolean =
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean(KEY_RUNNING, false)

    private fun setReportingRequested(value: Boolean) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_RUNNING, value)
            .apply()
    }

    private fun scheduleFallbackAlarm() {
        if (nextReportSecond <= 0) return
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val remainingSec = (nextReportSecond - computeElapsedSeconds()).coerceAtLeast(1)
        val triggerAt = System.currentTimeMillis() + remainingSec * 1000L
        val pendingIntent = buildAlarmPendingIntent()
        try {
            if (Build.VERSION.SDK_INT >= 31 && !alarmManager.canScheduleExactAlarms()) {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            } else {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            }
        } catch (e: SecurityException) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        } catch (e: Exception) {
            Log.w(TAG, "兜底闹钟设置失败", e)
        }
    }

    private fun cancelFallbackAlarm() {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = buildAlarmPendingIntent()
        alarmManager.cancel(pendingIntent)
        pendingIntent.cancel()
    }

    private fun buildAlarmPendingIntent(): PendingIntent =
        PendingIntent.getForegroundService(
            this,
            REQUEST_CODE_ALARM,
            Intent(this, TimeKeeperService::class.java).setAction(ACTION_TICK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun notifyStateChanged() {
        sendBroadcast(Intent(ACTION_STATE_CHANGED).setPackage(packageName))
    }

    private fun speakCurrentTime(elapsedMinutes: Int) {
        val now = System.currentTimeMillis()
        if (now - lastSpeakAtMillis < SPEAK_DEDUP_MILLIS) return
        lastSpeakAtMillis = now
        val text = buildReportText(elapsedMinutes)
        Log.d(TAG, "播报: $text")
        ttsManager.speak(text)
    }

    private fun buildReportText(elapsedMinutes: Int): String {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val text = StringBuilder(formatTimeText(Calendar.getInstance()))
        if (elapsedMinutes >= ELAPSED_PROMPT_THRESHOLD_MINUTES &&
            prefs.getBoolean(KEY_PROMPT_ELAPSED_ENABLED, true)
        ) {
            val template = resolvePromptText(KEY_PROMPT_ELAPSED_TEXT, DEFAULT_PROMPT_ELAPSED_TEXT)
            text.append(REPORT_SEPARATOR)
                .append(template.replace(ELAPSED_PLACEHOLDER, elapsedMinutes.toString()))
        }
        if (elapsedMinutes > 0 &&
            elapsedMinutes % WATER_PROMPT_INTERVAL_MINUTES == 0 &&
            prefs.getBoolean(KEY_PROMPT_WATER_ENABLED, true)
        ) {
            text.append(REPORT_SEPARATOR)
                .append(resolvePromptText(KEY_PROMPT_WATER_TEXT, DEFAULT_PROMPT_WATER_TEXT))
        }
        if (elapsedMinutes > 0 &&
            elapsedMinutes % CHEER_PROMPT_INTERVAL_MINUTES == 0 &&
            prefs.getBoolean(KEY_PROMPT_CHEER_ENABLED, true)
        ) {
            text.append(REPORT_SEPARATOR)
                .append(resolvePromptText(KEY_PROMPT_CHEER_TEXT, DEFAULT_PROMPT_CHEER_TEXT))
        }
        return text.toString()
    }

    private fun resolvePromptText(key: String, defaultValue: String): String =
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString(key, defaultValue)
            ?.takeIf { it.isNotBlank() } ?: defaultValue

    private fun formatTimeText(cal: Calendar): String {
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val minute = cal.get(Calendar.MINUTE)
        return if (minute == 0) "${hour}点整" else "${hour}点${minute}分"
    }

    private fun updateMediaSession(state: Int) {
        mediaSession.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, getString(R.string.app_name))
                .putString(
                    MediaMetadata.METADATA_KEY_ARTIST,
                    if (state == PlaybackState.STATE_PLAYING) {
                        getString(R.string.notification_running, intervalMinutes)
                    } else {
                        getString(R.string.notification_paused)
                    }
                )
                .build()
        )
        mediaSession.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or
                        PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_STOP
                )
                .setState(state, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build()
        )
    }

    private fun startForegroundWithNotification() {
        val notification = buildNotification()
        when {
            Build.VERSION.SDK_INT >= 34 -> startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )

            Build.VERSION.SDK_INT >= 29 -> startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )

            else -> startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(
                when {
                    running -> getString(R.string.notification_running, intervalMinutes)
                    isPaused -> getString(R.string.notification_timer_paused)
                    else -> getString(R.string.notification_paused)
                }
            )
            .setOngoing(true)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(openIntent)
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1)
            )
            .addAction(buildAction(start = true))
            .addAction(buildAction(start = false))
            .build()
    }

    private fun buildAction(start: Boolean): Notification.Action {
        val actionIntent = Intent(this, TimeKeeperService::class.java)
            .setAction(if (start) ACTION_RESUME else ACTION_PAUSE)
        val pendingIntent = PendingIntent.getForegroundService(
            this,
            if (start) REQUEST_CODE_START else REQUEST_CODE_STOP,
            actionIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Action.Builder(
            Icon.createWithResource(
                this,
                if (start) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause
            ),
            getString(if (start) R.string.start_reporting else R.string.stop_reporting),
            pendingIntent
        ).build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        channel.description = getString(R.string.notification_channel_desc)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TimeKeeper::TimeKeeperWakeLock")
            .apply {
                setReferenceCounted(false)
                acquire()
            }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        pauseReporting()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        pauseReporting()
        musicPlayer.release()
        ttsManager.shutdown()
        mediaSession.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "TimeKeeperService"
        private const val CHANNEL_ID = "time_keeper_channel"
        private const val NOTIFICATION_ID = 1001
        private const val REQUEST_CODE_START = 11
        private const val REQUEST_CODE_STOP = 12
        private const val REQUEST_CODE_ALARM = 21
        private const val SPEAK_DEDUP_MILLIS = 30_000L
        const val ACTION_RESUME = "com.timekeeper.app.action.RESUME"
        const val ACTION_PAUSE = "com.timekeeper.app.action.PAUSE"
        const val ACTION_PAUSE_TIMER = "com.timekeeper.app.action.PAUSE_TIMER"
        const val ACTION_RESUME_TIMER = "com.timekeeper.app.action.RESUME_TIMER"
        const val ACTION_TICK = "com.timekeeper.app.action.TICK"
        const val ACTION_SET_VOLUME = "com.timekeeper.app.action.SET_VOLUME"
        const val ACTION_SET_MUSIC = "com.timekeeper.app.action.SET_MUSIC"
        const val ACTION_STATE_CHANGED = "com.timekeeper.app.action.STATE_CHANGED"
        const val EXTRA_INTERVAL_MINUTES = "extra_interval_minutes"
        const val EXTRA_VOLUME_PERCENT = "extra_volume_percent"
        const val EXTRA_MUSIC_URI = "extra_music_uri"
        const val PREFS_NAME = "time_keeper_prefs"
        const val KEY_INTERVAL = "interval_minutes"
        const val KEY_RUNNING = "reporting_running"
        const val KEY_VOLUME = "music_volume_percent"
        const val KEY_MUSIC_URI = "music_uri"
        const val KEY_MUSIC_NAME = "music_name"
        const val KEY_PROMPT_ELAPSED_ENABLED = "prompt_elapsed_enabled"
        const val KEY_PROMPT_WATER_ENABLED = "prompt_water_enabled"
        const val KEY_PROMPT_CHEER_ENABLED = "prompt_cheer_enabled"
        const val KEY_PROMPT_ELAPSED_TEXT = "prompt_elapsed_text"
        const val KEY_PROMPT_WATER_TEXT = "prompt_water_text"
        const val KEY_PROMPT_CHEER_TEXT = "prompt_cheer_text"
        const val DEFAULT_PROMPT_ELAPSED_TEXT = "已完成 xx 分钟"
        const val DEFAULT_PROMPT_WATER_TEXT = "请补充水分"
        const val DEFAULT_PROMPT_CHEER_TEXT = "坚持运动，加油"
        private const val ELAPSED_PROMPT_THRESHOLD_MINUTES = 10
        private const val WATER_PROMPT_INTERVAL_MINUTES = 15
        private const val CHEER_PROMPT_INTERVAL_MINUTES = 30
        private const val ELAPSED_PLACEHOLDER = "xx"
        private const val REPORT_SEPARATOR = "，"
        const val DEFAULT_INTERVAL = 5
        const val DEFAULT_VOLUME_PERCENT = 60
        const val MIN_INTERVAL = 1
        const val MAX_INTERVAL = 240

        @Volatile
        var isReporting = false
            private set

        @Volatile
        var isPaused = false
            private set

        @Volatile
        var startedRealtimeMillis = 0L

        @Volatile
        var pausedAccumMillis = 0L

        @Volatile
        var pauseStartedRealtimeMillis = 0L

        @Volatile
        var intervalSeconds = DEFAULT_INTERVAL * 60

        fun computeElapsedSeconds(): Int {
            if (!isReporting || startedRealtimeMillis == 0L) return 0
            val now = SystemClock.elapsedRealtime()
            val pausedNow =
                if (isPaused && pauseStartedRealtimeMillis > 0L) {
                    now - pauseStartedRealtimeMillis
                } else {
                    0L
                }
            val activeMillis = now - startedRealtimeMillis - pausedAccumMillis - pausedNow
            return (activeMillis / 1000L).toInt().coerceAtLeast(0)
        }

        fun computeRemainingSeconds(): Int {
            if (!isReporting) return 0
            val seconds = intervalSeconds
            if (seconds <= 0) return 0
            return (seconds - computeElapsedSeconds() % seconds).coerceIn(0, seconds)
        }
    }
}