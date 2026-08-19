package dev.termish.vnc

/**
 * 纯 Kotlin DES（ECB 单块加密）——仅用于 VNC-Auth 挑战应答（RFC 6143 §7.2.2）。
 *
 * VNC 的密钥推导非常规：密码取前 8 字节、不足补零，且**每个字节按位反转**
 * （DES 标准 MSB-first，VNC 用 LSB-first 的位序读密钥）。本对象实现标准 DES
 * 单块加密，VNC 位反转由 [vncResponse] 封装。
 *
 * 位表示约定：块统一存 Long，bit 0 = 最高位（MSB-first，与 DES 表格惯例一致）。
 * permute 按 table 输出宽度 = table.size、输入宽度显式传入。
 */
internal object Des {

    private val IP = intArrayOf(
        57, 49, 41, 33, 25, 17, 9, 1, 59, 51, 43, 35, 27, 19, 11, 3,
        61, 53, 45, 37, 29, 21, 13, 5, 63, 55, 47, 39, 31, 23, 15, 7,
        56, 48, 40, 32, 24, 16, 8, 0, 58, 50, 42, 34, 26, 18, 10, 2,
        60, 52, 44, 36, 28, 20, 12, 4, 62, 54, 46, 38, 30, 22, 14, 6,
    )
    private val FP = intArrayOf(
        39, 7, 47, 15, 55, 23, 63, 31, 38, 6, 46, 14, 54, 22, 62, 30,
        37, 5, 45, 13, 53, 21, 61, 29, 36, 4, 44, 12, 52, 20, 60, 28,
        35, 3, 43, 11, 51, 19, 59, 27, 34, 2, 42, 10, 50, 18, 58, 26,
        33, 1, 41, 9, 49, 17, 57, 25, 32, 0, 40, 8, 48, 16, 56, 24,
    )
    private val PC1 = intArrayOf(
        56, 48, 40, 32, 24, 16, 8, 0, 57, 49, 41, 33, 25, 17,
        9, 1, 58, 50, 42, 34, 26, 18, 10, 2, 59, 51, 43, 35,
        62, 54, 46, 38, 30, 22, 14, 6, 61, 53, 45, 37, 29, 21,
        13, 5, 60, 52, 44, 36, 28, 20, 12, 4, 27, 19, 11, 3,
    )
    private val PC2 = intArrayOf(
        13, 16, 10, 23, 0, 4, 2, 27, 14, 5, 20, 9, 22, 18, 11, 3,
        25, 7, 15, 6, 26, 19, 12, 1, 40, 51, 30, 36, 46, 54, 29, 39,
        50, 44, 32, 47, 43, 48, 38, 55, 33, 52, 45, 41, 49, 35, 28, 31,
    )
    private val SHIFTS = intArrayOf(1, 1, 2, 2, 2, 2, 2, 2, 1, 2, 2, 2, 2, 2, 2, 1)
    private val EXP = intArrayOf(
        31, 0, 1, 2, 3, 4, 3, 4, 5, 6, 7, 8, 7, 8, 9, 10,
        11, 12, 11, 12, 13, 14, 15, 16, 15, 16, 17, 18, 19, 20, 19, 20,
        21, 22, 23, 24, 23, 24, 25, 26, 27, 28, 27, 28, 29, 30, 31, 0,
    )
    private val P32 = intArrayOf(
        15, 6, 19, 20, 28, 11, 27, 16, 0, 14, 22, 25, 4, 17, 30, 9,
        1, 7, 23, 13, 31, 26, 2, 8, 18, 12, 29, 5, 21, 10, 3, 24,
    )
    private val SBOX = arrayOf(
        intArrayOf(14, 4, 13, 1, 2, 15, 11, 8, 3, 10, 6, 12, 5, 9, 0, 7, 0, 15, 7, 4, 14, 2, 13, 1, 10, 6, 12, 11, 9, 5, 3, 8, 4, 1, 14, 8, 13, 6, 2, 11, 15, 12, 9, 7, 3, 10, 5, 0, 15, 12, 8, 2, 4, 9, 1, 7, 5, 11, 3, 14, 10, 0, 6, 13),
        intArrayOf(15, 1, 8, 14, 6, 11, 3, 4, 9, 7, 2, 13, 12, 0, 5, 10, 3, 13, 4, 7, 15, 2, 8, 14, 12, 0, 1, 10, 6, 9, 11, 5, 0, 14, 7, 11, 10, 4, 13, 1, 5, 8, 12, 6, 9, 3, 2, 15, 13, 8, 10, 1, 3, 15, 4, 2, 11, 6, 7, 12, 0, 5, 14, 9),
        intArrayOf(10, 0, 9, 14, 6, 3, 15, 5, 1, 13, 12, 7, 11, 4, 2, 8, 13, 7, 0, 9, 3, 4, 6, 10, 2, 8, 5, 14, 12, 11, 15, 1, 13, 6, 4, 9, 8, 15, 3, 0, 11, 1, 2, 12, 5, 10, 14, 7, 1, 10, 13, 0, 6, 9, 8, 7, 4, 15, 14, 3, 11, 5, 2, 12),
        intArrayOf(7, 13, 14, 3, 0, 6, 9, 10, 1, 2, 8, 5, 11, 12, 4, 15, 13, 8, 11, 5, 6, 15, 0, 3, 4, 7, 2, 12, 1, 10, 14, 9, 10, 6, 9, 0, 12, 11, 7, 13, 15, 1, 3, 14, 5, 2, 8, 4, 3, 15, 0, 6, 10, 1, 13, 8, 9, 4, 5, 11, 12, 7, 2, 14),
        intArrayOf(2, 12, 4, 1, 7, 10, 11, 6, 8, 5, 3, 15, 13, 0, 14, 9, 14, 11, 2, 12, 4, 7, 13, 1, 5, 0, 15, 10, 3, 9, 8, 6, 4, 2, 1, 11, 10, 13, 7, 8, 15, 9, 12, 5, 6, 3, 0, 14, 11, 8, 12, 7, 1, 14, 2, 13, 6, 15, 0, 9, 10, 4, 5, 3),
        intArrayOf(12, 1, 10, 15, 9, 2, 6, 8, 0, 13, 3, 4, 14, 7, 5, 11, 10, 15, 4, 2, 7, 12, 9, 5, 6, 1, 13, 14, 0, 11, 3, 8, 9, 14, 15, 5, 2, 8, 12, 3, 7, 0, 4, 10, 1, 13, 11, 6, 4, 3, 2, 12, 9, 5, 15, 10, 11, 14, 1, 7, 6, 0, 8, 13),
        intArrayOf(4, 11, 2, 14, 15, 0, 8, 13, 3, 12, 9, 7, 5, 10, 6, 1, 13, 0, 11, 7, 4, 9, 1, 10, 14, 3, 5, 12, 2, 15, 8, 6, 1, 4, 11, 13, 12, 3, 7, 14, 10, 15, 6, 8, 0, 5, 9, 2, 6, 11, 13, 8, 1, 4, 10, 7, 9, 5, 0, 15, 14, 2, 3, 12),
        intArrayOf(13, 2, 8, 4, 6, 15, 11, 1, 10, 9, 3, 14, 5, 0, 12, 7, 1, 15, 13, 8, 10, 3, 7, 4, 12, 5, 6, 11, 0, 14, 9, 2, 7, 11, 4, 1, 9, 12, 14, 2, 0, 6, 10, 13, 15, 3, 5, 8, 2, 1, 14, 7, 4, 10, 8, 13, 15, 12, 9, 0, 3, 5, 6, 11),
    )

    /** 单块加密：8 字节明文 + 8 字节密钥（标准 MSB-first 位序）→ 8 字节密文。 */
    fun encryptBlock(plain: ByteArray, key: ByteArray): ByteArray {
        require(plain.size == 8 && key.size == 8) { "DES 块必须 8 字节" }
        val subKeys = deriveSubKeys(key)
        val ipBits = readBits(plain, IP)
        var l = ipBits ushr 32
        var r = ipBits and 0xffffffffL
        for (round in 0 until 16) {
            val next = l xor feistel(r, subKeys[round])
            l = r
            r = next
        }
        // 末轮交换后做 FP
        val pre = (r shl 32) or l
        return writeBits(permute(pre, FP, 64))
    }

    /** VNC 密钥推导：前 8 字节、补零、每字节位反转（LSB→MSB）。 */
    fun vncKey(password: ByteArray): ByteArray {
        val key = ByteArray(8)
        for (i in 0 until 8) {
            val b = if (i < password.size) password[i].toInt() and 0xff else 0
            key[i] = reverseByte(b).toByte()
        }
        return key
    }

    /** 单字节位反转（0b0110_0001 → 0b1000_0110）。 */
    private fun reverseByte(v: Int): Int {
        var x = v
        x = ((x and 0xf0) shr 4) or ((x and 0x0f) shl 4)
        x = ((x and 0xcc) shr 2) or ((x and 0x33) shl 2)
        x = ((x and 0xaa) shr 1) or ((x and 0x55) shl 1)
        return x and 0xff
    }

    /** VNC-Auth 应答：对 16 字节挑战做两块 ECB 加密（密钥 = VNC 位反转密码）。 */
    fun vncResponse(challenge: ByteArray, password: ByteArray): ByteArray {
        require(challenge.size == 16) { "挑战必须 16 字节" }
        val key = vncKey(password)
        return encryptBlock(challenge.copyOf(8), key) +
            encryptBlock(challenge.copyOfRange(8, 16), key)
    }

    private fun deriveSubKeys(key: ByteArray): Array<Long> {
        val k56 = readBits(key, PC1)
        var c = k56 ushr 28
        var d = k56 and 0x0fffffffL
        val keys = Array(16) { 0L }
        for (i in 0 until 16) {
            val s = SHIFTS[i]
            c = ((c shl s) or (c ushr (28 - s))) and 0x0fffffffL
            d = ((d shl s) or (d ushr (28 - s))) and 0x0fffffffL
            keys[i] = permute((c shl 28) or d, PC2, 56)
        }
        return keys
    }

    private fun feistel(r: Long, subKey: Long): Long {
        val x = permute(r, EXP, 32) xor subKey
        var out = 0L
        for (box in 0 until 8) {
            val six = ((x ushr (42 - box * 6)) and 0x3fL).toInt()
            val row = ((six and 0x20) ushr 4) or (six and 1)
            val col = (six shr 1) and 0x0f
            out = (out shl 4) or SBOX[box][row * 16 + col].toLong()
        }
        return permute(out, P32, 32)
    }

    /** 按表重排：输入 bit 0（= 值的最高有效位）为 src 索引 0。 */
    private fun readBits(data: ByteArray, table: IntArray): Long {
        var v = 0L
        for (src in table) {
            v = (v shl 1) or ((data[src shr 3].toLong() ushr (7 - (src and 7))) and 1L)
        }
        return v
    }

    private fun permute(bits: Long, table: IntArray, inWidth: Int): Long {
        var v = 0L
        for (src in table) {
            v = (v shl 1) or ((bits ushr (inWidth - 1 - src)) and 1L)
        }
        return v
    }

    private fun writeBits(bits: Long): ByteArray {
        val out = ByteArray(8)
        for (i in 0 until 8) out[i] = (bits ushr (56 - i * 8)).toByte()
        return out
    }
}
