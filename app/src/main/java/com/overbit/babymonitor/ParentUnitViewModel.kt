package com.overbit.babymonitor

import android.app.Application
import android.os.Build
import android.os.CombinedVibration
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.Surface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.overbit.babymonitor.net.Control
import com.overbit.babymonitor.net.Packet
import com.overbit.babymonitor.net.PacketType
import com.overbit.babymonitor.net.StreamClient
import com.overbit.babymonitor.stream.AudioSink
import com.overbit.babymonitor.stream.VideoRenderer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.net.InetAddress

/** Noise must stay above the threshold this long before it counts as the baby waking up. */
private const val ALERT_SUSTAIN_MS = 1200L

/** How long to wait after an alert before raising another one. */
private const val ALERT_COOLDOWN_MS = 30_000L

/** How long the on-screen alert banner stays up. */
private const val ALERT_BANNER_MS = 8_000L

/**
 * Owns the parent unit's receive pipeline so it survives screen rotation and the brief
 * detachment of the video surface when the screen is dimmed.
 */
class ParentUnitViewModel(application: Application) : AndroidViewModel(application) {

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val renderer = VideoRenderer { message -> _error.value = message }
    private val audio = AudioSink()
    private var client: StreamClient? = null

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _noiseAlert = MutableStateFlow(false)
    val noiseAlert: StateFlow<Boolean> = _noiseAlert.asStateFlow()

    /** 0..1; the meter and the alert both read this. */
    val level: StateFlow<Float> = audio.level
    val videoFormat = renderer.format
    val rendering = renderer.rendering

    private val _alertThreshold = MutableStateFlow(0.5f)
    val alertThreshold: StateFlow<Float> = _alertThreshold.asStateFlow()

    private val _muted = MutableStateFlow(false)
    val muted: StateFlow<Boolean> = _muted.asStateFlow()

    init {
        viewModelScope.launch { watchForNoise() }
    }

    fun connect(host: InetAddress) {
        if (client != null) return
        audio.start()
        val streamClient = StreamClient(
            host = host,
            onPacket = ::onPacket,
            onConnected = { connected ->
                _connected.value = connected
                if (!connected) audio.silence()
            },
        )
        client = streamClient
        streamClient.start()
    }

    fun disconnect() {
        client?.stop()
        client = null
        audio.stop()
        renderer.setSurface(null)
        _connected.value = false
    }

    fun setSurface(surface: Surface?) = renderer.setSurface(surface)

    fun toggleTorch() = client?.sendControl(Control.TOGGLE_TORCH)

    fun switchCamera() = client?.sendControl(Control.SWITCH_CAMERA)

    fun setAlertThreshold(value: Float) {
        _alertThreshold.value = value.coerceIn(0f, 1f)
    }

    fun toggleMute() {
        val next = !_muted.value
        _muted.value = next
        audio.muted = next
    }

    fun dismissAlert() {
        _noiseAlert.value = false
    }

    fun clearError() {
        _error.value = null
    }

    override fun onCleared() {
        disconnect()
        renderer.release()
        super.onCleared()
    }

    private fun onPacket(packet: Packet) {
        when (packet.type) {
            PacketType.VIDEO_CONFIG -> renderer.onConfigPacket(packet)
            PacketType.VIDEO_FRAME -> renderer.onFramePacket(packet)
            PacketType.AUDIO_FRAME -> audio.enqueue(packet.payload)
        }
    }

    private suspend fun watchForNoise() {
        var loudSince = 0L
        var lastAlertAt = 0L
        audio.level.collect { loudness ->
            val now = SystemClock.elapsedRealtime()
            if (_noiseAlert.value && now - lastAlertAt > ALERT_BANNER_MS) {
                _noiseAlert.value = false
            }
            if (loudness < _alertThreshold.value) {
                loudSince = 0L
                return@collect
            }
            if (loudSince == 0L) loudSince = now
            if (now - loudSince >= ALERT_SUSTAIN_MS && now - lastAlertAt >= ALERT_COOLDOWN_MS) {
                lastAlertAt = now
                _noiseAlert.value = true
                vibrate()
            }
        }
    }

    private fun vibrate() {
        val context = getApplication<Application>()
        val effect = VibrationEffect.createWaveform(longArrayOf(0, 400, 200, 400), -1)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(VibratorManager::class.java) ?: return
            manager.vibrate(CombinedVibration.createParallel(effect))
        } else {
            @Suppress("DEPRECATION")
            val vibrator = context.getSystemService(Vibrator::class.java) ?: return
            vibrator.vibrate(effect)
        }
    }
}
