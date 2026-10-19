package com.timekeeper.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

class TtsManager(context: Context) {

    private val appContext = context.applicationContext
    private val audioManager =
        appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val focusRequestRef = AtomicReference<AudioFocusRequest?>()

    private var tts: TextToSpeech? = null

    @Volatile
    private var ttsReady = false

    init {
        tts = TextToSpeech(appContext, ::onTtsInit)
    }

    private fun onTtsInit(status: Int) {
        if (status == TextToSpeech.SUCCESS && setupLanguage()) {
            ttsReady = true
        } else {
            tts?.shutdown()
            tts = null
        }
    }

    private fun setupLanguage(): Boolean {
        val engine = tts ?: return false
        val candidates = listOf(
            Locale.SIMPLIFIED_CHINESE,
            Locale.CHINA,
            Locale.CHINESE,
            Locale.getDefault()
        )
        for (locale in candidates) {
            val result = engine.setLanguage(locale)
            if (result != TextToSpeech.LANG_MISSING_DATA &&
                result != TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                engine.setOnUtteranceProgressListener(utteranceListener)
                return true
            }
        }
        return false
    }

    fun speak(text: String) {
        val engine = tts ?: return
        if (!ttsReady || text.isBlank()) return
        requestAudioFocus()
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
    }

    private fun requestAudioFocus() {
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setOnAudioFocusChangeListener(focusChangeListener)
            .build()
        focusRequestRef.set(request)
        audioManager.requestAudioFocus(request)
    }

    private fun abandonAudioFocus() {
        val request = focusRequestRef.getAndSet(null) ?: return
        audioManager.abandonAudioFocusRequest(request)
    }

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_LOSS) {
            tts?.stop()
            abandonAudioFocus()
        }
    }

    private val utteranceListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit

        override fun onDone(utteranceId: String?) {
            if (utteranceId == UTTERANCE_ID) abandonAudioFocus()
        }

        override fun onError(utteranceId: String?) {
            if (utteranceId == UTTERANCE_ID) abandonAudioFocus()
        }
    }

    fun shutdown() {
        abandonAudioFocus()
        ttsReady = false
        tts?.apply {
            stop()
            setOnUtteranceProgressListener(null)
            shutdown()
        }
        tts = null
    }

    companion object {
        private const val UTTERANCE_ID = "time_keeper_utterance"
    }
}