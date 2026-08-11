package com.overbit.babymonitor.stream

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.min
import kotlin.math.sqrt

private const val TAG = "AudioSink"

/**
 * Parent-unit playback. Also publishes a 0..1 loudness value taken from the same PCM that is
 * played, which drives the level meter and the noise alert.
 */
class AudioSink {
    private val running = AtomicBoolean(false)

    /** ~1 s of audio; older chunks are dropped rather than allowed to build up latency. */
    private val queue = ArrayBlockingQueue<ByteArray>(25)
    private var track: AudioTrack? = null

    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level.asStateFlow()

    @Volatile var muted: Boolean = false

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val minBuffer = AudioTrack.getMinBufferSize(
            AUDIO_SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            running.set(false)
            Log.w(TAG, "No usable audio output")
            return
        }
        val output = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(AUDIO_SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(minBuffer * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = output
        output.play()
        thread(name = "audio-playback") { playbackLoop(output) }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        queue.clear()
        track?.let {
            try {
                it.pause()
                it.flush()
                it.stop()
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Track already stopped", e)
            }
            it.release()
        }
        track = null
        _level.value = 0f
    }

    /** Hands a received PCM chunk to the playback thread. Never blocks the network reader. */
    fun enqueue(pcm: ByteArray) {
        if (!running.get()) return
        _level.value = loudnessOf(pcm)
        if (!queue.offer(pcm)) {
            queue.poll()
            queue.offer(pcm)
        }
    }

    /** Drops anything buffered and zeroes the meter, e.g. when the link goes down. */
    fun silence() {
        queue.clear()
        _level.value = 0f
    }

    private fun playbackLoop(output: AudioTrack) {
        while (running.get()) {
            val chunk = try {
                queue.poll(200, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            } ?: continue
            if (muted) continue
            try {
                output.write(chunk, 0, chunk.size)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Playback write failed", e)
                break
            }
        }
    }

    /** RMS of the chunk, scaled so ordinary room speech lands near the middle of the meter. */
    private fun loudnessOf(pcm: ByteArray): Float {
        var sumOfSquares = 0.0
        var samples = 0
        var i = 0
        while (i + 1 < pcm.size) {
            val sample = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort().toInt()
            sumOfSquares += (sample * sample).toDouble()
            samples++
            i += 2
        }
        if (samples == 0) return 0f
        val rms = sqrt(sumOfSquares / samples) / Short.MAX_VALUE
        // sqrt() opens up the quiet end of the range, where a sleeping room lives.
        return min(1.0, sqrt(rms)).toFloat()
    }
}
