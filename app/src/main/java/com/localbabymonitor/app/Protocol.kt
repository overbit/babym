package com.localbabymonitor.app

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException

object Protocol {
    const val PORT = 8988
    const val MAGIC = 0x424D4F4E // BMON
    // Unchanged by the torch messages: both peers ignore packet types they do not know,
    // so a 0.6.6 phone still streams against a torch-capable one.
    const val VERSION = 2
    const val TYPE_VIDEO_CONFIG = 1
    const val TYPE_VIDEO_FRAME = 2
    const val TYPE_AUDIO_CONFIG = 3
    const val TYPE_AUDIO_FRAME = 4
    const val TYPE_TORCH_CONTROL = 5
    const val TYPE_TORCH_STATE = 6
    const val TYPE_NOISE_ALERT = 7
    const val TYPE_NOISE_CONTROL = 8
    const val TYPE_NOISE_STATE = 9

    private const val MAX_PACKET_SIZE = 4 * 1024 * 1024

    data class Packet(
        val type: Int,
        val flags: Int,
        val presentationTimeUs: Long,
        val payload: ByteArray
    )

    data class VideoConfig(
        val width: Int,
        val height: Int,
        val csd0: ByteArray,
        val csd1: ByteArray
    )

    data class AudioConfig(
        val sampleRate: Int,
        val channelCount: Int,
        val csd0: ByteArray
    )

    /**
     * [available] is false when the streaming camera has no flash unit, in which case
     * [enabled] is always false and the parent cannot turn the torch on.
     */
    data class TorchState(
        val available: Boolean,
        val enabled: Boolean
    )

    @Synchronized
    fun writePacket(
        out: DataOutputStream,
        type: Int,
        flags: Int,
        presentationTimeUs: Long,
        payload: ByteArray
    ) {
        out.writeInt(MAGIC)
        out.writeByte(VERSION)
        out.writeByte(type)
        out.writeInt(flags)
        out.writeLong(presentationTimeUs)
        out.writeInt(payload.size)
        out.write(payload)
        out.flush()
    }

    fun readPacket(input: DataInputStream): Packet {
        val magic = try {
            input.readInt()
        } catch (e: EOFException) {
            throw e
        }
        require(magic == MAGIC) { "Invalid stream magic" }
        val version = input.readUnsignedByte()
        require(version == VERSION) { "Unsupported protocol version: $version" }
        val type = input.readUnsignedByte()
        val flags = input.readInt()
        val pts = input.readLong()
        val length = input.readInt()
        require(length in 0..MAX_PACKET_SIZE) { "Invalid packet length: $length" }
        val payload = ByteArray(length)
        input.readFully(payload)
        return Packet(type, flags, pts, payload)
    }

    fun packVideoConfig(width: Int, height: Int, csd0: ByteArray, csd1: ByteArray): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(width)
            out.writeInt(height)
            out.writeInt(csd0.size)
            out.write(csd0)
            out.writeInt(csd1.size)
            out.write(csd1)
        }
        return bytes.toByteArray()
    }

    fun unpackVideoConfig(payload: ByteArray): VideoConfig {
        DataInputStream(ByteArrayInputStream(payload)).use { input ->
            val width = input.readInt()
            val height = input.readInt()
            val csd0 = ByteArray(input.readInt().also { require(it in 0..65536) })
            input.readFully(csd0)
            val csd1 = ByteArray(input.readInt().also { require(it in 0..65536) })
            input.readFully(csd1)
            return VideoConfig(width, height, csd0, csd1)
        }
    }

    fun packAudioConfig(sampleRate: Int, channelCount: Int, csd0: ByteArray): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(sampleRate)
            out.writeInt(channelCount)
            out.writeInt(csd0.size)
            out.write(csd0)
        }
        return bytes.toByteArray()
    }

    fun unpackAudioConfig(payload: ByteArray): AudioConfig {
        DataInputStream(ByteArrayInputStream(payload)).use { input ->
            val sampleRate = input.readInt()
            val channelCount = input.readInt()
            val csd0 = ByteArray(input.readInt().also { require(it in 0..65536) })
            input.readFully(csd0)
            return AudioConfig(sampleRate, channelCount, csd0)
        }
    }

    fun packTorchControl(enabled: Boolean): ByteArray = byteArrayOf(if (enabled) 1 else 0)

    fun unpackTorchControl(payload: ByteArray): Boolean {
        require(payload.size == 1) { "Invalid torch control" }
        return payload[0].toInt() != 0
    }

    fun packTorchState(available: Boolean, enabled: Boolean): ByteArray =
        byteArrayOf(if (available) 1 else 0, if (enabled) 1 else 0)

    fun unpackTorchState(payload: ByteArray): TorchState {
        require(payload.size == 2) { "Invalid torch state" }
        return TorchState(payload[0].toInt() != 0, payload[1].toInt() != 0)
    }

    fun packNoiseControl(enabled: Boolean): ByteArray = byteArrayOf(if (enabled) 1 else 0)

    fun unpackNoiseControl(payload: ByteArray): Boolean {
        require(payload.size == 1) { "Invalid noise control" }
        return payload[0].toInt() != 0
    }

    fun packNoiseState(enabled: Boolean): ByteArray = byteArrayOf(if (enabled) 1 else 0)

    fun unpackNoiseState(payload: ByteArray): Boolean {
        require(payload.size == 1) { "Invalid noise state" }
        return payload[0].toInt() != 0
    }

    fun packNoiseAlert(levelPercent: Int): ByteArray {
        val bytes = ByteArrayOutputStream(4)
        DataOutputStream(bytes).use { it.writeInt(levelPercent.coerceIn(0, 100)) }
        return bytes.toByteArray()
    }

    fun unpackNoiseAlert(payload: ByteArray): Int {
        require(payload.size == 4) { "Invalid noise alert" }
        return DataInputStream(ByteArrayInputStream(payload)).use { it.readInt().coerceIn(0, 100) }
    }
}
