package com.overbit.babymonitor.p2p

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "WifiDirect"

/**
 * Thin wrapper over [WifiP2pManager].
 *
 * The baby unit creates an *autonomous group* (`createGroup`), which makes it the group owner
 * at a well-known address. The parent unit only has to discover it and connect — no IP
 * exchange or service discovery is needed, because the group owner's address is handed to the
 * client in [WifiP2pInfo.groupOwnerAddress].
 */
@SuppressLint("MissingPermission") // Callers gate on runtime permissions before invoking.
class WifiDirectManager(private val context: Context) {

    private val manager = context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager?
    private val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager?
    private var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null

    private val _peers = MutableStateFlow<List<WifiP2pDevice>>(emptyList())
    val peers: StateFlow<List<WifiP2pDevice>> = _peers.asStateFlow()

    private val _connectionInfo = MutableStateFlow<WifiP2pInfo?>(null)
    val connectionInfo: StateFlow<WifiP2pInfo?> = _connectionInfo.asStateFlow()

    private val _wifiP2pEnabled = MutableStateFlow(false)
    val wifiP2pEnabled: StateFlow<Boolean> = _wifiP2pEnabled.asStateFlow()

    private val _thisDeviceName = MutableStateFlow<String?>(null)
    val thisDeviceName: StateFlow<String?> = _thisDeviceName.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** Name of the peer a [connect] is in flight for, or null when nothing is pending. */
    private val _connectingTo = MutableStateFlow<String?>(null)
    val connectingTo: StateFlow<String?> = _connectingTo.asStateFlow()

    val isSupported: Boolean get() = manager != null

    /**
     * The current channel, re-initialized on demand.
     *
     * The framework hands back a dead channel whenever the Wi-Fi stack restarts, and the only
     * notice is [WifiP2pManager.ChannelListener]. Re-opening it here rather than once at
     * registration keeps a later tap from quietly doing nothing.
     */
    private fun channel(): WifiP2pManager.Channel? {
        val mgr = manager ?: return null
        channel?.let { return it }
        return mgr.initialize(context, Looper.getMainLooper()) {
            Log.w(TAG, "Wi-Fi Direct channel disconnected")
            channel = null
        }.also { channel = it }
    }

    fun register() {
        if (manager == null) return
        channel()
        // WIFI_P2P_STATE_CHANGED is only broadcast on change, so seed the state from the radio
        // itself; Wi-Fi Direct needs Wi-Fi switched on regardless.
        _wifiP2pEnabled.value = wifiManager?.isWifiEnabled == true
        if (receiver != null) return
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        val newReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = handleBroadcast(intent)
        }
        receiver = newReceiver
        ContextCompat.registerReceiver(
            context,
            newReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    fun unregister() {
        receiver?.let { context.unregisterReceiver(it) }
        receiver = null
    }

    private fun handleBroadcast(intent: Intent) {
        val mgr = manager ?: return
        val ch = channel() ?: return
        when (intent.action) {
            WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                _wifiP2pEnabled.value = state == WifiP2pManager.WIFI_P2P_STATE_ENABLED
            }

            WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                mgr.requestPeers(ch) { list: WifiP2pDeviceList ->
                    _peers.value = list.deviceList.toList()
                }
            }

            WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                mgr.requestConnectionInfo(ch) { info: WifiP2pInfo ->
                    _connectionInfo.value = if (info.groupFormed) info else null
                    if (info.groupFormed) _connectingTo.value = null
                }
            }

            WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                @Suppress("DEPRECATION")
                val device: WifiP2pDevice? =
                    intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE)
                _thisDeviceName.value = device?.deviceName
            }
        }
    }

    /** Baby unit: become the group owner so the parent always knows where to connect. */
    fun createGroup() {
        val mgr = manager ?: return
        val ch = channel() ?: return notReady()
        _message.value = null
        mgr.requestGroupInfo(ch) { group ->
            if (group != null && group.isGroupOwner) {
                // Already hosting; refresh the connection info so the UI catches up.
                mgr.requestConnectionInfo(ch) { info ->
                    _connectionInfo.value = if (info.groupFormed) info else null
                }
                return@requestGroupInfo
            }
            if (group != null) {
                mgr.removeGroup(ch, actionListener("Leave old group") { createGroupNow() })
            } else {
                createGroupNow()
            }
        }
    }

    private fun createGroupNow() {
        val mgr = manager ?: return
        val ch = channel() ?: return notReady()
        mgr.createGroup(ch, actionListener("Create group"))
    }

    /** Parent unit: look for baby units in range. */
    fun discoverPeers() {
        val mgr = manager ?: return
        val ch = channel() ?: return notReady()
        _message.value = null
        mgr.discoverPeers(ch, actionListener("Discover"))
    }

    /** Parent unit: join the selected baby unit's group. */
    fun connect(device: WifiP2pDevice) {
        val mgr = manager ?: return
        val ch = channel() ?: return notReady()
        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
            // The baby unit is already the owner; never contest it.
            groupOwnerIntent = 0
        }
        _message.value = null
        // Negotiation runs for several seconds and the baby unit may have to accept an
        // invitation first, so hold the pending name for the UI to show meanwhile.
        _connectingTo.value = device.deviceName.ifBlank { device.deviceAddress }
        mgr.connect(ch, config, actionListener("Connect", onFailure = { _connectingTo.value = null }))
    }

    /** Abandons an in-flight [connect]; [reason] is surfaced to the user when given. */
    fun cancelConnect(reason: String? = null) {
        _connectingTo.value = null
        _message.value = reason
        val mgr = manager ?: return
        val ch = channel() ?: return
        // Nothing pending is a perfectly normal outcome here, so failures stay quiet.
        mgr.cancelConnect(ch, actionListener("Cancel connect", report = false))
    }

    /** Tears the group down on either side. */
    fun disconnect() {
        val mgr = manager ?: return
        val ch = channel() ?: return notReady()
        mgr.removeGroup(ch, actionListener("Disconnect", report = false))
        _connectionInfo.value = null
        _connectingTo.value = null
        _peers.value = emptyList()
    }

    private fun notReady() {
        Log.w(TAG, "No Wi-Fi Direct channel available")
        _message.value = "Wi-Fi Direct isn't ready. Turn Wi-Fi off and on, then try again."
    }

    fun clearMessage() {
        _message.value = null
    }

    private fun actionListener(
        label: String,
        report: Boolean = true,
        onFailure: () -> Unit = {},
        onSuccess: () -> Unit = {},
    ) = object : WifiP2pManager.ActionListener {
        override fun onSuccess() {
            Log.d(TAG, "$label succeeded")
            onSuccess()
        }

        override fun onFailure(reason: Int) {
            onFailure()
            val why = when (reason) {
                WifiP2pManager.P2P_UNSUPPORTED -> "Wi-Fi Direct is not supported on this device"
                WifiP2pManager.BUSY -> "Wi-Fi is busy, try again in a moment"
                WifiP2pManager.ERROR -> "internal error"
                WifiP2pManager.NO_SERVICE_REQUESTS -> "no service requests"
                else -> "reason $reason"
            }
            Log.w(TAG, "$label failed: $why")
            if (report) _message.value = "$label failed: $why"
        }
    }
}
