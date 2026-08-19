package dev.termish.vnc

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/** Android：直接用 android.graphics.Bitmap 承载（setPixels 原位写入）。 */
actual class VncBitmap actual constructor(width: Int, height: Int) {
    private val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

    actual fun update(pixels: IntArray) {
        bitmap.setPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    }

    actual val image: ImageBitmap get() = bitmap.asImageBitmap()

    actual fun recycle() {
        bitmap.recycle()
    }
}
