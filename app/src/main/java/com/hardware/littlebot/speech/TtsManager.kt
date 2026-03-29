package com.hardware.littlebot.speech

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

class TtsManager(context: Context) {

    companion object {
        private const val TAG = "TtsManager"
    }

    private var tts: TextToSpeech? = null

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val engine = tts ?: return@TextToSpeech
                engine.setOnUtteranceProgressListener(utteranceListener)

                val langResult = engine.setLanguage(Locale.CHINA)
                if (langResult == TextToSpeech.LANG_MISSING_DATA ||
                    langResult == TextToSpeech.LANG_NOT_SUPPORTED
                ) {
                    Log.w(TAG, "zh_CN not available (result=$langResult), trying zh")
                    val fallback = engine.setLanguage(Locale.SIMPLIFIED_CHINESE)
                    if (fallback == TextToSpeech.LANG_MISSING_DATA ||
                        fallback == TextToSpeech.LANG_NOT_SUPPORTED
                    ) {
                        Log.w(TAG, "zh not available either, using device default")
                        engine.setLanguage(Locale.getDefault())
                    }
                }
                _isReady.value = true
                Log.i(TAG, "TTS engine ready, language=${engine.voice?.locale}")
            } else {
                Log.e(TAG, "TTS init failed with status=$status")
            }
        }
    }

    fun speak(text: String) {
        val engine = tts ?: return
        if (text.isBlank()) return
        if (!_isReady.value) {
            Log.w(TAG, "speak() called but TTS not ready yet")
            return
        }

        _isSpeaking.value = true
        val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "littlebot_reply")
        if (result != TextToSpeech.SUCCESS) {
            Log.e(TAG, "speak() returned error=$result")
            _isSpeaking.value = false
        }
    }

    fun stop() {
        tts?.stop()
        _isSpeaking.value = false
    }

    fun destroy() {
        tts?.stop()
        tts?.shutdown()
        tts = null
    }

    private val utteranceListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            Log.d(TAG, "TTS utterance started")
        }

        override fun onDone(utteranceId: String?) {
            Log.d(TAG, "TTS utterance done")
            _isSpeaking.value = false
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            Log.e(TAG, "TTS utterance error")
            _isSpeaking.value = false
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            Log.e(TAG, "TTS utterance error, code=$errorCode")
            _isSpeaking.value = false
        }
    }
}
