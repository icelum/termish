package dev.termish.vnc

/**
 * 会话级持久 zlib 解压器（ZRLE 语义：RFC 6143 §7.7.6 —— 同一 zlib 流跨矩形、
 * 跨帧更新延续，矩形数据只是流的增量片段）。
 *
 * 用法：每收到一个矩形的压缩片段就 [push]，累积解压输出由解码器消费；
 * zlib 会保留约 32KB 窗口不吐出（未终结流的正常行为）。
 */
expect class VncInflater() {
    /** 喂入一段压缩数据，尽量解压到 out（从 off 起），返回本次产出字节数。 */
    fun push(input: ByteArray, out: ByteArray, off: Int): Int
    fun end()
}
