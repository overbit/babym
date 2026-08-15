package com.localbabymonitor.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pGroup
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
    private var connecting = false

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
            connecting = false
            if (recoveryCount < 2) {
                recoveryCount++
                handler.postDelayed({
                    initChannel()
                    discover()
                }, 500)
            } else onStatus("Wi‑Fi Direct stopped responding. Toggle Wi‑Fi off/on and retry.")
        }
    }

    fun discover() {
        if (!allowed()) return
        if (connecting) {
            onStatus("A Wi‑Fi Direct connection is already being negotiated…")
            return
        }
        onPeers(emptyList())
        onStatus("Preparing Wi‑Fi Direct scan…")
        clearStaleParentState { startDiscovery() }
    }

    private fun startDiscovery() {
        if (!allowed()) return
        onStatus("Scanning for nearby baby devices…")
        manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = Unit
            override fun onFailure(reason: Int) {
                onStatus("Scan failed: ${reasonText(reason)}")
            }
        })
    }

    private fun clearStaleParentState(next: () -> Unit) {
        // A previous failed negotiation can leave this device with a stale group or
        // invitation. Clear those states sequentially before starting a new scan.
        manager.cancelConnect(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = removeGroupIfPresent(next)
            override fun onFailure(reason: Int) = removeGroupIfPresent(next)
        })
    }

    private fun removeGroupIfPresent(next: () -> Unit) {
        if (!allowed()) return
        manager.requestGroupInfo(channel) { group: WifiP2pGroup? ->
            if (group == null) {
                handler.postDelayed(next, 150)
                return@requestGroupInfo
            }
            manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    handler.postDelayed(next, 300)
                }

                override fun onFailure(reason: Int) {
                    // If the framework already removed the stale group, continuing is safe.
                    handler.postDelayed(next, 300)
                }
            })
        }
    }

    private fun requestPeers() {
        if (!allowed() || connecting) return
        manager.requestPeers(channel) { list ->
            val peers = list.deviceList.toList()
            onPeers(peers)
            onStatus(if (peers.isEmpty()) "No baby camera found yet." else "Nearby Wi‑Fi Direct devices")
        }
    }

    fun connect(device: WifiP2pDevice) {
        if (!allowed() || connecting) return

        // Android's current Wi-Fi Direct sample uses the classic PBC config. It is
        // supported across our full API 26+ range and avoids OEM differences seen
        // with the newer builder-based negotiation path. This is Wi-Fi Direct's
        // system provisioning, not an app-level PIN/authentication flow.
        @Suppress("DEPRECATION")
        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
            wps.setup = WpsInfo.PBC
            groupOwnerIntent = 0 // Prefer joining the Baby Camera's existing group.
        }

        connecting = true
        attemptConnect(config, device.deviceName.ifBlank { "baby device" }, 1)
    }

    private fun attemptConnect(config: WifiP2pConfig, label: String, attempt: Int) {
        if (!allowed()) {
            connecting = false
            return
        }
        onStatus("Connecting to $label…")

        // Do not call stopPeerDiscovery() here. Android automatically stops peer
        // discovery when connection setup starts; issuing an asynchronous stop and
        // connect back-to-back can race on some vendor Wi-Fi Direct implementations.
        manager.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                recoveryCount = 0
                onStatus("Connection request sent. Waiting for Android…")
            }

            override fun onFailure(reason: Int) {
                if ((reason == WifiP2pManager.ERROR || reason == WifiP2pManager.BUSY) && attempt < 3) {
                    onStatus("Wi‑Fi Direct negotiation reset. Retrying (${attempt + 1}/3)…")
                    recoverNegotiation {
                        handler.postDelayed(
                            { attemptConnect(config, label, attempt + 1) },
                            700L * attempt
                        )
                    }
                } else {
                    connecting = false
                    onStatus("Could not connect: ${reasonText(reason)}")
                }
            }
        })
    }

    private fun recoverNegotiation(next: () -> Unit) {
        manager.cancelConnect(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = removeGroupIfPresent(next)
            override fun onFailure(reason: Int) = removeGroupIfPresent(next)
        })
    }

    private fun requestConnectionInfo() {
        if (!allowed()) return
        manager.requestConnectionInfo(channel) info@{ info ->
            if (!info.groupFormed) {
                connecting = false
                return@info
            }
            if (info.isGroupOwner) {
                connecting = false
                onStatus("This phone became group owner unexpectedly. Scan and connect again.")
                return@info
            }
            connecting = false
            info.groupOwnerAddress?.hostAddress?.let(onHost)
        }
    }

    private fun allowed() = context.checkSelfPermission(permission()) == PackageManager.PERMISSION_GRANTED

    fun stop() {
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
        connecting = false
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
