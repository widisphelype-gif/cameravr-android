package com.cameravr

import android.graphics.Bitmap
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Sends JPEG camera frames with a 4-byte big-endian length prefix to the desktop receiver. */
internal class VrTransport {
    private val executor = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    @Volatile private var host = "127.0.0.1"
    @Volatile private var port = 4242
    @Volatile private var socket: Socket? = null
    @Volatile private var output: DataOutputStream? = null

    fun configure(newHost: String, newPort: Int) {
        host = newHost
        port = newPort
        disconnect()
    }

    fun sendFrame(bitmap: Bitmap) {
        if (!busy.compareAndSet(false, true)) return
        val snapshot = try { bitmap.copy(Bitmap.Config.ARGB_8888, false) } catch (_: Exception) {
            busy.set(false)
            return
        }
        executor.execute {
            try {
                val bytes = java.io.ByteArrayOutputStream().use { buffer ->
                    snapshot.compress(Bitmap.CompressFormat.JPEG, 65, buffer)
                    buffer.toByteArray()
                }
                var out = output
                if (socket?.isConnected != true || socket?.isClosed != false || out == null) {
                    disconnectSocket()
                    val s = Socket()
                    s.tcpNoDelay = true
                    s.connect(InetSocketAddress(host, port), 1800)
                    socket = s
                    out = DataOutputStream(BufferedOutputStream(s.getOutputStream()))
                    output = out
                }
                out.writeInt(bytes.size)
                out.write(bytes)
                out.flush()
            } catch (_: Exception) {
                disconnectSocket()
            } finally {
                if (!snapshot.isRecycled) snapshot.recycle()
                busy.set(false)
            }
        }
    }

    fun disconnect() = disconnectSocket()
    private fun disconnectSocket() {
        try { output?.close() } catch (_: Exception) { }
        try { socket?.close() } catch (_: Exception) { }
        output = null
        socket = null
    }
    fun close() {
        disconnectSocket()
        executor.shutdownNow()
    }
}
