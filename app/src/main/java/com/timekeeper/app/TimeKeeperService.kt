package com.timekeeper.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
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
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        intent?.getIntExtra(EXTRA_INTERVAL_MINUTES, intervalMinutes)?.let { value ->
            intervalMinutes = value.coerceIn(MIN_INTERVAL, MAX_INTERVAL)
        }
        startForegroundWithNotification()
        startTicking()
        return START_STICKY
    }

    private fun startTicking() {
        if (running) return
        running = true
        isRunning = true
        acquireWakeLock()
        remainingSeconds = intervalMinutes * 60
        speakCurrentTime()
        handler.postDelayed(countdownRunnable, 1000L)
    }

    private fun stopTicking() {
        running = false
        isRunning = false
        remainingSeconds = 0
        handler.removeCallbacks(countdownRunnable)
        releaseWakeLock()
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

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_running, intervalMinutes))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .build()
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

    override fun onDestroy() {
        stopTicking()
        ttsManager.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "TimeKeeperService"
        private const val CHANNEL_ID = "time_keeper_channel"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.timekeeper.app.action.START"
        const val ACTION_STOP = "com.timekeeper.app.action.STOP"
        const val EXTRA_INTERVAL_MINUTES = "extra_interval_minutes"
        const val PREFS_NAME = "time_keeper_prefs"
        const val KEY_INTERVAL = "interval_minutes"
        const val DEFAULT_INTERVAL = 5
        const val MIN_INTERVAL = 1
        const val MAX_INTERVAL = 240

        @Volatile
        var isRunning = false
            private set

        @Volatile
        var remainingSeconds = 0
            private set
    }
}