package com.timekeeper.app

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import com.timekeeper.app.databinding.ActivityVoicePromptBinding

class VoicePromptActivity : Activity() {

    private lateinit var binding: ActivityVoicePromptBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVoicePromptBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val prefs = getSharedPreferences(TimeKeeperService.PREFS_NAME, MODE_PRIVATE)
        binding.promptElapsedCheck.isChecked =
            prefs.getBoolean(TimeKeeperService.KEY_PROMPT_ELAPSED_ENABLED, true)
        binding.promptWaterCheck.isChecked =
            prefs.getBoolean(TimeKeeperService.KEY_PROMPT_WATER_ENABLED, true)
        binding.promptCheerCheck.isChecked =
            prefs.getBoolean(TimeKeeperService.KEY_PROMPT_CHEER_ENABLED, true)
        prefs.getString(TimeKeeperService.KEY_PROMPT_ELAPSED_TEXT, null)
            ?.takeIf { it.isNotBlank() }?.let { binding.promptElapsedInput.setText(it) }
        prefs.getString(TimeKeeperService.KEY_PROMPT_WATER_TEXT, null)
            ?.takeIf { it.isNotBlank() }?.let { binding.promptWaterInput.setText(it) }
        prefs.getString(TimeKeeperService.KEY_PROMPT_CHEER_TEXT, null)
            ?.takeIf { it.isNotBlank() }?.let { binding.promptCheerInput.setText(it) }

        binding.promptSaveButton.setOnClickListener { savePrompts() }
    }

    private fun savePrompts() {
        val elapsedText = binding.promptElapsedInput.text?.toString()?.trim()
            .takeUnless { it.isNullOrBlank() } ?: getString(R.string.prompt_elapsed_default)
        val waterText = binding.promptWaterInput.text?.toString()?.trim()
            .takeUnless { it.isNullOrBlank() } ?: getString(R.string.prompt_water_default)
        val cheerText = binding.promptCheerInput.text?.toString()?.trim()
            .takeUnless { it.isNullOrBlank() } ?: getString(R.string.prompt_cheer_default)
        getSharedPreferences(TimeKeeperService.PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putBoolean(
                TimeKeeperService.KEY_PROMPT_ELAPSED_ENABLED,
                binding.promptElapsedCheck.isChecked
            )
            .putBoolean(
                TimeKeeperService.KEY_PROMPT_WATER_ENABLED,
                binding.promptWaterCheck.isChecked
            )
            .putBoolean(
                TimeKeeperService.KEY_PROMPT_CHEER_ENABLED,
                binding.promptCheerCheck.isChecked
            )
            .putString(TimeKeeperService.KEY_PROMPT_ELAPSED_TEXT, elapsedText)
            .putString(TimeKeeperService.KEY_PROMPT_WATER_TEXT, waterText)
            .putString(TimeKeeperService.KEY_PROMPT_CHEER_TEXT, cheerText)
            .apply()
        Toast.makeText(this, R.string.prompt_saved, Toast.LENGTH_SHORT).show()
        finish()
    }
}