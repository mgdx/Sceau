package io.github.mgdx.sceau.mrz

import kotlin.math.sqrt

/**
 * Modèles des 37 classes de la MRZ (`A-Z`, `0-9`, `<`), dérivés de la police OCR-B libre de
 * `mrz/fonts/` par `scripts/generate-ocrb-templates.sh` (voir `mrz/README.md`).
 *
 * Chaque modèle couvre une grille de [width] × [height] pixels : horizontalement la chasse du
 * glyphe, verticalement [REFERENCE_HEIGHT] unités de la police au-dessus de la ligne de base,
 * plus une marge de [MARGIN] × [REFERENCE_HEIGHT] en haut et en bas. [normalized] contient les
 * modèles centrés et de norme 1, à la suite, prêts pour une corrélation normalisée.
 */
internal class OcrbTemplates private constructor(
    val classes: CharArray,
    val width: Int,
    val height: Int,
    val normalized: FloatArray,
) {
    val size: Int get() = width * height

    companion object {
        const val RESOURCE = "ocrb-templates.bin"

        /** Hauteur de référence d'un glyphe, en unités de la police (1000 par cadratin). */
        const val REFERENCE_HEIGHT = 740f

        /** Chasse (fixe) des glyphes, dans les mêmes unités. */
        const val ADVANCE = 723f

        /** Marge verticale, en fraction de [REFERENCE_HEIGHT]. */
        const val MARGIN = 0.12f

        const val CLASSES = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789<"

        private const val HEADER = 8
        private const val VERSION = 1

        val default: OcrbTemplates by lazy {
            val bytes =
                checkNotNull(OcrbTemplates::class.java.getResourceAsStream(RESOURCE)) { "ressource $RESOURCE absente" }
                    .use { it.readBytes() }
            parse(bytes)
        }

        fun parse(bytes: ByteArray): OcrbTemplates {
            require(bytes.size >= HEADER) { "modèles OCR-B tronqués" }
            require(bytes[0] == 'O'.code.toByte() && bytes[1] == 'C'.code.toByte()) { "modèles OCR-B invalides" }
            require(bytes[2] == 'R'.code.toByte() && bytes[3] == 'B'.code.toByte()) { "modèles OCR-B invalides" }
            require(bytes[4].toInt() == VERSION) { "version des modèles OCR-B inconnue" }
            val width = bytes[5].toInt()
            val height = bytes[6].toInt()
            val count = bytes[7].toInt()
            require(width in 4..64 && height in 4..64 && count == CLASSES.length) { "modèles OCR-B invalides" }
            val size = width * height
            require(bytes.size == HEADER + count + count * size) { "modèles OCR-B de taille inattendue" }
            val classes = CharArray(count) { (bytes[HEADER + it].toInt() and 0xFF).toChar() }
            require(String(classes) == CLASSES) { "classes OCR-B inattendues" }
            val normalized = FloatArray(count * size)
            for (c in 0 until count) {
                val base = HEADER + count + c * size
                var sum = 0.0
                for (i in 0 until size) sum += bytes[base + i].toInt() and 0xFF
                val mean = sum / size
                var squares = 0.0
                for (i in 0 until size) {
                    val d = (bytes[base + i].toInt() and 0xFF) - mean
                    squares += d * d
                }
                val norm = sqrt(squares)
                require(norm > 0.0) { "modèle OCR-B vide" }
                for (i in 0 until size) {
                    normalized[c * size + i] = (((bytes[base + i].toInt() and 0xFF) - mean) / norm).toFloat()
                }
            }
            return OcrbTemplates(classes, width, height, normalized)
        }
    }
}
