package io.github.mgdx.sceau.mrz

import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Modèles des 37 classes de la MRZ (`A-Z`, `0-9`, `<`), dérivés de la police OCR-B libre de
 * `mrz/fonts/` par `scripts/generate-ocrb-templates.sh` (voir `mrz/README.md`).
 *
 * Chaque modèle couvre une grille de [width] × [height] pixels : horizontalement la chasse du
 * glyphe, verticalement [REFERENCE_HEIGHT] unités de la police au-dessus de la ligne de base,
 * plus une marge de [MARGIN] × [REFERENCE_HEIGHT] en haut et en bas. [levels] contient, pour
 * chaque flou de [BLUR_SIGMAS], les modèles centrés et de norme 1, à la suite, prêts pour une
 * corrélation normalisée ; [normalized] est le niveau net.
 *
 * Les niveaux flous sont calculés au chargement (flou gaussien de la couverture d'encre, hors
 * de la grille compté comme papier) : une petite image de la caméra est floue, et la corrélation
 * avec un modèle net y départage mal les classes proches.
 */
internal class OcrbTemplates private constructor(
    val classes: CharArray,
    val width: Int,
    val height: Int,
    val levels: List<FloatArray>,
) {
    val size: Int get() = width * height

    val normalized: FloatArray get() = levels[0]

    companion object {
        const val RESOURCE = "ocrb-templates.bin"

        /** Hauteur de référence d'un glyphe, en unités de la police (1000 par cadratin). */
        const val REFERENCE_HEIGHT = 740f

        /** Chasse (fixe) des glyphes, dans les mêmes unités. */
        const val ADVANCE = 723f

        /** Marge verticale, en fraction de [REFERENCE_HEIGHT]. */
        const val MARGIN = 0.12f

        const val CLASSES = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789<"

        /** Écarts types des niveaux de flou, en pixels de modèle (0 : modèles nets). */
        val BLUR_SIGMAS = floatArrayOf(0f, 0.9f, 1.6f, 2.3f)

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
            val coverage = FloatArray(count * size) { (bytes[HEADER + count + it].toInt() and 0xFF).toFloat() }
            val levels =
                BLUR_SIGMAS.map { sigma ->
                    val level = if (sigma > 0f) blur(coverage, count, width, height, sigma) else coverage.copyOf()
                    for (c in 0 until count) normalize(level, c * size, size)
                    level
                }
            return OcrbTemplates(classes, width, height, levels)
        }

        /** Centre et réduit à la norme 1 le modèle [from] .. [from] + [size]. */
        private fun normalize(
            values: FloatArray,
            from: Int,
            size: Int,
        ) {
            var sum = 0.0
            for (i in from until from + size) sum += values[i]
            val mean = sum / size
            var squares = 0.0
            for (i in from until from + size) {
                val d = values[i] - mean
                squares += d * d
            }
            val norm = sqrt(squares)
            require(norm > 0.0) { "modèle OCR-B vide" }
            for (i in from until from + size) values[i] = ((values[i] - mean) / norm).toFloat()
        }

        /** Flou gaussien séparable de chaque modèle ; hors de la grille, du papier (0). */
        private fun blur(
            source: FloatArray,
            count: Int,
            width: Int,
            height: Int,
            sigma: Float,
        ): FloatArray {
            val radius = ceil(3 * sigma).toInt()
            val kernel = FloatArray(2 * radius + 1) { exp(-((it - radius) * (it - radius)) / (2f * sigma * sigma)) }
            val total = kernel.sum()
            for (i in kernel.indices) kernel[i] /= total
            val size = width * height
            val tmp = FloatArray(size)
            val out = FloatArray(source.size)
            for (c in 0 until count) {
                val base = c * size
                for (y in 0 until height) {
                    for (x in 0 until width) {
                        var acc = 0f
                        for (d in -radius..radius) {
                            val xx = x + d
                            if (xx in 0 until width) acc += kernel[d + radius] * source[base + y * width + xx]
                        }
                        tmp[y * width + x] = acc
                    }
                }
                for (y in 0 until height) {
                    for (x in 0 until width) {
                        var acc = 0f
                        for (d in -radius..radius) {
                            val yy = y + d
                            if (yy in 0 until height) acc += kernel[d + radius] * tmp[yy * width + x]
                        }
                        out[base + y * width + x] = acc
                    }
                }
            }
            return out
        }
    }
}
