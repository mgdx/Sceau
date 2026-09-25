package io.github.mgdx.sceau.ui.result

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.graphics.scale
import io.github.mgdx.sceau.core.model.EncodedImage
import io.github.mgdx.sceau.core.model.ImageFormat
import io.github.mgdx.sceau.jp2.Jpeg2000Decoder

/**
 * Décode une image de la puce en un [Bitmap] mutable, uniquement en mémoire (aucun cache).
 * À appeler hors du thread principal. Renvoie null si l'image est illisible ou démesurée :
 * l'écran affiche alors un emplacement vide, jamais de plantage.
 */
fun decodeToBitmap(image: EncodedImage): Bitmap? =
    try {
        when (resolveImageFormat(image.format, image.bytes)) {
            ImageFormat.JPEG2000 -> Jpeg2000Decoder.decode(image.bytes)?.let(::limitSide)
            ImageFormat.JPEG, ImageFormat.UNKNOWN -> decodeWithPlatform(image.bytes)
        }
    } catch (_: Exception) {
        null
    } catch (_: OutOfMemoryError) {
        null
    }

/**
 * Bornes du décodage (audit V2) : une puce forgée peut déclarer une image immense dans
 * quelques centaines d'octets. Au-delà de [MAX_DECLARED_PIXELS], l'image est refusée ;
 * en deçà, elle est sous-échantillonnée pour qu'aucun côté ne dépasse [MAX_SIDE], soit au
 * plus 16 Mo en ARGB_8888, bien en dessous de la limite de dessin du canevas.
 */
object DecodeLimits {
    /** Pixels déclarés au-delà desquels l'image n'est pas décodée (40 mégapixels). */
    const val MAX_DECLARED_PIXELS = 40_000_000L

    /** Côté maximal du bitmap final, en pixels. */
    const val MAX_SIDE = 2048

    /**
     * Facteur `inSampleSize` (puissance de 2) pour une image de [width]×[height] déclarés,
     * ou null si elle doit être refusée (dimensions invalides ou trop de pixels). Le côté
     * obtenu, arrondi au supérieur, ne dépasse jamais [maxSide].
     */
    fun sampleSize(
        width: Int,
        height: Int,
        maxSide: Int = MAX_SIDE,
        maxPixels: Long = MAX_DECLARED_PIXELS,
    ): Int? {
        if (width <= 0 || height <= 0) return null
        if (width.toLong() * height.toLong() > maxPixels) return null
        val longest = maxOf(width, height)
        var sample = 1
        while ((longest + sample - 1) / sample > maxSide) sample *= 2
        return sample
    }
}

private fun decodeWithPlatform(bytes: ByteArray): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val sample = DecodeLimits.sampleSize(bounds.outWidth, bounds.outHeight) ?: return null
    val options =
        BitmapFactory.Options().apply {
            inMutable = true
            inSampleSize = sample
        }
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
    // Filet de sécurité : le décodeur ne doit jamais rendre plus grand qu'annoncé.
    return limitSide(bitmap)
}

/** Réduit [bitmap] si l'un de ses côtés dépasse [DecodeLimits.MAX_SIDE] ; l'original est effacé. */
private fun limitSide(bitmap: Bitmap): Bitmap {
    val longest = maxOf(bitmap.width, bitmap.height)
    if (longest <= DecodeLimits.MAX_SIDE) return bitmap
    val scale = DecodeLimits.MAX_SIDE.toDouble() / longest
    val width = (bitmap.width * scale).toInt().coerceIn(1, DecodeLimits.MAX_SIDE)
    val height = (bitmap.height * scale).toInt().coerceIn(1, DecodeLimits.MAX_SIDE)
    try {
        val scaled = bitmap.scale(width, height)
        if (scaled !== bitmap) bitmap.wipeAndRecycle()
        return scaled
    } catch (e: OutOfMemoryError) {
        bitmap.wipeAndRecycle()
        throw e
    }
}

/** Efface les pixels (si possible) puis libère le bitmap. */
fun Bitmap.wipeAndRecycle() {
    if (isRecycled) return
    if (isMutable) eraseColor(0)
    recycle()
}
