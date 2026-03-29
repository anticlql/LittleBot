package com.hardware.littlebot.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hardware.littlebot.ble.ActiveConnectionMode
import com.hardware.littlebot.ble.BleManager
import com.hardware.littlebot.ble.ScannedDevice
import com.hardware.littlebot.protocol.ESP32Protocol
import com.hardware.littlebot.wifi.WifiConnectionManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ── Default posture ───────────────────────────────────────────────────────────

const val DEFAULT_YAW   = 125
const val DEFAULT_PITCH = 85

// ── Preset actions ────────────────────────────────────────────────────────────

/** A single keyframe: target yaw/pitch + how long to hold before the next frame (ms). */
data class PresetFrame(val yaw: Int, val pitch: Int, val holdMs: Long = 500L)

data class PresetAction(val id: String, val label: String, val frames: List<PresetFrame>)

val PRESET_ACTIONS: List<PresetAction> = listOf(
    PresetAction(
        id = "nod", label = "点头",
        frames = listOf(
            PresetFrame(125, 50,  450),
            PresetFrame(125, 85,  350),
            PresetFrame(125, 50,  450),
        )
    ),
    PresetAction(
        id = "shake", label = "摇头",
        frames = listOf(
            PresetFrame(90,  85, 550),
            PresetFrame(160, 85, 550),
            PresetFrame(90,  85, 550),
        )
    ),
    PresetAction(
        id = "sway", label = "摇摆",
        frames = listOf(
            PresetFrame(100, 75, 500),
            PresetFrame(150, 95, 500),
            PresetFrame(100, 75, 500),
            PresetFrame(150, 95, 500),
        )
    ),
    PresetAction(
        id = "lookup", label = "昂首",
        frames = listOf(
            PresetFrame(125, 140, 800),
        )
    ),
    PresetAction(
        id = "dive", label = "俯瞰",
        frames = listOf(
            PresetFrame(125, 35, 800),
        )
    ),
    PresetAction(
        id = "scan", label = "环视",
        frames = listOf(
            PresetFrame(50,  85,  900),
            PresetFrame(125, 70,  600),
            PresetFrame(170, 85, 900),
        )
    ),
    PresetAction(
        id = "patrol", label = "巡视",
        frames = listOf(
            PresetFrame(40,  85, 1000),
            PresetFrame(170, 85, 1300),
        )
    ),
    PresetAction(
        id = "greet", label = "致意",
        frames = listOf(
            PresetFrame(125, 115, 380),
            PresetFrame(125, 55,  380),
            PresetFrame(125, 115, 380),
            PresetFrame(125, 55,  380),
        )
    ),
    PresetAction(
        id = "recon", label = "侦察",
        frames = listOf(
            PresetFrame(70,  65,  700),
            PresetFrame(125, 85,  300),
            PresetFrame(170, 65,  700),
            PresetFrame(125, 110, 600),
        )
    ),
    PresetAction(
        id = "alert", label = "警觉",
        frames = listOf(
            PresetFrame(125, 130, 300),
            PresetFrame(80,  85,  400),
            PresetFrame(170, 85,  400),
            PresetFrame(125, 130, 300),
        )
    ),
)

// ── Data models ───────────────────────────────────────────────────────────────

enum class ConnectionType { NONE, BLE, WIFI }

data class ServoState(
    val channel: Int = 0,
    val angle: Float = 90f,
    val speed: Float = 50f
)

data class LogEntry(
    val time: String,
    val message: String,
    val isError: Boolean = false
)

data class ESP32UiState(
    val connectionType: ConnectionType = ConnectionType.NONE,
    val isConnected: Boolean = false,
    val isScanning: Boolean = false,
    val bleDevices: List<ScannedDevice> = emptyList(),
    val connectedDeviceName: String? = null,
    val wifiIp: String = "192.168.4.1",
    val wifiPort: String = "80",
    val servoStates: List<ServoState> = listOf(
        ServoState(channel = 0, angle = DEFAULT_YAW.toFloat()),    // Yaw
        ServoState(channel = 1, angle = DEFAULT_PITCH.toFloat())   // Pitch
    ),
    val selectedServoIndex: Int = 0,
    val logMessages: List<LogEntry> = emptyList(),
    val showConnectionSheet: Boolean = false,
    val locationWarning: Boolean = false,
    /** BLE channels that have a matching characteristic on the device. */
    val bleDiscoveredChannels: Set<Int> = emptySet(),
    /** ESP32 step delay in ms (0–100). BLE GATT only. */
    val stepDelayMs: Int = 2,
    /** ESP32 step size in degrees (1–180). BLE GATT only. */
    val stepSizeDeg: Int = 10,
    /** When true, dragging the angle slider immediately sends the angle. */
    val liveAngleUpdate: Boolean = false,
    /** True while a preset action coroutine is running. */
    val isExecutingAction: Boolean = false,
    /** ESP32 exponential smooth alpha (0.01–0.30). WiFi HTTP only. */
    val smoothAlpha: Float = 0.08f
) {
    val currentServo: ServoState
        get() = servoStates[selectedServoIndex]
}

// ── ViewModel ────────────────────────────────────────────────────────────────

class ESP32ViewModel(application: Application) : AndroidViewModel(application) {

    val bleManager = BleManager.getInstance(application)
    val wifiManager = WifiConnectionManager()

    private val _uiState = MutableStateFlow(ESP32UiState())
    val uiState: StateFlow<ESP32UiState> = _uiState.asStateFlow()

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    init {
        // ── BLE / Classic BT ─────────────────────────────────────────────
        viewModelScope.launch {
            bleManager.scannedDevices.collect { devices ->
                _uiState.update { it.copy(bleDevices = devices) }
            }
        }
        viewModelScope.launch {
            bleManager.isScanning.collect { scanning ->
                _uiState.update { it.copy(isScanning = scanning) }
            }
        }
        viewModelScope.launch {
            bleManager.isConnected.collect { connected ->
                if (_uiState.value.connectionType == ConnectionType.BLE || connected) {
                    _uiState.update {
                        it.copy(isConnected = connected, showConnectionSheet = !connected)
                    }
                    if (connected) addLog("蓝牙已连接")
                }
            }
        }
        viewModelScope.launch {
            bleManager.connectedDeviceName.collect { name ->
                _uiState.update { it.copy(connectedDeviceName = name) }
            }
        }
        viewModelScope.launch {
            bleManager.receivedData.collect { data ->
                if (data.isNotEmpty()) addLog("← $data")
            }
        }
        viewModelScope.launch {
            bleManager.discoveredChannels.collect { channels ->
                _uiState.update { it.copy(bleDiscoveredChannels = channels) }
                if (channels.isNotEmpty()) {
                    val labels = channels.sorted().joinToString(", ") { ch ->
                        val label = BleManager.CHANNEL_LABELS[ch]
                        if (label != null) "CH$ch($label)" else "CH$ch"
                    }
                    addLog("发现 BLE 舵机通道: $labels")
                }
            }
        }

        // ── WiFi HTTP ────────────────────────────────────────────────────
        viewModelScope.launch {
            wifiManager.isConnected.collect { connected ->
                if (_uiState.value.connectionType == ConnectionType.WIFI || connected) {
                    _uiState.update {
                        it.copy(
                            isConnected = connected,
                            connectedDeviceName = if (connected) _uiState.value.wifiIp else null,
                            showConnectionSheet = !connected
                        )
                    }
                    if (connected) addLog("WiFi 已连接")
                }
            }
        }
        viewModelScope.launch {
            wifiManager.receivedData.collect { data ->
                if (data.isNotEmpty()) addLog("← $data")
            }
        }
        viewModelScope.launch {
            wifiManager.status.collect { st ->
                if (isWifiConnected()) {
                    _uiState.update { it.copy(smoothAlpha = st.alpha) }
                }
            }
        }
    }

    // ── Connection actions ───────────────────────────────────────────────

    fun startBleScan() {
        if (!bleManager.isLocationEnabled()) {
            _uiState.update { it.copy(locationWarning = true) }
            addLog("⚠ 请开启手机定位服务，否则可能无法扫描设备", isError = true)
        } else {
            _uiState.update { it.copy(locationWarning = false) }
        }
        _uiState.update { it.copy(connectionType = ConnectionType.BLE) }
        bleManager.startScan()
        addLog("开始扫描蓝牙设备 (BLE + 经典)...")
    }

    fun stopBleScan() = bleManager.stopScan()

    fun connectBle(scannedDevice: ScannedDevice) {
        _uiState.update { it.copy(connectionType = ConnectionType.BLE) }
        bleManager.connect(scannedDevice)
        addLog("正在连接 ${scannedDevice.name ?: scannedDevice.address} [${scannedDevice.type}]...")
    }

    fun connectWifi() {
        val state = _uiState.value
        _uiState.update { it.copy(connectionType = ConnectionType.WIFI) }
        val port = state.wifiPort.toIntOrNull() ?: 80
        wifiManager.connect(state.wifiIp, port)
        val url = "http://${state.wifiIp}${if (port != 80) ":$port" else ""}"
        addLog("正在连接 $url ...")
    }

    fun disconnect() {
        when (_uiState.value.connectionType) {
            ConnectionType.BLE -> bleManager.disconnect()
            ConnectionType.WIFI -> wifiManager.disconnect()
            ConnectionType.NONE -> {}
        }
        _uiState.update {
            it.copy(
                isConnected = false,
                connectionType = ConnectionType.NONE,
                showConnectionSheet = true,
                connectedDeviceName = null,
                bleDiscoveredChannels = emptySet(),
                isExecutingAction = false
            )
        }
        addLog("已断开连接")
    }

    fun dismissLocationWarning() {
        _uiState.update { it.copy(locationWarning = false) }
    }

    // ── Input updates ────────────────────────────────────────────────────

    fun updateWifiIp(ip: String) { _uiState.update { it.copy(wifiIp = ip) } }
    fun updateWifiPort(port: String) { _uiState.update { it.copy(wifiPort = port) } }

    fun updateServoAngle(angle: Float) {
        updateCurrentServo { it.copy(angle = angle) }
        if (_uiState.value.liveAngleUpdate) {
            val channel = _uiState.value.currentServo.channel
            sendAngleForChannel(channel, angle.toInt())
        }
    }

    fun updateServoSpeed(speed: Float) { updateCurrentServo { it.copy(speed = speed) } }
    fun updateServoChannel(channel: Int) { updateCurrentServo { it.copy(channel = channel) } }

    /** Update a single servo by channel number, updating UI state and sending to device. */
    fun updateServoByChannel(channel: Int, angle: Float) {
        val servos = _uiState.value.servoStates.toMutableList()
        for (i in servos.indices) {
            if (servos[i].channel == channel) {
                servos[i] = servos[i].copy(angle = angle)
            }
        }
        _uiState.update { it.copy(servoStates = servos) }
        sendAngleForChannel(channel, angle.toInt())
    }

    fun resetHead() {
        viewModelScope.launch {
            updateServoByChannel(0, DEFAULT_YAW.toFloat())
            delay(120)
            updateServoByChannel(1, DEFAULT_PITCH.toFloat())
            addLog("→ 复位 Yaw=${DEFAULT_YAW}° Pitch=${DEFAULT_PITCH}°")
        }
    }

    fun toggleLiveAngleUpdate() {
        _uiState.update { it.copy(liveAngleUpdate = !it.liveAngleUpdate) }
        addLog(if (_uiState.value.liveAngleUpdate) "已开启即时发送" else "已关闭即时发送")
    }

    fun updateStepDelay(ms: Int) { _uiState.update { it.copy(stepDelayMs = ms.coerceIn(0, 100)) } }
    fun updateStepSize(deg: Int) { _uiState.update { it.copy(stepSizeDeg = deg.coerceIn(1, 180)) } }
    fun updateSmoothAlpha(alpha: Float) {
        _uiState.update { it.copy(smoothAlpha = alpha.coerceIn(0.01f, 0.30f)) }
    }

    fun selectServo(index: Int) { _uiState.update { it.copy(selectedServoIndex = index) } }

    fun addServo() {
        val servos = _uiState.value.servoStates.toMutableList()
        val next = (servos.maxOfOrNull { it.channel } ?: -1) + 1
        servos.add(ServoState(channel = next))
        _uiState.update { it.copy(servoStates = servos, selectedServoIndex = servos.size - 1) }
    }

    fun removeServo(index: Int) {
        if (_uiState.value.servoStates.size <= 1) return
        val servos = _uiState.value.servoStates.toMutableList()
        servos.removeAt(index)
        val newIdx = _uiState.value.selectedServoIndex.coerceAtMost(servos.size - 1)
        _uiState.update { it.copy(servoStates = servos, selectedServoIndex = newIdx) }
    }

    // ── Command actions ──────────────────────────────────────────────────

    /** Send the currently selected servo's angle. */
    fun sendServoCommand() {
        val servo = _uiState.value.currentServo
        val angle = servo.angle.toInt()
        val channel = servo.channel
        val label = BleManager.CHANNEL_LABELS[channel]
        val chDisplay = if (label != null) "CH$channel($label)" else "CH$channel"

        when {
            isBleGatt() -> {
                val ok = bleManager.sendAngleToChannel(channel, angle)
                if (ok) addLog("→ $chDisplay = ${angle}°")
                else addLog("⚠ $chDisplay 无对应 BLE 特征值，无法发送", isError = true)
            }

            isClassicSpp() -> {
                val cmd = ESP32Protocol.servoCommand(channel, angle, servo.speed.toInt())
                val ok = bleManager.sendClassicCommand(cmd)
                if (ok) addLog("→ ${cmd.trim()}") else addLog("⚠ 发送失败", isError = true)
            }

            isWifiConnected() -> {
                when (channel) {
                    0 -> wifiManager.setYaw(angle)
                    1 -> wifiManager.setPitch(angle)
                }
                addLog("→ $chDisplay = ${angle}°")
            }

            else -> addLog("⚠ 未连接设备", isError = true)
        }
    }

    /** Send all servos' angles at once. */
    fun sendAllServosCommand() {
        when {
            isBleGatt() -> {
                var sent = 0
                _uiState.value.servoStates.forEach { servo ->
                    if (bleManager.sendAngleToChannel(servo.channel, servo.angle.toInt())) {
                        sent++
                    }
                }
                addLog("→ 批量发送 $sent 个通道")
            }

            isClassicSpp() -> {
                val triples = _uiState.value.servoStates.map {
                    Triple(it.channel, it.angle.toInt(), it.speed.toInt())
                }
                val cmd = ESP32Protocol.multiServoCommand(triples)
                val ok = bleManager.sendClassicCommand(cmd)
                if (ok) addLog("→ ${cmd.trim()}") else addLog("⚠ 发送失败", isError = true)
            }

            isWifiConnected() -> {
                val yaw = _uiState.value.servoStates
                    .firstOrNull { it.channel == 0 }?.angle?.toInt()
                val pitch = _uiState.value.servoStates
                    .firstOrNull { it.channel == 1 }?.angle?.toInt()
                if (yaw != null && pitch != null) {
                    wifiManager.setAngles(yaw, pitch)
                } else {
                    yaw?.let { wifiManager.setYaw(it) }
                    pitch?.let { wifiManager.setPitch(it) }
                }
                addLog("→ 批量发送 Yaw=${yaw ?: "-"}° Pitch=${pitch ?: "-"}°")
            }

            else -> addLog("⚠ 未连接设备", isError = true)
        }
    }

    fun sendResetAll() {
        when {
            isBleGatt() -> {
                bleManager.sendAngleToChannel(0, DEFAULT_YAW)
                bleManager.sendAngleToChannel(1, DEFAULT_PITCH)
                resetServoStates()
                addLog("→ 复位到默认姿势 (Yaw=${DEFAULT_YAW}°, Pitch=${DEFAULT_PITCH}°)")
            }

            isWifiConnected() -> {
                wifiManager.setAngles(DEFAULT_YAW, DEFAULT_PITCH)
                resetServoStates()
                addLog("→ 复位到默认姿势 (Yaw=${DEFAULT_YAW}°, Pitch=${DEFAULT_PITCH}°)")
            }

            else -> sendTextCommand(ESP32Protocol.resetAll())
        }
    }

    fun sendStopAll() {
        when {
            isBleGatt() -> {
                _uiState.value.servoStates.forEach { s ->
                    bleManager.sendAngleToChannel(s.channel, s.angle.toInt())
                }
                addLog("→ 急停 (保持当前角度)")
            }

            isWifiConnected() -> {
                val yaw = _uiState.value.servoStates
                    .firstOrNull { it.channel == 0 }?.angle?.toInt() ?: DEFAULT_YAW
                val pitch = _uiState.value.servoStates
                    .firstOrNull { it.channel == 1 }?.angle?.toInt() ?: DEFAULT_PITCH
                wifiManager.setAngles(yaw, pitch)
                addLog("→ 急停 (保持当前角度)")
            }

            else -> sendTextCommand(ESP32Protocol.stopAll())
        }
    }

    /**
     * Send step delay and step size to the ESP32 via BLE GATT.
     * Only applicable in BLE GATT mode.
     */
    fun sendMotionParams() {
        if (!isBleGatt()) {
            addLog("⚠ 运动参数仅支持 BLE GATT 模式", isError = true)
            return
        }
        viewModelScope.launch {
            val state = _uiState.value
            val okDelay = bleManager.sendStepDelay(state.stepDelayMs)
            if (!okDelay) {
                addLog("⚠ 步进间隔发送失败（ESP32 未公开该特征值）", isError = true)
                return@launch
            }
            delay(50)
            val okSize = bleManager.sendStepSize(state.stepSizeDeg)
            if (!okSize) {
                addLog("⚠ 步进度数发送失败（ESP32 未公开该特征值）", isError = true)
                return@launch
            }
            addLog("→ 步进间隔=${state.stepDelayMs}ms, 步进度数=${state.stepSizeDeg}°")
        }
    }

    /** Send smooth alpha to the ESP32 via WiFi HTTP. */
    fun sendSmoothAlpha() {
        if (!isWifiConnected()) {
            addLog("⚠ 平滑参数仅支持 WiFi 模式", isError = true)
            return
        }
        val alpha = _uiState.value.smoothAlpha
        wifiManager.setAlpha(alpha)
        addLog("→ 平滑系数 Alpha = ${"%.2f".format(alpha)}")
    }

    /** Execute a preset action sequence and return to default posture when done. */
    fun executePresetAction(action: PresetAction) {
        if (!isBleGatt() && !isWifiConnected()) {
            addLog("⚠ 预置动作需要 BLE GATT 或 WiFi 连接", isError = true)
            return
        }
        if (_uiState.value.isExecutingAction) return
        _uiState.update { it.copy(isExecutingAction = true) }
        addLog("▶ ${action.label}")

        viewModelScope.launch {
            try {
                for (frame in action.frames) {
                    if (!_uiState.value.isConnected) break
                    sendFrame(frame.yaw, frame.pitch)
                    updateServoAnglesLocally(frame.yaw.toFloat(), frame.pitch.toFloat())
                    delay(frame.holdMs)
                }
                if (_uiState.value.isConnected) {
                    sendFrame(DEFAULT_YAW, DEFAULT_PITCH)
                    delay(400)
                }
            } finally {
                resetServoStates()
                _uiState.update { it.copy(isExecutingAction = false) }
                addLog("✓ ${action.label} 完成")
            }
        }
    }

    // ── Sheet / log controls ─────────────────────────────────────────────

    fun showConnectionSheet() { _uiState.update { it.copy(showConnectionSheet = true) } }
    fun hideConnectionSheet() { _uiState.update { it.copy(showConnectionSheet = false) } }
    fun clearLogs() { _uiState.update { it.copy(logMessages = emptyList()) } }

    // ── Internal helpers ─────────────────────────────────────────────────

    private fun isBleGatt(): Boolean =
        _uiState.value.connectionType == ConnectionType.BLE &&
                bleManager.connectionMode.value == ActiveConnectionMode.BLE_GATT

    private fun isClassicSpp(): Boolean =
        _uiState.value.connectionType == ConnectionType.BLE &&
                bleManager.connectionMode.value == ActiveConnectionMode.CLASSIC_SPP

    private fun isWifiConnected(): Boolean =
        _uiState.value.connectionType == ConnectionType.WIFI &&
                wifiManager.isConnected.value

    /** Route a single angle value to the appropriate transport by channel. */
    private fun sendAngleForChannel(channel: Int, angle: Int) {
        when {
            isBleGatt() -> bleManager.sendAngleToChannel(channel, angle)
            isWifiConnected() -> when (channel) {
                0 -> wifiManager.setYaw(angle)
                1 -> wifiManager.setPitch(angle)
            }
        }
    }

    /** Send a yaw+pitch frame via the active transport. */
    private suspend fun sendFrame(yaw: Int, pitch: Int) {
        when {
            isBleGatt() -> {
                bleManager.sendAngleToChannel(0, yaw)
                delay(50)
                bleManager.sendAngleToChannel(1, pitch)
            }
            isWifiConnected() -> wifiManager.setAngles(yaw, pitch)
        }
    }

    /** Update local UI servo states for yaw (ch0) and pitch (ch1). */
    private fun updateServoAnglesLocally(yaw: Float, pitch: Float) {
        val servos = _uiState.value.servoStates.map { servo ->
            when (servo.channel) {
                0 -> servo.copy(angle = yaw)
                1 -> servo.copy(angle = pitch)
                else -> servo
            }
        }
        _uiState.update { it.copy(servoStates = servos) }
    }

    /** Reset servo states to default posture. */
    private fun resetServoStates() {
        val servos = _uiState.value.servoStates.map { servo ->
            when (servo.channel) {
                0    -> servo.copy(angle = DEFAULT_YAW.toFloat())
                1    -> servo.copy(angle = DEFAULT_PITCH.toFloat())
                else -> servo.copy(angle = 90f)
            }
        }
        _uiState.update { it.copy(servoStates = servos) }
    }

    private fun sendTextCommand(command: String) {
        val ok = when {
            isClassicSpp() -> bleManager.sendClassicCommand(command)
            else -> false
        }
        if (ok) addLog("→ ${command.trim()}")
        else addLog("⚠ 发送失败: ${command.trim()}", isError = true)
    }

    private fun updateCurrentServo(transform: (ServoState) -> ServoState) {
        val idx = _uiState.value.selectedServoIndex
        val servos = _uiState.value.servoStates.toMutableList()
        servos[idx] = transform(servos[idx])
        _uiState.update { it.copy(servoStates = servos) }
    }

    private fun addLog(message: String, isError: Boolean = false) {
        _uiState.update { state ->
            val logs = state.logMessages.toMutableList()
            logs.add(0, LogEntry(timeFormat.format(Date()), message, isError))
            if (logs.size > 200) logs.removeLast()
            state.copy(logMessages = logs)
        }
    }

    override fun onCleared() {
        super.onCleared()
        wifiManager.destroy()
    }
}
