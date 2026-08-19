package dev.termish.vnc

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UnsafeNumber
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocPointerTo
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import platform.posix.AF_UNSPEC
import platform.posix.SOCK_STREAM
import platform.posix.addrinfo
import platform.posix.errno
import platform.posix.freeaddrinfo
import platform.posix.getaddrinfo
import platform.posix.recv
import platform.posix.send
import platform.posix.socket
import platform.posix.connect
import platform.posix.close
import platform.zlib.Z_BUF_ERROR
import platform.zlib.Z_OK
import platform.zlib.Z_STREAM_END
import platform.zlib.inflate
import platform.zlib.inflateEnd
import platform.zlib.inflateInit_
import platform.zlib.z_stream
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar

/** iOS POSIX TCP 实现（照 MoshUdpSocket 模式：getaddrinfo + fd）。 */
@OptIn(ExperimentalForeignApi::class)
actual class VncTcpSocket actual constructor(host: String, port: Int) {
    private var fd: Int = -1

    init {
        memScoped {
            val hints = alloc<addrinfo>()
            platform.posix.memset(hints.ptr, 0, sizeOf<addrinfo>().convert())
            hints.ai_family = AF_UNSPEC
            hints.ai_socktype = SOCK_STREAM
            val res = allocPointerTo<addrinfo>()
            val rc = getaddrinfo(host, port.toString(), hints.ptr, res.ptr)
            require(rc == 0) { "getaddrinfo 失败: $rc" }
            var s = -1
            var cur: CPointer<addrinfo>? = res.value
            while (cur != null && s < 0) {
                val info = cur.pointed
                val tried = socket(info.ai_family, info.ai_socktype, 0)
                if (tried >= 0 && connect(tried, info.ai_addr, info.ai_addrlen) == 0) {
                    s = tried
                } else if (tried >= 0) {
                    close(tried)
                }
                cur = info.ai_next
            }
            res.value?.let { freeaddrinfo(it) }
            require(s >= 0) { "TCP connect 失败: $errno" }
            fd = s
        }
    }

    actual fun write(data: ByteArray) {
        data.usePinned { pinned ->
            var off = 0
            while (off < data.size) {
                val n = send(fd, pinned.addressOf(off), (data.size - off).convert(), 0).toInt()
                if (n <= 0) error("TCP send 失败: $errno")
                off += n
            }
        }
    }

    actual fun read(buf: ByteArray, off: Int, len: Int): Int {
        buf.usePinned { pinned ->
            val n = recv(fd, pinned.addressOf(off), len.convert(), 0).toInt()
            return n
        }
    }

    actual fun close() {
        if (fd >= 0) {
            close(fd)
            fd = -1
        }
    }
}
