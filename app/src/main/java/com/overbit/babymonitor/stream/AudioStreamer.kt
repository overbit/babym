package com.overbit.babymonitor.stream

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.overbit.babymonitor.net.Packet
import com.overbit.babymonitor.net.PacketType
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

private const val TAG = "AudioStreamer"

/** Mono 16 kHz PCM: ~256 kbit/s, which is nothing next to the video on a direct Wi-Fi link. */
const val AUDIO_SAMPLE_RATE = 16_000

/** 40 ms of audio per packet — small enough to keep latency low, large enough to be cheap. */
private const val CHUNK_BYTES = AUDIO_SAMPLE_RATE / 25 * 2

/**
 * Baby-unit microphone capture. Sends raw PCM rather than compressed audio: at this sample
 * rate the bandwidth is irrelevant on a peer-to-peer link, and skipping the codec removes both
 * an encoder and a decoder from the path.
 */
@SuppressLint("MissingPermission") // The service checks RECORD_AUDIO before starting.
class AudioStreamer(
    private val onPacket: (Packet) -> Unit,
    private val onStreamError: (String) -> Unit,
) {
    private val running = AtomicBoolean(false)
    private var record: AudioRecord? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val minBuffer = AudioRecord.getMinBufferSize(
            AUDIO_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            running.set(false)
            onStreamError("Microphone is not available")
            return
        }
        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                AUDIO_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer, CHUNK_BYTES * 4),
            )
        } catch (e: IllegalArgumentException) {
            running.set(false)
            onStreamError("Cannot open the microphone: ${e.message}")
            return
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            running.set(false)
            onStreamError("Cannot open the microphone")
            return
        }
        record = recorder
        recorder.startRecording()
        thread(name = "audio-capture") { captureLoop(recorder) }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        record?.let {
            try {
                it.stop()
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Recorder already stopped", e)
            }
            it.release()
        }
        record = null
    }

    private fun captureLoop(recorder: AudioRecord) {
        val buffer = ByteArray(CHUNK_BYTES)
        while (running.get()) {
            val read = recorder.read(buffer, 0, buffer.size)
            if (read <= 0) {
                if (read == AudioRecord.ERROR_INVALID_OPERATION || read == AudioRecord.ERROR_BAD_VALUE) {
                    Log.w(TAG, "Microphone read failed: $read")
                    break
                }
                continue
            }
            onPacket(
                Packet(
                    PacketType.AUDIO_FRAME,
                    0,
                    System.nanoTime() / 1000,
                    if (read == buffer.size) buffer.copyOf() else buffer.copyOf(read),
                )
            )
        }
    }
}
