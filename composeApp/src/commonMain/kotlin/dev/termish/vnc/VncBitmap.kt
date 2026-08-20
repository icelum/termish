package dev.termish.vnc

import androidx.compose.ui.graphics.ImageBitmap

/**
 * 可复用的帧缓冲位图：IntARGB 像素 → 平台位图 → Compose ImageBitmap。
 * 每帧 [update] 原位刷新（不新建位图，避免高频分配）。
 */
expect class VncBitmap(width: Int, height: Int) {
    /** 写入整帧像素（0xAARRGGBB，长度 = width * height）。 */
    fun update(pixels: IntArray)
    val image: ImageBitmap
    val width: Int
    val height: Int
    fun recycle()
}
