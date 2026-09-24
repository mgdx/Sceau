package io.github.mgdx.sceau.ui.result

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import io.github.mgdx.sceau.core.model.EncodedImage
import io.github.mgdx.sceau.core.model.ImageFormat
import io.github.mgdx.sceau.jp2.Jpeg2000Decoder

/**
 * Décode une image de la puce en un [Bitmap] mutable, uniquement en mémoire (aucun cache).
 * À appeler hors du thread principal. Renvoie null si l'image est illisible : l'écran
 * affiche alors un emplacement vide, jamais de plantage.
 */
fun decodeToBitmap(image: EncodedImage): Bitmap? =
    try {
        when (resolveImageFormat(image.format, image.bytes)) {
            ImageFormat.JPEG2000 -> Jpeg2000Decoder.decode(image.bytes)
            ImageFormat.JPEG, ImageFormat.UNKNOWN -> decodeWithPlatform(image.bytes)
        }
    } catch (_: Exception) {
        null
    } catch (_: OutOfMemoryError) {
        null
    }

private fun decodeWithPlatform(bytes: ByteArray): Bitmap? {
    val options = BitmapFactory.Options().apply { inMutable = true }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
}

/** Efface les pixels (si possible) puis libère le bitmap. */
fun Bitmap.wipeAndRecycle() {
    if (isRecycled) return
    if (isMutable) eraseColor(0)
    recycle()
}
