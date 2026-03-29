@file:OptIn(ExperimentalMaterial3Api::class)

package com.hardware.littlebot.ui.screen

import android.annotation.SuppressLint
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hardware.littlebot.ble.BleManager
import com.hardware.littlebot.ble.BtDeviceType
import com.hardware.littlebot.ble.ScannedDevice
import com.hardware.littlebot.viewmodel.ConnectionType
import com.hardware.littlebot.viewmodel.ESP32UiState
import com.hardware.littlebot.viewmodel.ESP32ViewModel
import com.hardware.littlebot.viewmodel.LogEntry
import com.hardware.littlebot.viewmodel.DEFAULT_PITCH
import com.hardware.littlebot.viewmodel.DEFAULT_YAW
import com.hardware.littlebot.viewmodel.PRESET_ACTIONS
import com.hardware.littlebot.viewmodel.PresetAction
import kotlin.math.cos
import kotlin.math.sin

// ══════════════════════════════════════════════════════════════════════════════
//  Main Screen
// ══════════════════════════════════════════════════════════════════════════════

@Composable
fun MainScreen(
    viewModel: ESP32ViewModel,
    onRequestBlePermissions: () -> Unit,
    onOpenLocationSettings: () -> Unit,
    onOpenAiApiTest: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = { TopBar(state, onSettingsClick = { viewModel.showConnectionSheet() }) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            // Connection status banner – tap to open connection sheet when disconnected
            ConnectionStatusBanner(
                state = state,
                onDisconnect = { viewModel.disconnect() },
                onOpenConnectionSheet = { viewModel.showConnectionSheet() }
            )

            Spacer(modifier = Modifier.height(8.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "大模型 API 验证",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onOpenAiApiTest) {
                        Text("打开测试页")
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Quick head control – 2D touch pad
            HeadControlCard(
                state = state,
                onYawChanged = { viewModel.updateServoByChannel(0, it) },
                onPitchChanged = { viewModel.updateServoByChannel(1, it) },
                onReset = viewModel::resetHead
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Servo control card (advanced)
            ServoControlCard(
                state = state,
                onAngleChanged = viewModel::updateServoAngle,
                onSpeedChanged = viewModel::updateServoSpeed,
                onChannelChanged = viewModel::updateServoChannel,
                onSelectServo = viewModel::selectServo,
                onAddServo = viewModel::addServo,
                onRemoveServo = viewModel::removeServo,
                onSendCommand = viewModel::sendServoCommand,
                onSendAll = viewModel::sendAllServosCommand,
                onResetAll = viewModel::sendResetAll,
                onStopAll = viewModel::sendStopAll,
                onToggleLiveUpdate = viewModel::toggleLiveAngleUpdate
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Motion parameters card (BLE: step delay/size, WiFi: smooth alpha)
            MotionParamsCard(
                state = state,
                onStepDelayChanged = viewModel::updateStepDelay,
                onStepSizeChanged = viewModel::updateStepSize,
                onSendMotionParams = viewModel::sendMotionParams,
                onSmoothAlphaChanged = viewModel::updateSmoothAlpha,
                onSendSmoothAlpha = viewModel::sendSmoothAlpha
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Preset actions card
            PresetActionsCard(
                state = state,
                onExecuteAction = viewModel::executePresetAction
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Communication log card
            LogCard(
                logs = state.logMessages,
                onClear = viewModel::clearLogs
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    // Connection bottom sheet – always allow dismiss so state stays in sync
    if (state.showConnectionSheet) {
        ConnectionBottomSheet(
            state = state,
            onDismiss = { viewModel.hideConnectionSheet() },
            onStartBleScan = onRequestBlePermissions,
            onStopBleScan = viewModel::stopBleScan,
            onConnectDevice = viewModel::connectBle,
            onConnectWifi = viewModel::connectWifi,
            onWifiIpChanged = viewModel::updateWifiIp,
            onWifiPortChanged = viewModel::updateWifiPort,
            onDisconnect = viewModel::disconnect,
            onOpenLocationSettings = onOpenLocationSettings,
            onDismissLocationWarning = viewModel::dismissLocationWarning
        )
    }
}

// ══════════════════════════════════════════════════════════════════════════════
//  Top App Bar
// ══════════════════════════════════════════════════════════════════════════════

@Composable
private fun TopBar(state: ESP32UiState, onSettingsClick: () -> Unit) {
    CenterAlignedTopAppBar(
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = "🤖", fontSize = 24.sp)
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = "LittleBot", fontWeight = FontWeight.Bold)
            }
        },
        actions = {
            val dotColor by animateColorAsState(
                targetValue = if (state.isConnected) Color(0xFF4CAF50) else Color(0xFFBDBDBD),
                label = "dot"
            )
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(dotColor, CircleShape)
            )
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(onClick = onSettingsClick) {
                Icon(Icons.Default.Settings, contentDescription = "连接设置")
            }
        },
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    )
}

// ══════════════════════════════════════════════════════════════════════════════
//  Connection Status Banner
// ══════════════════════════════════════════════════════════════════════════════

@Composable
private fun ConnectionStatusBanner(
    state: ESP32UiState,
    onDisconnect: () -> Unit,
    onOpenConnectionSheet: () -> Unit
) {
    val bgColor by animateColorAsState(
        targetValue = if (state.isConnected)
            MaterialTheme.colorScheme.primaryContainer
        else
            MaterialTheme.colorScheme.surfaceVariant,
        label = "bannerBg"
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = {
            if (!state.isConnected) onOpenConnectionSheet()
        },
        colors = CardDefaults.cardColors(containerColor = bgColor),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = when (state.connectionType) {
                    ConnectionType.BLE -> Icons.Default.Bluetooth
                    ConnectionType.WIFI -> Icons.Default.Wifi
                    ConnectionType.NONE -> Icons.Default.LinkOff
                },
                contentDescription = null,
                tint = if (state.isConnected)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (state.isConnected) "已连接" else "未连接",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                if (state.isConnected && state.connectedDeviceName != null) {
                    Text(
                        text = state.connectedDeviceName!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (!state.isConnected) {
                    Text(
                        text = "点击此处连接设备",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            if (state.isConnected) {
                OutlinedButton(
                    onClick = onDisconnect,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Text("断开", fontSize = 12.sp)
                }
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════════
//  Head Control Card – Dual Sliders + Position Indicator
// ══════════════════════════════════════════════════════════════════════════════

@Composable
private fun HeadControlCard(
    state: ESP32UiState,
    onYawChanged: (Float) -> Unit,
    onPitchChanged: (Float) -> Unit,
    onReset: () -> Unit
) {
    val yaw = state.servoStates.firstOrNull { it.channel == 0 }?.angle ?: DEFAULT_YAW.toFloat()
    val pitch = state.servoStates.firstOrNull { it.channel == 1 }?.angle ?: DEFAULT_PITCH.toFloat()

    val primary = MaterialTheme.colorScheme.primary
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    val surface = MaterialTheme.colorScheme.surface

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "头部控制",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(
                    onClick = { onReset() },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    enabled = state.isConnected
                ) {
                    Icon(Icons.Default.RestartAlt, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("复位", fontSize = 12.sp)
                }
            }

            Text(
                "拖动滑条即时控制 Yaw 和 Pitch",
                style = MaterialTheme.typography.bodySmall,
                color = onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))

            // ── Position indicator ────────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2.2f)
                    .background(surfaceVariant.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height

                    val gridColor = onSurfaceVariant.copy(alpha = 0.08f)
                    for (i in 1..5) {
                        val x = w * i / 6f
                        drawLine(gridColor, Offset(x, 0f), Offset(x, h), 1.dp.toPx())
                    }
                    for (i in 1..2) {
                        val y = h * i / 3f
                        drawLine(gridColor, Offset(0f, y), Offset(w, y), 1.dp.toPx())
                    }

                    val defX = DEFAULT_YAW.toFloat() / 180f * w
                    val defY = (1f - DEFAULT_PITCH.toFloat() / 180f) * h
                    val crossColor = onSurfaceVariant.copy(alpha = 0.2f)
                    drawLine(crossColor, Offset(defX, 0f), Offset(defX, h), 1.dp.toPx())
                    drawLine(crossColor, Offset(0f, defY), Offset(w, defY), 1.dp.toPx())

                    val cx = yaw / 180f * w
                    val cy = (1f - pitch / 180f) * h
                    drawLine(primary.copy(alpha = 0.25f), Offset(cx, 0f), Offset(cx, h), 1.dp.toPx())
                    drawLine(primary.copy(alpha = 0.25f), Offset(0f, cy), Offset(w, cy), 1.dp.toPx())

                    drawCircle(primary.copy(alpha = 0.15f), 20.dp.toPx(), Offset(cx, cy))
                    drawCircle(primary, 8.dp.toPx(), Offset(cx, cy))
                    drawCircle(surface, 3.dp.toPx(), Offset(cx, cy))
                }

                Text(
                    "← Yaw →",
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 10.sp,
                    color = onSurfaceVariant.copy(alpha = 0.4f)
                )
                Text(
                    "↑Pitch",
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 10.sp,
                    color = onSurfaceVariant.copy(alpha = 0.4f)
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // ── Yaw Slider ────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Yaw", style = MaterialTheme.typography.labelMedium, color = onSurfaceVariant, modifier = Modifier.width(42.dp))
                Slider(
                    value = yaw,
                    onValueChange = { newYaw -> onYawChanged(newYaw) },
                    valueRange = 0f..180f,
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(
                        thumbColor = primary,
                        activeTrackColor = primary,
                        inactiveTrackColor = surfaceVariant
                    )
                )
                Badge(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ) {
                    Text(
                        "${yaw.toInt()}°",
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp
                    )
                }
            }

            // ── Pitch Slider ──────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Pitch", style = MaterialTheme.typography.labelMedium, color = onSurfaceVariant, modifier = Modifier.width(42.dp))
                Slider(
                    value = pitch,
                    onValueChange = { newPitch -> onPitchChanged(newPitch) },
                    valueRange = 0f..180f,
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(
                        thumbColor = primary,
                        activeTrackColor = primary,
                        inactiveTrackColor = surfaceVariant
                    )
                )
                Badge(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ) {
                    Text(
                        "${pitch.toInt()}°",
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════════
//  Servo Control Card
// ══════════════════════════════════════════════════════════════════════════════

@Composable
private fun ServoControlCard(
    state: ESP32UiState,
    onAngleChanged: (Float) -> Unit,
    onSpeedChanged: (Float) -> Unit,
    onChannelChanged: (Int) -> Unit,
    onSelectServo: (Int) -> Unit,
    onAddServo: () -> Unit,
    onRemoveServo: (Int) -> Unit,
    onSendCommand: () -> Unit,
    onSendAll: () -> Unit,
    onResetAll: () -> Unit,
    onStopAll: () -> Unit,
    onToggleLiveUpdate: () -> Unit
) {
    val servo = state.currentServo

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {

            // ── Title row with live-update toggle ────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "舵机控制",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "即时发送",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (state.liveAngleUpdate)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(6.dp))
                Switch(
                    checked = state.liveAngleUpdate,
                    onCheckedChange = { onToggleLiveUpdate() },
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = MaterialTheme.colorScheme.primary
                    )
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ── Servo tabs ───────────────────────────────────────────────
            Text(
                text = "选择舵机",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))

            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(state.servoStates) { index, s ->
                    FilterChip(
                        selected = index == state.selectedServoIndex,
                        onClick = { onSelectServo(index) },
                        label = { Text("CH${s.channel}") },
                        trailingIcon = if (state.servoStates.size > 1 && index == state.selectedServoIndex) {
                            {
                                IconButton(
                                    onClick = { onRemoveServo(index) },
                                    modifier = Modifier.size(16.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "删除",
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                        } else null,
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    )
                }
                item {
                    AssistChip(
                        onClick = onAddServo,
                        label = {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = "添加",
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ── Channel number ───────────────────────────────────────────
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "通道号",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(56.dp)
                    )
                    // Show channel label if available
                    BleManager.CHANNEL_LABELS[servo.channel]?.let { label ->
                        Badge(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                            contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                        ) {
                            Text(
                                label,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items((0..20).toList()) { ch ->
                        FilterChip(
                            selected = servo.channel == ch,
                            onClick = { onChannelChanged(ch) },
                            label = {
                                Text(
                                    "$ch",
                                    fontSize = 12.sp,
                                    modifier = Modifier.width(16.dp),
                                    textAlign = TextAlign.Center
                                )
                            },
                            modifier = Modifier.height(32.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ── Servo Gauge ──────────────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp),
                contentAlignment = Alignment.Center
            ) {
                ServoGauge(angle = servo.angle)
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ── Sliders ──────────────────────────────────────────────────
            SliderWithLabel(
                label = "角度",
                value = servo.angle,
                valueRange = 0f..180f,
                valueText = "${servo.angle.toInt()}°",
                onValueChange = onAngleChanged
            )

            Spacer(modifier = Modifier.height(8.dp))

            SliderWithLabel(
                label = "速度",
                value = servo.speed,
                valueRange = 1f..100f,
                valueText = "${servo.speed.toInt()}%",
                onValueChange = onSpeedChanged
            )

            Spacer(modifier = Modifier.height(16.dp))

            // ── Buttons ──────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onSendCommand,
                    modifier = Modifier.weight(1f),
                    // Disable single-channel send when live update is on (slider already sends)
                    enabled = state.isConnected && !state.liveAngleUpdate
                ) {
                    Icon(Icons.Default.Send, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("发送")
                }
                FilledTonalButton(
                    onClick = onSendAll,
                    modifier = Modifier.weight(1f),
                    enabled = state.isConnected
                ) {
                    Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("全部发送")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onResetAll,
                    modifier = Modifier.weight(1f),
                    enabled = state.isConnected
                ) {
                    Icon(Icons.Default.RestartAlt, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("复位")
                }
                OutlinedButton(
                    onClick = onStopAll,
                    modifier = Modifier.weight(1f),
                    enabled = state.isConnected,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(Icons.Default.Stop, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("急停")
                }
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════════
//  Servo Gauge
// ══════════════════════════════════════════════════════════════════════════════

@Composable
private fun ServoGauge(angle: Float) {
    val animatedAngle by animateFloatAsState(
        targetValue = angle,
        animationSpec = tween(durationMillis = 300),
        label = "gaugeAngle"
    )

    val primary = MaterialTheme.colorScheme.primary
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    val onSurface = MaterialTheme.colorScheme.onSurface

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(modifier = Modifier.size(200.dp, 110.dp)) {
            val centerX = size.width / 2
            val centerY = size.height - 10.dp.toPx()
            val radius = size.width / 2 - 20.dp.toPx()

            drawArc(
                color = surfaceVariant,
                startAngle = 180f,
                sweepAngle = 180f,
                useCenter = false,
                style = Stroke(width = 12.dp.toPx(), cap = StrokeCap.Round),
                topLeft = Offset(centerX - radius, centerY - radius),
                size = androidx.compose.ui.geometry.Size(radius * 2, radius * 2)
            )

            drawArc(
                brush = Brush.sweepGradient(
                    colors = listOf(
                        Color(0xFF2196F3),
                        Color(0xFF00BCD4),
                        Color(0xFF4CAF50)
                    ),
                    center = Offset(centerX, centerY)
                ),
                startAngle = 180f,
                sweepAngle = animatedAngle,
                useCenter = false,
                style = Stroke(width = 12.dp.toPx(), cap = StrokeCap.Round),
                topLeft = Offset(centerX - radius, centerY - radius),
                size = androidx.compose.ui.geometry.Size(radius * 2, radius * 2)
            )

            val needleAngle = Math.toRadians((180 + animatedAngle).toDouble())
            val needleLen = radius - 10.dp.toPx()
            val nx = centerX + (needleLen * cos(needleAngle)).toFloat()
            val ny = centerY + (needleLen * sin(needleAngle)).toFloat()

            drawLine(onSurface, Offset(centerX, centerY), Offset(nx, ny), 3.dp.toPx(), StrokeCap.Round)
            drawCircle(primary, 6.dp.toPx(), Offset(centerX, centerY))
        }

        Row(
            modifier = Modifier.width(200.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("0°", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "${animatedAngle.toInt()}°",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Text("180°", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════════
//  Slider With Label
// ══════════════════════════════════════════════════════════════════════════════

@Composable
private fun SliderWithLabel(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    valueText: String,
    onValueChange: (Float) -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Badge(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            ) {
                Text(
                    valueText,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("${valueRange.start.toInt()}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("${valueRange.endInclusive.toInt()}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════════
//  Preset Actions Card
// ══════════════════════════════════════════════════════════════════════════════

@Composable
private fun PresetActionsCard(
    state: ESP32UiState,
    onExecuteAction: (PresetAction) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "预置动作",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                if (state.isExecutingAction) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "执行中…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Text(
                text = "执行完毕后自动复位到默认姿势（支持 BLE / WiFi）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))

            val canExecute = state.isConnected &&
                    (state.connectionType == ConnectionType.BLE || state.connectionType == ConnectionType.WIFI)
            val enabled = canExecute && !state.isExecutingAction
            PRESET_ACTIONS.chunked(2).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    row.forEach { action ->
                        FilledTonalButton(
                            onClick = { onExecuteAction(action) },
                            modifier = Modifier.weight(1f),
                            enabled = enabled
                        ) {
                            Text(action.label)
                        }
                    }
                    if (row.size < 2) Spacer(Modifier.weight(1f))
                }
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════════
//  Motion Parameters Card
// ══════════════════════════════════════════════════════════════════════════════

@Composable
private fun MotionParamsCard(
    state: ESP32UiState,
    onStepDelayChanged: (Int) -> Unit,
    onStepSizeChanged: (Int) -> Unit,
    onSendMotionParams: () -> Unit,
    onSmoothAlphaChanged: (Float) -> Unit,
    onSendSmoothAlpha: () -> Unit
) {
    val isWifi = state.connectionType == ConnectionType.WIFI

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "运动参数",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            if (isWifi) {
                Text(
                    text = "控制 ESP32 指数平滑系数（越小越顺滑）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(12.dp))

                SliderWithLabel(
                    label = "平滑系数 Alpha",
                    value = state.smoothAlpha,
                    valueRange = 0.01f..0.30f,
                    valueText = "${"%.2f".format(state.smoothAlpha)}",
                    onValueChange = onSmoothAlphaChanged
                )

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = onSendSmoothAlpha,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = state.isConnected
                ) {
                    Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("发送平滑参数")
                }
            } else {
                Text(
                    text = "控制 ESP32 的步进间隔与每步移动度数",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(12.dp))

                SliderWithLabel(
                    label = "步进间隔",
                    value = state.stepDelayMs.toFloat(),
                    valueRange = 0f..100f,
                    valueText = "${state.stepDelayMs}ms",
                    onValueChange = { onStepDelayChanged(it.toInt()) }
                )

                Spacer(modifier = Modifier.height(8.dp))

                SliderWithLabel(
                    label = "步进度数",
                    value = state.stepSizeDeg.toFloat(),
                    valueRange = 1f..180f,
                    valueText = "${state.stepSizeDeg}°",
                    onValueChange = { onStepSizeChanged(it.toInt()) }
                )

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = onSendMotionParams,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = state.isConnected
                ) {
                    Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("发送运动参数")
                }
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════════
//  Log Card
// ══════════════════════════════════════════════════════════════════════════════

@Composable
private fun LogCard(logs: List<LogEntry>, onClear: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("通信日志", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                if (logs.isNotEmpty()) {
                    IconButton(onClick = onClear, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.DeleteSweep, "清空", Modifier.size(20.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        RoundedCornerShape(8.dp)
                    )
            ) {
                if (logs.isEmpty()) {
                    Text(
                        "暂无日志\n连接设备后发送指令即可查看",
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(16.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        items(logs) { entry ->
                            Row {
                                Text(
                                    entry.time,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.width(64.dp)
                                )
                                Text(
                                    entry.message,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = if (entry.isError) MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════════
//  Connection Bottom Sheet
// ══════════════════════════════════════════════════════════════════════════════

@Composable
private fun ConnectionBottomSheet(
    state: ESP32UiState,
    onDismiss: () -> Unit,
    onStartBleScan: () -> Unit,
    onStopBleScan: () -> Unit,
    onConnectDevice: (ScannedDevice) -> Unit,
    onConnectWifi: () -> Unit,
    onWifiIpChanged: (String) -> Unit,
    onWifiPortChanged: (String) -> Unit,
    onDisconnect: () -> Unit,
    onOpenLocationSettings: () -> Unit,
    onDismissLocationWarning: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("蓝牙", "WiFi")

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp)
        ) {
            Text(
                "连接 ESP32",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            TabRow(selectedTabIndex = selectedTab, containerColor = Color.Transparent) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title) },
                        icon = {
                            Icon(
                                if (index == 0) Icons.Default.Bluetooth else Icons.Default.Wifi,
                                null,
                                Modifier.size(20.dp)
                            )
                        }
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            when (selectedTab) {
                0 -> BleConnectionContent(
                    state = state,
                    onStartScan = onStartBleScan,
                    onStopScan = onStopBleScan,
                    onConnect = onConnectDevice,
                    onDisconnect = onDisconnect,
                    onOpenLocationSettings = onOpenLocationSettings,
                    onDismissLocationWarning = onDismissLocationWarning
                )

                1 -> WifiConnectionContent(
                    state = state,
                    onIpChanged = onWifiIpChanged,
                    onPortChanged = onWifiPortChanged,
                    onConnect = onConnectWifi,
                    onDisconnect = onDisconnect
                )
            }
        }
    }
}

// ── BLE / Classic Connection Content ─────────────────────────────────────────

@SuppressLint("MissingPermission")
@Composable
private fun BleConnectionContent(
    state: ESP32UiState,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onConnect: (ScannedDevice) -> Unit,
    onDisconnect: () -> Unit,
    onOpenLocationSettings: () -> Unit,
    onDismissLocationWarning: () -> Unit
) {
    Column {
        // Already connected
        if (state.isConnected && state.connectionType == ConnectionType.BLE) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Bluetooth, null)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("已连接", fontWeight = FontWeight.SemiBold)
                        Text(state.connectedDeviceName ?: "Unknown", style = MaterialTheme.typography.bodySmall)
                    }
                    OutlinedButton(onClick = onDisconnect) { Text("断开") }
                }
            }
            return
        }

        // ── Location warning ─────────────────────────────────────────────
        if (state.locationWarning) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.LocationOff,
                        null,
                        tint = Color(0xFFE65100),
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "定位服务未开启",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp,
                            color = Color(0xFFE65100)
                        )
                        Text(
                            "Android 要求开启定位才能扫描蓝牙设备",
                            fontSize = 11.sp,
                            color = Color(0xFF795548)
                        )
                    }
                    FilledTonalButton(
                        onClick = onOpenLocationSettings,
                        contentPadding = PaddingValues(horizontal = 12.dp)
                    ) {
                        Text("去开启", fontSize = 12.sp)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        // ── Scan button ──────────────────────────────────────────────────
        Button(
            onClick = if (state.isScanning) onStopScan else onStartScan,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (state.isScanning) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp
                )
                Spacer(Modifier.width(8.dp))
                Text("停止扫描")
            } else {
                Icon(Icons.Default.BluetoothSearching, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("扫描设备")
            }
        }

        Spacer(Modifier.height(4.dp))

        Text(
            "同时扫描 BLE 和经典蓝牙设备",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(12.dp))

        // ── Device list ──────────────────────────────────────────────────
        if (state.bleDevices.isEmpty() && !state.isScanning) {
            Text(
                "点击「扫描设备」搜索附近的蓝牙设备",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 32.dp),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            val bleCount = state.bleDevices.count { it.type == BtDeviceType.BLE }
            val classicCount = state.bleDevices.count { it.type == BtDeviceType.CLASSIC }
            val parts = mutableListOf<String>()
            if (bleCount > 0) parts.add("$bleCount BLE")
            if (classicCount > 0) parts.add("$classicCount 经典")
            Text(
                "发现 ${state.bleDevices.size} 个设备 (${parts.joinToString(" / ")})",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))

            LazyColumn(
                modifier = Modifier.height(280.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(state.bleDevices) { sd ->
                    DeviceItem(scannedDevice = sd, onConnect = { onConnect(sd) })
                }
            }
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun DeviceItem(scannedDevice: ScannedDevice, onConnect: () -> Unit) {
    val isBle = scannedDevice.type == BtDeviceType.BLE
    val typeLabel = if (isBle) "BLE" else "经典"
    val typeColor = if (isBle) Color(0xFF1565C0) else Color(0xFF00838F)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Bluetooth,
                null,
                tint = typeColor,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        scannedDevice.name ?: "未知设备",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(Modifier.width(6.dp))
                    Badge(containerColor = typeColor.copy(alpha = 0.15f), contentColor = typeColor) {
                        Text(
                            typeLabel,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                Row {
                    Text(
                        scannedDevice.address,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace
                    )
                    if (scannedDevice.rssi != null) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "${scannedDevice.rssi} dBm",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            FilledTonalButton(
                onClick = onConnect,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)
            ) {
                Text("连接", fontSize = 13.sp)
            }
        }
    }
}

// ── WiFi Connection Content ──────────────────────────────────────────────────

@Composable
private fun WifiConnectionContent(
    state: ESP32UiState,
    onIpChanged: (String) -> Unit,
    onPortChanged: (String) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit
) {
    Column {
        if (state.isConnected && state.connectionType == ConnectionType.WIFI) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Wifi, null)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("已连接", fontWeight = FontWeight.SemiBold)
                        Text(
                            "http://${state.wifiIp}${if (state.wifiPort != "80") ":${state.wifiPort}" else ""}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    OutlinedButton(onClick = onDisconnect) { Text("断开") }
                }
            }
            return
        }

        Text(
            "输入 ESP32 的 IP 地址（通过 HTTP 控制）",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = state.wifiIp,
            onValueChange = onIpChanged,
            label = { Text("IP 地址") },
            placeholder = { Text("192.168.x.x") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            leadingIcon = { Icon(Icons.Default.Wifi, null) }
        )

        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = state.wifiPort,
            onValueChange = onPortChanged,
            label = { Text("端口号（默认 80）") },
            placeholder = { Text("80") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(20.dp))

        Button(onClick = onConnect, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Wifi, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("连接")
        }

        Spacer(Modifier.height(12.dp))
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))

        Text(
            "提示：ESP32 以 STA 模式连接路由器，IP 地址由路由器分配。\n请查看 ESP32 串口输出获取 IP，并确保手机与 ESP32 在同一局域网。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
