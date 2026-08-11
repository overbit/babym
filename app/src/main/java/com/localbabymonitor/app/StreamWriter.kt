package com.localbabymonitor.app

import java.io.DataOutputStream
import java.io.IOException
import java.net.Socket

class StreamWriter(
    private val socket: Socket,
    private val output: DataOutputStream
) {
    @Volatile
    var failed: Boolean = false
        private set

    @Synchronized
    fun packet(type: Int, flags: Int, ptsUs: Long, payload: ByteArray) {
        if (failed) return
        try {
            Protocol.writePacket(output, type, flags, ptsUs, payload)
        } catch (_: IOException) {
            failed = true
            runCatching { socket.close() }
        }
    }
}
