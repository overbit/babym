package com.localbabymonitor.app

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

class AudioStreamer(
    private val onNoiseAlert: (Int) -> Unit = {}
) {
    companion object {
        private const val NOISE_RMS_THRESHOLD = 0.08
        private const val NOISE_SUSTAINED_MS = 750L
        private const val NOISE_COOLDOWN_MS = 12_000L
    }

    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    private var audioRecord: AudioRecord? = null
    private var codec: MediaCodec? = null
    private var loudDurationMs = 0L
    private var lastNoiseAlertAtMs = 0L
    @Volatile private var noiseAlertsEnabled = true

    fun start(writer: StreamWriter) {
        if (running.getAndSet(true)) return
        thread = Thread({ encodeLoop(writer) }, "aac-encoder").also { it.start() }
    }

    private fun encodeLoop(writer: StreamWriter) {
        val sampleRate = 48_000
        val channelCount = 1
        val channelMask = AudioFormat.CHANNEL_IN_MONO
        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) return

        try {
            audioRecord = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelMask)
                        .build()
                )
                .setBufferSizeInBytes(minBuffer * 2)
                .build()

            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channelCount).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 64_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, minBuffer * 2)
            }
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).also {
                it.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                it.start()
            }

            val encoder = codec ?: return
            val recorder = audioRecord ?: return
            val info = MediaCodec.BufferInfo()
            val startedNs = System.nanoTime()
            val pcm = ShortArray((minBuffer / 2).coerceAtLeast(1024))
            recorder.startRecording()

            while (running.get() && !writer.failed) {
                val inputIndex = encoder.dequeueInputBuffer(10_000)
                if (inputIndex >= 0) {
                    val inputBuffer = encoder.getInputBuffer(inputIndex)
                    if (inputBuffer != null) {
                        inputBuffer.clear()
                        val maxSamples = minOf(pcm.size, inputBuffer.capacity() / 2)
                        val readSamples = recorder.read(pcm, 0, maxSamples, AudioRecord.READ_BLOCKING)
                        if (readSamples > 0) {
                            detectNoise(pcm, readSamples, sampleRate)
                            inputBuffer.asShortBuffer().put(pcm, 0, readSamples)
                            val ptsUs = (System.nanoTime() - startedNs) / 1000L
                            encoder.queueInputBuffer(inputIndex, 0, readSamples * 2, ptsUs, 0)
                        } else {
                            encoder.queueInputBuffer(inputIndex, 0, 0, 0, 0)
                        }
                    }
                }

                drainEncoder(encoder, info, writer, sampleRate, channelCount)
            }
        } catch (_: Exception) {
            // Device-specific encoder/recording failures end only the audio stream.
        } finally {
            runCatching { audioRecord?.stop() }
            runCatching { audioRecord?.release() }
            audioRecord = null
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            codec = null
        }
    }

    private fun detectNoise(samples: ShortArray, count: Int, sampleRate: Int) {
        if (count <= 0) return
        if (!noiseAlertsEnabled) {
            loudDurationMs = 0L
            return
        }
        var sumSquares = 0.0
        for (i in 0 until count) {
            val normalized = samples[i] / 32768.0
            sumSquares += normalized * normalized
        }
        val rms = sqrt(sumSquares / count)
        val durationMs = (count * 1000L) / sampleRate
        if (rms >= NOISE_RMS_THRESHOLD) {
            loudDurationMs += durationMs
        } else {
            loudDurationMs = 0L
        }

        val now = android.os.SystemClock.elapsedRealtime()
        if (loudDurationMs >= NOISE_SUSTAINED_MS && now - lastNoiseAlertAtMs >= NOISE_COOLDOWN_MS) {
            lastNoiseAlertAtMs = now
            loudDurationMs = 0L
            val levelPercent = (rms * 100.0 / 0.5).toInt().coerceIn(1, 100)
            onNoiseAlert(levelPercent)
        }
    }

    private fun drainEncoder(
        encoder: MediaCodec,
        info: MediaCodec.BufferInfo,
        writer: StreamWriter,
        sampleRate: Int,
        channelCount: Int
    ) {
        while (true) {
            when (val index = encoder.dequeueOutputBuffer(info, 0)) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val csd0 = encoder.outputFormat.getByteBuffer("csd-0")?.toByteArray() ?: ByteArray(0)
                    writer.packet(
                        Protocol.TYPE_AUDIO_CONFIG,
                        0,
                        0,
                        Protocol.packAudioConfig(sampleRate, channelCount, csd0)
                    )
                }
                MediaCodec.INFO_TRY_AGAIN_LATER -> return
                else -> if (index >= 0) {
                    val buffer = encoder.getOutputBuffer(index)
                    if (buffer != null && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        val payload = ByteArray(info.size)
                        buffer.get(payload)
                        writer.packet(Protocol.TYPE_AUDIO_FRAME, info.flags, info.presentationTimeUs, payload)
                    }
                    encoder.releaseOutputBuffer(index, false)
                } else return
            }
        }
    }

    fun setNoiseAlertsEnabled(enabled: Boolean) {
        noiseAlertsEnabled = enabled
        if (!enabled) loudDurationMs = 0L
    }

    fun stop() {
        running.set(false)
        runCatching { audioRecord?.stop() }
        runCatching { thread?.join(700) }
        thread = null
    }

    private fun java.nio.ByteBuffer.toByteArray(): ByteArray {
        val copy = duplicate()
        val bytes = ByteArray(copy.remaining())
        copy.get(bytes)
        return bytes
    }
}
