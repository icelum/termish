package dev.termish.vnc

import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo

/** iOS：与 desktop 同构（CMP iOS 同为 Skia 渲染，nativeCanvas 可用）。 */
actual class VncBitmap actual constructor(width: Int, height: Int) {
    private val composeBitmap: ImageBitmap = ImageBitmap(width, height)
    private val native = Canvas(composeBitmap).nativeCanvas
    private val skBitmap = Bitmap()
    private val bytes = ByteArray(width * height * 4)

    init {
        skBitmap.allocPixels(ImageInfo.makeN32(width, height, ColorAlphaType.OPAQUE))
    }

    actual fun update(pixels: IntArray) {
        val n = pixels.size
        for (i in 0 until n) {
            val v = pixels[i]
            val o = i * 4
            bytes[o] = (v shr 16).toByte() // R
            bytes[o + 1] = (v shr 8).toByte() // G
            bytes[o + 2] = v.toByte() // B
            bytes[o + 3] = 0xff.toByte() // A
        }
        skBitmap.installPixels(bytes)
        native.drawImage(Image.makeFromBitmap(skBitmap), 0f, 0f)
    }

    actual val image: ImageBitmap get() = composeBitmap

    actual fun recycle() {
    }
}
