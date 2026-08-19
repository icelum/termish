package dev.termish.vnc

import java.util.zip.Inflater

/** JVM：java.util.zip.Inflater 持久实例（ZRLE 流跨矩形延续）。 */
actual class VncInflater actual constructor() {
    private val inflater = Inflater()

    actual fun push(input: ByteArray, out: ByteArray, off: Int): Int {
        inflater.setInput(input)
        var total = 0
        while (!inflater.finished() && off + total < out.size) {
            val n = inflater.inflate(out, off + total, out.size - off - total)
            if (n == 0) break
            total += n
        }
        return total
    }

    actual fun end() {
        inflater.end()
    }
}
