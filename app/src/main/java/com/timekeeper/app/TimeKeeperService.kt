package com.timekeeper.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.MediaMetadata
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.widget.Toast
import java.util.Calendar

class TimeKeeperService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var ttsManager: TtsManager
    private lateinit var mediaSession: MediaSession
    private var silentPlayer: MediaPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile
    private var intervalMinutes = DEFAULT_INTERVAL

    @Volatile
    private var running = false

    private val countdownRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            remainingSeconds--
            if (remainingSeconds <= 0) {
                speakCurrentTime()
                remainingSeconds = intervalMinutes * 60
            }
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        intervalMinutes = prefs.getInt(KEY_INTERVAL, DEFAULT_INTERVAL)
            .coerceIn(MIN_INTERVAL, MAX_INTERVAL)
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
            null -> {
                val wasRunning = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .getBoolean(KEY_RUNNING, false)
                if (wasRunning) startReporting() else pauseReporting()
            }

            else -> startReporting()
        }
        startForegroundWithNotification()
        return START_STICKY
    }

    private fun startReporting() {
        if (running) return
        running = true
        isReporting = true
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_RUNNING, true)
            .apply()
        acquireWakeLock()
        startSilentAudio()
        remainingSeconds = intervalMinutes * 60
        updateMediaSession(PlaybackState.STATE_PLAYING)
        updateNotification()
        notifyStateChanged()
        speakCurrentTime()
        handler.postDelayed(countdownRunnable, 1000L)
    }

    private fun pauseReporting() {
        val wasRunning = running
        running = false
        isReporting = false
        remainingSeconds = 0
        handler.removeCallbacks(countdownRunnable)
        releaseWakeLock()
        stopSilentAudio()
        updateMediaSession(PlaybackState.STATE_PAUSED)
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_RUNNING, false)
            .apply()
        if (wasRunning) {
            updateNotification()
            notifyStateChanged()
        }
    }

    private fun notifyStateChanged() {
        sendBroadcast(Intent(ACTION_STATE_CHANGED).setPackage(packageName))
    }

    private fun speakCurrentTime() {
        val text = formatTimeText(Calendar.getInstance())
        Log.d(TAG, "播报: $text")
        ttsManager.speak(text)
    }

    private fun formatTimeText(cal: Calendar): String {
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val minute = cal.get(Calendar.MINUTE)
        return if (minute == 0) "${hour}点整" else "${hour}点${minute}分"
    }

    private fun startSilentAudio() {
        if (silentPlayer != null) return
        try {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            silentPlayer = MediaPlayer().apply {
                setAudioAttributes(attrs)
                setDataSource(
                    this@TimeKeeperService,
                    Uri.parse("android.resource://${packageName}/${R.raw.silence}")
                )
                isLooping = true
                setVolume(0f, 0f)
                prepare()
                start()
            }
        } catch (e: Exception) {
            Log.w(TAG, "静音保活音频启动失败", e)
            silentPlayer?.release()
            silentPlayer = null
        }
    }

    private fun stopSilentAudio() {
        silentPlayer?.let { player ->
            try {
                if (player.isPlaying) player.stop()
            } catch (e: Exception) {
                Log.w(TAG, "静音保活音频停止异常", e)
            }
            player.release()
        }
        silentPlayer = null
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
                if (running) {
                    getString(R.string.notification_running, intervalMinutes)
                } else {
                    getString(R.string.notification_paused)
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
        stopSilentAudio()
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
        const val ACTION_RESUME = "com.timekeeper.app.action.RESUME"
        const val ACTION_PAUSE = "com.timekeeper.app.action.PAUSE"
        const val ACTION_STATE_CHANGED = "com.timekeeper.app.action.STATE_CHANGED"
        const val EXTRA_INTERVAL_MINUTES = "extra_interval_minutes"
        const val PREFS_NAME = "time_keeper_prefs"
        const val KEY_INTERVAL = "interval_minutes"
        const val KEY_RUNNING = "reporting_running"
        const val DEFAULT_INTERVAL = 5
        const val MIN_INTERVAL = 1
        const val MAX_INTERVAL = 240

        @Volatile
        var isReporting = false
            private set

        @Volatile
        var remainingSeconds = 0
            private set
    }
}