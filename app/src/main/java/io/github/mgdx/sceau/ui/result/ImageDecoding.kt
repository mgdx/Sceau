package io.github.mgdx.sceau.ui.result

import android.content.Context
import android.graphics.Bitmap
import androidx.core.graphics.scale
import io.github.mgdx.sceau.core.model.EncodedImage
import io.github.mgdx.sceau.jp2.IsolatedJpeg2000Decoder
import kotlin.coroutines.cancellation.CancellationException

/**
 * Décode une image de la puce en un [Bitmap] mutable, uniquement en mémoire (aucun cache).
 * À appeler hors du thread principal. Renvoie null si l'image est illisible ou démesurée :
 * l'écran affiche alors un emplacement vide, jamais de plantage. Seuls le JPEG et le JPEG 2000,
 * reconnus à leurs octets ([detectImageFormat]), sont acceptés, et les deux sont décodés dans le
 * processus isolé (D23, audit V18) : aucun octet d'image venu de la puce n'atteint un décodeur
 * natif dans le processus de l'app.
 */
suspend fun decodeToBitmap(
    context: Context,
    image: EncodedImage,
): Bitmap? =
    try {
        if (detectImageFormat(image.bytes) == null) {
            null
        } else {
            IsolatedJpeg2000Decoder.decode(context, image.bytes)?.let(::limitSide)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    } catch (_: OutOfMemoryError) {
        null
    }

/**
 * Bornes du décodage (audit V2) : une puce forgée peut déclarer une image immense dans
 * quelques centaines d'octets. Au-delà de [MAX_DECLARED_PIXELS], l'image est refusée ;
 * en deçà, elle est sous-échantillonnée pour qu'aucun côté ne dépasse [MAX_SIDE], soit au
 * plus 16 Mo en ARGB_8888, bien en dessous de la limite de dessin du canevas. Appliquées au
 * JPEG par le service de décodage isolé (`Jpeg2000Service`, D23).
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

/** Réduit [bitmap] si l'un de ses côtés dépasse [DecodeLimits.MAX_SIDE] ; l'original est effacé. */
internal fun limitSide(bitmap: Bitmap): Bitmap {
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
