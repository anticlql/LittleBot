package com.hardware.littlebot

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import com.hardware.littlebot.ui.screen.MainScreen
import com.hardware.littlebot.ui.theme.LittleBotTheme
import com.hardware.littlebot.viewmodel.ESP32ViewModel

class MainActivity : ComponentActivity() {

    private lateinit var viewModel: ESP32ViewModel

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            viewModel.startBleScan()
        } else {
            Toast.makeText(this, "需要蓝牙和定位权限才能扫描设备", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        viewModel = ViewModelProvider(this)[ESP32ViewModel::class.java]

        setContent {
            LittleBotTheme {
                MainScreen(
                    viewModel = viewModel,
                    onRequestBlePermissions = ::requestBlePermissions,
                    onOpenLocationSettings = ::openLocationSettings
                )
            }
        }
    }

    /**
     * Request all Bluetooth-related permissions.
     *
     * On Android 12+: BLUETOOTH_SCAN, BLUETOOTH_CONNECT, and ACCESS_FINE_LOCATION
     *   (some OEMs still require location for BLE scanning even on 12+).
     * On older versions: BLUETOOTH, BLUETOOTH_ADMIN, ACCESS_FINE_LOCATION.
     *
     * Classic Bluetooth discovery also needs ACCESS_FINE_LOCATION on all versions.
     */
    private fun requestBlePermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }

        val notGranted = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (notGranted.isEmpty()) {
            viewModel.startBleScan()
        } else {
            permissionLauncher.launch(notGranted.toTypedArray())
        }
    }

    /**
     * Open the system Location settings so the user can enable GPS / location services.
     */
    private fun openLocationSettings() {
        try {
            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        } catch (_: Exception) {
            Toast.makeText(this, "无法打开定位设置", Toast.LENGTH_SHORT).show()
        }
    }
}
