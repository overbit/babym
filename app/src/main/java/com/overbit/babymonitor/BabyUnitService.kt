package com.overbit.babymonitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.util.Size
import android.view.Surface
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.overbit.babymonitor.net.Control
import com.overbit.babymonitor.net.StreamServer
import com.overbit.babymonitor.stream.AudioStreamer
import com.overbit.babymonitor.stream.CameraStreamer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Everything the baby-unit UI needs to render its status. */
data class BabyUnitState(
    val streaming: Boolean = false,
    val parentConnected: Boolean = false,
    val torchOn: Boolean = false,
    val usingFrontCamera: Boolean = false,
    val videoSize: Size? = null,
    val rotationDegrees: Int = 0,
    val error: String? = null,
)

/**
 * Keeps the camera, microphone and server socket alive while the baby unit's screen is off.
 *
 * A foreground service is what makes overnight use work at all: without it Android suspends
 * the process shortly after the screen goes off and the stream dies.
 */
class BabyUnitService : Service() {

    companion object {
        const val ACTION_START = "com.overbit.babymonitor.START"
        const val ACTION_STOP = "com.overbit.babymonitor.STOP"
        private const val CHANNEL_ID = "baby_unit"
        private const val NOTIFICATION_ID = 42

        private val _state = MutableStateFlow(BabyUnitState())

        /** Observable from the UI whether or not it is currently bound. */
        val state: StateFlow<BabyUnitState> = _state.asStateFlow()

        fun start(context: Context) {
            val intent = Intent(context, BabyUnitService::class.java).setAction(ACTION_START)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, BabyUnitService::class.java).setAction(ACTION_STOP))
        }

        /**
         * The running instance, if any. Held statically rather than through a binder: the UI
         * only ever needs three fire-and-forget calls, and it is cleared in [onDestroy].
         */
        @Volatile
        private var instance: BabyUnitService? = null

        /** Attaches the on-device preview; pass null when the baby-unit screen goes away. */
        fun attachPreview(surface: Surface?) {
            instance?.camera?.setPreviewSurface(surface)
        }

        fun toggleTorch() {
            instance?.camera?.toggleTorch()
        }

        fun switchCamera() {
            instance?.camera?.switchCamera()
        }
    }

    private var server: StreamServer? = null
    private var camera: CameraStreamer? = null
    private var audio: AudioStreamer? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopStreaming()
            stopSelf()
            return START_NOT_STICKY
        }
        createNotificationChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )
        startStreaming()
        // Do not resurrect on its own: the parent has no way to know a restarted stream is live.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopStreaming()
        instance = null
        super.onDestroy()
    }

    private fun startStreaming() {
        if (server != null) return
        _state.value = BabyUnitState(streaming = true)

        val streamServer = StreamServer(
            onClientConnected = { connected ->
                _state.value = _state.value.copy(parentConnected = connected)
                updateNotification()
            },
            onControl = ::handleControl,
        )
        val cameraStreamer = CameraStreamer(
            context = this,
            onPacket = { streamServer.send(it) },
            onStreamError = { message -> _state.value = _state.value.copy(error = message) },
        )
        cameraStreamer.onVideoConfig = { packet ->
            streamServer.videoConfig = packet
            _state.value = _state.value.copy(
                videoSize = cameraStreamer.videoSize,
                rotationDegrees = cameraStreamer.rotationDegrees,
            )
        }
        cameraStreamer.onStatusChanged = { torchOn, front ->
            _state.value = _state.value.copy(torchOn = torchOn, usingFrontCamera = front)
        }
        streamServer.onKeyFrameNeeded = { cameraStreamer.requestKeyFrame() }

        val audioStreamer = AudioStreamer(
            onPacket = { streamServer.send(it) },
            onStreamError = { message -> _state.value = _state.value.copy(error = message) },
        )

        server = streamServer
        camera = cameraStreamer
        audio = audioStreamer

        acquireWakeLock()
        streamServer.start()
        cameraStreamer.start()
        audioStreamer.start()
    }

    private fun stopStreaming() {
        audio?.stop()
        camera?.release()
        server?.stop()
        audio = null
        camera = null
        server = null
        releaseWakeLock()
        _state.value = BabyUnitState()
    }

    private fun handleControl(command: Byte) {
        when (command) {
            Control.TOGGLE_TORCH -> camera?.toggleTorch()
            Control.SWITCH_CAMERA -> camera?.switchCamera()
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock != null) return
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BabyMonitor:stream").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.baby_unit_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            setShowBadge(false)
        }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, BabyUnitService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val text = if (_state.value.parentConnected) {
            getString(R.string.baby_unit_notification_connected)
        } else {
            getString(R.string.baby_unit_notification_waiting)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.baby_unit_notification_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_monitor)
            .setContentIntent(open)
            .addAction(0, getString(R.string.stop), stop)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification() {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, buildNotification())
    }
}
