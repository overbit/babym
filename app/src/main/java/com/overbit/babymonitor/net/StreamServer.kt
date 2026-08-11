package com.overbit.babymonitor.net

import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

private const val TAG = "StreamServer"

/**
 * Baby-unit side of the link: listens on [STREAM_PORT] and streams packets to the single
 * connected parent unit. A newly arriving parent replaces the previous one, which is what
 * you want when the parent phone dropped off and reconnected.
 */
class StreamServer(
    private val onClientConnected: (Boolean) -> Unit,
    private val onControl: (Byte) -> Unit,
) {
    private class Client(val socket: Socket) {
        /** ~4 s of media at 30 packets/s before we start dropping. */
        val queue = ArrayBlockingQueue<Packet>(240)
    }

    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    @Volatile private var client: Client? = null

    /** Latest SPS/PPS packet, replayed to every parent that connects. */
    @Volatile var videoConfig: Packet? = null

    /** Called when a parent connects, so the encoder can emit an immediate key frame. */
    var onKeyFrameNeeded: (() -> Unit)? = null

    val isClientConnected: Boolean get() = client != null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        thread(name = "stream-accept") { acceptLoop() }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        serverSocket.closeQuietly()
        client?.socket.closeQuietly()
        client = null
    }

    /** Queues a packet for the connected parent. Never blocks the encoder. */
    fun send(packet: Packet) {
        val current = client ?: return
        if (!current.queue.offer(packet)) {
            // Link is slower than the encoder: shed the oldest frames rather than stall.
            current.queue.poll()
            current.queue.offer(packet)
        }
    }

    private fun acceptLoop() {
        try {
            serverSocket = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(STREAM_PORT))
            }
        } catch (e: IOException) {
            Log.e(TAG, "Cannot bind port $STREAM_PORT", e)
            running.set(false)
            return
        }
        while (running.get()) {
            val socket = try {
                serverSocket!!.accept()
            } catch (e: IOException) {
                break
            }
            socket.tcpNoDelay = true
            client?.socket.closeQuietly()
            val newClient = Client(socket)
            client = newClient
            onClientConnected(true)
            videoConfig?.let { newClient.queue.offer(it) }
            onKeyFrameNeeded?.invoke()
            thread(name = "stream-write") { writeLoop(newClient) }
            thread(name = "stream-read") { readLoop(newClient) }
        }
        serverSocket.closeQuietly()
    }

    private fun writeLoop(target: Client) {
        try {
            val out = DataOutputStream(BufferedOutputStream(target.socket.getOutputStream(), 64 * 1024))
            while (running.get() && client === target) {
                val packet = target.queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                packet.writeTo(out)
            }
        } catch (e: IOException) {
            Log.i(TAG, "Parent unit disconnected: ${e.message}")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            disconnect(target)
        }
    }

    private fun readLoop(target: Client) {
        try {
            val input = DataInputStream(BufferedInputStream(target.socket.getInputStream()))
            while (running.get() && client === target) {
                val packet = Packet.readFrom(input)
                if (packet.type == PacketType.CONTROL && packet.payload.isNotEmpty()) {
                    onControl(packet.payload[0])
                }
            }
        } catch (e: IOException) {
            // Normal on disconnect.
        } finally {
            disconnect(target)
        }
    }

    private fun disconnect(target: Client) {
        target.socket.closeQuietly()
        if (client === target) {
            client = null
            onClientConnected(false)
        }
    }
}

internal fun java.io.Closeable?.closeQuietly() {
    try {
        this?.close()
    } catch (ignored: IOException) {
    }
}
