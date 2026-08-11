package com.overbit.babymonitor.stream

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.StreamConfigurationMap
import android.hardware.display.DisplayManager
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Display
import android.view.Surface
import android.view.SurfaceHolder
import com.overbit.babymonitor.net.Packet
import com.overbit.babymonitor.net.PacketType
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

private const val TAG = "CameraStreamer"
private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
private const val TARGET_WIDTH = 1280
private const val TARGET_HEIGHT = 720
private const val FRAME_RATE = 20
private const val BIT_RATE = 2_000_000
private const val I_FRAME_INTERVAL_SEC = 2

/**
 * Camera2 -> hardware H.264 encoder. The encoder's input surface is a capture target, so
 * frames never travel through Java heap: the camera writes straight into the encoder.
 *
 * The optional preview surface is a second capture target that is attached and detached as
 * the baby-unit screen comes and goes; the encoder target stays alive so the stream keeps
 * running with the screen off.
 */
@SuppressLint("MissingPermission") // The service checks CAMERA before starting.
class CameraStreamer(
    private val context: Context,
    private val onPacket: (Packet) -> Unit,
    private val onStreamError: (String) -> Unit,
) {
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val thread = HandlerThread("camera-streamer").apply { start() }
    private val handler = Handler(thread.looper)

    private var encoder: MediaCodec? = null
    private var encoderSurface: Surface? = null
    private var cameraDevice: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var sessionTargets: List<Surface> = emptyList()
    private var previewSurface: Surface? = null

    private var backCameraId: String? = null
    private var frontCameraId: String? = null
    private var cameraId: String? = null

    @Volatile private var running = false
    @Volatile private var torchOn = false
    @Volatile private var videoConfig: Packet? = null
    private var lastCsd: ByteArray? = null

    /** Encoded frame size, also used as the preview size. Null until [start] has run. */
    @Volatile var videoSize: Size? = null
        private set

    /** How far the picture has to be rotated to be upright. See [displayRotationDegrees]. */
    @Volatile var rotationDegrees: Int = 0
        private set

    /** Emitted once the SPS/PPS are known, so the server can replay it to new clients. */
    var onVideoConfig: ((Packet) -> Unit)? = null

    /** Reports torch / lens changes once they have actually been applied. */
    var onStatusChanged: ((torchOn: Boolean, usingFrontCamera: Boolean) -> Unit)? = null

    val isTorchOn: Boolean get() = torchOn
    val isUsingFrontCamera: Boolean get() = cameraId != null && cameraId == frontCameraId

    fun start() {
        handler.post {
            if (running) return@post
            try {
                resolveCameraIds()
                val id = backCameraId ?: frontCameraId
                if (id == null) {
                    onStreamError("No camera on this device")
                    return@post
                }
                cameraId = id
                videoSize = chooseSize()
                running = true
                startEncoder()
                openCamera(id)
                notifyStatus()
            } catch (e: Exception) {
                Log.e(TAG, "start failed", e)
                onStreamError("Camera start failed: ${e.message}")
                releaseInternal()
            }
        }
    }

    fun stop() {
        handler.post { releaseInternal() }
    }

    /** Releases the background thread; the streamer cannot be restarted afterwards. */
    fun release() {
        handler.post { releaseInternal() }
        thread.quitSafely()
    }

    /** Attaches or detaches the on-device preview. Pass null when the surface is destroyed. */
    fun setPreviewSurface(surface: Surface?) {
        handler.post {
            if (previewSurface === surface) return@post
            previewSurface = surface
            if (running && cameraDevice != null) createSession()
        }
    }

    fun toggleTorch() {
        handler.post {
            val id = cameraId ?: return@post
            val hasFlash = cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
            if (!hasFlash) {
                onStreamError("This camera has no light")
                return@post
            }
            torchOn = !torchOn
            applyRepeatingRequest()
            notifyStatus()
        }
    }

    fun switchCamera() {
        handler.post {
            val other = if (cameraId == backCameraId) frontCameraId else backCameraId
            if (other == null) {
                onStreamError("Only one camera on this device")
                return@post
            }
            cameraId = other
            torchOn = false
            notifyStatus()
            // The new lens has its own sensor orientation, so the parent needs a fresh
            // rotation hint; the encoder itself keeps running across the switch.
            lastCsd?.let { publishVideoConfig(it) }
            requestKeyFrame()
            session?.close()
            session = null
            cameraDevice?.close()
            cameraDevice = null
            openCamera(other)
        }
    }

    /** Forces an immediate key frame, e.g. right after a parent unit connects. */
    fun requestKeyFrame() {
        handler.post {
            try {
                encoder?.setParameters(
                    Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }
                )
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Cannot request key frame", e)
            }
        }
    }

    private fun notifyStatus() {
        onStatusChanged?.invoke(torchOn, isUsingFrontCamera)
    }

    private fun resolveCameraIds() {
        for (id in cameraManager.cameraIdList) {
            when (cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)) {
                CameraCharacteristics.LENS_FACING_BACK -> if (backCameraId == null) backCameraId = id
                CameraCharacteristics.LENS_FACING_FRONT -> if (frontCameraId == null) frontCameraId = id
            }
        }
    }

    /**
     * Picks a capture size supported for both the encoder and an on-screen preview, and — when
     * the device has two cameras — supported by both of them, so switching cameras never has to
     * rebuild the encoder.
     */
    private fun chooseSize(): Size {
        val perCamera = listOfNotNull(backCameraId, frontCameraId).mapNotNull { id ->
            val map = cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return@mapNotNull null
            supportedSizes(map)
        }
        val common = perCamera.reduceOrNull { acc, sizes -> acc.intersect(sizes) }
            ?.takeIf { it.isNotEmpty() }
            ?: perCamera.firstOrNull()
            ?: return Size(640, 480)
        val targetArea = TARGET_WIDTH * TARGET_HEIGHT
        return common.minByOrNull { abs(it.width * it.height - targetArea) } ?: Size(640, 480)
    }

    private fun supportedSizes(map: StreamConfigurationMap): Set<Size> {
        val encoderSizes = map.getOutputSizes(MediaCodec::class.java)?.toSet() ?: emptySet()
        val previewSizes = map.getOutputSizes(SurfaceHolder::class.java)?.toSet() ?: emptySet()
        return if (previewSizes.isEmpty()) encoderSizes else encoderSizes.intersect(previewSizes)
    }

    private fun startEncoder() {
        val size = videoSize ?: return
        val format = MediaFormat.createVideoFormat(MIME, size.width, size.height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
            )
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_SEC)
        }
        val codec = MediaCodec.createEncoderByType(MIME)
        codec.setCallback(encoderCallback, handler)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoderSurface = codec.createInputSurface()
        codec.start()
        encoder = codec
    }

    private val encoderCallback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            // Unused: this encoder is fed by a surface.
        }

        override fun onOutputBufferAvailable(
            codec: MediaCodec,
            index: Int,
            info: MediaCodec.BufferInfo,
        ) {
            try {
                val buffer = codec.getOutputBuffer(index)
                if (buffer != null && info.size > 0) {
                    buffer.position(info.offset)
                    buffer.limit(info.offset + info.size)
                    val bytes = ByteArray(info.size)
                    buffer.get(bytes)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        publishVideoConfig(bytes)
                    } else {
                        onPacket(
                            Packet(
                                PacketType.VIDEO_FRAME,
                                info.flags,
                                info.presentationTimeUs,
                                bytes,
                            )
                        )
                    }
                }
                codec.releaseOutputBuffer(index, false)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Encoder output dropped", e)
            }
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            // Some encoders deliver SPS/PPS here instead of as a codec-config buffer.
            if (videoConfig != null) return
            val csd = ByteArrayOutputStream()
            listOf("csd-0", "csd-1").forEach { key ->
                val bb: ByteBuffer? = if (format.containsKey(key)) format.getByteBuffer(key) else null
                if (bb != null) {
                    val bytes = ByteArray(bb.remaining())
                    bb.duplicate().get(bytes)
                    csd.write(bytes)
                }
            }
            if (csd.size() > 0) publishVideoConfig(csd.toByteArray())
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            Log.e(TAG, "Encoder error", e)
            this@CameraStreamer.onStreamError("Encoder error: ${e.message}")
        }
    }

    private fun publishVideoConfig(csd: ByteArray) {
        val size = videoSize ?: return
        lastCsd = csd
        rotationDegrees = displayRotationDegrees()
        val payload = ByteBuffer.allocate(12 + csd.size).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(size.width)
            putInt(size.height)
            putInt(rotationDegrees)
            put(csd)
        }.array()
        val packet = Packet(PacketType.VIDEO_CONFIG, 0, 0L, payload)
        videoConfig = packet
        onVideoConfig?.invoke(packet)
        onPacket(packet)
    }

    /**
     * How far the parent unit must rotate the picture to show it upright, given how the baby
     * phone is currently held. Computed once per stream: the baby unit is meant to be put down
     * and left alone.
     */
    private fun displayRotationDegrees(): Int {
        val id = cameraId ?: return 0
        val sensor = cameraManager.getCameraCharacteristics(id)
            .get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val display = (context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
            .getDisplay(Display.DEFAULT_DISPLAY)
        val deviceDegrees = when (display?.rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        return if (id == frontCameraId) {
            (sensor + deviceDegrees) % 360
        } else {
            (sensor - deviceDegrees + 360) % 360
        }
    }

    private fun openCamera(id: String) {
        cameraManager.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                if (!running) {
                    camera.close()
                    return
                }
                cameraDevice = camera
                createSession()
            }

            override fun onDisconnected(camera: CameraDevice) {
                camera.close()
                if (cameraDevice === camera) cameraDevice = null
            }

            override fun onError(camera: CameraDevice, error: Int) {
                camera.close()
                if (cameraDevice === camera) cameraDevice = null
                onStreamError("Camera error $error")
            }
        }, handler)
    }

    @Suppress("DEPRECATION") // The SessionConfiguration API is API 28+; minSdk here is 26.
    private fun createSession() {
        val camera = cameraDevice ?: return
        val targets = listOfNotNull(encoderSurface, previewSurface)
        if (targets.isEmpty()) return
        session?.close()
        session = null
        sessionTargets = targets
        camera.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(configured: CameraCaptureSession) {
                if (!running) {
                    configured.close()
                    return
                }
                session = configured
                applyRepeatingRequest()
            }

            override fun onConfigureFailed(configured: CameraCaptureSession) {
                onStreamError("Could not configure the camera session")
            }
        }, handler)
    }

    private fun applyRepeatingRequest() {
        val camera = cameraDevice ?: return
        val active = session ?: return
        val id = cameraId ?: return
        try {
            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                // Must match the surfaces the session was configured with, not the current
                // preview surface, which may have changed while the session was configuring.
                sessionTargets.forEach { addTarget(it) }
                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                bestFpsRange(id)?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
                set(
                    CaptureRequest.FLASH_MODE,
                    if (torchOn) CameraMetadata.FLASH_MODE_TORCH else CameraMetadata.FLASH_MODE_OFF,
                )
            }
            active.setRepeatingRequest(request.build(), null, handler)
        } catch (e: Exception) {
            Log.e(TAG, "Repeating request failed", e)
            onStreamError("Capture failed: ${e.message}")
        }
    }

    private fun bestFpsRange(id: String): Range<Int>? {
        val ranges = cameraManager.getCameraCharacteristics(id)
            .get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: return null
        return ranges.filter { it.upper >= FRAME_RATE }.minByOrNull { it.upper }
            ?: ranges.maxByOrNull { it.upper }
    }

    private fun releaseInternal() {
        running = false
        torchOn = false
        session?.close()
        session = null
        cameraDevice?.close()
        cameraDevice = null
        encoder?.let {
            try {
                it.stop()
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Encoder already stopped", e)
            }
            it.release()
        }
        encoder = null
        encoderSurface?.release()
        encoderSurface = null
        videoConfig = null
        lastCsd = null
        sessionTargets = emptyList()
    }
}
