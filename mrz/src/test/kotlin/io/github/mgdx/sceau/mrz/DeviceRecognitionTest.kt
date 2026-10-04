package io.github.mgdx.sceau.mrz

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reconnaissance sur des images « appareil » ([DeviceSceneRenderer]) : petits caractères
 * (pas de 10 à 22 px), flou, dématriçage, bruit, guilloches, éclairage, reflet, bord de page,
 * inclinaison de ±5°, capteur tourné de 90°. Mesures par tranche de pas : MRZ localisée au bon
 * format, caractères justes au rang 1 et dans les deux premiers, et chaîne complète (`Found`
 * juste en deux images distinctes de la même scène). Aucune fausse MRZ n'est tolérée.
 */
class DeviceRecognitionTest {
    internal class BucketStats {
        var scenes = 0
        var located = 0
        var wrongFormat = 0
        var characters = 0
        var rank1 = 0
        var top2 = 0
        var found = 0
        var falseFound = 0

        /** Décodages d'une seule image aux contrôles justes : champs justes, champs faux. */
        var frameValid = 0
        var frameWrong = 0

        val locationRate: Double get() = located.toDouble() / scenes
        val rank1Rate: Double get() = if (characters == 0) 0.0 else rank1.toDouble() / characters
        val top2Rate: Double get() = if (characters == 0) 0.0 else top2.toDouble() / characters
        val foundRate: Double get() = found.toDouble() / scenes

        override fun toString(): String =
            (
                "localisées %.3f, rang 1 %.4f, top 2 %.4f, bout en bout %.3f, image seule valide %.3f, " +
                    "format faux %d, image seule fausse %d, fausses MRZ %d (%d scènes)"
            ).format(
                locationRate,
                rank1Rate,
                top2Rate,
                foundRate,
                frameValid.toDouble() / scenes,
                wrongFormat,
                frameWrong,
                falseFound,
                scenes,
            )
    }

    internal companion object {
        /** Formats tirés : moitié de passeports, un tiers de cartes, un sixième de TD2. */
        private val FORMATS = listOf(MrzFormat.TD3, MrzFormat.TD1, MrzFormat.TD3, MrzFormat.TD2, MrzFormat.TD3, MrzFormat.TD1)

        fun formatOf(seed: Long): MrzFormat = FORMATS[(seed % FORMATS.size).toInt()]

        fun measure(
            bucket: Pair<Float, Float>,
            scenes: Long,
        ): BucketStats {
            val stats = BucketStats()
            val recognizer = TemplateLineRecognizer()
            for (seed in 1L..scenes) {
                val format = formatOf(seed)
                val scene = DeviceSceneRenderer.randomScene(format, seed, bucket)
                val first = DeviceSceneRenderer.render(scene)
                val second = DeviceSceneRenderer.render(scene.copy(frameSeed = scene.frameSeed + 1))
                val expected = expectedFields(format, first.useful)
                stats.scenes++
                val result = recognizer.recognize(first.frame)
                if (result != null && result.format != format) stats.wrongFormat++
                if (result != null && result.format == format) {
                    stats.located++
                    val decoded = MrzDecoding.decode(result)
                    if (decoded is Decoded.Valid) {
                        if (decoded.fields == expected) stats.frameValid++ else stats.frameWrong++
                    }
                    result.lines.zip(first.useful).forEach { (candidates, line) ->
                        candidates.zip(line.toList()).forEach { (glyph, char) ->
                            stats.characters++
                            if (glyph.ranked[0].first == char) stats.rank1++
                            if (glyph.ranked.take(2).any { it.first == char }) stats.top2++
                        }
                    }
                }
                val scanner = MrzScanner()
                scanner.analyze(first.frame)
                val outcome = scanner.analyze(second.frame)
                if (outcome is MrzScanResult.Found) {
                    if (outcome.fields == expected) stats.found++ else stats.falseFound++
                }
            }
            return stats
        }

        fun expectedFields(
            format: MrzFormat,
            useful: List<String>,
        ): MrzKeyFields =
            when (format) {
                MrzFormat.TD1 -> {
                    MrzKeyFields(format, useful[0].substring(5, 14).trimEnd('<'), useful[1].substring(0, 6), useful[1].substring(8, 14))
                }

                else -> {
                    val line = useful[0]
                    MrzKeyFields(format, line.substring(0, 9).trimEnd('<'), line.substring(13, 19), line.substring(21, 27))
                }
            }

        const val SCENES = 40L
    }

    private fun check(
        index: Int,
        minLocation: Double,
        minRank1: Double,
        minFound: Double,
    ) {
        val bucket = DeviceSceneRenderer.PITCH_BUCKETS[index]
        val stats = measure(bucket, SCENES)
        val message = "pas ${bucket.first}-${bucket.second} px : $stats"
        assertEquals(message, 0, stats.falseFound)
        assertEquals(message, 0, stats.wrongFormat)
        assertTrue(message, stats.locationRate >= minLocation)
        assertTrue(message, stats.rank1Rate >= minRank1)
        assertTrue(message, stats.foundRate >= minFound)
    }

    // Seuils fixés d'après les mesures (mrz/README.md), avec une marge pour 40 scènes.
    @Test
    fun pitch10to12() = check(0, 0.85, 0.97, 0.6)

    @Test
    fun pitch12to14() = check(1, 0.92, 0.99, 0.82)

    @Test
    fun pitch14to16() = check(2, 0.88, 0.99, 0.85)

    @Test
    fun pitch16to18() = check(3, 0.9, 0.995, 0.88)

    @Test
    fun pitch18to22() = check(4, 0.88, 0.995, 0.85)
}
