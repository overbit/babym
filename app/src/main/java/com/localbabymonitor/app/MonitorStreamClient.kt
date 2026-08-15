package com.localbabymonitor.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MonitorStreamClient(
    private val context: Context,
    private val host: String,
    private val onStatus: (String) -> Unit,
    private val onVideoSize: (Int, Int) -> Unit,
    private val onStreaming: () -> Unit,
    private val onNoiseState: (enabled: Boolean) -> Unit,
    private val onNoiseAlert: (levelPercent: Int) -> Unit,
    private val onTorchState: (state: Protocol.TorchState?) -> Unit
) {
    private val running = AtomicBoolean(false)
    private val decoderLock = Any()
    private var thread: Thread? = null
    private var socket: Socket? = null
    // Written on the stream thread, read from the UI thread by the torch and noise controls.
    @Volatile private var output: DataOutputStream? = null
    private val controlWriter: ExecutorService =
        Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "monitor-control-writer") }
    private var videoDecoder: VideoDecoder? = null
    private var audioDecoder: AudioDecoder? = null
    private var lastVideoConfig: Protocol.VideoConfig? = null
    @Volatile private var surface: Surface? = null
    @Volatile private var muted = false

    val isRunning: Boolean get() = running.get()

    fun start() {
        if (running.getAndSet(true)) return
        thread = Thread({ reconnectLoop() }, "monitor-stream-client").also { it.start() }
    }

    private fun reconnectLoop() {
        var connectedOnce = false
        while (running.get()) {
            try {
                onStatus(if (connectedOnce) "Reconnecting to baby camera..." else "Opening live stream...")
                socket = Socket().apply {
                    tcpNoDelay = true
                    keepAlive = true
                    connect(InetSocketAddress(host, Protocol.PORT), 5_000)
                }
                output = DataOutputStream(socket!!.getOutputStream())
                connectedOnce = true
                onStatus("Connected. Waiting for video...")
                resetDecoders(clearVideoConfig = true)
                readPackets(DataInputStream(socket!!.getInputStream()))
            } catch (_: Exception) {
                if (running.get()) onStatus("Stream interrupted. Reconnecting automatically...")
            } finally {
                output = null
                runCatching { socket?.close() }
                socket = null
                resetDecoders(clearVideoConfig = true)
                // Without a socket the torch state is unknown, not off; the baby phone resends it on reconnect.
                onTorchState(null)
            }

            if (running.get()) {
                try {
                    Thread.sleep(2_000)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
        running.set(false)
    }

    private fun readPackets(input: DataInputStream) {
        var streamingShown = false
        while (running.get()) {
            val packet = Protocol.readPacket(input)
            when (packet.type) {
                Protocol.TYPE_VIDEO_CONFIG -> {
                    val config = Protocol.unpackVideoConfig(packet.payload)
                    lastVideoConfig = config
                    onVideoSize(config.width, config.height)
                    rebuildVideoDecoder()
                    if (!streamingShown) {
                        streamingShown = true
                        onStreaming()
                    }
                }
                Protocol.TYPE_VIDEO_FRAME -> synchronized(decoderLock) {
                    videoDecoder?.decode(packet)
                }
                Protocol.TYPE_AUDIO_CONFIG -> {
                    val config = Protocol.unpackAudioConfig(packet.payload)
                    audioDecoder?.release()
                    audioDecoder = AudioDecoder(context, muted).also { it.configure(config) }
                }
                Protocol.TYPE_AUDIO_FRAME -> audioDecoder?.decode(packet)
                Protocol.TYPE_NOISE_STATE -> onNoiseState(Protocol.unpackNoiseState(packet.payload))
                Protocol.TYPE_NOISE_ALERT -> onNoiseAlert(Protocol.unpackNoiseAlert(packet.payload))
                Protocol.TYPE_TORCH_STATE -> onTorchState(Protocol.unpackTorchState(packet.payload))
            }
        }
    }

    fun attachSurface(newSurface: Surface) {
        if (!newSurface.isValid) return
        surface = newSurface
        rebuildVideoDecoder()
    }

    fun detachSurface(oldSurface: Surface? = null) {
        if (oldSurface != null && surface !== oldSurface) return
        surface = null
        synchronized(decoderLock) {
            videoDecoder?.release()
            videoDecoder = null
        }
    }

    private fun rebuildVideoDecoder() {
        val targetSurface = surface
        val config = lastVideoConfig
        synchronized(decoderLock) {
            videoDecoder?.release()
            videoDecoder = null
            if (targetSurface != null && targetSurface.isValid && config != null) {
                videoDecoder = VideoDecoder(targetSurface).also { it.configure(config) }
            }
        }
    }

    fun setMuted(value: Boolean) {
        muted = value
        audioDecoder?.setMuted(value)
    }

    /**
     * Controls are tapped on the UI thread, and Android throws NetworkOnMainThreadException for a
     * socket write there, so the write is handed to a background thread. The return value only says
     * the request was queued against a live socket; the baby phone's state reply is what confirms it.
     */
    private fun sendControl(type: Int, payload: ByteArray): Boolean {
        if (output == null) return false
        return runCatching {
            controlWriter.execute {
                val stream = output ?: return@execute
                runCatching { Protocol.writePacket(stream, type, 0, 0, payload) }
            }
            true
        }.getOrElse { false }
    }

    fun setTorch(enabled: Boolean): Boolean =
        sendControl(Protocol.TYPE_TORCH_CONTROL, Protocol.packTorchControl(enabled))

    fun setNoiseAlerts(enabled: Boolean): Boolean =
        sendControl(Protocol.TYPE_NOISE_CONTROL, Protocol.packNoiseControl(enabled))

    fun stop() {
        running.set(false)
        controlWriter.shutdownNow()
        runCatching { socket?.close() }
        thread?.interrupt()
        runCatching { thread?.join(700) }
        thread = null
        output = null
        resetDecoders(clearVideoConfig = true)
    }

    private fun resetDecoders(clearVideoConfig: Boolean) {
        synchronized(decoderLock) {
            videoDecoder?.release()
            videoDecoder = null
        }
        audioDecoder?.release()
        audioDecoder = null
        if (clearVideoConfig) lastVideoConfig = null
    }
}

private class VideoDecoder(private val surface: Surface) {
    private var codec: MediaCodec? = null
    private val info = MediaCodec.BufferInfo()

    fun configure(config: Protocol.VideoConfig) {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, config.width, config.height).apply {
            if (config.csd0.isNotEmpty()) setByteBuffer("csd-0", ByteBuffer.wrap(config.csd0))
            if (config.csd1.isNotEmpty()) setByteBuffer("csd-1", ByteBuffer.wrap(config.csd1))
        }
        codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).also {
            it.configure(format, surface, null, 0)
            it.start()
        }
    }

    fun decode(packet: Protocol.Packet) {
        val decoder = codec ?: return
        val inputIndex = decoder.dequeueInputBuffer(10_000)
        if (inputIndex >= 0) {
            decoder.getInputBuffer(inputIndex)?.apply {
                clear()
                put(packet.payload)
            }
            decoder.queueInputBuffer(
                inputIndex,
                0,
                packet.payload.size,
                packet.presentationTimeUs,
                packet.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM
            )
        }
        drain(decoder)
    }

    private fun drain(decoder: MediaCodec) {
        while (true) {
            val outputIndex = decoder.dequeueOutputBuffer(info, 0)
            if (outputIndex >= 0) {
                decoder.releaseOutputBuffer(outputIndex, true)
            } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                continue
            } else {
                break
            }
        }
    }

    fun release() {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
    }
}

private class AudioDecoder(private val context: Context, private var muted: Boolean) {
    private var codec: MediaCodec? = null
    private var audioTrack: AudioTrack? = null
    private val info = MediaCodec.BufferInfo()

    fun configure(config: Protocol.AudioConfig) {
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC,
            config.sampleRate,
            config.channelCount
        ).apply {
            if (config.csd0.isNotEmpty()) setByteBuffer("csd-0", ByteBuffer.wrap(config.csd0))
        }
        codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).also {
            it.configure(format, null, null, 0)
            it.start()
        }

        val outMask = if (config.channelCount == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val minBuffer = AudioTrack.getMinBufferSize(config.sampleRate, outMask, AudioFormat.ENCODING_PCM_16BIT)
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(config.sampleRate)
                    .setChannelMask(outMask)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuffer, config.sampleRate / 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .also {
                it.setVolume(if (muted) 0f else 1f)
                it.play()
            }

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.mode = AudioManager.MODE_NORMAL
    }

    fun setMuted(value: Boolean) {
        muted = value
        audioTrack?.setVolume(if (value) 0f else 1f)
    }

    fun decode(packet: Protocol.Packet) {
        val decoder = codec ?: return
        val inputIndex = decoder.dequeueInputBuffer(10_000)
        if (inputIndex >= 0) {
            decoder.getInputBuffer(inputIndex)?.apply {
                clear()
                put(packet.payload)
            }
            decoder.queueInputBuffer(
                inputIndex,
                0,
                packet.payload.size,
                packet.presentationTimeUs,
                packet.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM
            )
        }
        drain(decoder)
    }

    private fun drain(decoder: MediaCodec) {
        while (true) {
            val outputIndex = decoder.dequeueOutputBuffer(info, 0)
            if (outputIndex >= 0) {
                val buffer = decoder.getOutputBuffer(outputIndex)
                if (buffer != null && info.size > 0) {
                    buffer.position(info.offset)
                    buffer.limit(info.offset + info.size)
                    val pcm = ByteArray(info.size)
                    buffer.get(pcm)
                    audioTrack?.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
                }
                decoder.releaseOutputBuffer(outputIndex, false)
            } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                continue
            } else {
                break
            }
        }
    }

    fun release() {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
        runCatching { audioTrack?.stop() }
        runCatching { audioTrack?.release() }
        audioTrack = null
    }
}
