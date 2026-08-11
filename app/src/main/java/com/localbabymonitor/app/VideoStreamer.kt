package com.localbabymonitor.app

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
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
    @Volatile private var torchAvailable = false
    @Volatile private var torchEnabled = false

    val isTorchAvailable: Boolean get() = torchAvailable
    val isTorchEnabled: Boolean get() = torchEnabled

    @SuppressLint("MissingPermission")
    fun start(writer: StreamWriter) {
        if (running.getAndSet(true)) return
        try {
            val cameraId = chooseCameraId()
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
                    createCaptureSession(camera)
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
        return cameraManager.cameraIdList.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == wanted
        } ?: cameraManager.cameraIdList.first()
    }

    private fun chooseVideoSize(cameraId: String): Size {
        val map = cameraManager.getCameraCharacteristics(cameraId)
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
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

    private fun createCaptureSession(camera: CameraDevice) {
        val surface = inputSurface ?: return
        camera.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                if (!running.get()) {
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

    private fun applyRepeatingRequest(onTorchApplied: ((Boolean, Boolean) -> Unit)? = null) {
        val camera = cameraDevice
        val session = captureSession
        val surface = inputSurface
        if (camera == null || session == null || surface == null) {
            if (onTorchApplied != null) {
                torchEnabled = false
                onTorchApplied(torchAvailable, false)
            }
            return
        }

        val requestedTorch = torchEnabled
        val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            addTarget(surface)
            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            if (torchAvailable) {
                set(
                    CaptureRequest.FLASH_MODE,
                    if (requestedTorch) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF
                )
            }
        }.build()

        if (onTorchApplied == null) {
            runCatching { session.setRepeatingRequest(request, null, cameraHandler) }
            return
        }

        var captureResults = 0
        var delivered = false
        val callback = object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult
            ) {
                if (delivered) return
                captureResults += 1
                val flashState = result.get(CaptureResult.FLASH_STATE)

                val confirmed = when {
                    !requestedTorch -> flashState != CaptureResult.FLASH_STATE_FIRED
                    flashState == CaptureResult.FLASH_STATE_FIRED -> true
                    else -> false
                }

                if (confirmed || captureResults >= 5) {
                    delivered = true
                    if (requestedTorch && !confirmed) {
                        torchEnabled = false
                        applyRepeatingRequest()
                    }
                    onTorchApplied(torchAvailable, torchEnabled && confirmed)
                }
            }
        }

        runCatching {
            session.setRepeatingRequest(request, callback, cameraHandler)
        }.onFailure {
            torchEnabled = false
            onTorchApplied(torchAvailable, false)
        }
    }

    fun setTorch(enabled: Boolean, onState: (available: Boolean, enabled: Boolean) -> Unit) {
        if (!torchAvailable || !running.get()) {
            torchEnabled = false
            onState(torchAvailable, false)
            return
        }
        val handler = cameraHandler
        if (handler == null) {
            onState(torchAvailable, torchEnabled)
            return
        }
        handler.post {
            torchEnabled = enabled
            applyRepeatingRequest(onState)
        }
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
                        val config = Protocol.packVideoConfig(selectedSize.width, selectedSize.height, csd0, csd1)
                        writer.packet(Protocol.TYPE_VIDEO_CONFIG, 0, 0, config)
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
            // stop() handles cleanup.
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
    }

    private fun java.nio.ByteBuffer.toByteArray(): ByteArray {
        val copy = duplicate()
        val bytes = ByteArray(copy.remaining())
        copy.get(bytes)
        return bytes
    }
}
