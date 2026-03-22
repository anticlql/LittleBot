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
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Manages a plain TCP socket connection to an ESP32 over WiFi.
 *
 * Default configuration assumes the ESP32 is running in AP mode
 * with IP 192.168.4.1, listening on port 8080.
 */
class WifiConnectionManager {

    companion object {
        private const val TAG = "WifiConnectionManager"
        const val DEFAULT_PORT = 8080
        private const val CONNECT_TIMEOUT_MS = 5000
    }

    private var socket: Socket? = null
    private var writer: PrintWriter? = null
    private var reader: BufferedReader? = null
    private var readJob: Job? = null

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected

    private val _receivedData = MutableStateFlow("")
    val receivedData: StateFlow<String> = _receivedData

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun connect(ip: String, port: Int = DEFAULT_PORT) {
        scope.launch {
            try {
                val sock = Socket()
                sock.connect(InetSocketAddress(ip, port), CONNECT_TIMEOUT_MS)
                sock.soTimeout = 3000

                socket = sock
                writer = PrintWriter(BufferedOutputStream(sock.getOutputStream()), true)
                reader = BufferedReader(InputStreamReader(sock.getInputStream()))

                _isConnected.value = true
                Log.d(TAG, "Connected to $ip:$port")

                startReading()
            } catch (e: Exception) {
                Log.e(TAG, "Connection failed: ${e.message}")
                _isConnected.value = false
            }
        }
    }

    private fun startReading() {
        readJob = scope.launch {
            try {
                while (isActive && socket?.isConnected == true) {
                    val line = withContext(Dispatchers.IO) {
                        try {
                            reader?.readLine()
                        } catch (_: Exception) {
                            null
                        }
                    }
                    if (line != null) {
                        _receivedData.value = line
                        Log.d(TAG, "Received: $line")
                    } else {
                        delay(100)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Read error: ${e.message}")
            }
        }
    }

    fun sendCommand(command: String): Boolean {
        return try {
            writer?.println(command)
            writer?.flush()
            Log.d(TAG, "Sent: $command")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Send failed: ${e.message}")
            false
        }
    }

    fun disconnect() {
        readJob?.cancel()
        try {
            writer?.close()
            reader?.close()
            socket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Disconnect error: ${e.message}")
        }
        socket = null
        writer = null
        reader = null
        _isConnected.value = false
    }

    fun destroy() {
        disconnect()
        scope.cancel()
    }
}
