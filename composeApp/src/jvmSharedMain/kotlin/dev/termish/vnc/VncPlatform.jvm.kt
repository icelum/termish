package dev.termish.vnc

import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.zip.Inflater

/** JVM（Android + 桌面）TCP 实现。 */
actual class VncTcpSocket actual constructor(host: String, port: Int) {
    private val socket: Socket
    private val input: InputStream

    init {
        socket = Socket()
        socket.tcpNoDelay = true
        socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        input = socket.getInputStream()
    }

    actual fun write(data: ByteArray) {
        socket.getOutputStream().apply {
            write(data)
            flush()
        }
    }

    actual fun read(buf: ByteArray, off: Int, len: Int): Int = input.read(buf, off, len)

    actual fun close() {
        try {
            socket.close()
        } catch (_: Exception) {
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000
    }
}

