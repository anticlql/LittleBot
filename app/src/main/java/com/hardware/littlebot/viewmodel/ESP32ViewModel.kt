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
    val wifiPort: String = "8080",
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
    /** ESP32 step delay in ms (0–100). Sent via CHAR_STEP_DELAY. */
    val stepDelayMs: Int = 2,
    /** ESP32 step size in degrees (1–180). Sent via CHAR_STEP_SIZE. */
    val stepSizeDeg: Int = 10,
    /** When true, dragging the angle slider immediately sends the angle via BLE. */
    val liveAngleUpdate: Boolean = false,
    /** True while a preset action coroutine is running. */
    val isExecutingAction: Boolean = false
) {
    val currentServo: ServoState
        get() = servoStates[selectedServoIndex]
}

// ── ViewModel ────────────────────────────────────────────────────────────────

class ESP32ViewModel(application: Application) : AndroidViewModel(application) {

    val bleManager = BleManager(application)
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

        // ── WiFi ─────────────────────────────────────────────────────────
        viewModelScope.launch {
            wifiManager.isConnected.collect { connected ->
                if (_uiState.value.connectionType == ConnectionType.WIFI || connected) {
                    _uiState.update {
                        it.copy(
                            isConnected = connected,
                            connectedDeviceName = if (connected)
                                "${_uiState.value.wifiIp}:${_uiState.value.wifiPort}" else null,
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
        val port = state.wifiPort.toIntOrNull() ?: WifiConnectionManager.DEFAULT_PORT
        wifiManager.connect(state.wifiIp, port)
        addLog("正在连接 WiFi ${state.wifiIp}:$port ...")
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
        if (_uiState.value.liveAngleUpdate && isBleGatt()) {
            val channel = _uiState.value.currentServo.channel
            bleManager.sendAngleToChannel(channel, angle.toInt())
        }
    }
    fun updateServoSpeed(speed: Float) { updateCurrentServo { it.copy(speed = speed) } }
    fun updateServoChannel(channel: Int) { updateCurrentServo { it.copy(channel = channel) } }

    fun toggleLiveAngleUpdate() {
        _uiState.update { it.copy(liveAngleUpdate = !it.liveAngleUpdate) }
        addLog(if (_uiState.value.liveAngleUpdate) "已开启即时发送" else "已关闭即时发送")
    }

    fun updateStepDelay(ms: Int) { _uiState.update { it.copy(stepDelayMs = ms.coerceIn(0, 100)) } }
    fun updateStepSize(deg: Int) { _uiState.update { it.copy(stepSizeDeg = deg.coerceIn(1, 180)) } }

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

    /**
     * Send the currently selected servo's angle.
     *
     * - **BLE GATT**: writes the angle string directly to the channel's characteristic.
     * - **Classic SPP / WiFi**: sends the text protocol command.
     */
    fun sendServoCommand() {
        val servo = _uiState.value.currentServo
        val angle = servo.angle.toInt()
        val channel = servo.channel
        val label = BleManager.CHANNEL_LABELS[channel]
        val chDisplay = if (label != null) "CH$channel($label)" else "CH$channel"

        when {
            // ── BLE GATT: write angle to characteristic ──────────────────
            isBleGatt() -> {
                val ok = bleManager.sendAngleToChannel(channel, angle)
                if (ok) {
                    addLog("→ $chDisplay = ${angle}°")
                } else {
                    addLog("⚠ $chDisplay 无对应 BLE 特征值，无法发送", isError = true)
                }
            }

            // ── Classic SPP ──────────────────────────────────────────────
            isClassicSpp() -> {
                val cmd = ESP32Protocol.servoCommand(channel, angle, servo.speed.toInt())
                val ok = bleManager.sendClassicCommand(cmd)
                if (ok) addLog("→ ${cmd.trim()}") else addLog("⚠ 发送失败", isError = true)
            }

            // ── WiFi TCP ─────────────────────────────────────────────────
            _uiState.value.connectionType == ConnectionType.WIFI -> {
                val cmd = ESP32Protocol.servoCommand(channel, angle, servo.speed.toInt())
                val ok = wifiManager.sendCommand(cmd)
                if (ok) addLog("→ ${cmd.trim()}") else addLog("⚠ 发送失败", isError = true)
            }

            else -> addLog("⚠ 未连接设备", isError = true)
        }
    }

    /**
     * Send all servos' angles at once.
     */
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

            _uiState.value.connectionType == ConnectionType.WIFI -> {
                val triples = _uiState.value.servoStates.map {
                    Triple(it.channel, it.angle.toInt(), it.speed.toInt())
                }
                val cmd = ESP32Protocol.multiServoCommand(triples)
                val ok = wifiManager.sendCommand(cmd)
                if (ok) addLog("→ ${cmd.trim()}") else addLog("⚠ 发送失败", isError = true)
            }

            else -> addLog("⚠ 未连接设备", isError = true)
        }
    }

    fun sendResetAll() {
        when {
            isBleGatt() -> {
                bleManager.sendAngleToChannel(0, DEFAULT_YAW)
                bleManager.sendAngleToChannel(1, DEFAULT_PITCH)
                val servos = _uiState.value.servoStates.map { servo ->
                    when (servo.channel) {
                        0    -> servo.copy(angle = DEFAULT_YAW.toFloat())
                        1    -> servo.copy(angle = DEFAULT_PITCH.toFloat())
                        else -> servo.copy(angle = 90f)
                    }
                }
                _uiState.update { it.copy(servoStates = servos) }
                addLog("→ 复位到默认姿势 (Yaw=${DEFAULT_YAW}°, Pitch=${DEFAULT_PITCH}°)")
            }

            else -> sendTextCommand(ESP32Protocol.resetAll())
        }
    }

    fun sendStopAll() {
        when {
            isBleGatt() -> {
                // BLE: write current angles (effectively "hold position")
                _uiState.value.servoStates.forEach { s ->
                    bleManager.sendAngleToChannel(s.channel, s.angle.toInt())
                }
                addLog("→ 急停 (保持当前角度)")
            }

            else -> sendTextCommand(ESP32Protocol.stopAll())
        }
    }

    /**
     * Send step delay and step size to the ESP32 via BLE GATT.
     * Only applicable in BLE GATT mode (the ESP32 exposes these as BLE characteristics).
     */
    fun sendMotionParams() {
        if (!isBleGatt()) {
            addLog("⚠ 运动参数仅支持 BLE GATT 模式", isError = true)
            return
        }
        // Two consecutive BLE writes need a gap so the first ACK arrives before the second write.
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

    /** Execute a preset action sequence and return to default posture when done. */
    fun executePresetAction(action: PresetAction) {
        if (!isBleGatt()) {
            addLog("⚠ 预置动作仅支持 BLE GATT 模式", isError = true)
            return
        }
        if (_uiState.value.isExecutingAction) return
        _uiState.update { it.copy(isExecutingAction = true) }
        addLog("▶ ${action.label}")

        viewModelScope.launch {
            try {
                for (frame in action.frames) {
                    if (!isBleGatt()) break
                    bleManager.sendAngleToChannel(0, frame.yaw)
                    delay(50)
                    bleManager.sendAngleToChannel(1, frame.pitch)
                    delay(frame.holdMs)
                }
                // Return to default posture
                if (isBleGatt()) {
                    bleManager.sendAngleToChannel(0, DEFAULT_YAW)
                    delay(50)
                    bleManager.sendAngleToChannel(1, DEFAULT_PITCH)
                    delay(400)
                }
            } finally {
                val servos = _uiState.value.servoStates.map { servo ->
                    when (servo.channel) {
                        0    -> servo.copy(angle = DEFAULT_YAW.toFloat())
                        1    -> servo.copy(angle = DEFAULT_PITCH.toFloat())
                        else -> servo
                    }
                }
                _uiState.update { it.copy(isExecutingAction = false, servoStates = servos) }
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

    private fun sendTextCommand(command: String) {
        val ok = when {
            isClassicSpp() -> bleManager.sendClassicCommand(command)
            _uiState.value.connectionType == ConnectionType.WIFI -> wifiManager.sendCommand(command)
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
        bleManager.destroy()
        wifiManager.destroy()
    }
}
