package io.github.mgdx.sceau.ui.result

import android.content.Context
import android.graphics.Bitmap
import androidx.core.graphics.scale
import io.github.mgdx.sceau.core.model.EncodedImage
import io.github.mgdx.sceau.jp2.IsolatedJpeg2000Decoder
import kotlin.coroutines.cancellation.CancellationException

/**
 * Decodes an image from the chip into a mutable [Bitmap], in memory only (no cache).
 * To be called off the main thread. Returns null if the image is unreadable or oversized:
 * the screen then displays an empty slot, never a crash. Only JPEG and JPEG 2000, recognized
 * by their bytes ([detectImageFormat]), are accepted, and both are decoded in the isolated
 * process (D23, audit V18): no image byte coming from the chip reaches a native decoder in
 * the app process.
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
 * Decoding bounds (audit V2): a forged chip can declare a huge image in a few hundred
 * bytes. Beyond [MAX_DECLARED_PIXELS], the image is rejected; below that, it is subsampled
 * so that no side exceeds [MAX_SIDE], i.e. at most 16 MB in ARGB_8888, well below the
 * canvas drawing limit. Applied to JPEG by the isolated decoding service
 * (`Jpeg2000Service`, D23).
 */
object DecodeLimits {
    /** Declared pixels beyond which the image is not decoded (40 megapixels). */
    const val MAX_DECLARED_PIXELS = 40_000_000L

    /** Maximum side of the final bitmap, in pixels. */
    const val MAX_SIDE = 2048

    /**
     * `inSampleSize` factor (power of 2) for an image of declared [width]×[height],
     * or null if it must be rejected (invalid dimensions or too many pixels). The resulting
     * side, rounded up, never exceeds [maxSide].
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

/** Shrinks [bitmap] if one of its sides exceeds [DecodeLimits.MAX_SIDE]; the original is wiped. */
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

/** Wipes the pixels (if possible), then releases the bitmap. */
fun Bitmap.wipeAndRecycle() {
    if (isRecycled) return
    if (isMutable) eraseColor(0)
    recycle()
}
