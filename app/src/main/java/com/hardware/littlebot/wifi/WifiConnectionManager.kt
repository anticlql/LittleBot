package com.hardware.littlebot.wifi

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * ESP32 real-time status from the `/status` JSON endpoint.
 */
data class ESP32Status(
    val yawTarget: Int = 90,
    val pitchTarget: Int = 90,
    val yawCurrent: Float = 90f,
    val pitchCurrent: Float = 90f,
    val alpha: Float = 0.08f
)

/**
 * Manages HTTP communication with the ESP32 WiFi servo controller.
 *
 * The ESP32 runs a WebServer on port 80 with these endpoints:
 * - `GET /set?yaw=<0-180>&pitch=<0-180>&alpha=<0.01-0.30>` – set targets
 * - `GET /status` – returns JSON with current positions and targets
 */
class WifiConnectionManager {

    companion object {
        private const val TAG = "WifiMgr"
        private const val HTTP_TIMEOUT_MS = 3000
        private const val POLL_INTERVAL_MS = 500L
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pollJob: Job? = null
    private var baseUrl: String = ""

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected

    private val _status = MutableStateFlow(ESP32Status())
    val status: StateFlow<ESP32Status> = _status

    private val _receivedData = MutableStateFlow("")
    val receivedData: StateFlow<String> = _receivedData

    /**
     * Test connection by fetching `/status` and start periodic polling.
     * @param ip  ESP32 IP address (e.g. "192.168.1.123")
     * @param port HTTP port, default 80
     */
    fun connect(ip: String, port: Int = 80) {
        baseUrl = if (port == 80) "http://$ip" else "http://$ip:$port"
        scope.launch {
            try {
                val st = fetchStatus()
                if (st != null) {
                    _status.value = st
                    _isConnected.value = true
                    _receivedData.value = "已连接 $ip"
                    Log.d(TAG, "Connected to $baseUrl")
                    startPolling()
                } else {
                    _isConnected.value = false
                    _receivedData.value = "无法连接 $ip"
                }
            } catch (e: Exception) {
                Log.e(TAG, "Connect failed: ${e.message}")
                _isConnected.value = false
                _receivedData.value = "连接失败: ${e.message}"
            }
        }
    }

    fun setYaw(angle: Int) = sendSet("yaw=$angle")
    fun setPitch(angle: Int) = sendSet("pitch=$angle")
    fun setAngles(yaw: Int, pitch: Int) = sendSet("yaw=$yaw&pitch=$pitch")
    fun setAlpha(alpha: Float) = sendSet("alpha=${"%.2f".format(alpha)}")

    private fun sendSet(params: String) {
        if (!_isConnected.value || baseUrl.isEmpty()) return
        scope.launch {
            val ok = httpGet("$baseUrl/set?$params")
            if (!ok) Log.w(TAG, "SET failed: $params")
        }
    }

    private fun httpGet(urlStr: String): Boolean {
        return try {
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.connectTimeout = HTTP_TIMEOUT_MS
            conn.readTimeout = HTTP_TIMEOUT_MS
            conn.requestMethod = "GET"
            val ok = conn.responseCode == 200
            conn.disconnect()
            ok
        } catch (e: Exception) {
            Log.e(TAG, "HTTP error ($urlStr): ${e.message}")
            false
        }
    }

    private suspend fun fetchStatus(): ESP32Status? = withContext(Dispatchers.IO) {
        try {
            val conn = URL("$baseUrl/status").openConnection() as HttpURLConnection
            conn.connectTimeout = HTTP_TIMEOUT_MS
            conn.readTimeout = HTTP_TIMEOUT_MS
            conn.requestMethod = "GET"
            if (conn.responseCode != 200) {
                conn.disconnect()
                return@withContext null
            }
            val body = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            val obj = JSONObject(body)
            ESP32Status(
                yawTarget = obj.optInt("yawTarget", 90),
                pitchTarget = obj.optInt("pitchTarget", 90),
                yawCurrent = obj.optDouble("yawCurrent", 90.0).toFloat(),
                pitchCurrent = obj.optDouble("pitchCurrent", 90.0).toFloat(),
                alpha = obj.optDouble("alpha", 0.08).toFloat()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Fetch status error: ${e.message}")
            null
        }
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive && _isConnected.value) {
                delay(POLL_INTERVAL_MS)
                val st = fetchStatus()
                if (st != null) {
                    _status.value = st
                } else {
                    _isConnected.value = false
                    _receivedData.value = "连接断开"
                    break
                }
            }
        }
    }

    fun disconnect() {
        pollJob?.cancel()
        pollJob = null
        _isConnected.value = false
        baseUrl = ""
    }

    fun destroy() {
        disconnect()
        scope.cancel()
    }
}
