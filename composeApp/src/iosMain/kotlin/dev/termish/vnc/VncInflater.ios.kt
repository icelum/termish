package dev.termish.vnc

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UnsafeNumber
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.memcpy
import platform.posix.memset
import platform.zlib.Z_OK
import platform.zlib.Z_STREAM_END
import platform.zlib.inflate
import platform.zlib.inflateEnd
import platform.zlib.inflateInit_
import platform.zlib.z_stream

/**
 * iOS：z_stream 持久实例。z_stream 结构不能跨 memScoped 生存，序列化到
 * ByteArray 保存；未消费的输入字节跨 push 保留。
 */
@OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)
actual class VncInflater actual constructor() {
    private var streamBytes: ByteArray
    /** 尚未被 zlib 消费的输入字节。 */
    private var pending: ByteArray = ByteArray(0)

    init {
        memScoped {
            val s = alloc<z_stream>()
            memset(s.ptr, 0, sizeOf<z_stream>().convert())
            require(
                inflateInit_(s.ptr, platform.zlib.zlibVersion()?.toKString() ?: "1", sizeOf<z_stream>().convert()) == Z_OK,
            ) { "inflateInit 失败" }
            val raw = ByteArray(sizeOf<z_stream>().toInt())
            memcpy(raw.usePinned { it.addressOf(0) }, s.ptr, sizeOf<z_stream>().convert())
            streamBytes = raw
        }
    }

    actual fun push(input: ByteArray, out: ByteArray, off: Int): Int = memScoped {
        val s = alloc<z_stream>()
        memcpy(s.ptr, streamBytes.usePinned { it.addressOf(0) }, sizeOf<z_stream>().convert())
        val newInput = pending + input
        var total = 0
        out.usePinned { outPin ->
            newInput.usePinned { inPin ->
                s.next_in = inPin.addressOf(0).reinterpret<UByteVar>()
                s.avail_in = newInput.size.convert()
                while (total < out.size - off) {
                    s.next_out = outPin.addressOf(off + total).reinterpret<UByteVar>()
                    s.avail_out = (out.size - off - total).convert()
                    val rc = inflate(s.ptr, platform.zlib.Z_NO_FLUSH)
                    val produced = out.size - off - total - s.avail_out.toInt()
                    total += produced
                    if (rc == Z_STREAM_END) break
                    if (produced == 0) break
                    if (rc != Z_OK) error("zlib inflate 失败: $rc")
                }
            }
        }
        memcpy(streamBytes.usePinned { it.addressOf(0) }, s.ptr, sizeOf<z_stream>().convert())
        val consumed = newInput.size - s.avail_in.toInt()
        pending = if (consumed < newInput.size) newInput.copyOfRange(consumed, newInput.size) else ByteArray(0)
        total
    }

    actual fun end() {
        memScoped {
            val s = alloc<z_stream>()
            memcpy(s.ptr, streamBytes.usePinned { it.addressOf(0) }, sizeOf<z_stream>().convert())
            inflateEnd(s.ptr)
        }
    }
}
