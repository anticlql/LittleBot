package com.hardware.littlebot.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

// ── Data model ──────────────────────────────────────────────────────────────

enum class BtDeviceType { BLE, CLASSIC }

enum class ActiveConnectionMode { NONE, BLE_GATT, CLASSIC_SPP }

data class ScannedDevice(
    val device: BluetoothDevice,
    val name: String?,
    val address: String,
    val type: BtDeviceType,
    val rssi: Int? = null
)

// ══════════════════════════════════════════════════════════════════════════════

/**
 * Bluetooth Manager for ESP32 communication.
 *
 * ### BLE GATT mode
 * Connects to the ESP32's custom BLE service and writes angle values
 * **directly** to per-servo characteristics (matching the ESP32 firmware).
 *
 * Channel-to-characteristic mapping is defined in [SERVO_CHAR_UUIDS].
 * To add more servos, add their characteristic UUIDs to that map and
 * create matching characteristics on the ESP32 side.
 *
 * ### Classic SPP mode
 * Uses RFCOMM/SPP serial – sends text commands just like a serial terminal.
 */
class BleManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "BleManager"
        private const val SCAN_TIMEOUT_MS = 12_000L

        @Volatile
        private var instance: BleManager? = null

        fun getInstance(context: Context): BleManager =
            instance ?: synchronized(this) {
                instance ?: BleManager(context.applicationContext).also { instance = it }
            }

        // ── ESP32 BLE Service UUID ───────────────────────────────────────
        val ESP32_SERVICE_UUID: UUID =
            UUID.fromString("4fafc201-1fb5-459e-8fcc-c5c9c331914b")

        // ── Servo characteristic UUIDs keyed by channel number ───────────
        //    Channel 0 = Yaw (水平),  Channel 1 = Pitch (俯仰)
        //    Add more entries here when you add servos on the ESP32 side.
        val SERVO_CHAR_UUIDS: Map<Int, UUID> = mapOf(
            0 to UUID.fromString("beb5483e-36e1-4688-b7f5-ea07361b26a8"),   // Yaw
            1 to UUID.fromString("1c95d5e3-d8f7-413a-bf3d-7a2e5d7be87e")    // Pitch
        )

        /** Human-readable labels for known channels. */
        val CHANNEL_LABELS: Map<Int, String> = mapOf(
            0 to "Yaw 水平",
            1 to "Pitch 俯仰"
        )

        // ── Motion parameter characteristic UUIDs ────────────────────────
        val STEP_DELAY_UUID: UUID = UUID.fromString("a1b2c3d4-0001-4000-8000-00805f9b34fb")
        val STEP_SIZE_UUID: UUID  = UUID.fromString("a1b2c3d4-0002-4000-8000-00805f9b34fb")

        // ── Classic Bluetooth SPP UUID ───────────────────────────────────
        val SPP_UUID: UUID =
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private val handler = Handler(Looper.getMainLooper())

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ── BLE GATT fields ──────────────────────────────────────────────────
    private var bluetoothGatt: BluetoothGatt? = null

    /** Discovered per-channel characteristics (channel → characteristic). */
    private val servoCharacteristics = mutableMapOf<Int, BluetoothGattCharacteristic>()

    private var stepDelayCharacteristic: BluetoothGattCharacteristic? = null
    private var stepSizeCharacteristic: BluetoothGattCharacteristic? = null

    // ── Classic SPP fields ───────────────────────────────────────────────
    private var sppSocket: BluetoothSocket? = null
    private var sppOutputStream: OutputStream? = null
    private var sppInputStream: InputStream? = null
    private var sppReadJob: Job? = null

    // ── Observable state ─────────────────────────────────────────────────

    private val _scannedDevices = MutableStateFlow<List<ScannedDevice>>(emptyList())
    val scannedDevices: StateFlow<List<ScannedDevice>> = _scannedDevices

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected

    private val _receivedData = MutableStateFlow("")
    val receivedData: StateFlow<String> = _receivedData

    private val _connectedDeviceName = MutableStateFlow<String?>(null)
    val connectedDeviceName: StateFlow<String?> = _connectedDeviceName

    private val _connectionMode = MutableStateFlow(ActiveConnectionMode.NONE)
    val connectionMode: StateFlow<ActiveConnectionMode> = _connectionMode

    /** Channels that were successfully discovered on the connected BLE device. */
    private val _discoveredChannels = MutableStateFlow<Set<Int>>(emptySet())
    val discoveredChannels: StateFlow<Set<Int>> = _discoveredChannels

    val isBluetoothEnabled: Boolean get() = bluetoothAdapter?.isEnabled == true
    val isBluetoothSupported: Boolean get() = bluetoothAdapter != null

    // ── Location service check ───────────────────────────────────────────

    fun isLocationEnabled(): Boolean {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return false
        return lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    // ══════════════════════════════════════════════════════════════════════
    //  BLE Scan callback
    // ══════════════════════════════════════════════════════════════════════

    private val bleScanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            addDevice(
                ScannedDevice(
                    device = result.device,
                    name = result.device.name,
                    address = result.device.address,
                    type = BtDeviceType.BLE,
                    rssi = result.rssi
                )
            )
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "BLE scan failed: $errorCode")
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Classic Bluetooth discovery BroadcastReceiver
    // ══════════════════════════════════════════════════════════════════════

    private var receiverRegistered = false

    private val classicReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(
                                BluetoothDevice.EXTRA_DEVICE,
                                BluetoothDevice::class.java
                            )
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                        } ?: return

                    val rssi =
                        intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt()

                    addDevice(
                        ScannedDevice(
                            device = device,
                            name = device.name,
                            address = device.address,
                            type = BtDeviceType.CLASSIC,
                            rssi = if (rssi != Short.MIN_VALUE.toInt()) rssi else null
                        )
                    )
                }

                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    Log.d(TAG, "Classic discovery finished")
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  BLE GATT callback
    // ══════════════════════════════════════════════════════════════════════

    private val gattCallback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "GATT connected")
                    _isConnected.value = true
                    _connectionMode.value = ActiveConnectionMode.BLE_GATT
                    _connectedDeviceName.value = gatt.device.name ?: gatt.device.address
                    gatt.discoverServices()
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "GATT disconnected")
                    _isConnected.value = false
                    _connectionMode.value = ActiveConnectionMode.NONE
                    _connectedDeviceName.value = null
                    servoCharacteristics.clear()
                    stepDelayCharacteristic = null
                    stepSizeCharacteristic = null
                    _discoveredChannels.value = emptySet()
                    bluetoothGatt?.close()
                    bluetoothGatt = null
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return

            val service = gatt.getService(ESP32_SERVICE_UUID)
            if (service == null) {
                Log.w(TAG, "ESP32 service NOT found (UUID=${ESP32_SERVICE_UUID})")
                return
            }

            // Discover servo characteristics by matching known UUIDs
            servoCharacteristics.clear()
            SERVO_CHAR_UUIDS.forEach { (channel, uuid) ->
                val c = service.getCharacteristic(uuid)
                if (c != null) {
                    servoCharacteristics[channel] = c
                    Log.d(TAG, "CH$channel characteristic found")
                }
            }
            _discoveredChannels.value = servoCharacteristics.keys.toSet()

            // Discover motion parameter characteristics
            stepDelayCharacteristic = service.getCharacteristic(STEP_DELAY_UUID)
            stepSizeCharacteristic  = service.getCharacteristic(STEP_SIZE_UUID)
            if (stepDelayCharacteristic != null) Log.d(TAG, "StepDelay characteristic found")
            if (stepSizeCharacteristic  != null) Log.d(TAG, "StepSize  characteristic found")

            Log.d(
                TAG,
                "Service discovered – ${servoCharacteristics.size} servo characteristic(s)"
            )
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "Write failed for ${characteristic.uuid}, status=$status")
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Scan
    // ══════════════════════════════════════════════════════════════════════

    @SuppressLint("MissingPermission")
    fun startScan() {
        if (_isScanning.value) return
        _scannedDevices.value = emptyList()
        _isScanning.value = true

        // BLE scan
        bluetoothAdapter?.bluetoothLeScanner?.let { scanner ->
            try {
                val settings = ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build()
                scanner.startScan(null, settings, bleScanCallback)
                Log.d(TAG, "BLE scan started")
            } catch (e: Exception) {
                Log.e(TAG, "BLE scan start failed: ${e.message}")
            }
        }

        // Classic discovery
        try {
            if (!receiverRegistered) {
                val filter = IntentFilter().apply {
                    addAction(BluetoothDevice.ACTION_FOUND)
                    addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.registerReceiver(classicReceiver, filter, Context.RECEIVER_EXPORTED)
                } else {
                    context.registerReceiver(classicReceiver, filter)
                }
                receiverRegistered = true
            }
            bluetoothAdapter?.startDiscovery()
            Log.d(TAG, "Classic discovery started")
        } catch (e: Exception) {
            Log.e(TAG, "Classic discovery start failed: ${e.message}")
        }

        handler.postDelayed({ stopScan() }, SCAN_TIMEOUT_MS)
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        if (!_isScanning.value) return
        try { bluetoothAdapter?.bluetoothLeScanner?.stopScan(bleScanCallback) } catch (_: Exception) {}
        try { bluetoothAdapter?.cancelDiscovery() } catch (_: Exception) {}
        _isScanning.value = false
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Connect
    // ══════════════════════════════════════════════════════════════════════

    fun connect(scannedDevice: ScannedDevice) {
        stopScan()
        when (scannedDevice.type) {
            BtDeviceType.BLE -> connectBle(scannedDevice.device)
            BtDeviceType.CLASSIC -> connectClassic(scannedDevice.device)
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectBle(device: BluetoothDevice) {
        bluetoothGatt = device.connectGatt(context, false, gattCallback)
    }

    @SuppressLint("MissingPermission")
    private fun connectClassic(device: BluetoothDevice) {
        scope.launch {
            try {
                bluetoothAdapter?.cancelDiscovery()
                val socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                socket.connect()
                sppSocket = socket
                sppOutputStream = socket.outputStream
                sppInputStream = socket.inputStream
                _isConnected.value = true
                _connectionMode.value = ActiveConnectionMode.CLASSIC_SPP
                _connectedDeviceName.value = device.name ?: device.address
                Log.d(TAG, "Classic SPP connected")
                startSppReading()
            } catch (e: IOException) {
                Log.e(TAG, "Classic SPP connect failed: ${e.message}")
                _isConnected.value = false
                _connectionMode.value = ActiveConnectionMode.NONE
            }
        }
    }

    private fun startSppReading() {
        sppReadJob = scope.launch {
            val buf = ByteArray(1024)
            try {
                while (isActive) {
                    val input = sppInputStream ?: break
                    val n = input.read(buf)
                    if (n > 0) {
                        _receivedData.value = String(buf, 0, n, Charsets.UTF_8)
                    } else if (n == -1) break
                }
            } catch (e: IOException) {
                Log.e(TAG, "SPP read error: ${e.message}")
            }
            disconnectClassic()
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Disconnect
    // ══════════════════════════════════════════════════════════════════════

    @SuppressLint("MissingPermission")
    fun disconnect() {
        when (_connectionMode.value) {
            ActiveConnectionMode.BLE_GATT -> bluetoothGatt?.disconnect()
            ActiveConnectionMode.CLASSIC_SPP -> disconnectClassic()
            ActiveConnectionMode.NONE -> {}
        }
    }

    private fun disconnectClassic() {
        sppReadJob?.cancel()
        try { sppOutputStream?.close(); sppInputStream?.close(); sppSocket?.close() }
        catch (_: Exception) {}
        sppSocket = null; sppOutputStream = null; sppInputStream = null
        _isConnected.value = false
        _connectionMode.value = ActiveConnectionMode.NONE
        _connectedDeviceName.value = null
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Send – BLE GATT per-channel writes
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Write an angle value to the BLE characteristic mapped to [channel].
     *
     * The ESP32 firmware expects a plain integer string (e.g. `"90"`).
     *
     * @return `true` if the write was initiated, `false` if channel not found.
     */
    @SuppressLint("MissingPermission")
    fun sendAngleToChannel(channel: Int, angle: Int): Boolean {
        val gatt = bluetoothGatt ?: return false
        val char = servoCharacteristics[channel] ?: return false
        return writeIntString(gatt, char, angle)
    }

    /** Write the step delay (ms) to the ESP32. Range: 0–100. */
    @SuppressLint("MissingPermission")
    fun sendStepDelay(ms: Int): Boolean {
        val gatt = bluetoothGatt ?: return false
        val char = stepDelayCharacteristic ?: return false
        return writeIntString(gatt, char, ms)
    }

    /** Write the step size (degrees) to the ESP32. Range: 1–180. */
    @SuppressLint("MissingPermission")
    fun sendStepSize(degrees: Int): Boolean {
        val gatt = bluetoothGatt ?: return false
        val char = stepSizeCharacteristic ?: return false
        return writeIntString(gatt, char, degrees)
    }

    @SuppressLint("MissingPermission")
    private fun writeIntString(
        gatt: BluetoothGatt,
        char: BluetoothGattCharacteristic,
        value: Int
    ): Boolean {
        val data = value.toString().toByteArray(Charsets.UTF_8)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(
                char, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            ) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            char.value = data
            char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(char)
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Send – Classic SPP text command
    // ══════════════════════════════════════════════════════════════════════

    fun sendClassicCommand(command: String): Boolean {
        return try {
            sppOutputStream?.write(command.toByteArray(Charsets.UTF_8))
            sppOutputStream?.flush()
            true
        } catch (e: IOException) {
            Log.e(TAG, "SPP send failed: ${e.message}")
            false
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Helpers
    // ══════════════════════════════════════════════════════════════════════

    private fun addDevice(sd: ScannedDevice) {
        val list = _scannedDevices.value.toMutableList()
        val idx = list.indexOfFirst { it.address == sd.address }
        if (idx >= 0) {
            if (list[idx].name == null && sd.name != null) {
                list[idx] = sd
                _scannedDevices.value = list
            }
        } else {
            list.add(sd)
            _scannedDevices.value = list
        }
    }

    fun destroy() {
        stopScan()
        disconnect()
        if (receiverRegistered) {
            try { context.unregisterReceiver(classicReceiver) } catch (_: Exception) {}
            receiverRegistered = false
        }
        scope.cancel()
    }
}
