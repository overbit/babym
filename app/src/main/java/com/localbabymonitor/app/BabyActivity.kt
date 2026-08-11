package com.localbabymonitor.app

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView

class BabyActivity : Activity() {
    private val requestCode = 1001
    private lateinit var startStopButton: Button
    private lateinit var switchCameraButton: Button
    private lateinit var settingsButton: Button
    private lateinit var status: TextView
    private lateinit var heroTitle: TextView
    private lateinit var heroSubtitle: TextView
    private lateinit var liveBadge: TextView
    private lateinit var readyChip: TextView
    private lateinit var wifiStatus: TextView
    private lateinit var connectionStatus: TextView
    private lateinit var audioStatus: TextView
    private lateinit var batteryBadge: TextView
    private var running = false
    private var statusReceiver: BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_baby)
        applySafeArea(findViewById(R.id.babyRoot))

        startStopButton = findViewById(R.id.startStopButton)
        switchCameraButton = findViewById(R.id.switchCameraButton)
        settingsButton = findViewById(R.id.babySettingsButton)
        status = findViewById(R.id.babyStatus)
        heroTitle = findViewById(R.id.cameraHeroTitle)
        heroSubtitle = findViewById(R.id.cameraHeroSubtitle)
        liveBadge = findViewById(R.id.liveBadge)
        readyChip = findViewById(R.id.readyChip)
        wifiStatus = findViewById(R.id.wifiStatus)
        connectionStatus = findViewById(R.id.connectionStatus)
        audioStatus = findViewById(R.id.audioStatus)
        batteryBadge = findViewById(R.id.batteryBadge)

        findViewById<View>(R.id.babyBackButton).setOnClickListener { finish() }
        startStopButton.setOnClickListener {
            if (running) stopMonitor() else ensurePermissionsAndStart()
        }

        switchCameraButton.setOnClickListener {
            startService(Intent(this, BabyMonitorService::class.java).setAction(BabyMonitorService.ACTION_SWITCH_CAMERA))
            applyServiceStatus("Switching camera...")
        }

        settingsButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
        }

        registerStatusReceiver()
        running = BabyMonitorService.isActive
        updateBattery()
        refreshReadiness()
    }

    private fun requiredPermissions(): Array<String> {
        val permissions = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
        if (Build.VERSION.SDK_INT >= 33) {
            permissions += Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            permissions += Manifest.permission.ACCESS_FINE_LOCATION
        }
        return permissions.toTypedArray()
    }

    private fun ensurePermissionsAndStart() {
        val missing = requiredPermissions().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), requestCode)
        } else {
            startMonitor()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == this.requestCode && grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            startMonitor()
        } else if (requestCode == this.requestCode) {
            applyServiceStatus("Camera, microphone, and nearby-device permissions are required.")
        }
    }

    private fun startMonitor() {
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (!wifi.isWifiEnabled) {
            running = false
            settingsButton.visibility = View.VISIBLE
            applyServiceStatus("Wi-Fi is off. Turn it on, then start monitoring again.")
            updateUiState()
            return
        }

        val intent = Intent(this, BabyMonitorService::class.java).setAction(BabyMonitorService.ACTION_START)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
        running = true
        settingsButton.visibility = View.GONE
        applyServiceStatus("Preparing a direct connection...")
        updateUiState()
    }

    private fun stopMonitor() {
        startService(Intent(this, BabyMonitorService::class.java).setAction(BabyMonitorService.ACTION_STOP))
        running = false
        applyServiceStatus("Camera stopped")
        updateUiState()
        refreshReadiness()
    }

    private fun registerStatusReceiver() {
        val filter = IntentFilter(BabyMonitorService.ACTION_STATUS)
        statusReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val message = intent?.getStringExtra(BabyMonitorService.EXTRA_STATUS) ?: return
                running = intent.getBooleanExtra(BabyMonitorService.EXTRA_RUNNING, running)
                applyServiceStatus(message)
                updateUiState()
            }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(statusReceiver, filter)
        }
    }

    private fun refreshReadiness() {
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiStatus.text = if (wifi.isWifiEnabled) "ON  ●" else "OFF"
        wifiStatus.setTextColor(getColor(if (wifi.isWifiEnabled) R.color.success else R.color.danger))
        if (!wifi.isWifiEnabled) {
            running = BabyMonitorService.isActive
            settingsButton.visibility = View.VISIBLE
            applyServiceStatus("Wi-Fi is off. Turn it on before starting.")
        } else if (running) {
            settingsButton.visibility = View.GONE
            applyServiceStatus(BabyMonitorService.lastStatus)
        } else {
            settingsButton.visibility = View.GONE
            applyServiceStatus("Ready. Start monitoring, then connect from the parent phone.")
        }
        updateBattery()
        updateUiState()
    }

    private fun applyServiceStatus(message: String) {
        status.text = message
        val connected = message.contains("Parent connected", ignoreCase = true) ||
            message.contains("live audio and video", ignoreCase = true)
        val waiting = message.contains("Ready", ignoreCase = true) ||
            message.contains("Waiting", ignoreCase = true)
        connectionStatus.text = when {
            connected -> "Parent connected"
            running && waiting -> "Discoverable"
            running -> "Preparing"
            else -> "Not active"
        }
        connectionStatus.setTextColor(getColor(if (connected) R.color.success else R.color.text_secondary))
        if (connected) {
            heroTitle.text = "Live to parent"
            heroSubtitle.text = "Video and audio are streaming over the local Wi‑Fi Direct connection."
            liveBadge.text = "●  LIVE"
        } else if (running) {
            heroTitle.text = "Monitoring is on"
            heroSubtitle.text = "Keep this phone near the baby. You can lock the screen after the parent connects."
            liveBadge.text = "ON"
        } else {
            heroTitle.text = "Camera ready"
            heroSubtitle.text = "The live feed appears on the parent phone when connected."
            liveBadge.text = "READY"
        }
    }

    private fun updateUiState() {
        startStopButton.text = if (running) "Stop Monitoring" else "Start Monitoring"
        switchCameraButton.isEnabled = running
        readyChip.text = if (running) "●  Monitoring" else "●  Ready"
        readyChip.setTextColor(getColor(if (running) R.color.success else R.color.text_secondary))
        audioStatus.text = if (running) "Microphone on" else "Microphone ready"
    }

    private fun updateBattery() {
        val battery = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        batteryBadge.text = if (level in 0..100) "$level%" else "Battery"
    }

    override fun onResume() {
        super.onResume()
        running = BabyMonitorService.isActive
        refreshReadiness()
    }

    override fun onDestroy() {
        statusReceiver?.let { runCatching { unregisterReceiver(it) } }
        statusReceiver = null
        super.onDestroy()
    }
}
