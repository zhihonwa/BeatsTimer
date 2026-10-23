package com.timekeeper.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log

class MusicPlayer(context: Context) {

    private val appContext = context.applicationContext
    private val audioManager =
        appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var player: MediaPlayer? = null
    private var focusRequest: AudioFocusRequest? = null

    @Volatile
    private var musicUri: Uri? = null

    @Volatile
    private var volume = DEFAULT_VOLUME

    @Volatile
    private var ducked = false

    @Volatile
    private var playing = false

    fun setMusicUri(uri: Uri?) {
        musicUri = uri
    }

    fun setVolume(value: Float) {
        volume = value.coerceIn(0f, 1f)
        applyVolume()
    }

    fun start() {
        if (playing) {
            applyVolume()
            return
        }
        val useUserMusic = musicUri != null
        if (useUserMusic) {
            requestFocus()
        }
        try {
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                setDataSource(appContext, musicUri ?: builtinTrackUri())
                isLooping = true
                val initial = if (useUserMusic) volume else BUILTIN_VOLUME
                setVolume(initial, initial)
                prepare()
                start()
            }
            playing = true
            ducked = false
        } catch (e: Exception) {
            Log.w(TAG, "音乐播放启动失败，回退到内置保活音轨", e)
            releasePlayer()
            startBuiltinTrack()
        }
    }

    private fun startBuiltinTrack() {
        if (musicUri == null) {
            playing = false
            return
        }
        try {
            musicUri = null
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                setDataSource(appContext, builtinTrackUri())
                isLooping = true
                setVolume(BUILTIN_VOLUME, BUILTIN_VOLUME)
                prepare()
                start()
            }
            playing = true
        } catch (e: Exception) {
            Log.w(TAG, "内置保活音轨启动失败", e)
            releasePlayer()
            playing = false
        }
    }

    fun pause() {
        releasePlayer()
        abandonFocus()
        playing = false
        ducked = false
    }

    fun release() {
        pause()
    }

    private fun builtinTrackUri(): Uri =
        Uri.parse("android.resource://${appContext.packageName}/${R.raw.keepalive}")

    private fun releasePlayer() {
        player?.let { mp ->
            try {
                if (mp.isPlaying) mp.stop()
            } catch (e: Exception) {
                Log.w(TAG, "音乐停止异常", e)
            }
            try {
                mp.release()
            } catch (e: Exception) {
                Log.w(TAG, "音乐释放异常", e)
            }
        }
        player = null
    }

    private fun applyVolume() {
        val mp = player ?: return
        val base = if (musicUri != null) volume else BUILTIN_VOLUME
        val effective = if (ducked) base * DUCK_RATIO else base
        try {
            mp.setVolume(effective, effective)
        } catch (e: Exception) {
            Log.w(TAG, "音量调整异常", e)
        }
    }

    private fun handleFocusChange(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                ducked = false
                applyVolume()
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                ducked = true
                applyVolume()
            }

            AudioManager.AUDIOFOCUS_LOSS -> pause()
        }
    }

    private fun requestFocus() {
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setOnAudioFocusChangeListener { change -> handleFocusChange(change) }
            .build()
        focusRequest = request
        audioManager.requestAudioFocus(request)
    }

    private fun abandonFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    companion object {
        private const val TAG = "MusicPlayer"
        const val DEFAULT_VOLUME = 0.6f
        private const val BUILTIN_VOLUME = 1.0f
        private const val DUCK_RATIO = 0.2f
    }
}