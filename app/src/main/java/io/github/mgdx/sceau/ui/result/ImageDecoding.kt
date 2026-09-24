package io.github.mgdx.sceau.ui.result

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.graphics.createBitmap
import io.github.mgdx.sceau.core.model.EncodedImage
import io.github.mgdx.sceau.core.model.ImageFormat
import io.github.mgdx.sceau.core.model.Images

/**
 * Décode une image de la puce en un [Bitmap] mutable, uniquement en mémoire (aucun cache).
 * À appeler hors du thread principal. Renvoie null si l'image est illisible : l'écran
 * affiche alors un emplacement vide, jamais de plantage.
 */
fun decodeToBitmap(image: EncodedImage): Bitmap? =
    try {
        when (resolveImageFormat(image.format, image.bytes)) {
            ImageFormat.JPEG2000 -> decodeJpeg2000(image.bytes)
            ImageFormat.JPEG, ImageFormat.UNKNOWN -> decodeWithPlatform(image.bytes)
        }
    } catch (_: Exception) {
        null
    } catch (_: NotImplementedError) {
        // Décodeur JPEG 2000 pas encore disponible dans :core.
        null
    } catch (_: OutOfMemoryError) {
        null
    }

private fun decodeWithPlatform(bytes: ByteArray): Bitmap? {
    val options = BitmapFactory.Options().apply { inMutable = true }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
}

private fun decodeJpeg2000(bytes: ByteArray): Bitmap? {
    val argb = Images.decodeJpeg2000(bytes)
    try {
        if (argb.width <= 0 || argb.height <= 0) return null
        val bitmap = createBitmap(argb.width, argb.height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(argb.pixels, 0, argb.width, 0, 0, argb.width, argb.height)
        return bitmap
    } finally {
        argb.wipe()
    }
}

/** Efface les pixels (si possible) puis libère le bitmap. */
fun Bitmap.wipeAndRecycle() {
    if (isRecycled) return
    if (isMutable) eraseColor(0)
    recycle()
}
