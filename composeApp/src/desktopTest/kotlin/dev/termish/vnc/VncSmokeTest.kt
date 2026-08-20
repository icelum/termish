package dev.termish.vnc

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 对本机 docker x11vnc 的端到端冒烟（127.0.0.1:5900 无 server 时跳过）。 */
class VncSmokeTest {
    private fun serverUp(): Boolean = try {
        java.net.Socket("127.0.0.1", 5901).use { true }
    } catch (_: Exception) { false }

    @Test
    fun `握手并收到首帧`() {
        if (!serverUp()) return // CI/无 server 环境跳过
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val client = RfbClient("127.0.0.1", 5901, "termish", viewOnly = false, scope = scope)
        runBlocking { client.connect() }
        // 等真正的首帧内容：握手会置 version=1 的空帧，增量帧 version>=2
        runBlocking {
            repeat(150) {
                val v = client.frame.value?.version ?: 0
                if (v >= 2) return@runBlocking
                delay(100)
            }
        }
        // ZRLE 滞后释放：多轮 update 才解完一帧（等几秒让续解完成）
        Thread.sleep(4000)
        assertEquals(VncStatus.CONNECTED, client.status, "断线: " + client.errorMessage)
        val f = client.frame.value
        assertTrue(f != null && f.width == 1280 && f.height == 800, "首帧应 1280x800，实际 ${f?.width}x${f?.height}")
        val nonBlack = f!!.pixels.count { it != 0 }
        assertTrue(nonBlack > 1000, "画面应有内容（非全黑），非黑像素=$nonBlack enc=${client.debugEncCount} status=${client.status} err=${client.errorMessage} sample=${f!!.pixels.slice(0..7).joinToString()}")
        // 指针事件不崩
        client.pointerEvent(1, 100, 100)
        client.pointerEvent(0, 100, 100)
        client.keyEvent(true, 'a'.code)
        client.keyEvent(false, 'a'.code)
        println("encodings: " + client.debugEncCount + " nonBlack=" + (client.frame.value?.pixels?.count { it != 0 } ?: -1))
        client.close()
    }
}
