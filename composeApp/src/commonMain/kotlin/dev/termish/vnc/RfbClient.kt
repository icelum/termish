package dev.termish.vnc

import dev.termish.util.TermLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** VNC 认证失败（密码错误/无权限）。 */
class VncAuthException(message: String) : Exception(message)

/** VNC 会话状态。 */
enum class VncStatus { CONNECTING, CONNECTED, RECONNECTING, ERROR, CLOSED }

/** 一帧远程桌面（版本号驱动 UI 重绘）。 */
class VncFrame(
    val width: Int,
    val height: Int,
    /** ARGB（0xAARRGGBB）像素，长度 = width * height。 */
    val pixels: IntArray,
    /** 帧版本：每应用一批矩形 +1，UI 据此感知变化。 */
    val version: Long,
)

/** 远端剪贴板文本（ServerCutText）。 */
data class VncClipboard(val text: String)

/**
 * RFB（RFC 6143）客户端：握手 → VNC-Auth → ClientInit → 帧循环。
 *
 * 设计要点：
 * - 请求 32bpp/RGB888 客户端像素格式，解码端无需处理服务器原生格式色带
 * - 编码协商：ZRLE > Hextile > Raw + CopyRect + RRE；Tight 不启用
 *   （ZRLE 已覆盖主流服务器的高效路径，Tight 的 JPEG 分支复杂度高收益低）
 * - DesktopSize 伪编码：远端分辨率变化时自动重建帧缓冲并全量刷新
 * - 输入事件经 Channel 串行化到写协程，读循环独占 socket 读端
 */
class RfbClient(
    private val host: String,
    private val port: Int,
    private val password: String?,
    /** 只读模式：不发任何输入事件（演示/监控场景）。 */
    private val viewOnly: Boolean = false,
    private val scope: CoroutineScope,
) {
    var status: VncStatus = VncStatus.CONNECTING
        private set

    var errorMessage: String? = null
        private set

    private val _frame = MutableStateFlow<VncFrame?>(null)
    val frame: StateFlow<VncFrame?> = _frame

    private val _clipboard = MutableStateFlow<VncClipboard?>(null)
    val clipboard: StateFlow<VncClipboard?> = _clipboard

    private var socket: VncTcpSocket? = null
    private var readJob: Job? = null
    private val outbox = Channel<ByteArray>(Channel.UNLIMITED)
    private var frameVersion = 0L

    /** 连接（IO 线程执行握手后启动帧循环）。成功后状态 CONNECTED。 */
    suspend fun connect() {
        val sock = kotlinx.coroutines.withContext(dev.termish.util.ioDispatcher()) {
            val s = VncTcpSocket(host, port)
            handshake(s)
            s
        }
        socket = sock
        status = VncStatus.CONNECTED
        errorMessage = null
        // 写协程：输入事件串行写出
        scope.launch {
            for (data in outbox) {
                try {
                    kotlinx.coroutines.withContext(dev.termish.util.ioDispatcher()) { sock.write(data) }
                } catch (e: Exception) {
                    if (isActive) onConnectionLost("发送失败: ${e.message}")
                    break
                }
            }
        }
        // 读循环
        readJob = scope.launch {
            try {
                kotlinx.coroutines.withContext(dev.termish.util.ioDispatcher()) { readLoop(sock) }
            } catch (e: Exception) {
                onConnectionLost(e.message ?: "连接断开")
            }
        }
        // 周期性全量刷新请求（保底：漏掉的损坏区域最终会修复）
        scope.launch {
            while (isActive && status == VncStatus.CONNECTED) {
                delay(FULL_REFRESH_MS)
                requestFramebufferUpdate(incremental = true)
            }
        }
    }

    fun close(reason: String? = null) {
        status = VncStatus.CLOSED
        readJob?.cancel()
        outbox.close()
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        zrleInflater.end()
        TermLog.i("vnc") { "closed $host:$port ${reason ?: ""}" }
    }

    private fun onConnectionLost(reason: String) {
        if (status == VncStatus.CLOSED) return
        status = VncStatus.ERROR
        errorMessage = reason
        TermLog.w("vnc") { "connection lost $host:$port: $reason" }
    }

    // ---------- 握手 ----------

    private class Reader(val sock: VncTcpSocket) {
        private val buf = ByteArray(8192)
        private var pos = 0
        private var limit = 0

        private fun fill() {
            if (pos < limit) return
            pos = 0
            limit = sock.read(buf, 0, buf.size)
            if (limit < 0) error("RFB 流结束")
        }

        fun readByte(): Int {
            fill()
            return buf[pos++].toInt() and 0xff
        }

        fun readBytes(n: Int): ByteArray {
            val out = ByteArray(n)
            var off = 0
            while (off < n) {
                fill()
                val avail = limit - pos
                if (avail <= 0) error("RFB 流结束")
                val take = minOf(avail, n - off)
                buf.copyInto(out, off, pos, pos + take)
                pos += take
                off += take
            }
            return out
        }

        fun readU16(): Int = (readByte() shl 8) or readByte()
        fun readU32(): Long = ((readByte().toLong() shl 24) or (readByte().toLong() shl 16) or
            (readByte().toLong() shl 8) or readByte().toLong())

        fun readString(): String {
            val len = readU32().toInt()
            return readBytes(len).decodeToString()
        }
    }

    private fun handshake(sock: VncTcpSocket) {
        val r = Reader(sock)
        reader = r
        // 1) 版本协商：取服务器版本，应答同等（3.8 服务器，向下兼容 3.3/3.7）
        val serverVersion = r.readBytes(12).decodeToString()
        if (!serverVersion.startsWith("RFB ")) error("非 RFB 服务器: ${serverVersion.take(12)}")
        val majorMinor = serverVersion.substring(4, 11)
        val replyVersion = when {
            majorMinor >= "003.008" -> "RFB 003.008\n"
            majorMinor == "003.007" -> "RFB 003.007\n"
            else -> "RFB 003.003\n"
        }
        sock.write(replyVersion.encodeToByteArray())

        // 2) 安全协商
        val securityType: Int = if (majorMinor >= "003.007") {
            val count = r.readByte()
            if (count == 0) error("服务器拒绝连接: ${r.readString()}")
            val types = (0 until count).map { r.readByte() }
            val chosen = pickSecurityType(types)
            if (chosen == null) error("无共同安全类型: $types")
            sock.write(byteArrayOf(chosen.toByte()))
            chosen
        } else {
            // RFB 3.3：服务器直接指定类型
            r.readU32().toInt()
        }

        when (securityType) {
            SEC_NONE -> {
                if (majorMinor >= "003.008") checkSecurityResult(r)
            }
            SEC_VNC_AUTH -> {
                val challenge = r.readBytes(16)
                if (password.isNullOrBlank()) error("服务器要求 VNC 密码")
                val response = Des.vncResponse(challenge, password.encodeToByteArray())
                sock.write(response)
                checkSecurityResult(r)
            }
            else -> error("不支持的安全类型 $securityType")
        }

        // 3) ClientInit（独占会话 = 0：不挤掉其他观看者）
        sock.write(byteArrayOf(0))

        // 4) ServerInit
        val width = r.readU16()
        val height = r.readU16()
        // 服务器像素格式（读掉，客户端用自己的格式请求覆盖）
        r.readBytes(16)
        val name = r.readString()
        TermLog.i("vnc") { "server: ${width}x$height '$name' sec=$securityType" }

        // 5) 请求 32bpp RGB888 客户端像素格式
        val pf = ByteArray(20)
        pf[0] = 0 // 消息类型 SetPixelFormat
        pf[1] = 0; pf[2] = 0; pf[3] = 0 // padding
        pf[4] = 32.toByte() // bitsPerPixel
        pf[5] = 24.toByte() // depth
        pf[6] = 1.toByte() // bigEndian = 1：MSB-first（解码按 b0=R b1=G b2=B）
        pf[7] = 1.toByte() // trueColor
        pf[8] = 0.toByte(); pf[9] = 255.toByte() // red-max
        pf[10] = 0.toByte(); pf[11] = 255.toByte() // green-max
        pf[12] = 0.toByte(); pf[13] = 255.toByte() // blue-max
        pf[14] = 16.toByte() // red-shift
        pf[15] = 8.toByte() // green-shift
        pf[16] = 0.toByte() // blue-shift
        pf[17] = 0.toByte(); pf[18] = 0.toByte(); pf[19] = 0.toByte() // padding
        sock.write(pf)

        // 6) SetEncodings
        val encodings = intArrayOf(
            ENC_ZRLE, ENC_HEXTILE, ENC_RAW, ENC_RRE, ENC_COPYRECT,
            PSEUDO_DESKTOP_SIZE,
        )
        // SetEncodings：type(1)+pad(1)+count(2)+encodings(4×n)，编码从 offset 4 起
        val se = ByteArray(4 + encodings.size * 4)
        se[0] = 2
        se[2] = (encodings.size shr 8).toByte(); se[3] = (encodings.size and 0xff).toByte()
        encodings.forEachIndexed { i, e ->
            val v = e.toLong()
            val o = 4 + i * 4
            se[o] = ((v shr 24) and 0xff).toByte()
            se[o + 1] = ((v shr 16) and 0xff).toByte()
            se[o + 2] = ((v shr 8) and 0xff).toByte()
            se[o + 3] = (v and 0xff).toByte()
        }
        sock.write(se)

        frameWidth = width
        frameHeight = height
        pixels = IntArray(width * height)
        _frame.value = VncFrame(width, height, pixels, ++frameVersion)

        requestFramebufferUpdate(sock, incremental = false)
    }

    private fun pickSecurityType(types: List<Int>): Int? = when {
        SEC_VNC_AUTH in types && !password.isNullOrBlank() -> SEC_VNC_AUTH
        SEC_NONE in types && password.isNullOrBlank() -> SEC_NONE
        SEC_NONE in types -> SEC_NONE // 有密码但服务器允许 None：优先直连，失败让用户改配置
        SEC_VNC_AUTH in types -> SEC_VNC_AUTH
        else -> null
    }

    private fun checkSecurityResult(r: Reader) {
        val result = r.readU32()
        if (result != 0L) throw VncAuthException("VNC 认证失败（密码错误或无权限）")
    }

    // ---------- 帧循环 ----------

    /** 帧循环复用握手的 Reader：其缓冲可能已预读首帧字节（贪婪 8KB fill），丢弃会死等。 */
    private var reader: Reader? = null

    /** ZRLE 持久 zlib 流（RFC 6143 §7.7.6：同一流跨矩形/跨帧延续）。 */
    private var zrleInflater = VncInflater()
    /** 已解压的 ZRLE 字节流（累积；[zrlePos] 之前为已消费）。 */
    private var zrleData = ByteArray(0)
    private var zrlePos = 0
    /** 上一矩形 tile 未解完（zlib 流滞后释放，等后续 update 续喂数据续解）。 */
    private var zrlePending: ZrleProgress? = null

    private class ZrleProgress(val x: Int, val y: Int, val w: Int, val h: Int, var tx: Int, var ty: Int)

    private class ZrleStarved : Exception()

    private var frameWidth = 0
    private var frameHeight = 0
    private var pixels = IntArray(0)

    /** 调试：收到的编码分布（冒烟测试用）。 */
    internal val debugEncCount = mutableMapOf<Int, Int>()

    private fun readLoop(sock: VncTcpSocket) {
        val r = reader ?: Reader(sock)
        while (true) {
            when (val type = r.readByte()) {
                0 -> handleFramebufferUpdate(r, sock)
                1 -> { // SetColorMapEntries：客户端 true-color 模式，忽略
                    r.readBytes(6)
                    val n = r.readU16()
                    r.readBytes(n * 6)
                }
                2 -> { // Bell
                    TermLog.d("vnc") { "bell" }
                }
                3 -> { // ServerCutText
                    r.readBytes(3)
                    val len = r.readU32().toInt()
                    val text = r.readBytes(len).decodeToString()
                    _clipboard.value = VncClipboard(text)
                }
                else -> error("未知 RFB 消息类型 $type")
            }
            // 每处理完一批消息，请求下一帧增量
        }
    }

    private fun handleFramebufferUpdate(r: Reader, sock: VncTcpSocket) {
        r.readBytes(1) // padding
        val numRects = r.readU16()
        var desktopResized = false
        repeat(numRects) {
            val x = r.readU16()
            val y = r.readU16()
            val w = r.readU16()
            val h = r.readU16()
            val enc = r.readU32().toInt()
            debugEncCount[enc] = (debugEncCount[enc] ?: 0) + 1
            if (debugEncCount.size <= 32 && debugEncCount.values.sum() <= 50) {
                TermLog.d("vnc") { "rect enc=$enc ${w}x$h@$x,$y" }
            }
            when {
                enc == PSEUDO_DESKTOP_SIZE -> {
                    desktopResized = true
                    // 新分辨率 = x/y 位置字段复用
                    frameWidth = x
                    frameHeight = y
                    pixels = IntArray(x * y)
                    // 分辨率变化：ZRLE 流状态不可跨分辨率延续（旧 tile 坐标/pending
                    // 会写入新帧越界）——重建 inflater + 清缓冲/pending
                    zrleInflater.end()
                    zrleInflater = VncInflater()
                    zrleData = ByteArray(0)
                    zrlePos = 0
                    zrlePending = null
                    requestFramebufferUpdate(sock, incremental = false)
                }
                enc == ENC_RAW -> decodeRaw(r, x, y, w, h)
                enc == ENC_COPYRECT -> decodeCopyRect(r, x, y, w, h)
                enc == ENC_RRE -> decodeRre(r, x, y, w, h)
                enc == ENC_HEXTILE -> decodeHextile(r, x, y, w, h)
                enc == ENC_ZRLE -> decodeZrle(r, x, y, w, h)
                else -> error("未协商的编码 $enc")
            }
        }
        _frame.value = VncFrame(frameWidth, frameHeight, pixels, ++frameVersion)
        // ZRLE 滞后：数据不足时必须发非增量请求（增量+无 damage 服务器不回包，
        // 持久流续不上会永远卡半帧）；非增量强制服务器继续推流（重复部分是廉价回引）
        if (!desktopResized) requestFramebufferUpdate(sock, incremental = zrlePending == null)
    }

    /** 32bpp RGB888（big-endian）：字节序 R,G,B,X → 0xFF RR GG BB。 */
    private inline fun px(r: Byte, g: Byte, b: Byte): Int =
        (0xff shl 24) or ((r.toInt() and 0xff) shl 16) or ((g.toInt() and 0xff) shl 8) or (b.toInt() and 0xff)

    private fun decodeRaw(r: Reader, x: Int, y: Int, w: Int, h: Int) {
        val rowBytes = w * 4
        var row = y
        repeat(h) {
            val line = r.readBytes(rowBytes)
            var idx = frameWidth * row + x
            var p = 0
            repeat(w) {
                pixels[idx++] = px(line[p], line[p + 1], line[p + 2])
                p += 4
            }
            row++
        }
    }

    private fun decodeCopyRect(r: Reader, x: Int, y: Int, w: Int, h: Int) {
        val sx = r.readU16()
        val sy = r.readU16()
        for (dy in 0 until h) {
            val srcBase = frameWidth * (sy + dy) + sx
            val dstBase = frameWidth * (y + dy) + x
            if (sx < x) {
                for (dx in 0 until w) pixels[dstBase + dx] = pixels[srcBase + dx]
            } else {
                for (dx in w - 1 downTo 0) pixels[dstBase + dx] = pixels[srcBase + dx]
            }
        }
    }

    private fun decodeRre(r: Reader, x: Int, y: Int, w: Int, h: Int) {
        val sub = r.readU32().toInt()
        val bg = readPx(r)
        fillRect(x, y, w, h, bg)
        repeat(sub) {
            val color = readPx(r)
            val sx = r.readU16(); val sy = r.readU16()
            val sw = r.readU16(); val sh = r.readU16()
            fillRect(x + sx, y + sy, sw, sh, color)
        }
    }

    private fun readPx(r: Reader): Int {
        val b = r.readBytes(4)
        return px(b[1], b[2], b[3])
    }

    private fun decodeHextile(r: Reader, rx: Int, ry: Int, w: Int, h: Int) {
        val bgDefault = 0
        var bg = bgDefault
        var fg = bgDefault
        val tilesX = (w + 15) / 16
        val tilesY = (h + 15) / 16
        for (ty in 0 until tilesY) {
            for (tx in 0 until tilesX) {
                val x = rx + tx * 16
                val y = ry + ty * 16
                val tw = minOf(16, w - tx * 16)
                val th = minOf(16, h - ty * 16)
                val sub = r.readByte()
                if (sub and 0x01 == 0) { // Raw tile
                    val rowBytes = tw * 4
                    repeat(th) { dy ->
                        val line = r.readBytes(rowBytes)
                        var idx = frameWidth * (y + dy) + x
                        var p = 0
                        repeat(tw) {
                            pixels[idx++] = px(line[p], line[p + 1], line[p + 2])
                            p += 4
                        }
                    }
                    continue
                }
                if (sub and 0x02 != 0) bg = readPx(r)
                if (sub and 0x04 != 0) fg = readPx(r)
                fillRect(x, y, tw, th, bg)
                when {
                    sub and 0x08 != 0 -> { // foreground tiles
                        val n = r.readByte()
                        repeat(n) {
                            val bit = r.readByte()
                            val dx = bit and 0x0f
                            val dy = bit shr 4
                            pixels[frameWidth * (y + dy) + x + dx] = fg
                        }
                    }
                    sub and 0x10 != 0 -> { // any-colored subrects
                        val n = r.readByte()
                        repeat(n) {
                            val color = readPx(r)
                            val bit = r.readByte()
                            val dx = bit and 0x0f
                            val dy = bit shr 4
                            pixels[frameWidth * (y + dy) + x + dx] = color
                        }
                    }
                }
            }
        }
    }

    /** ZRLE：矩形数据是**会话级 zlib 流的增量片段**（RFC 6143 §7.7.6）。
     *  zlib 滞后释放（窗口内字节要等后续 update 的新片段才吐出）——数据不足时
     *  记下 tile 进度，下一帧 update 继续解（对齐 noVNC ZlibStream 行为）。 */
    private fun decodeZrle(r: Reader, x: Int, y: Int, w: Int, h: Int) {
        val compLen = r.readU32().toInt()
        val comp = r.readBytes(compLen)
        // 已消费超 1MB：压缩累积缓冲头部（防长会话无限增长）
        if (zrlePos > 1_000_000) {
            zrleData = zrleData.copyOfRange(zrlePos, zrleData.size)
            zrlePos = 0
        }
        // 追加解压（zlib 未终结时保留窗口不产出属正常；下一矩形续喂）
        var cap = maxOf(comp.size * 4, 1 shl 16)
        while (true) {
            val out = ByteArray(cap)
            val n = zrleInflater.push(comp, out, 0)
            if (n in 1 until cap) {
                zrleData += out.copyOf(n)
                break
            }
            if (n == 0) break // 本次无新产出（窗口滞留）
            if (cap >= 64 * 1024 * 1024) error("ZRLE 解压缓冲超限")
            cap *= 4
        }
        val dr = ByteReader(zrleData, zrlePos)
        // 续解上一矩形（同几何 = 同一视觉区域继续；不同几何 = 丢旧起新）
        val prog = zrlePending
        var px0: Int; var py0: Int; var pw: Int; var ph: Int; var tx: Int; var ty: Int
        if (prog != null && prog.w == w && prog.h == h && prog.x == x && prog.y == y) {
            px0 = prog.x; py0 = prog.y; pw = prog.w; ph = prog.h; tx = prog.tx; ty = prog.ty
        } else {
            px0 = x; py0 = y; pw = w; ph = h; tx = 0; ty = 0
        }
        zrlePending = null
        val tilesX = (pw + 63) / 64
        val tilesY = (ph + 63) / 64
        var starved = false
        outer@ while (ty < tilesY) {
            while (tx < tilesX) {
                val tileX = px0 + tx * 64
                val tileY = py0 + ty * 64
                val tw = minOf(64, pw - tx * 64)
                val th = minOf(64, ph - ty * 64)
                try {
                    decodeZrleTile(dr, tileX, tileY, tw, th)
                } catch (_: ZrleStarved) {
                    zrlePending = ZrleProgress(px0, py0, pw, ph, tx, ty)
                    starved = true
                    break@outer
                }
                tx++
            }
            tx = 0
            ty++
        }
        if (!starved) {
            zrlePos = dr.pos
        }
    }

    private fun decodeZrleTile(dr: ByteReader, x: Int, y: Int, tw: Int, th: Int) {
        val sub = dr.readByte().toInt() and 0xff
        val paletteSize = sub and 0x7f
        val rle = sub and 0x80 != 0
        val palette = IntArray(paletteSize)
        for (i in 0 until paletteSize) {
            // cpixel：客户端格式 32bpp/depth24 时只发 3 字节（最高位省略），
            // 位序 R,G,B（BE）；读 4 字节会让后续全部错位 →「ZRLE 数据不足」
            val b = dr.readBytes(3)
            palette[i] = px(b[0], b[1], b[2])
        }
        when {
            paletteSize == 0 -> { // 全 raw 像素（cpixel 3 字节）
                repeat(th) { dy ->
                    repeat(tw) { dx ->
                        val b = dr.readBytes(3)
                        pixels[frameWidth * (y + dy) + x + dx] =
                            px(b[0], b[1], b[2])
                    }
                }
            }
            paletteSize == 1 -> { // 单色
                fillRect(x, y, tw, th, palette[0])
            }
            rle -> { // palette RLE：首字节 bit7=1 表示后跟 run 长度字节
                var dy = 0; var dx = 0
                while (dy < th) {
                    val b = dr.readByte().toInt() and 0xff
                    val idx = b and 0x7f
                    var runLen = 1
                    if (b and 0x80 != 0) runLen = 1 + readZrleRunLength(dr)
                    if (idx >= palette.size) continue
                    repeat(runLen) {
                        if (dy >= th) return
                        pixels[frameWidth * (y + dy) + x + dx] = palette[idx]
                        dx++
                        if (dx == tw) { dx = 0; dy++ }
                    }
                }
            }
            else -> { // palette 索引像素（位打包，tile 内从首字节开始对齐）
                val bitsPerIdx = when {
                    paletteSize == 2 -> 1
                    paletteSize <= 4 -> 2
                    paletteSize <= 16 -> 4
                    else -> 8
                }
                var dy = 0; var dx = 0
                val mask = (1 shl bitsPerIdx) - 1
                var bitPos = 0
                var bitBuf = 0
                repeat(th * tw) {
                    if (bitPos == 0) bitBuf = dr.readByte().toInt() and 0xff
                    val idx = (bitBuf shr bitPos) and mask
                    bitPos += bitsPerIdx
                    if (bitPos == 8) bitPos = 0
                    if (idx < palette.size) pixels[frameWidth * (y + dy) + x + dx] = palette[idx]
                    dx++
                    if (dx == tw) { dx = 0; dy++ }
                }
            }
        }
    }

    private fun readZrleRunLength(dr: ByteReader): Int {
        var total = 0
        while (true) {
            val b = dr.readByte().toInt() and 0xff
            total += b
            if (b != 255) return total
        }
    }

    private fun fillRect(x: Int, y: Int, w: Int, h: Int, color: Int) {
        for (dy in 0 until h) {
            val base = frameWidth * (y + dy) + x
            pixels.fill(color, base, base + w)
        }
    }

    private class ByteReader(val data: ByteArray, start: Int = 0) {
        var pos = start
        fun readByte(): Byte {
            if (pos >= data.size) throw ZrleStarved()
            return data[pos++]
        }
        fun readBytes(n: Int): ByteArray {
            if (pos + n > data.size) throw ZrleStarved()
            val out = data.copyOfRange(pos, pos + n)
            pos += n
            return out
        }
    }

    // ---------- 输入事件 ----------

    /** 指针事件：buttonMask bit0=左 bit1=中 bit2=右 bit3=滚轮上 bit4=滚轮下。 */
    fun pointerEvent(buttonMask: Int, x: Int, y: Int) {
        if (viewOnly || status != VncStatus.CONNECTED) return
        val msg = ByteArray(6)
        msg[0] = 5
        msg[1] = buttonMask.toByte()
        msg[2] = (x shr 8).toByte(); msg[3] = (x and 0xff).toByte()
        msg[4] = (y shr 8).toByte(); msg[5] = (y and 0xff).toByte()
        outbox.trySend(msg)
    }

    /** 按键事件：keysym 为 RFB/X11 键码（Unicode 字符直接可用）。 */
    fun keyEvent(down: Boolean, keysym: Int) {
        if (viewOnly || status != VncStatus.CONNECTED) return
        val msg = ByteArray(8)
        msg[0] = 4
        msg[1] = if (down) 1 else 0
        msg[4] = ((keysym shr 24) and 0xff).toByte()
        msg[5] = ((keysym shr 16) and 0xff).toByte()
        msg[6] = ((keysym shr 8) and 0xff).toByte()
        msg[7] = (keysym and 0xff).toByte()
        outbox.trySend(msg)
    }

    /** 发送本机剪贴板到远端。 */
    fun sendClipboard(text: String) {
        if (viewOnly || status != VncStatus.CONNECTED) return
        val bytes = text.encodeToByteArray()
        val msg = ByteArray(8 + bytes.size)
        msg[0] = 6
        val len = bytes.size
        msg[4] = ((len shr 24) and 0xff).toByte()
        msg[5] = ((len shr 16) and 0xff).toByte()
        msg[6] = ((len shr 8) and 0xff).toByte()
        msg[7] = (len and 0xff).toByte()
        bytes.copyInto(msg, 8)
        outbox.trySend(msg)
    }

    private fun requestFramebufferUpdate(incremental: Boolean) {
        val sock = socket ?: return
        if (status != VncStatus.CONNECTED) return
        requestFramebufferUpdate(sock, incremental)
    }

    private fun requestFramebufferUpdate(sock: VncTcpSocket, incremental: Boolean) {
        val msg = ByteArray(10)
        msg[0] = 3
        msg[1] = if (incremental) 1 else 0
        val w = frameWidth
        val h = frameHeight
        msg[6] = ((w shr 8) and 0xff).toByte(); msg[7] = (w and 0xff).toByte()
        msg[8] = ((h shr 8) and 0xff).toByte(); msg[9] = (h and 0xff).toByte()
        try {
            sock.write(msg)
        } catch (_: Exception) {
        }
    }

    private companion object {
        const val SEC_NONE = 1
        const val SEC_VNC_AUTH = 2
        const val ENC_RAW = 0
        const val ENC_COPYRECT = 1
        const val ENC_RRE = 2
        const val ENC_HEXTILE = 5
        const val ENC_ZRLE = 16
        const val PSEUDO_DESKTOP_SIZE = -223
        const val FULL_REFRESH_MS = 5_000L
    }
}
