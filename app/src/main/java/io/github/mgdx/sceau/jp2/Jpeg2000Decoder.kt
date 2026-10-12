package io.github.mgdx.sceau.jp2

import android.graphics.Bitmap
import androidx.core.graphics.createBitmap

/**
 * JPEG 2000 decoder (DG2 portrait, DG7 signature, DG12 images) on top of OpenJPEG, compiled from
 * source into `libsceau_jp2.so` (decision D3). Accepts a JP2 file or a raw J2K stream.
 *
 * The stream comes from a potentially hostile chip: invalid or truncated data, or data with
 * excessive dimensions (more than 4096 pixels per side announced) gives null, never an
 * exception. Against decompression bombs, the native code estimates the required memory before
 * decoding and caps OpenJPEG's allocations (64 MB); an image with sides of more than 2048 pixels
 * is decoded at reduced resolution. Decodings are serialized: only one at a time in the
 * process, regardless of the number of callers. Decoding happens entirely in memory and
 * without any trace. To be called off the main thread.
 */
object Jpeg2000Decoder {
    /** Only one decoding at a time: the native memory budget applies to the whole process. */
    private val decodeLock = Any()

    private val libraryLoaded: Boolean by lazy {
        try {
            System.loadLibrary("sceau_jp2")
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    /** Returns a mutable ARGB_8888 [Bitmap], or null if the stream cannot be decoded. */
    fun decode(bytes: ByteArray): Bitmap? {
        if (bytes.isEmpty() || !libraryLoaded) return null
        return synchronized(decodeLock) { decodeLocked(bytes) }
    }

    private fun decodeLocked(bytes: ByteArray): Bitmap? {
        val dimensions = IntArray(2)
        val pixels =
            try {
                nativeDecode(bytes, dimensions)
            } catch (_: UnsatisfiedLinkError) {
                null
            } ?: return null
        try {
            val width = dimensions[0]
            val height = dimensions[1]
            if (width <= 0 || height <= 0 || pixels.size.toLong() != width.toLong() * height) return null
            val bitmap = createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
            return bitmap
        } finally {
            // The photo's pixels remain only in the bitmap, wiped by the result screen.
            pixels.fill(0)
        }
    }

    private external fun nativeDecode(
        data: ByteArray,
        dimensions: IntArray,
    ): IntArray?
}
