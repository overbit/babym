package com.localbabymonitor.app

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import android.util.Size
import android.view.Surface
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

class VideoStreamer(
    context: Context,
    private val useFrontCamera: Boolean
) {
    private val appContext = context.applicationContext
    private val cameraManager = appContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val running = AtomicBoolean(false)
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var codec: MediaCodec? = null
    private var codecThread: Thread? = null
    private var inputSurface: Surface? = null
    private var selectedSize = Size(1280, 720)
    private var selectedCameraId: String? = null
    @Volatile private var torchAvailable = false
    @Volatile private var torchEnabled = false

    val isTorchAvailable: Boolean get() = torchAvailable
    val isTorchEnabled: Boolean get() = torchEnabled

    @SuppressLint("MissingPermission")
    fun start(writer: StreamWriter) {
        if (running.getAndSet(true)) return
        try {
            val cameraId = chooseCameraId()
            selectedCameraId = cameraId
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            torchAvailable = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            torchEnabled = false
            selectedSize = chooseVideoSize(cameraId)
            setupCodec(writer)

            cameraThread = HandlerThread("baby-camera").apply { start() }
            cameraHandler = Handler(cameraThread!!.looper)
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (!running.get()) {
                        camera.close()
                        return
                    }
                    cameraDevice = camera
                    createCaptureSession()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    cameraDevice = null
                }
            }, cameraHandler)
        } catch (_: Exception) {
            stop()
        }
    }

    private fun chooseCameraId(): String {
        val wanted = if (useFrontCamera) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
        val matching = cameraManager.cameraIdList.filter { id ->
            cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == wanted
        }
        if (!useFrontCamera) {
            matching.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }?.let { return it }
        }
        return matching.firstOrNull() ?: cameraManager.cameraIdList.first()
    }

    private fun chooseVideoSize(cameraId: String): Size {
        val map = cameraManager.getCameraCharacteristics(cameraId).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = map?.getOutputSizes(MediaCodec::class.java)?.toList().orEmpty()
        if (sizes.isEmpty()) return Size(1280, 720)
        val landscape = sizes.filter { it.width >= it.height }
        val pool = landscape.ifEmpty { sizes }
        return pool.minByOrNull { size ->
            abs(size.width - 1280) + abs(size.height - 720) + if (size.width * size.height > 1920 * 1080) 10000 else 0
        } ?: Size(1280, 720)
    }

    private fun setupCodec(writer: StreamWriter) {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, selectedSize.width, selectedSize.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 1_500_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 20)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).also { encoder ->
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = encoder.createInputSurface()
            encoder.start()
        }
        codecThread = Thread({ drainEncoder(writer) }, "h264-encoder").also { it.start() }
    }

    @Suppress("DEPRECATION")
    private fun createCaptureSession() {
        val camera = cameraDevice ?: return
        val surface = inputSurface ?: return
        runCatching { captureSession?.close() }
        captureSession = null
        camera.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                if (!running.get() || cameraDevice !== camera) {
                    session.close()
                    return
                }
                captureSession = session
                applyRepeatingRequest()
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                session.close()
            }
        }, cameraHandler)
    }

    private fun applyRepeatingRequest(): Boolean {
        val camera = cameraDevice ?: return false
        val session = captureSession ?: return false
        val surface = inputSurface ?: return false
        val cameraId = selectedCameraId ?: return false
        return runCatching {
            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(surface)
                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_AE_LOCK, false)
                if (supportsContinuousVideoAf(cameraId)) {
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                }
                bestFpsRange(cameraId)?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
                if (torchAvailable) {
                    set(CaptureRequest.FLASH_MODE, if (torchEnabled) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
                }
            }.build()
            session.setRepeatingRequest(request, null, cameraHandler)
            true
        }.getOrDefault(false)
    }

    private fun supportsContinuousVideoAf(cameraId: String): Boolean {
        val modes = cameraManager.getCameraCharacteristics(cameraId).get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: return false
        return modes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
    }

    private fun bestFpsRange(cameraId: String): Range<Int>? {
        val ranges = cameraManager.getCameraCharacteristics(cameraId).get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: return null
        return ranges.filter { it.upper >= 20 }.minByOrNull { it.upper } ?: ranges.maxByOrNull { it.upper }
    }

    fun setTorch(enabled: Boolean): Boolean {
        if (!torchAvailable || !running.get()) {
            torchEnabled = false
            return false
        }
        val handler = cameraHandler ?: return false
        if (cameraDevice == null || captureSession == null) return false
        torchEnabled = enabled
        handler.post {
            val session = captureSession
            runCatching { session?.stopRepeating() }
            runCatching { session?.abortCaptures() }
            runCatching { session?.close() }
            captureSession = null
            createCaptureSession()
        }
        return true
    }

    // Keep the service callback contract while the device-compatible camera-session
    // reconfiguration remains asynchronous. The callback confirms that the command was
    // accepted for a flash-capable active camera; the rebuilt session applies the state.
    fun setTorch(enabled: Boolean, onApplied: (available: Boolean, enabled: Boolean) -> Unit) {
        val accepted = setTorch(enabled)
        onApplied(accepted, accepted && enabled)
    }

    private fun drainEncoder(writer: StreamWriter) {
        val encoder = codec ?: return
        val info = MediaCodec.BufferInfo()
        try {
            while (running.get() && !writer.failed) {
                when (val index = encoder.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outputFormat = encoder.outputFormat
                        val csd0 = outputFormat.getByteBuffer("csd-0")?.toByteArray() ?: ByteArray(0)
                        val csd1 = outputFormat.getByteBuffer("csd-1")?.toByteArray() ?: ByteArray(0)
                        writer.packet(Protocol.TYPE_VIDEO_CONFIG, 0, 0, Protocol.packVideoConfig(selectedSize.width, selectedSize.height, csd0, csd1))
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (index >= 0) {
                        val buffer = encoder.getOutputBuffer(index)
                        if (buffer != null && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            val payload = ByteArray(info.size)
                            buffer.get(payload)
                            writer.packet(Protocol.TYPE_VIDEO_FRAME, info.flags, info.presentationTimeUs, payload)
                        }
                        encoder.releaseOutputBuffer(index, false)
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        torchEnabled = false
        runCatching { captureSession?.stopRepeating() }
        runCatching { captureSession?.close() }
        captureSession = null
        runCatching { cameraDevice?.close() }
        cameraDevice = null
        runCatching { codecThread?.join(500) }
        codecThread = null
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
        runCatching { inputSurface?.release() }
        inputSurface = null
        cameraThread?.quitSafely()
        runCatching { cameraThread?.join(500) }
        cameraThread = null
        cameraHandler = null
        torchAvailable = false
        selectedCameraId = null
    }

    private fun java.nio.ByteBuffer.toByteArray(): ByteArray {
        val copy = duplicate()
        val bytes = ByteArray(copy.remaining())
        copy.get(bytes)
        return bytes
    }
}
