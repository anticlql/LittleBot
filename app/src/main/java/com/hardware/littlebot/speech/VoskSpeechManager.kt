package com.hardware.littlebot.speech

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

enum class SpeechMode { IDLE, WAKE_WORD_LISTENING, RECOGNIZING }

class VoskSpeechManager(private val context: Context) {

    companion object {
        private const val TAG = "VoskSpeechManager"
        private const val MODEL_NAME = "vosk-model-small-cn-0.22"
        private const val MODEL_URL =
            "https://alphacephei.com/vosk/models/$MODEL_NAME.zip"
        private const val SAMPLE_RATE = 16000f
        private const val WAKE_WORD_TIMEOUT_MS = 120_000
        private const val SILENCE_TIMEOUT_MS = 1500L
    }

    private var model: Model? = null
    private var speechService: SpeechService? = null

    /**
     * Monotonically increasing counter. Each new SpeechService gets a fresh
     * generation value; listener callbacks that carry a stale generation are
     * silently ignored, preventing the race condition where a delayed
     * `onFinalResult` from a stopped service shuts down the *new* service.
     */
    private var serviceGeneration = 0

    private var wakeWordTriggered = false
    private var triggeredByWakeWord = false

    private val modelDir get() = File(context.filesDir, MODEL_NAME)

    private val mainHandler = Handler(Looper.getMainLooper())
    private var silenceRunnable: Runnable? = null
    private var lastPartialText = ""

    var wakeWord: String = "你好"

    private val _speechText = MutableStateFlow("")
    val speechText: StateFlow<String> = _speechText.asStateFlow()

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _speechHint = MutableStateFlow("")
    val speechHint: StateFlow<String> = _speechHint.asStateFlow()

    private val _mode = MutableStateFlow(SpeechMode.IDLE)
    val mode: StateFlow<SpeechMode> = _mode.asStateFlow()

    private val _isModelReady = MutableStateFlow(false)
    val isModelReady: StateFlow<Boolean> = _isModelReady.asStateFlow()

    private val _isDownloading = MutableStateFlow(false)
    val isDownloading: StateFlow<Boolean> = _isDownloading.asStateFlow()

    private val _downloadProgress = MutableStateFlow(0f)
    val downloadProgress: StateFlow<Float> = _downloadProgress.asStateFlow()

    /** True when recognition was auto-stopped by the silence timer. */
    private val _autoStopped = MutableStateFlow(false)
    val autoStopped: StateFlow<Boolean> = _autoStopped.asStateFlow()

    init {
        LibVosk.setLogLevel(LogLevel.INFO)
    }

    // ── Model management ──────────────────────────────────────────────

    suspend fun initModel() = withContext(Dispatchers.IO) {
        if (model != null) {
            _isModelReady.value = true
            return@withContext
        }
        if (isModelDownloaded()) {
            loadModel()
        }
    }

    suspend fun downloadAndInitModel() {
        if (_isDownloading.value) return
        withContext(Dispatchers.IO) {
            try {
                _isDownloading.value = true
                _downloadProgress.value = 0f
                _speechHint.value = "正在下载中文语音模型..."
                downloadModel()
                _speechHint.value = "加载语音模型..."
                loadModel()
            } catch (e: Exception) {
                _speechHint.value = "下载失败: ${e.message}"
                modelDir.deleteRecursively()
            } finally {
                _isDownloading.value = false
            }
        }
    }

    // ── Manual recognition (mic button) ───────────────────────────────

    fun startListening() {
        if (_isListening.value || _mode.value == SpeechMode.WAKE_WORD_LISTENING) return
        triggeredByWakeWord = false
        startFullRecognition()
    }

    fun stopListening() {
        speechService?.stop()
    }

    // ── Wake word mode ────────────────────────────────────────────────

    fun startWakeWordListening() {
        val currentModel = model ?: run {
            _speechHint.value = "语音模型未就绪"
            return
        }

        stopCurrentService()
        wakeWordTriggered = false
        val gen = serviceGeneration

        try {
            val recognizer = Recognizer(currentModel, SAMPLE_RATE)
            speechService = SpeechService(recognizer, SAMPLE_RATE).also {
                it.startListening(makeWakeWordListener(gen), WAKE_WORD_TIMEOUT_MS)
            }
            _mode.value = SpeechMode.WAKE_WORD_LISTENING
            _isListening.value = false
            _speechHint.value = "等待唤醒词「$wakeWord」..."
            Log.i(TAG, "Wake word listening started (gen=$gen, word=$wakeWord)")
        } catch (e: IOException) {
            _speechHint.value = "启动唤醒词监听失败: ${e.message}"
            _mode.value = SpeechMode.IDLE
            Log.e(TAG, "startWakeWordListening failed", e)
        }
    }

    fun stopWakeWordListening() {
        stopCurrentService()
        _mode.value = SpeechMode.IDLE
        _speechHint.value = ""
    }

    // ── Lifecycle ─────────────────────────────────────────────────────

    fun destroy() {
        stopCurrentService()
        model?.close()
        model = null
    }

    // ── Private helpers ───────────────────────────────────────────────

    private fun stopCurrentService() {
        serviceGeneration++
        cancelSilenceTimer()
        val service = speechService
        speechService = null
        _isListening.value = false
        if (service != null) {
            service.setPause(true)
            try {
                service.shutdown()
            } catch (e: Exception) {
                Log.w(TAG, "Error during service shutdown", e)
            }
        }
        Log.d(TAG, "stopCurrentService, generation now=$serviceGeneration")
    }

    private fun startFullRecognition() {
        val currentModel = model ?: run {
            _speechHint.value = "语音模型未就绪"
            return
        }

        stopCurrentService()
        _autoStopped.value = false
        lastPartialText = ""
        // Don't override triggeredByWakeWord here — it's set before this call
        val gen = serviceGeneration

        try {
            _speechText.value = ""
            val recognizer = Recognizer(currentModel, SAMPLE_RATE)
            speechService = SpeechService(recognizer, SAMPLE_RATE).also {
                it.startListening(makeRecognitionListener(gen))
            }
            _mode.value = SpeechMode.RECOGNIZING
            _isListening.value = true
            _speechHint.value = "正在聆听..."
            startSilenceTimer()
            Log.i(TAG, "Full recognition started (gen=$gen)")
        } catch (e: IOException) {
            _speechHint.value = "启动录音失败: ${e.message}"
            _mode.value = SpeechMode.IDLE
            Log.e(TAG, "startFullRecognition failed", e)
        }
    }

    // ── Silence timer ─────────────────────────────────────────────────

    private fun startSilenceTimer() {
        cancelSilenceTimer()
        silenceRunnable = Runnable {
            if (_mode.value == SpeechMode.RECOGNIZING) {
                Log.i(TAG, "Silence timeout (${SILENCE_TIMEOUT_MS}ms), auto-stopping")
                _autoStopped.value = true
                speechService?.stop()
            }
        }
        mainHandler.postDelayed(silenceRunnable!!, SILENCE_TIMEOUT_MS)
    }

    private fun resetSilenceTimer() {
        silenceRunnable?.let {
            mainHandler.removeCallbacks(it)
            mainHandler.postDelayed(it, SILENCE_TIMEOUT_MS)
        }
    }

    private fun cancelSilenceTimer() {
        silenceRunnable?.let { mainHandler.removeCallbacks(it) }
        silenceRunnable = null
    }

    // ── Other private helpers ─────────────────────────────────────────

    private fun isModelDownloaded(): Boolean {
        return modelDir.exists() && File(modelDir, "conf").exists()
    }

    private fun loadModel() {
        try {
            model = Model(modelDir.absolutePath)
            _isModelReady.value = true
            _speechHint.value = "语音模型已就绪"
        } catch (e: Exception) {
            _speechHint.value = "模型加载失败: ${e.message}"
        }
    }

    private fun downloadModel() {
        val zipFile = File(context.cacheDir, "$MODEL_NAME.zip")
        try {
            val url = URL(MODEL_URL)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 30_000
            connection.readTimeout = 120_000
            connection.connect()

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("HTTP ${connection.responseCode}")
            }

            val totalBytes = connection.contentLengthLong
            var downloadedBytes = 0L

            connection.inputStream.buffered().use { input ->
                zipFile.outputStream().buffered().use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead
                        if (totalBytes > 0) {
                            val pct = downloadedBytes * 100 / totalBytes
                            _downloadProgress.value =
                                downloadedBytes.toFloat() / totalBytes
                            _speechHint.value = "下载中 $pct%..."
                        }
                    }
                }
            }

            _speechHint.value = "解压模型文件..."
            extractZip(zipFile)
        } finally {
            zipFile.delete()
        }
    }

    private fun extractZip(zipFile: File) {
        ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val outFile = File(context.filesDir, entry.name)
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    outFile.outputStream().buffered().use { out ->
                        zis.copyTo(out)
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    // ── Listener factories (each captures a generation to guard staleness) ──

    private fun makeWakeWordListener(gen: Int) = object : RecognitionListener {

        override fun onPartialResult(hypothesis: String?) {
            if (gen != serviceGeneration) return
            val text = parseJson(hypothesis, "partial")
            if (text.isNotBlank()) {
                Log.d(TAG, "wakeWord partial: $text")
            }
            if (text.contains(wakeWord) && !wakeWordTriggered) {
                wakeWordTriggered = true
                _speechHint.value = "唤醒成功！请说话..."
                Log.i(TAG, "Wake word detected in partial: $text")
                speechService?.stop()
            }
        }

        override fun onResult(hypothesis: String?) {
            if (gen != serviceGeneration) return
            val text = parseJson(hypothesis, "text")
            if (text.isNotBlank()) {
                Log.d(TAG, "wakeWord result: $text")
            }
            if (text.contains(wakeWord) && !wakeWordTriggered) {
                wakeWordTriggered = true
                _speechHint.value = "唤醒成功！请说话..."
                Log.i(TAG, "Wake word detected in result: $text")
                speechService?.stop()
            }
        }

        override fun onFinalResult(hypothesis: String?) {
            if (gen != serviceGeneration) {
                Log.d(TAG, "wakeWord onFinalResult STALE (gen=$gen, current=$serviceGeneration)")
                return
            }
            val triggered = wakeWordTriggered
            wakeWordTriggered = false
            Log.d(TAG, "wakeWord onFinalResult, triggered=$triggered")

            if (triggered) {
                triggeredByWakeWord = true
                startFullRecognition()
            }
        }

        override fun onError(e: Exception?) {
            if (gen != serviceGeneration) return
            Log.e(TAG, "wakeWord onError", e)
            stopCurrentService()
            _mode.value = SpeechMode.IDLE
            _speechHint.value = "唤醒词监听错误: ${e?.message}"
        }

        override fun onTimeout() {
            if (gen != serviceGeneration) return
            Log.d(TAG, "wakeWord onTimeout, restarting")
            speechService?.shutdown()
            speechService = null
            startWakeWordListening()
        }
    }

    private fun makeRecognitionListener(gen: Int) = object : RecognitionListener {

        override fun onPartialResult(hypothesis: String?) {
            if (gen != serviceGeneration) return
            val text = parseJson(hypothesis, "partial")
            if (text.isNotBlank() && text != lastPartialText) {
                lastPartialText = text
                _speechText.value = text
                resetSilenceTimer()
            }
        }

        override fun onResult(hypothesis: String?) {
            if (gen != serviceGeneration) return
            val text = parseJson(hypothesis, "text")
            if (text.isNotBlank()) {
                _speechText.value = text
                lastPartialText = ""
                resetSilenceTimer()
            }
        }

        override fun onFinalResult(hypothesis: String?) {
            if (gen != serviceGeneration) {
                Log.d(TAG, "recognition onFinalResult STALE (gen=$gen, current=$serviceGeneration)")
                return
            }
            cancelSilenceTimer()
            val text = parseJson(hypothesis, "text")
            if (text.isNotBlank()) _speechText.value = text
            val hasText = _speechText.value.isNotBlank()
            val wasFromWakeWord = triggeredByWakeWord
            triggeredByWakeWord = false

            if (hasText) {
                _speechHint.value = "识别完成"
                _isListening.value = false
                _mode.value = SpeechMode.IDLE
                speechService?.shutdown()
                speechService = null
            } else if (wasFromWakeWord) {
                Log.i(TAG, "Empty result from wake-word flow, restarting wake word")
                _isListening.value = false
                speechService?.shutdown()
                speechService = null
                startWakeWordListening()
            } else {
                _speechHint.value = "未识别到内容"
                _isListening.value = false
                _mode.value = SpeechMode.IDLE
                speechService?.shutdown()
                speechService = null
            }
            Log.i(TAG, "recognition onFinalResult: text='$text' hasText=$hasText wasFromWakeWord=$wasFromWakeWord")
        }

        override fun onError(e: Exception?) {
            if (gen != serviceGeneration) return
            cancelSilenceTimer()
            val wasFromWakeWord = triggeredByWakeWord
            triggeredByWakeWord = false
            Log.e(TAG, "recognition onError", e)
            _isListening.value = false
            _speechHint.value = "识别错误: ${e?.message}"
            speechService?.shutdown()
            speechService = null
            if (wasFromWakeWord) {
                startWakeWordListening()
            } else {
                _mode.value = SpeechMode.IDLE
            }
        }

        override fun onTimeout() {
            if (gen != serviceGeneration) return
            cancelSilenceTimer()
            val wasFromWakeWord = triggeredByWakeWord
            triggeredByWakeWord = false
            Log.d(TAG, "recognition onTimeout")
            _isListening.value = false
            _speechHint.value = "语音超时，请重试"
            speechService?.shutdown()
            speechService = null
            if (wasFromWakeWord) {
                startWakeWordListening()
            } else {
                _mode.value = SpeechMode.IDLE
            }
        }
    }

    // ── JSON parsing ──────────────────────────────────────────────────

    private fun parseJson(json: String?, key: String): String {
        if (json.isNullOrBlank()) return ""
        return try {
            JSONObject(json).optString(key, "")
        } catch (_: Exception) {
            ""
        }
    }
}
