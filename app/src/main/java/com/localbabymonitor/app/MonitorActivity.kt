package com.localbabymonitor.app

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pDevice
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.view.LayoutInflater
import android.view.SurfaceHolder
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

class MonitorActivity : Activity() {
    companion object {
        private const val REQUEST_P2P_PERMISSION = 2001
        private const val REQUEST_NOTIFICATION_PERMISSION = 2002
        private const val NOISE_CHANNEL_ID = "noise_alerts_v2"
        private const val NOISE_NOTIFICATION_ID = 21
        private const val PREFS = "monitor_preferences"
        private const val PREF_NOISE_ALERT_LEVEL = "noise_alert_level"
        private const val MIN_NOISE_ALERT_LEVEL = 16
        private const val MAX_NOISE_ALERT_LEVEL = 100
        private const val DEFAULT_NOISE_ALERT_LEVEL = 16
    }

    private lateinit var p2p: MonitorP2pController
    private lateinit var status: TextView
    private lateinit var deviceList: LinearLayout
    private lateinit var emptyState: View
    private lateinit var discoveryScreen: View
    private lateinit var liveScreen: LinearLayout
    private lateinit var videoSurface: AspectRatioSurfaceView
    private lateinit var liveStatus: TextView
    private lateinit var liveTimer: TextView
    private lateinit var audioButton: Button
    private lateinit var noiseButton: Button
    private lateinit var torchButton: Button
    private lateinit var torchDetail: TextView
    private lateinit var noiseAlertBanner: TextView
    private lateinit var noiseAlertDetail: TextView
    private lateinit var noiseAlertExplanation: TextView
    private lateinit var noiseThresholdLabel: TextView
    private lateinit var noiseThresholdSeekBar: SeekBar
    private lateinit var liveHeader: View
    private lateinit var liveControls: View
    private lateinit var liveInfoCard: View

    private val handler = Handler(Looper.getMainLooper())
    private var client: MonitorStreamClient? = null
    private var currentHost: String? = null
    private var muted = false
    private var noiseAlertsEnabled = true
    private var torchState: Protocol.TorchState? = null
    private var pendingTorch: Boolean? = null
    private var noiseAlertLevel = DEFAULT_NOISE_ALERT_LEVEL
    private var fullscreen = false
    private var liveStartedAt = 0L
    private var notificationPermissionRequested = false

    private val hideNoiseAlert = Runnable { noiseAlertBanner.visibility = View.GONE }
    private val noiseAckTimeout = Runnable { updateNoiseAlertState(noiseAlertsEnabled) }
    private val torchAckTimeout = Runnable {
        pendingTorch = null
        renderTorch("No answer from the baby phone · tap to try again")
    }
    private val timerTick = object : Runnable {
        override fun run() {
            if (liveStartedAt == 0L || liveScreen.visibility != View.VISIBLE) return
            val seconds = (SystemClock.elapsedRealtime() - liveStartedAt) / 1000
            liveTimer.text = "● LIVE · %02d:%02d".format(seconds / 60, seconds % 60)
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_monitor)
        applySafeArea(findViewById(R.id.monitorRoot))
        noiseAlertLevel = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getInt(PREF_NOISE_ALERT_LEVEL, DEFAULT_NOISE_ALERT_LEVEL)
            .coerceIn(MIN_NOISE_ALERT_LEVEL, MAX_NOISE_ALERT_LEVEL)
        createNoiseNotificationChannel()
        bindViews()
        bindActions()
        p2p = MonitorP2pController(
            context = this,
            permission = { requiredPermission() },
            onStatus = { runOnUiThread { status.text = it } },
            onPeers = { runOnUiThread { renderPeers(it) } },
            onHost = { runOnUiThread { startClient(it) } },
            onStateChanged = { runOnUiThread { updateReadiness() } }
        ).also { it.start() }
        updateReadiness()
        updateNoiseAlertState(noiseAlertsEnabled)
        updateTorchState(null)
    }

    private fun bindViews() {
        status = findViewById(R.id.monitorStatus)
        deviceList = findViewById(R.id.deviceListContainer)
        emptyState = findViewById(R.id.deviceListEmpty)
        discoveryScreen = findViewById(R.id.discoveryScreen)
        liveScreen = findViewById(R.id.liveScreen)
        videoSurface = findViewById(R.id.videoSurface)
        liveStatus = findViewById(R.id.liveStatus)
        liveTimer = findViewById(R.id.liveTimer)
        audioButton = findViewById(R.id.audioButton)
        noiseButton = findViewById(R.id.noiseButton)
        torchButton = findViewById(R.id.torchButton)
        torchDetail = findViewById(R.id.torchDetail)
        noiseAlertBanner = findViewById(R.id.noiseAlertBanner)
        noiseAlertDetail = findViewById(R.id.noiseAlertDetail)
        noiseAlertExplanation = findViewById(R.id.noiseAlertExplanation)
        noiseThresholdLabel = findViewById(R.id.noiseThresholdLabel)
        noiseThresholdSeekBar = findViewById(R.id.noiseThresholdSeekBar)
        liveHeader = findViewById(R.id.liveHeader)
        liveControls = findViewById(R.id.liveControls)
        liveInfoCard = findViewById(R.id.liveInfoCard)
    }

    private fun bindActions() {
        findViewById<View>(R.id.monitorBackButton).setOnClickListener { finish() }
        findViewById<View>(R.id.liveBackButton).setOnClickListener { finish() }
        findViewById<View>(R.id.refreshButton).setOnClickListener { ensureReadyAndScan() }
        findViewById<Button>(R.id.findButton).setOnClickListener { ensureReadyAndScan() }
        findViewById<Button>(R.id.reconnectButton).setOnClickListener { currentHost?.let { startClient(it, true) } }
        findViewById<Button>(R.id.fullscreenButton).setOnClickListener { toggleFullscreen() }
        audioButton.setOnClickListener {
            muted = !muted
            client?.setMuted(muted)
            audioButton.text = if (muted) "Audio muted" else "Audio on"
        }
        torchButton.setOnClickListener {
            val requested = torchState?.enabled != true
            if (client?.setTorch(requested) == true) {
                pendingTorch = requested
                torchButton.isEnabled = false
                torchButton.text = "Updating torch…"
                handler.removeCallbacks(torchAckTimeout)
                handler.postDelayed(torchAckTimeout, 2_000)
            } else {
                pendingTorch = null
                renderTorch("Could not reach the baby phone · reconnect the stream and try again")
            }
        }
        noiseButton.isEnabled = false
        noiseButton.setOnClickListener {
            val requested = !noiseAlertsEnabled
            if (client?.setNoiseAlerts(requested) == true) {
                noiseButton.isEnabled = false
                noiseButton.text = "Updating alerts…"
                handler.removeCallbacks(noiseAckTimeout)
                handler.postDelayed(noiseAckTimeout, 2_000)
            }
        }
        noiseThresholdSeekBar.min = MIN_NOISE_ALERT_LEVEL
        noiseThresholdSeekBar.max = MAX_NOISE_ALERT_LEVEL
        noiseThresholdSeekBar.progress = noiseAlertLevel
        noiseThresholdSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                noiseAlertLevel = progress.coerceIn(MIN_NOISE_ALERT_LEVEL, MAX_NOISE_ALERT_LEVEL)
                updateNoiseAlertState(noiseAlertsEnabled)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                getSharedPreferences(PREFS, MODE_PRIVATE)
                    .edit()
                    .putInt(PREF_NOISE_ALERT_LEVEL, noiseAlertLevel)
                    .apply()
            }
        })
        findViewById<View>(R.id.videoFrame).setOnClickListener { if (fullscreen) toggleFullscreen() }
        videoSurface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) { client?.attachSurface(holder.surface) }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
            override fun surfaceDestroyed(holder: SurfaceHolder) { client?.detachSurface(holder.surface) }
        })
    }

    private fun requiredPermission() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION

    private fun ensureReadyAndScan() {
        val permission = requiredPermission()
        when {
            checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED -> requestPermissions(arrayOf(permission), REQUEST_P2P_PERMISSION)
            !wifiEnabled() -> startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
            !locationEnabled() -> startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            else -> p2p.discover()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQUEST_P2P_PERMISSION -> {
                if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) ensureReadyAndScan()
                else status.text = "Nearby-device permission is required to find the baby camera."
            }
            REQUEST_NOTIFICATION_PERMISSION -> updateNoiseAlertState(noiseAlertsEnabled)
        }
    }

    private fun renderPeers(peers: List<WifiP2pDevice>) {
        deviceList.removeAllViews()
        emptyState.visibility = if (peers.isEmpty()) View.VISIBLE else View.GONE
        peers.forEach { device ->
            val row = LayoutInflater.from(this).inflate(R.layout.item_device, deviceList, false)
            row.findViewById<TextView>(R.id.deviceName).text = device.deviceName.ifBlank { "Nearby device" }
            row.findViewById<TextView>(R.id.deviceState).text = when (device.status) {
                WifiP2pDevice.AVAILABLE -> "Available nearby"
                WifiP2pDevice.INVITED -> "Invitation sent"
                WifiP2pDevice.CONNECTED -> "Connected"
                else -> "Nearby Wi‑Fi Direct device"
            }
            row.setOnClickListener { p2p.connect(device) }
            deviceList.addView(row)
        }
    }

    private fun startClient(host: String, force: Boolean = false) {
        if (!force && client?.isRunning == true && currentHost == host) return
        currentHost = host
        client?.stop()
        startForegroundService(
            Intent(this, ParentMonitorService::class.java)
                .setAction(ParentMonitorService.ACTION_START)
        )
        client = MonitorStreamClient(
            context = this,
            host = host,
            onStatus = { runOnUiThread { liveStatus.text = it } },
            onVideoSize = { w, h -> runOnUiThread { videoSurface.setVideoSize(w, h) } },
            onStreaming = { runOnUiThread { showLive() } },
            onNoiseState = { enabled -> runOnUiThread { updateNoiseAlertState(enabled) } },
            onNoiseAlert = { level -> runOnUiThread { showNoiseAlert(level) } },
            onTorchState = { state -> runOnUiThread { updateTorchState(state) } }
        ).also {
            it.setMuted(muted)
            if (videoSurface.holder.surface.isValid) it.attachSurface(videoSurface.holder.surface)
            it.start()
        }
        discoveryScreen.visibility = View.GONE
        liveScreen.visibility = View.VISIBLE
        liveStatus.text = "Opening local stream…"
        // The baby phone reports its real torch state once the stream is up.
        updateTorchState(null)
    }

    private fun showLive() {
        discoveryScreen.visibility = View.GONE
        liveScreen.visibility = View.VISIBLE
        liveStatus.text = "HD · Local"
        if (liveStartedAt == 0L) liveStartedAt = SystemClock.elapsedRealtime()
        handler.removeCallbacks(timerTick)
        handler.post(timerTick)
        requestNoiseNotificationPermissionIfNeeded()
        updateNoiseAlertState(noiseAlertsEnabled)
    }

    private fun updateNoiseAlertState(enabled: Boolean) {
        handler.removeCallbacks(noiseAckTimeout)
        noiseAlertsEnabled = enabled
        noiseButton.isEnabled = client?.isRunning == true
        noiseButton.text = if (enabled) "Turn noise alerts off" else "Turn noise alerts on"
        noiseThresholdLabel.text = "Alert level · $noiseAlertLevel%"
        noiseAlertDetail.text = when {
            !enabled -> "Off · the baby phone is not checking sound level"
            notificationsAllowed() -> "On · level $noiseAlertLevel% · lock-screen notification ready"
            else -> "On · level $noiseAlertLevel% · enable Android notifications for lock-screen alerts"
        }
        noiseAlertDetail.setTextColor(getColor(if (enabled) R.color.success else R.color.text_secondary))
        noiseAlertExplanation.text =
            "How it works: the baby phone sends sustained-noise events over the local connection. " +
                "The alert-level control filters those events on this parent phone; lower is more sensitive. " +
                "Live monitoring uses a foreground service and wake lock so processing can continue when the screen is off. " +
                "Android notification and Do Not Disturb settings still apply. The percentage is relative, not calibrated dB."
    }

    /** A null [state] means no live stream has reported the torch yet, which is not the same as off. */
    private fun updateTorchState(state: Protocol.TorchState?) {
        handler.removeCallbacks(torchAckTimeout)
        val requested = pendingTorch
        pendingTorch = null
        torchState = state
        // The baby phone answers every request with what the camera actually holds, so a reply that
        // does not match the request means the hardware refused it rather than that nothing happened.
        val refused = state != null && requested != null && state.available && state.enabled != requested
        renderTorch(
            when {
                !refused -> null
                requested == true -> "The baby phone could not switch its light on"
                else -> "The baby phone could not switch its light off"
            }
        )
    }

    private fun renderTorch(note: String? = null) {
        val state = torchState
        val streaming = client?.isRunning == true
        // Only an explicit "no flash" from the baby phone disables the control. A state report that
        // has not arrived yet must not leave a dead button that swallows taps.
        torchButton.isEnabled = streaming && state?.available != false
        torchButton.text = when {
            state?.available == false -> "Torch unavailable"
            state?.enabled == true -> "Turn torch off"
            else -> "Turn torch on"
        }
        torchDetail.text = when {
            note != null -> note
            !streaming -> "Connect to the baby phone to use its light"
            state == null -> "Ready · the baby phone has not reported its light yet"
            !state.available -> "Unavailable · the camera in use on the baby phone has no flash"
            state.enabled -> "On · the baby phone light is lit"
            else -> "Off · the baby phone light is not lit"
        }
        torchDetail.setTextColor(
            getColor(
                when {
                    note != null -> R.color.danger
                    state?.enabled == true -> R.color.success
                    else -> R.color.text_secondary
                }
            )
        )
    }

    private fun showNoiseAlert(level: Int) {
        if (!noiseAlertsEnabled || level < noiseAlertLevel) return
        val title = if (level >= 70) "Loud noise detected" else "Noise detected"
        noiseAlertBanner.text = "$title · $level%"
        noiseAlertBanner.visibility = View.VISIBLE
        handler.removeCallbacks(hideNoiseAlert)
        handler.postDelayed(hideNoiseAlert, 4_000)

        if (notificationsAllowed()) {
            postNoiseNotification(title, level)
        } else {
            ToneGenerator(AudioManager.STREAM_ALARM, 75).also { tone ->
                tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 700)
                handler.postDelayed({ runCatching { tone.release() } }, 900)
            }
            runCatching {
                (getSystemService(Context.VIBRATOR_SERVICE) as Vibrator)
                    .vibrate(VibrationEffect.createOneShot(450, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        }
    }

    private fun createNoiseNotificationChannel() {
        val sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val channel = NotificationChannel(
            NOISE_CHANNEL_ID,
            "Noise alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Urgent alerts when sustained noise is detected by the baby phone"
            setSound(sound, audioAttributes)
            enableVibration(true)
            setVibrationPattern(longArrayOf(0, 300, 150, 500))
            setLockscreenVisibility(Notification.VISIBILITY_PUBLIC)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun requestNoiseNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < 33 || notificationsAllowed() || notificationPermissionRequested) return
        notificationPermissionRequested = true
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATION_PERMISSION)
    }

    private fun notificationsAllowed(): Boolean {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return false
        }
        return getSystemService(NotificationManager::class.java).areNotificationsEnabled()
    }

    private fun postNoiseNotification(title: String, level: Int) {
        if (!notificationsAllowed()) return
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MonitorActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = Notification.Builder(this, NOISE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_monitor)
            .setContentTitle(title)
            .setContentText("Baby Camera detected sustained noise ($level%).")
            .setCategory(Notification.CATEGORY_ALARM)
            .setPriority(Notification.PRIORITY_HIGH)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(openIntent)
            .build()
        getSystemService(NotificationManager::class.java).notify(NOISE_NOTIFICATION_ID, notification)
    }

    private fun toggleFullscreen() {
        fullscreen = !fullscreen
        liveHeader.visibility = if (fullscreen) View.GONE else View.VISIBLE
        liveControls.visibility = if (fullscreen) View.GONE else View.VISIBLE
        liveInfoCard.visibility = if (fullscreen) View.GONE else View.VISIBLE
        liveScreen.setBackgroundColor(if (fullscreen) Color.BLACK else getColor(R.color.app_bg))
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = if (fullscreen) {
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        } else {
            View.SYSTEM_UI_FLAG_VISIBLE or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
    }

    private fun updateReadiness() {
        val wifi = wifiEnabled()
        val location = locationEnabled()
        findViewById<TextView>(R.id.wifiReadiness).text = if (wifi) "Wi‑Fi On" else "Wi‑Fi Off"
        findViewById<TextView>(R.id.wifiReadinessDetail).text = if (wifi) "Ready for Wi‑Fi Direct" else "Turn Wi‑Fi on"
        findViewById<TextView>(R.id.locationReadiness).text = if (location) "Location On" else "Location Off"
        findViewById<TextView>(R.id.locationReadinessDetail).text = if (location) "Required by Android discovery" else "Enable for nearby discovery"
    }

    private fun wifiEnabled() = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).isWifiEnabled
    private fun locationEnabled(): Boolean {
        val manager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return if (Build.VERSION.SDK_INT >= 28) manager.isLocationEnabled else {
            @Suppress("DEPRECATION")
            Settings.Secure.getInt(contentResolver, Settings.Secure.LOCATION_MODE, Settings.Secure.LOCATION_MODE_OFF) != Settings.Secure.LOCATION_MODE_OFF
        }
    }

    override fun onResume() {
        super.onResume()
        updateReadiness()
        if (::noiseAlertDetail.isInitialized) updateNoiseAlertState(noiseAlertsEnabled)
        if (::torchDetail.isInitialized) renderTorch()
    }

    override fun onBackPressed() { if (fullscreen) toggleFullscreen() else super.onBackPressed() }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        client?.stop()
        client = null
        stopService(Intent(this, ParentMonitorService::class.java))
        if (::p2p.isInitialized) p2p.stop()
        super.onDestroy()
    }
}
