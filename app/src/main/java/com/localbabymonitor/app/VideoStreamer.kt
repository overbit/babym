package com.localbabymonitor.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
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
    @Volatile private var minZoomRatio = 1f
    @Volatile private var maxZoomRatio = 1f
    @Volatile private var zoomRatio = 1f
    @Volatile private var useZoomRatioKey = false
    private var activeArraySize: Rect? = null

    val isTorchAvailable: Boolean get() = torchAvailable
    val isTorchEnabled: Boolean get() = torchEnabled
    val isZoomAvailable: Boolean get() = maxZoomRatio > minZoomRatio
    val minZoom: Float get() = minZoomRatio
    val maxZoom: Float get() = maxZoomRatio
    val zoom: Float get() = zoomRatio

    @SuppressLint("MissingPermission")
    fun start(writer: StreamWriter) {
        if (running.getAndSet(true)) return
        try {
            val cameraId = chooseCameraId()
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            torchAvailable = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            torchEnabled = false
            readZoomRange(characteristics)
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

    /**
     * CONTROL_ZOOM_RATIO arrived in API 30 and is the only path that can zoom out past 1x on phones
     * with an ultra-wide lens. Below that the crop region is the only option, which starts at 1x.
     */
    private fun readZoomRange(characteristics: CameraCharacteristics) {
        activeArraySize = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        var min = 1f
        var max = 1f
        var ratioKey = false
        if (Build.VERSION.SDK_INT >= 30) {
            val range = characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
            if (range != null && range.upper > range.lower) {
                min = range.lower
                max = range.upper
                ratioKey = true
            }
        }
        // Reached both below API 30 and on an upgraded phone that never reported a zoom-ratio range.
        if (!ratioKey) {
            val digital = characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)
            if (digital != null && digital > 1f && activeArraySize != null) max = digital
        }
        if (!min.isFinite() || min <= 0f) min = 1f
        if (!max.isFinite() || max < min) max = min
        minZoomRatio = min
        maxZoomRatio = max
        zoomRatio = 1f.coerceIn(min, max)
        useZoomRatioKey = ratioKey
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

    private fun applyRepeatingRequest(): Boolean {
        val camera = cameraDevice ?: return false
        val session = captureSession ?: return false
        val surface = inputSurface ?: return false
        val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            addTarget(surface)
            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            if (torchAvailable) {
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(
                    CaptureRequest.FLASH_MODE,
                    if (torchEnabled) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF
                )
            }
            applyZoom(this)
        }.build()
        return runCatching {
            session.setRepeatingRequest(request, null, cameraHandler)
            true
        }.getOrDefault(false)
    }

    /**
     * Switches the flash on the live capture request. The template stays TEMPLATE_RECORD and the
     * capture session is left intact, so the encoder never sees an interruption. The submitted
     * request is the state: FLASH_STATE is optional in Camera2 and several devices never report
     * FLASH_STATE_FIRED for torch mode, so waiting for it would report false failures. Returns
     * false only when the camera has no flash or the request was rejected outright.
     */
    fun setTorch(enabled: Boolean): Boolean {
        if (!torchAvailable || !running.get()) return false
        val previous = torchEnabled
        torchEnabled = enabled
        if (applyRepeatingRequest()) return true
        torchEnabled = previous
        return false
    }

    private fun applyZoom(request: CaptureRequest.Builder) {
        if (!isZoomAvailable) return
        if (useZoomRatioKey && Build.VERSION.SDK_INT >= 30) {
            request.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoomRatio)
            return
        }
        // Crop-region zoom is a centred crop of the sensor's active array; the camera scales it back
        // up to the stream size, so the encoder output stays exactly the same resolution.
        val active = activeArraySize ?: return
        val width = (active.width() / zoomRatio).toInt().coerceAtLeast(1)
        val height = (active.height() / zoomRatio).toInt().coerceAtLeast(1)
        val left = active.left + (active.width() - width) / 2
        val top = active.top + (active.height() - height) / 2
        request.set(CaptureRequest.SCALER_CROP_REGION, Rect(left, top, left + width, top + height))
    }

    /**
     * Zooms the live capture request, under the same rules as the torch: same TEMPLATE_RECORD
     * template, same capture session, no blocking wait. The ratio is clamped to what the camera
     * reported, so a parent asking for more than the hardware has gets the maximum instead of a
     * rejected request.
     */
    fun setZoom(ratio: Float): Boolean {
        if (!isZoomAvailable || !running.get()) return false
        if (!ratio.isFinite()) return false
        val previous = zoomRatio
        zoomRatio = ratio.coerceIn(minZoomRatio, maxZoomRatio)
        if (applyRepeatingRequest()) return true
        zoomRatio = previous
        return false
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
        // Closing the camera device below releases the flash unit, so no torch-off request is needed.
        torchEnabled = false
        zoomRatio = minZoomRatio
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
    }

    private fun java.nio.ByteBuffer.toByteArray(): ByteArray {
        val copy = duplicate()
        val bytes = ByteArray(copy.remaining())
        copy.get(bytes)
        return bytes
    }
}
