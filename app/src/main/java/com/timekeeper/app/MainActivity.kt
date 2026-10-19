package com.timekeeper.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import com.timekeeper.app.databinding.ActivityMainBinding

class MainActivity : Activity() {

    private lateinit var binding: ActivityMainBinding

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
        refreshButtonState()
    }

    private fun toggleReporting() {
        if (TimeKeeperService.isRunning) {
            stopService(Intent(this, TimeKeeperService::class.java))
            refreshButtonState()
        } else {
            val interval = resolveInterval() ?: return
            getSharedPreferences(TimeKeeperService.PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putInt(TimeKeeperService.KEY_INTERVAL, interval)
                .apply()
            ensureNotificationPermission()
            val intent = Intent(this, TimeKeeperService::class.java)
                .setAction(TimeKeeperService.ACTION_START)
                .putExtra(TimeKeeperService.EXTRA_INTERVAL_MINUTES, interval)
            startForegroundService(intent)
            refreshButtonState()
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

    private fun refreshButtonState() {
        val running = TimeKeeperService.isRunning
        binding.startButton.setText(
            if (running) R.string.stop_reporting else R.string.start_reporting
        )
        binding.intervalInput.isEnabled = !running
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