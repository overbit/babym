package com.localbabymonitor.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.MacAddress
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.Looper

class MonitorP2pController(
    private val context: Context,
    private val permission: () -> String,
    private val onStatus: (String) -> Unit,
    private val onPeers: (List<WifiP2pDevice>) -> Unit,
    private val onHost: (String) -> Unit,
    private val onStateChanged: () -> Unit
) {
    private val manager = context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var channel: WifiP2pManager.Channel
    private var receiver: BroadcastReceiver? = null
    private var recoveryCount = 0

    fun start() {
        initChannel()
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
        }
        receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                when (intent?.action) {
                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> requestPeers()
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> requestConnectionInfo()
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> onStateChanged()
                }
            }
        }
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        else @Suppress("DEPRECATION") context.registerReceiver(receiver, filter)
    }

    private fun initChannel() {
        channel = manager.initialize(context, Looper.getMainLooper()) {
            if (recoveryCount < 2) {
                recoveryCount++
                handler.postDelayed({ initChannel(); discover() }, 500)
            } else onStatus("Wi‑Fi Direct stopped responding. Toggle Wi‑Fi off/on and retry.")
        }
    }

    fun discover() {
        if (!allowed()) return
        onPeers(emptyList())
        onStatus("Scanning for nearby baby devices…")
        manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = Unit
            override fun onFailure(reason: Int) { onStatus("Scan failed: ${reasonText(reason)}") }
        })
    }

    private fun requestPeers() {
        if (!allowed()) return
        manager.requestPeers(channel) { list ->
            val peers = list.deviceList.toList()
            onPeers(peers)
            onStatus(if (peers.isEmpty()) "No baby camera found yet." else "Nearby Wi‑Fi Direct devices")
        }
    }

    fun connect(device: WifiP2pDevice) {
        if (!allowed()) return
        manager.stopPeerDiscovery(channel, null)
        val config = if (Build.VERSION.SDK_INT >= 29) {
            WifiP2pConfig.Builder()
                .setDeviceAddress(MacAddress.fromString(device.deviceAddress))
                .enablePersistentMode(false)
                .build()
        } else {
            @Suppress("DEPRECATION") WifiP2pConfig().apply { deviceAddress = device.deviceAddress }
        }
        attemptConnect(config, device.deviceName.ifBlank { "baby device" }, 1)
    }

    private fun attemptConnect(config: WifiP2pConfig, label: String, attempt: Int) {
        onStatus("Connecting to $label…")
        manager.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { recoveryCount = 0; onStatus("Connection request sent. Waiting for Android…") }
            override fun onFailure(reason: Int) {
                if ((reason == WifiP2pManager.ERROR || reason == WifiP2pManager.BUSY) && attempt < 3) {
                    if (reason == WifiP2pManager.ERROR) initChannel()
                    handler.postDelayed({ attemptConnect(config, label, attempt + 1) }, 800L * attempt)
                } else onStatus("Could not connect: ${reasonText(reason)}")
            }
        })
    }

    private fun requestConnectionInfo() {
        if (!allowed()) return
        manager.requestConnectionInfo(channel) info@{ info ->
            if (!info.groupFormed || info.isGroupOwner) return@info
            info.groupOwnerAddress?.hostAddress?.let(onHost)
        }
    }

    private fun allowed() = context.checkSelfPermission(permission()) == PackageManager.PERMISSION_GRANTED

    fun stop() {
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
        handler.removeCallbacksAndMessages(null)
        if (::channel.isInitialized) manager.stopPeerDiscovery(channel, null)
    }

    private fun reasonText(reason: Int) = when (reason) {
        WifiP2pManager.P2P_UNSUPPORTED -> "Wi‑Fi Direct unsupported"
        WifiP2pManager.BUSY -> "Wi‑Fi Direct busy"
        WifiP2pManager.ERROR -> "Android Wi‑Fi Direct internal error (0)"
        else -> "Wi‑Fi Direct error $reason"
    }
}
