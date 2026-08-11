package com.overbit.babymonitor.net

import android.util.Log
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

private const val TAG = "StreamClient"
private const val RETRY_DELAY_MS = 1500L
private const val CONNECT_TIMEOUT_MS = 5000

/**
 * Parent-unit side of the link. Keeps trying to reach the baby unit's [StreamServer] and
 * hands every received packet to [onPacket] on a background thread.
 */
class StreamClient(
    private val host: InetAddress,
    private val onPacket: (Packet) -> Unit,
    private val onConnected: (Boolean) -> Unit,
) {
    private val running = AtomicBoolean(false)
    private val controlQueue = ArrayBlockingQueue<Packet>(16)
    @Volatile private var socket: Socket? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        thread(name = "client-connect") { connectLoop() }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        socket.closeQuietly()
        socket = null
        controlQueue.clear()
    }

    /** Queues a [Control] command for the baby unit; safe to call from the main thread. */
    fun sendControl(command: Byte) {
        controlQueue.offer(Packet.control(command))
    }

    private fun connectLoop() {
        while (running.get()) {
            val attempt = try {
                Socket().apply {
                    tcpNoDelay = true
                    connect(InetSocketAddress(host, STREAM_PORT), CONNECT_TIMEOUT_MS)
                }
            } catch (e: IOException) {
                Log.d(TAG, "Connect to $host failed: ${e.message}")
                sleepQuietly(RETRY_DELAY_MS)
                continue
            }
            socket = attempt
            onConnected(true)
            val writer = thread(name = "client-write") { writeLoop(attempt) }
            try {
                readLoop(attempt)
            } finally {
                attempt.closeQuietly()
                writer.join()
                if (socket === attempt) socket = null
                onConnected(false)
            }
            if (running.get()) sleepQuietly(RETRY_DELAY_MS)
        }
    }

    private fun readLoop(active: Socket) {
        try {
            val input = DataInputStream(BufferedInputStream(active.getInputStream(), 64 * 1024))
            while (running.get() && socket === active) {
                onPacket(Packet.readFrom(input))
            }
        } catch (e: IOException) {
            Log.i(TAG, "Stream ended: ${e.message}")
        }
    }

    private fun writeLoop(active: Socket) {
        try {
            val out = DataOutputStream(active.getOutputStream())
            while (running.get() && socket === active) {
                val packet = controlQueue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                packet.writeTo(out)
            }
        } catch (e: IOException) {
            // Read loop reports the disconnect.
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun sleepQuietly(millis: Long) {
        try {
            Thread.sleep(millis)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
