package com.hardware.littlebot

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.alibaba.dashscope.aigc.generation.Generation
import com.alibaba.dashscope.aigc.generation.GenerationParam
import com.alibaba.dashscope.aigc.generation.GenerationResult
import com.alibaba.dashscope.common.Message
import com.alibaba.dashscope.common.Role
import com.alibaba.dashscope.exception.ApiException
import com.alibaba.dashscope.exception.InputRequiredException
import com.alibaba.dashscope.exception.NoApiKeyException
import com.hardware.littlebot.ai.AiRules
import com.hardware.littlebot.speech.SpeechMode
import com.hardware.littlebot.speech.TtsManager
import com.hardware.littlebot.speech.VoskSpeechManager
import com.hardware.littlebot.ui.theme.LittleBotTheme
import com.hardware.littlebot.wifi.WifiConnectionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AiApiTestActivity : ComponentActivity() {

    private lateinit var voskManager: VoskSpeechManager
    private lateinit var ttsManager: TtsManager
    private lateinit var wifiManager: WifiConnectionManager
    private var pendingAction: (() -> Unit)? = null

    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            pendingAction?.invoke()
        }
        pendingAction = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        installVoskCrashGuard()

        voskManager = VoskSpeechManager(applicationContext)
        ttsManager = TtsManager(applicationContext)
        wifiManager = WifiConnectionManager()

        lifecycleScope.launch { voskManager.initModel() }

        setContent {
            LittleBotTheme {
                AiApiTestScreen(
                    voskManager = voskManager,
                    ttsManager = ttsManager,
                    wifiManager = wifiManager,
                    onBack = { finish() },
                    onToggleListening = { toggleListening() },
                    onToggleWakeWord = { enabled -> toggleWakeWord(enabled) },
                    onRestartWakeWord = { restartWakeWord() },
                    onSetWakeWord = { word -> voskManager.wakeWord = word }
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        voskManager.destroy()
        ttsManager.destroy()
        wifiManager.destroy()
    }

    private fun ensureAudioPermission(action: () -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            action()
        } else {
            pendingAction = action
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun toggleListening() {
        ttsManager.stop()

        if (voskManager.isListening.value) {
            voskManager.stopListening()
            return
        }

        if (voskManager.isDownloading.value) return

        if (!voskManager.isModelReady.value) {
            lifecycleScope.launch { voskManager.downloadAndInitModel() }
            return
        }

        ensureAudioPermission { voskManager.startListening() }
    }

    private fun toggleWakeWord(enabled: Boolean) {
        if (enabled) {
            if (!voskManager.isModelReady.value) {
                lifecycleScope.launch {
                    voskManager.downloadAndInitModel()
                    if (voskManager.isModelReady.value) {
                        ensureAudioPermission { voskManager.startWakeWordListening() }
                    }
                }
            } else {
                ensureAudioPermission { voskManager.startWakeWordListening() }
            }
        } else {
            voskManager.stopWakeWordListening()
        }
    }

    private fun restartWakeWord() {
        if (voskManager.isModelReady.value) {
            voskManager.startWakeWordListening()
        }
    }

    private fun installVoskCrashGuard() {
        val existing = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            if (throwable is RuntimeException &&
                throwable.message?.contains("error reading audio buffer") == true
            ) {
                Log.w("VoskCrashGuard", "Suppressed Vosk audio read error on ${thread.name}", throwable)
            } else {
                existing?.uncaughtException(thread, throwable)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AiApiTestScreen(
    voskManager: VoskSpeechManager,
    ttsManager: TtsManager,
    wifiManager: WifiConnectionManager,
    onBack: () -> Unit,
    onToggleListening: () -> Unit,
    onToggleWakeWord: (Boolean) -> Unit,
    onRestartWakeWord: () -> Unit,
    onSetWakeWord: (String) -> Unit
) {
    val speechText by voskManager.speechText.collectAsState()
    val isListening by voskManager.isListening.collectAsState()
    val speechHint by voskManager.speechHint.collectAsState()
    val isModelReady by voskManager.isModelReady.collectAsState()
    val isDownloading by voskManager.isDownloading.collectAsState()
    val downloadProgress by voskManager.downloadProgress.collectAsState()
    val mode by voskManager.mode.collectAsState()
    val autoStopped by voskManager.autoStopped.collectAsState()
    val isTtsSpeaking by ttsManager.isSpeaking.collectAsState()
    val wifiConnected by wifiManager.isConnected.collectAsState()

    var apiKey by remember { mutableStateOf("sk-ee8d79ce39f14a4f83597c2d9cca3c64") }
    var model by remember { mutableStateOf("qwen-plus") }
    var prompt by remember { mutableStateOf("你好呀，跟我打个招呼吧！") }
    var loading by remember { mutableStateOf(false) }

    var displayText by remember { mutableStateOf("") }
    var rawResponse by remember { mutableStateOf("") }
    var frames by remember { mutableStateOf<List<AiRules.ServoFrame>>(emptyList()) }
    var executingIndex by remember { mutableIntStateOf(-1) }
    var autoExecute by remember { mutableStateOf(false) }
    var statusHint by remember { mutableStateOf("点击发送开始测试") }

    var wifiIp by remember { mutableStateOf("192.168.31.17") }
    var wakeWordEnabled by remember { mutableStateOf(false) }
    var customWakeWord by remember { mutableStateOf("你好") }
    var ttsEnabled by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()

    fun executeFrames(targetFrames: List<AiRules.ServoFrame>) {
        if (executingIndex >= 0 || targetFrames.isEmpty()) return
        scope.launch {
            for (i in targetFrames.indices) {
                executingIndex = i
                val frame = targetFrames[i]
                if (wifiConnected) {
                    wifiManager.setAngles(frame.yaw, frame.pitch)
                }
                delay(frame.delayMs)
            }
            executingIndex = -1
            statusHint = if (wifiConnected) "舵机动作执行完成" else "动作模拟播放完成（WiFi 未连接）"
        }
    }

    fun doSendToAi(autoRestart: Boolean = false) {
        if (apiKey.isBlank() || prompt.isBlank() || loading) return
        loading = true
        statusHint = "请求中..."
        displayText = ""
        rawResponse = ""
        frames = emptyList()
        executingIndex = -1

        scope.launch {
            val result = callDashScope(apiKey, model, prompt)
            rawResponse = result.first
            if (result.second != null) {
                statusHint = "调用失败"
                displayText = result.second!!
            } else {
                val (text, parsedFrames) = AiRules.parseResponse(rawResponse)
                displayText = text
                frames = parsedFrames
                statusHint = if (parsedFrames.isNotEmpty())
                    "解析到 ${parsedFrames.size} 帧动作指令"
                else
                    "调用成功（无动作指令）"

                if (ttsEnabled && text.isNotBlank()) {
                    ttsManager.speak(text)
                }

                if (autoExecute && parsedFrames.isNotEmpty()) {
                    for (i in parsedFrames.indices) {
                        executingIndex = i
                        val frame = parsedFrames[i]
                        if (wifiConnected) {
                            wifiManager.setAngles(frame.yaw, frame.pitch)
                        }
                        delay(frame.delayMs)
                    }
                    executingIndex = -1
                    statusHint = if (wifiConnected) "舵机动作执行完成"
                    else "动作模拟播放完成（WiFi 未连接）"
                }

                if (ttsEnabled) {
                    while (ttsManager.isSpeaking.value) { delay(200) }
                }
            }

            loading = false

            if (autoRestart && wakeWordEnabled) {
                delay(500)
                onRestartWakeWord()
            }
        }
    }

    LaunchedEffect(speechText) {
        if (speechText.isNotBlank()) {
            prompt = speechText
        }
    }

    // After recognition completes (silence auto-stop or wake word flow), auto-send
    var prevMode by remember { mutableStateOf(SpeechMode.IDLE) }
    LaunchedEffect(mode) {
        val wasRecognizing = prevMode == SpeechMode.RECOGNIZING
        prevMode = mode
        if (wasRecognizing && mode == SpeechMode.IDLE) {
            delay(300)
            if (speechText.isNotBlank() && (autoStopped || wakeWordEnabled)) {
                doSendToAi(autoRestart = true)
            } else if (wakeWordEnabled) {
                onRestartWakeWord()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("大模型 API 测试") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // ── WiFi connection card ──────────────────────────────────────
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (wifiConnected)
                        MaterialTheme.colorScheme.primaryContainer
                    else
                        MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .background(
                                    if (wifiConnected) Color(0xFF4CAF50) else Color(0xFFBDBDBD),
                                    CircleShape
                                )
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Icon(
                            if (wifiConnected) Icons.Default.Wifi else Icons.Default.WifiOff,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (wifiConnected) "WiFi 已连接 — 动作将驱动真实舵机"
                            else "WiFi 未连接 — 仅模拟播放",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f)
                        )
                        if (wifiConnected) {
                            OutlinedButton(
                                onClick = { wifiManager.disconnect() },
                                modifier = Modifier.height(32.dp)
                            ) {
                                Icon(Icons.Default.LinkOff, null, Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("断开", fontSize = 12.sp)
                            }
                        }
                    }

                    if (!wifiConnected) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = wifiIp,
                                onValueChange = { wifiIp = it.trim() },
                                label = { Text("ESP32 IP") },
                                placeholder = { Text("192.168.x.x") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                                leadingIcon = {
                                    Icon(Icons.Default.Wifi, null, Modifier.size(18.dp))
                                }
                            )
                            Button(
                                onClick = { wifiManager.connect(wifiIp) },
                                enabled = wifiIp.isNotBlank(),
                                modifier = Modifier.height(56.dp)
                            ) {
                                Text("连接")
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ── Config section ────────────────────────────────────────────
            Text("配置", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it.trim() },
                label = { Text("API Key") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = model,
                onValueChange = { model = it.trim() },
                label = { Text("模型") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "自动执行动作",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        if (autoExecute) "解析到指令后立即执行"
                        else "需手动点击播放按钮",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = autoExecute,
                    onCheckedChange = { autoExecute = it },
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = MaterialTheme.colorScheme.primary
                    )
                )
            }
            Spacer(modifier = Modifier.height(6.dp))

            // ── Wake word switch ──────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Hearing,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = if (wakeWordEnabled) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "唤醒词模式",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        if (wakeWordEnabled) "监听中：说「$customWakeWord」唤醒"
                        else "关闭：手动点击麦克风按钮",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = wakeWordEnabled,
                    onCheckedChange = { enabled ->
                        wakeWordEnabled = enabled
                        if (!enabled) ttsManager.stop()
                        onToggleWakeWord(enabled)
                    },
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = MaterialTheme.colorScheme.primary
                    )
                )
            }

            if (wakeWordEnabled) {
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedTextField(
                    value = customWakeWord,
                    onValueChange = { newWord ->
                        if (newWord.isNotBlank()) {
                            customWakeWord = newWord
                            onSetWakeWord(newWord)
                        }
                    },
                    label = { Text("自定义唤醒词") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = {
                        Icon(Icons.Default.Hearing, null, Modifier.size(18.dp))
                    }
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            // ── TTS switch ────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    if (ttsEnabled) Icons.AutoMirrored.Filled.VolumeUp
                    else Icons.Default.VolumeOff,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = if (ttsEnabled) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "语音播报",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        if (ttsEnabled) "AI 回复后自动朗读"
                        else "仅文字显示",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = ttsEnabled,
                    onCheckedChange = { enabled ->
                        ttsEnabled = enabled
                        if (!enabled) ttsManager.stop()
                    },
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = MaterialTheme.colorScheme.primary
                    )
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ── Prompt section ────────────────────────────────────────────
            Text("提问", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                label = {
                    Text(
                        when {
                            isListening -> "正在聆听..."
                            mode == SpeechMode.WAKE_WORD_LISTENING -> "等待唤醒词..."
                            isTtsSpeaking -> "正在播报..."
                            else -> "输入内容"
                        }
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(100.dp)
            )
            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val micColor by animateColorAsState(
                    targetValue = when {
                        isDownloading -> MaterialTheme.colorScheme.surfaceVariant
                        mode == SpeechMode.WAKE_WORD_LISTENING -> MaterialTheme.colorScheme.tertiaryContainer
                        isListening -> MaterialTheme.colorScheme.error
                        !isModelReady -> MaterialTheme.colorScheme.tertiaryContainer
                        else -> MaterialTheme.colorScheme.secondaryContainer
                    },
                    label = "micColor"
                )
                SmallFloatingActionButton(
                    onClick = { if (!isDownloading) onToggleListening() },
                    containerColor = micColor,
                    modifier = Modifier.size(48.dp)
                ) {
                    when {
                        isDownloading -> CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.5.dp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        !isModelReady -> Icon(
                            Icons.Default.FileDownload,
                            contentDescription = "下载语音模型",
                            tint = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                        isListening -> Icon(
                            Icons.Default.MicOff,
                            contentDescription = "停止录音",
                            tint = MaterialTheme.colorScheme.onError
                        )
                        mode == SpeechMode.WAKE_WORD_LISTENING -> Icon(
                            Icons.Default.Hearing,
                            contentDescription = "唤醒词监听中",
                            tint = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                        else -> Icon(
                            Icons.Default.Mic,
                            contentDescription = "开始录音",
                            tint = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }

                Button(
                    onClick = { doSendToAi() },
                    enabled = !loading,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                ) {
                    if (loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("请求中...")
                    } else {
                        Icon(Icons.Default.Send, null, Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("发送")
                    }
                }
            }

            if (isDownloading) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { downloadProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp),
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
            }

            if (speechHint.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    speechHint,
                    style = MaterialTheme.typography.labelMedium,
                    color = when {
                        isListening -> MaterialTheme.colorScheme.error
                        isDownloading -> MaterialTheme.colorScheme.primary
                        mode == SpeechMode.WAKE_WORD_LISTENING -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }

            if (isTtsSpeaking) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 1.5.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        "正在语音播报...",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                statusHint,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))

            // ── AI reply section ──────────────────────────────────────────
            Text(
                "AI 回复",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Text(
                    text = displayText.ifEmpty { "——" },
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ── Servo frames section ──────────────────────────────────────
            Text(
                "动作指令",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))

            if (frames.isEmpty()) {
                Text(
                    "暂无动作帧",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                frames.forEachIndexed { index, frame ->
                    val isActive = index == executingIndex
                    val bgColor by animateColorAsState(
                        targetValue = if (isActive)
                            MaterialTheme.colorScheme.primaryContainer
                        else
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        label = "frameBg"
                    )

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = CardDefaults.cardColors(containerColor = bgColor)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .background(
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                        RoundedCornerShape(6.dp)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "${index + 1}",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                "Yaw=${frame.yaw}°  Pitch=${frame.pitch}°  ${frame.delayMs}ms",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.weight(1f)
                            )
                            if (isActive) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                FilledTonalButton(
                    onClick = { executeFrames(frames) },
                    enabled = executingIndex < 0 && frames.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        when {
                            autoExecute -> "重新播放"
                            wifiConnected -> "执行动作"
                            else -> "模拟播放动作"
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))

            // ── Raw response section ──────────────────────────────────────
            Text(
                "原始返回",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = rawResponse.ifEmpty { "——" },
                onValueChange = {},
                readOnly = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp),
                textStyle = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace
                )
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

private suspend fun callDashScope(
    apiKey: String,
    model: String,
    prompt: String
): Pair<String, String?> = withContext(Dispatchers.IO) {
    try {
        val systemMessage = Message.builder()
            .role(Role.SYSTEM.getValue())
            .content(AiRules.SYSTEM_PROMPT)
            .build()

        val userMessage = Message.builder()
            .role(Role.USER.getValue())
            .content(prompt)
            .build()

        val param = GenerationParam.builder()
            .apiKey(apiKey)
            .model(model)
            .messages(listOf(systemMessage, userMessage))
            .resultFormat(GenerationParam.ResultFormat.MESSAGE)
            .build()

        val generation = Generation()
        val result: GenerationResult = generation.call(param)

        val content = result.output?.choices
            ?.firstOrNull()
            ?.message
            ?.content
            ?: result.toString()

        content to null
    } catch (e: NoApiKeyException) {
        "" to "API Key 无效：${e.message}"
    } catch (e: InputRequiredException) {
        "" to "参数不完整：${e.message}"
    } catch (e: ApiException) {
        "" to "API 异常：${e.message}"
    } catch (e: Exception) {
        "" to "未知错误：${e.message ?: e.javaClass.simpleName}"
    }
}
