package com.localbabymonitor.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

class BabyMonitorService : Service() {
    companion object {
        const val ACTION_START = "com.localbabymonitor.app.START"
        const val ACTION_STOP = "com.localbabymonitor.app.STOP"
        const val ACTION_SWITCH_CAMERA = "com.localbabymonitor.app.SWITCH_CAMERA"
        const val ACTION_STATUS = "com.localbabymonitor.app.STATUS"
        const val EXTRA_STATUS = "status"
        const val EXTRA_RUNNING = "running"

        private const val NOTIFICATION_ID = 10
        private const val CHANNEL_ID = "baby_monitor_stream"

        @Volatile var isActive: Boolean = false
            private set
        @Volatile var lastStatus: String = "Ready"
            private set
    }

    private lateinit var manager: WifiP2pManager
    private lateinit var channel: WifiP2pManager.Channel
    private var receiver: BroadcastReceiver? = null
    private var serverSocket: ServerSocket? = null
    private var serverThread: Thread? = null
    private val serviceRunning = AtomicBoolean(false)
    private var wakeLock: PowerManager.WakeLock? = null
    private var useFrontCamera = false
    @Volatile private var videoStreamer: VideoStreamer? = null
    private var audioStreamer: AudioStreamer? = null
    private var currentWriter: StreamWriter? = null
    @Volatile private var noiseAlertsEnabled = true
    private val handler = Handler(Looper.getMainLooper())
    private var groupCreateAttempt = 0
    private var channelRecoveryCount = 0

    override fun onCreate() {
        super.onCreate()
        manager = getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
        initializeP2pChannel()
        createNotificationChannel()
    }

    private fun initializeP2pChannel() {
        channel = manager.initialize(this, mainLooper) {
            if (!serviceRunning.get()) return@initialize
            if (channelRecoveryCount >= 2) {
                updateStatus("Android Wi-Fi Direct stopped responding. Toggle Wi-Fi off/on, then restart the camera.")
                return@initialize
            }
            channelRecoveryCount += 1
            handler.postDelayed({
                initializeP2pChannel()
                prepareGroup()
            }, 500)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopMonitor()
            ACTION_SWITCH_CAMERA -> switchCamera()
            ACTION_START -> startMonitor()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startMonitor() {
        if (serviceRunning.getAndSet(true)) {
            broadcastStatus("Baby camera is already running.")
            return
        }
        isActive = true
        lastStatus = "Preparing direct connection..."
        startForeground(NOTIFICATION_ID, notification(lastStatus))

        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LocalBabyMonitor:stream").apply {
            setReferenceCounted(false)
            acquire()
        }

        registerP2pReceiver()
        groupCreateAttempt = 0
        prepareGroup()
    }

    private fun registerP2pReceiver() {
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
        }
        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        manager.requestConnectionInfo(channel) { connection ->
                            if (connection.groupFormed && connection.isGroupOwner) {
                                startServer()
                                manager.requestGroupInfo(channel) { group ->
                                    val connected = group?.clientList?.isNotEmpty() == true
                                    if (connected) updateStatus("Parent connected. Live audio and video are starting...")
                                    else updateStatus("Ready. On the parent phone, tap Scan nearby devices.")
                                }
                            }
                        }
                    }
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                        val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                        if (state != WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                            updateStatus("Wi-Fi Direct is unavailable. Turn Wi-Fi on.")
                        }
                    }
                }
            }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(receiver, filter)
        }
    }

    private fun prepareGroup() {
        if (!serviceRunning.get()) return
        updateStatus("Preparing Wi-Fi Direct...")
        manager.requestConnectionInfo(channel) { info ->
            if (!serviceRunning.get()) return@requestConnectionInfo
            when {
                info.groupFormed && info.isGroupOwner -> onGroupReady()
                info.groupFormed -> manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() { handler.postDelayed({ createGroup() }, 300) }
                    override fun onFailure(reason: Int) { handler.postDelayed({ createGroup() }, 300) }
                })
                else -> createGroup()
            }
        }
    }

    private fun createGroup() {
        if (!serviceRunning.get()) return
        groupCreateAttempt += 1
        manager.createGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                channelRecoveryCount = 0
                onGroupReady()
            }

            override fun onFailure(reason: Int) {
                manager.requestConnectionInfo(channel) { info ->
                    if (info.groupFormed && info.isGroupOwner) {
                        onGroupReady()
                    } else if ((reason == WifiP2pManager.ERROR || reason == WifiP2pManager.BUSY) && groupCreateAttempt < 3) {
                        if (reason == WifiP2pManager.ERROR) initializeP2pChannel()
                        updateStatus("Wi-Fi Direct was busy. Retrying (${groupCreateAttempt + 1}/3)...")
                        handler.postDelayed({ createGroup() }, 900L * groupCreateAttempt)
                    } else {
                        updateStatus(
                            "Could not start Wi-Fi Direct: ${reasonText(reason)}. " +
                                "Toggle Wi-Fi off/on, then tap Stop camera and Start camera."
                        )
                    }
                }
            }
        })
    }

    private fun onGroupReady() {
        startServer()
        startP2pListening()
        updateStatus("Ready. On the parent phone, tap Scan nearby devices.")
    }

    private fun startP2pListening() {
        if (Build.VERSION.SDK_INT < 33 || !serviceRunning.get()) return
        runCatching {
            manager.startListening(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() = Unit
                override fun onFailure(reason: Int) = Unit
            })
        }
    }

    @Synchronized
    private fun startServer() {
        if (serverThread?.isAlive == true) return
        serverThread = Thread({ serverLoop() }, "baby-monitor-server").also { it.start() }
    }

    private fun serverLoop() {
        try {
            serverSocket = ServerSocket(Protocol.PORT)
            while (serviceRunning.get()) {
                val socket = serverSocket?.accept() ?: break
                handleClient(socket)
            }
        } catch (_: Exception) {
            if (serviceRunning.get()) updateStatus("Streaming socket stopped. Stop and start the camera again.")
        }
    }

    private fun handleClient(socket: Socket) {
        socket.tcpNoDelay = true
        socket.keepAlive = true
        val clientAlive = AtomicBoolean(true)
        var commandThread: Thread? = null
        try {
            val input = DataInputStream(socket.getInputStream())
            val output = DataOutputStream(socket.getOutputStream())
            updateStatus("Parent connected - live audio and video")

            val writer = StreamWriter(socket, output)
            currentWriter = writer
            startMedia(writer)
            sendNoiseState(writer)
            sendTorchState(writer)

            commandThread = Thread({
                try {
                    while (serviceRunning.get() && clientAlive.get() && !socket.isClosed) {
                        val packet = Protocol.readPacket(input)
                        handleControlPacket(packet, writer)
                    }
                } catch (_: Exception) {
                    // Socket close/EOF ends this parent session.
                } finally {
                    clientAlive.set(false)
                }
            }, "baby-monitor-controls").also { it.start() }

            while (serviceRunning.get() && clientAlive.get() && !writer.failed && !socket.isClosed) {
                Thread.sleep(250)
            }
        } catch (_: Exception) {
            // The accept loop waits for the next parent connection.
        } finally {
            clientAlive.set(false)
            runCatching { socket.close() }
            runCatching { commandThread?.join(500) }
            stopMedia()
            currentWriter = null
            if (serviceRunning.get()) updateStatus("Ready. Waiting for the parent phone...")
        }
    }

    private fun handleControlPacket(packet: Protocol.Packet, writer: StreamWriter) {
        when (packet.type) {
            Protocol.TYPE_NOISE_CONTROL -> {
                val enabled = runCatching { Protocol.unpackNoiseControl(packet.payload) }.getOrNull() ?: return
                noiseAlertsEnabled = enabled
                audioStreamer?.setNoiseAlertsEnabled(enabled)
                sendNoiseState(writer)
            }
            Protocol.TYPE_TORCH_CONTROL -> {
                val enabled = runCatching { Protocol.unpackTorchControl(packet.payload) }.getOrNull() ?: return
                videoStreamer?.setTorch(enabled)
                sendTorchState(writer)
            }
        }
    }

    /**
     * Always reports what the camera actually holds, so a request the hardware rejected shows up
     * on the parent phone as the torch staying off rather than as a control that silently did nothing.
     */
    private fun sendTorchState(writer: StreamWriter) {
        val streamer = videoStreamer
        writer.packet(
            Protocol.TYPE_TORCH_STATE,
            0,
            0,
            Protocol.packTorchState(
                streamer?.isTorchAvailable == true,
                streamer?.isTorchEnabled == true
            )
        )
    }

    private fun sendNoiseState(writer: StreamWriter) {
        writer.packet(
            Protocol.TYPE_NOISE_STATE,
            0,
            0,
            Protocol.packNoiseState(noiseAlertsEnabled)
        )
    }

    private fun startMedia(writer: StreamWriter) {
        stopMedia()
        videoStreamer = VideoStreamer(this, useFrontCamera).also { it.start(writer) }
        audioStreamer = AudioStreamer { levelPercent ->
            writer.packet(
                Protocol.TYPE_NOISE_ALERT,
                0,
                0,
                Protocol.packNoiseAlert(levelPercent)
            )
        }.also {
            it.setNoiseAlertsEnabled(noiseAlertsEnabled)
            it.start(writer)
        }
    }

    private fun stopMedia() {
        videoStreamer?.stop()
        videoStreamer = null
        audioStreamer?.stop()
        audioStreamer = null
    }

    private fun switchCamera() {
        useFrontCamera = !useFrontCamera
        val writer = currentWriter
        if (writer != null && !writer.failed) {
            videoStreamer?.stop()
            videoStreamer = VideoStreamer(this, useFrontCamera).also { it.start(writer) }
            // The new camera starts with the torch off, and the front one usually has no flash at all.
            sendTorchState(writer)
        }
        updateStatus(if (useFrontCamera) "Front camera selected" else "Rear camera selected")
    }

    private fun stopMonitor() {
        if (!serviceRunning.getAndSet(false)) {
            stopSelf()
            return
        }
        stopMedia()
        runCatching { serverSocket?.close() }
        serverSocket = null
        handler.removeCallbacksAndMessages(null)
        manager.stopPeerDiscovery(channel, null)
        if (Build.VERSION.SDK_INT >= 33) runCatching { manager.stopListening(channel, null) }
        manager.removeGroup(channel, null)
        receiver?.let { runCatching { unregisterReceiver(it) } }
        receiver = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        isActive = false
        lastStatus = "Camera stopped"
        broadcastStatus(lastStatus)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (serviceRunning.get()) stopMonitor()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Baby monitor stream", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun notification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, BabyActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        return builder
            .setSmallIcon(R.drawable.ic_monitor)
            .setContentTitle("Local Baby Monitor")
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateStatus(text: String) {
        lastStatus = text
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
        broadcastStatus(text)
    }

    private fun broadcastStatus(text: String) {
        sendBroadcast(
            Intent(ACTION_STATUS)
                .setPackage(packageName)
                .putExtra(EXTRA_STATUS, text)
                .putExtra(EXTRA_RUNNING, serviceRunning.get())
        )
    }

    private fun reasonText(reason: Int): String = when (reason) {
        WifiP2pManager.P2P_UNSUPPORTED -> "Wi-Fi Direct is not supported"
        WifiP2pManager.BUSY -> "Wi-Fi Direct is busy"
        WifiP2pManager.ERROR -> "Android Wi-Fi Direct internal error (0)"
        else -> "Wi-Fi Direct error $reason"
    }
}
