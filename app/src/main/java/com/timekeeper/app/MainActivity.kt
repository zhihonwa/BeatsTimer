package com.timekeeper.app

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Toast
import com.timekeeper.app.databinding.ActivityMainBinding

class MainActivity : Activity() {

    private lateinit var binding: ActivityMainBinding
    private val uiHandler = Handler(Looper.getMainLooper())

    private val countdownUpdater = object : Runnable {
        override fun run() {
            renderCountdown()
            uiHandler.postDelayed(this, 250L)
        }
    }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshButtonState()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val prefs = getSharedPreferences(TimeKeeperService.PREFS_NAME, MODE_PRIVATE)
        val savedInterval = prefs.getInt(
            TimeKeeperService.KEY_INTERVAL,
            TimeKeeperService.DEFAULT_INTERVAL
        ).coerceIn(TimeKeeperService.MIN_INTERVAL, TimeKeeperService.MAX_INTERVAL)
        binding.intervalInput.setText(savedInterval.toString())

        binding.startButton.setOnClickListener { toggleReporting() }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(TimeKeeperService.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(stateReceiver, filter)
        }
        refreshButtonState()
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(stateReceiver)
    }

    override fun onResume() {
        super.onResume()
        renderCountdown()
        uiHandler.postDelayed(countdownUpdater, 250L)
    }

    override fun onPause() {
        super.onPause()
        uiHandler.removeCallbacks(countdownUpdater)
    }

    private fun toggleReporting() {
        if (TimeKeeperService.isRunning) {
            stopService(Intent(this, TimeKeeperService::class.java))
        } else {
            val interval = resolveInterval() ?: return
            getSharedPreferences(TimeKeeperService.PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putInt(TimeKeeperService.KEY_INTERVAL, interval)
                .apply()
            ensureNotificationPermission()
            ensureBatteryOptimizationExemption()
            val intent = Intent(this, TimeKeeperService::class.java)
                .setAction(TimeKeeperService.ACTION_START)
                .putExtra(TimeKeeperService.EXTRA_INTERVAL_MINUTES, interval)
            startForegroundService(intent)
            Toast.makeText(
                this,
                getString(R.string.toast_started, interval),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun resolveInterval(): Int? {
        val text = binding.intervalInput.text?.toString()?.trim().orEmpty()
        val value = text.toIntOrNull()
        if (value == null ||
            value < TimeKeeperService.MIN_INTERVAL ||
            value > TimeKeeperService.MAX_INTERVAL
        ) {
            binding.intervalInput.setText(TimeKeeperService.DEFAULT_INTERVAL.toString())
            Toast.makeText(
                this,
                getString(
                    R.string.toast_invalid_interval,
                    TimeKeeperService.MIN_INTERVAL,
                    TimeKeeperService.MAX_INTERVAL,
                    TimeKeeperService.DEFAULT_INTERVAL
                ),
                Toast.LENGTH_SHORT
            ).show()
            return null
        }
        return value
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_CODE_NOTIFICATION
            )
        }
    }

    private fun ensureBatteryOptimizationExemption() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        try {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (e: Exception) {
        }
    }

    private fun refreshButtonState() {
        val running = TimeKeeperService.isRunning
        binding.startButton.setText(
            if (running) R.string.stop_reporting else R.string.start_reporting
        )
        binding.intervalInput.isEnabled = !running
        renderCountdown()
    }

    private fun renderCountdown() {
        val running = TimeKeeperService.isRunning
        binding.countdownText.visibility = if (running) View.VISIBLE else View.GONE
        if (running) {
            val total = TimeKeeperService.remainingSeconds.coerceAtLeast(0)
            binding.countdownText.text = String.format("%02d:%02d", total / 60, total % 60)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    companion object {
        private const val REQUEST_CODE_NOTIFICATION = 100
    }
}