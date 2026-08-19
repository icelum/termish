package dev.termish.vnc

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** DES / VNC-Auth 单测（向量来自 RFC 6143 附录与 FIPS 标准向量）。 */
class DesTest {

    @Test
    fun `FIPS 标准向量`() {
        // FIPS 81 / 经典教科书向量：key=133457799BBCDFF1, PT=0123456789ABCDEF → 85E813540F0AB405
        val key = hex("133457799bbcdff1")
        val plain = hex("0123456789abcdef")
        val cipher = Des.encryptBlock(plain, key)
        assertContentEquals(hex("85e813540f0ab405"), cipher)
    }

    @Test
    fun `VNC 密钥位反转`() {
        // RFC 6143 §7.2.2：VNC 密钥字节 LSB-first，如密码首字节 0x01 → DES 密钥首字节 0x80
        val key = Des.vncKey(hex("0102030405060708"))
        assertContentEquals(hex("8040c020a060e010"), key)
    }

    @Test
    fun `VNC 密钥不足 8 字节补零`() {
        // 'a'=0x61→0x86, 'b'=0x62→0x46, 'c'=0x63→0xC6
        val key = Des.vncKey("abc".encodeToByteArray())
        assertContentEquals(hex("8646c6") + hex("0000000000"), key)
    }

    @Test
    fun `挑战应答长度 16`() {
        val challenge = ByteArray(16) { it.toByte() }
        val response = Des.vncResponse(challenge, "password".encodeToByteArray())
        assertEquals(16, response.size)
        // 两块独立 ECB：交换挑战前后两块，应答相应交换
        val swapped = Des.vncResponse(challenge.copyOfRange(8, 16) + challenge.copyOf(8), "password".encodeToByteArray())
        assertContentEquals(response.copyOfRange(8, 16) + response.copyOf(8), swapped)
    }

    @Test
    fun `空密码也可推导密钥`() {
        assertContentEquals(ByteArray(8), Des.vncKey(ByteArray(0)))
    }

    private fun hex(s: String): ByteArray =
        ByteArray(s.length / 2) { i -> ((Character.digit(s[i * 2], 16) shl 4) + Character.digit(s[i * 2 + 1], 16)).toByte() }
}
