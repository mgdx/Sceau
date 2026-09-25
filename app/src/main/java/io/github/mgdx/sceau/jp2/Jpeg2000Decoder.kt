package io.github.mgdx.sceau.jp2

import android.graphics.Bitmap
import androidx.core.graphics.createBitmap

/**
 * Décodeur JPEG 2000 (portrait DG2, images DG12) au-dessus d'OpenJPEG, compilé depuis les
 * sources dans `libsceau_jp2.so` (décision D3). Accepte un fichier JP2 ou un flux J2K brut.
 *
 * Le flux vient d'une puce potentiellement hostile : une donnée invalide, tronquée ou aux
 * dimensions excessives (plus de 4096 pixels de côté annoncés) donne null, jamais une
 * exception. Contre les bombes de décompression, le natif estime la mémoire nécessaire avant
 * de décoder et plafonne les allocations d'OpenJPEG (64 Mo) ; une image de plus de 2048 pixels
 * de côté est décodée à résolution réduite. Les décodages sont sérialisés : un seul à la fois
 * dans le processus, quel que soit le nombre d'appelants. Le décodage se fait entièrement en
 * mémoire et sans aucune trace. À appeler hors du thread principal.
 */
object Jpeg2000Decoder {
    /** Un seul décodage à la fois : le budget mémoire natif vaut pour le processus entier. */
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

    /** Renvoie un [Bitmap] ARGB_8888 mutable, ou null si le flux n'est pas décodable. */
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
            // Les pixels de la photo ne restent que dans le bitmap, effacé par l'écran de résultat.
            pixels.fill(0)
        }
    }

    private external fun nativeDecode(
        data: ByteArray,
        dimensions: IntArray,
    ): IntArray?
}
