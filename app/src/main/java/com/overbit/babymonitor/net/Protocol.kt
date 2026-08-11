package com.overbit.babymonitor.net

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/** TCP port the baby unit listens on, inside the Wi-Fi Direct group. */
const val STREAM_PORT = 8988

object PacketType {
    /** payload: [width:int][height:int][H.264 SPS+PPS] — resent to every new client. */
    const val VIDEO_CONFIG: Byte = 1

    /** payload: one H.264 access unit. */
    const val VIDEO_FRAME: Byte = 2

    /** payload: 16-bit little-endian PCM, mono, [AUDIO_SAMPLE_RATE] Hz. */
    const val AUDIO_FRAME: Byte = 3

    /** payload: a single [Control] byte, parent unit -> baby unit. */
    const val CONTROL: Byte = 4
}

object Control {
    const val TOGGLE_TORCH: Byte = 1
    const val SWITCH_CAMERA: Byte = 2
}

/**
 * One framed message. Header is type(1) + flags(4) + ptsUs(8) + length(4), then the payload.
 * [flags] carries the raw `MediaCodec.BufferInfo.flags` for video so the receiver can tell
 * key frames apart.
 */
class Packet(
    val type: Byte,
    val flags: Int,
    val ptsUs: Long,
    val payload: ByteArray,
) {
    fun writeTo(out: DataOutputStream) {
        out.writeByte(type.toInt())
        out.writeInt(flags)
        out.writeLong(ptsUs)
        out.writeInt(payload.size)
        out.write(payload)
        out.flush()
    }

    companion object {
        private const val MAX_PAYLOAD = 4 * 1024 * 1024

        fun readFrom(input: DataInputStream): Packet {
            val type = input.readByte()
            val flags = input.readInt()
            val ptsUs = input.readLong()
            val length = input.readInt()
            if (length < 0 || length > MAX_PAYLOAD) {
                throw IOException("Corrupt stream: payload length $length")
            }
            val payload = ByteArray(length)
            input.readFully(payload)
            return Packet(type, flags, ptsUs, payload)
        }

        fun control(command: Byte) = Packet(PacketType.CONTROL, 0, 0L, byteArrayOf(command))
    }
}
