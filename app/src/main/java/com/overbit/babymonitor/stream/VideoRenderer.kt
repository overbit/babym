package com.overbit.babymonitor.stream

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import com.overbit.babymonitor.net.Packet
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

private const val TAG = "VideoRenderer"

/** Geometry the parent unit needs to lay the picture out, taken from the config packet. */
data class VideoFormatInfo(val width: Int, val height: Int, val rotationDegrees: Int)

/**
 * Decodes the incoming H.264 stream straight onto a [Surface].
 *
 * The stream config is kept so the decoder can be rebuilt whenever the parent's surface goes
 * away and comes back (screen off, app backgrounded) without waiting for the baby unit.
 */
class VideoRenderer(private val onStreamError: (String) -> Unit) {

    private val thread = HandlerThread("video-renderer").apply { start() }
    private val handler = Handler(thread.looper)
    private val freeInputBuffers = LinkedBlockingQueue<Int>()

    // Touched from both the renderer thread and the network reader thread.
    @Volatile private var codec: MediaCodec? = null
    @Volatile private var awaitingKeyFrame = true
    private var surface: Surface? = null
    private var csd: ByteArray? = null

    private val _format = MutableStateFlow<VideoFormatInfo?>(null)
    val format: StateFlow<VideoFormatInfo?> = _format.asStateFlow()

    private val _rendering = MutableStateFlow(false)
    val rendering: StateFlow<Boolean> = _rendering.asStateFlow()

    fun setSurface(newSurface: Surface?) {
        handler.post {
            surface = newSurface
            if (newSurface == null) {
                releaseCodec()
            } else if (codec == null && csd != null) {
                configureCodec()
            }
        }
    }

    /** Feeds a video-config payload, laid out as width, height, rotation, then SPS+PPS. */
    fun onConfigPacket(packet: Packet) {
        handler.post {
            if (packet.payload.size <= 12) return@post
            val buffer = ByteBuffer.wrap(packet.payload)
            val info = VideoFormatInfo(buffer.int, buffer.int, buffer.int)
            val newCsd = ByteArray(buffer.remaining()).also { buffer.get(it) }
            if (_format.value == info && newCsd.contentEquals(csd) && codec != null) return@post
            _format.value = info
            csd = newCsd
            releaseCodec()
            configureCodec()
        }
    }

    /** Feeds one encoded access unit. Dropped if the decoder is not ready for it yet. */
    fun onFramePacket(packet: Packet) {
        val active = codec ?: return
        val isKeyFrame = packet.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
        if (awaitingKeyFrame) {
            if (!isKeyFrame) return
            awaitingKeyFrame = false
        }
        val index = freeInputBuffers.poll(20, TimeUnit.MILLISECONDS)
        if (index == null) {
            // Decoder is behind; resync on the next key frame instead of feeding it garbage.
            if (!isKeyFrame) awaitingKeyFrame = true
            return
        }
        try {
            val buffer = active.getInputBuffer(index) ?: return
            buffer.clear()
            buffer.put(packet.payload)
            active.queueInputBuffer(index, 0, packet.payload.size, packet.ptsUs, 0)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Decoder input dropped", e)
        }
    }

    fun release() {
        handler.post {
            releaseCodec()
            csd = null
            _format.value = null
        }
        thread.quitSafely()
    }

    private fun configureCodec() {
        val target = surface ?: return
        val info = _format.value ?: return
        val configData = csd ?: return
        try {
            val format = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                info.width,
                info.height,
            ).apply {
                setByteBuffer("csd-0", ByteBuffer.wrap(configData))
            }
            val decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            decoder.setCallback(decoderCallback, handler)
            decoder.configure(format, target, null, 0)
            decoder.start()
            codec = decoder
            awaitingKeyFrame = true
        } catch (e: Exception) {
            Log.e(TAG, "Decoder setup failed", e)
            onStreamError("Cannot play this video stream: ${e.message}")
        }
    }

    private val decoderCallback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            freeInputBuffers.offer(index)
        }

        override fun onOutputBufferAvailable(
            codec: MediaCodec,
            index: Int,
            info: MediaCodec.BufferInfo,
        ) {
            try {
                // Render as soon as it is decoded: latency matters more than perfect pacing.
                codec.releaseOutputBuffer(index, info.size > 0)
                if (info.size > 0) _rendering.value = true
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Decoder output dropped", e)
            }
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) = Unit

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            Log.e(TAG, "Decoder error", e)
            // Rebuild on the next key frame rather than tearing the whole session down.
            handler.post {
                releaseCodec()
                configureCodec()
            }
        }
    }

    private fun releaseCodec() {
        freeInputBuffers.clear()
        awaitingKeyFrame = true
        _rendering.value = false
        codec?.let {
            try {
                it.stop()
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Decoder already stopped", e)
            }
            it.release()
        }
        codec = null
    }
}
