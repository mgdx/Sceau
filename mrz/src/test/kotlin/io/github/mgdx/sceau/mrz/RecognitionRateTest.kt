package io.github.mgdx.sceau.mrz

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * Taux de reconnaissance sur des MRZ de synthèse rendues avec la police OCR-B et dégradées
 * (flou, bruit, inclinaison, perspective, éclairage, reflet, contraste, graisse, échelle,
 * rotation du capteur). Seuls les caractères de rang 1 et 2 sont comparés au texte attendu :
 * le décodeur des champs (lot A) n'intervient pas.
 */
class RecognitionRateTest {
    private val recognizer = TemplateLineRecognizer()

    internal class Stats {
        var scenes = 0
        var detected = 0
        var wrongFormat = 0
        var characters = 0
        var rank1 = 0
        var top2 = 0

        val detectionRate: Double get() = detected.toDouble() / scenes
        val rank1Rate: Double get() = rank1.toDouble() / characters
        val top2Rate: Double get() = top2.toDouble() / characters

        override fun toString(): String =
            "détectées $detected/$scenes, format faux $wrongFormat, rang 1 %.4f, top 2 %.4f sur $characters caractères"
                .format(rank1Rate, top2Rate)
    }

    private fun measure(
        stats: Stats,
        rendered: RenderedScene,
    ): RecognizedMrz? {
        stats.scenes++
        val result = recognizer.recognize(rendered.frame) ?: return null
        stats.detected++
        if (result.format != rendered.scene.format) {
            stats.wrongFormat++
            return result
        }
        result.lines.zip(rendered.useful).forEach { (candidates, expected) ->
            assertEquals(expected.length, candidates.size)
            candidates.zip(expected.toList()).forEach { (glyph, char) ->
                stats.characters++
                if (glyph.ranked[0].first == char) stats.rank1++
                if (glyph.ranked.take(2).any { it.first == char }) stats.top2++
            }
        }
        return result
    }

    /** Scène tirée au hasard, avec plusieurs dégradations combinées. */
    internal fun randomScene(
        format: MrzFormat,
        seed: Long,
    ): Scene {
        val random = Random(seed * 31 + format.ordinal)
        val width = intArrayOf(960, 1280, 1600)[random.nextInt(3)]
        val ratio = if (format == MrzFormat.TD1) 0.42 + 0.15 * random.nextDouble() else 0.3 + 0.12 * random.nextDouble()
        val lowContrast = random.nextInt(4) == 0
        return Scene(
            format = format,
            seed = seed,
            width = width,
            height = (width * ratio).toInt(),
            mrzWidth = 0.8f + 0.15f * random.nextFloat(),
            mrzCenter = 0.5f + 0.15f * random.nextFloat(),
            angleDegrees = -8 + 16 * random.nextDouble(),
            blurRadius = random.nextInt(3),
            noiseSigma = 12 * random.nextDouble(),
            paper = if (lowContrast) 150 + random.nextInt(50) else 210 + random.nextInt(40),
            inkLevel = if (lowContrast) 80 + random.nextInt(30) else 20 + random.nextInt(40),
            shading = if (random.nextBoolean()) 0.5 + 0.5 * random.nextDouble() else 1.0,
            glare = if (random.nextInt(4) == 0) 80 + random.nextInt(80) else 0,
            keystone = if (random.nextInt(3) == 0) 0.93 + 0.07 * random.nextDouble() else 1.0,
            bold = if (random.nextInt(3) == 0) 0.03f * random.nextFloat() else 0f,
            rotationDegrees = intArrayOf(0, 90, 180, 270)[random.nextInt(4)],
            rowPadding = if (random.nextBoolean()) random.nextInt(32) else 0,
        )
    }

    private fun campaign(format: MrzFormat): Stats {
        val stats = Stats()
        for (seed in 1L..SCENES) measure(stats, SceneRenderer.render(randomScene(format, seed)))
        return stats
    }

    private fun assertRates(
        format: MrzFormat,
        stats: Stats,
    ) {
        val message = "$format : $stats"
        assertEquals(message, 0, stats.wrongFormat)
        assertTrue(message, stats.detectionRate >= MIN_DETECTION)
        assertTrue(message, stats.rank1Rate >= MIN_RANK1)
        assertTrue(message, stats.top2Rate >= MIN_TOP2)
    }

    @Test
    fun td3() = assertRates(MrzFormat.TD3, campaign(MrzFormat.TD3))

    @Test
    fun td2() = assertRates(MrzFormat.TD2, campaign(MrzFormat.TD2))

    @Test
    fun td1() = assertRates(MrzFormat.TD1, campaign(MrzFormat.TD1))

    /** Chaque dégradation seule, à un niveau marqué : la MRZ doit être trouvée et lue. */
    @Test
    fun eachDegradationAlone() {
        for (format in MrzFormat.entries) {
            val height = if (format == MrzFormat.TD1) 600 else 420
            val base = Scene(format, seed = 7L + format.ordinal, height = height)
            val variants =
                mapOf(
                    "nette" to base,
                    "flou" to base.copy(blurRadius = 2),
                    "bruit" to base.copy(noiseSigma = 15.0),
                    "rotation +8°" to base.copy(angleDegrees = 8.0),
                    "rotation -8°" to base.copy(angleDegrees = -8.0),
                    "perspective" to base.copy(keystone = 0.92),
                    "éclairage" to base.copy(shading = 0.45),
                    "reflet saturé" to base.copy(glare = 160),
                    "faible contraste" to base.copy(paper = 170, inkLevel = 115),
                    "graisse" to base.copy(bold = 0.04f),
                    "petite échelle" to base.copy(width = 800, height = height * 800 / 1280, mrzWidth = 0.8f),
                    "grande échelle" to base.copy(width = 2400, height = height * 2400 / 1280),
                    "capteur 90°" to base.copy(rotationDegrees = 90),
                    "capteur 180°" to base.copy(rotationDegrees = 180),
                    "capteur 270°" to base.copy(rotationDegrees = 270, rowPadding = 24),
                )
            for ((name, scene) in variants) {
                val stats = Stats()
                val result = measure(stats, SceneRenderer.render(scene))
                assertNotNull("$format $name : MRZ non trouvée", result)
                assertEquals("$format $name : $stats", 0, stats.wrongFormat)
                assertTrue("$format $name : $stats", stats.rank1Rate >= MIN_RANK1_SINGLE)
                assertTrue("$format $name : $stats", stats.top2Rate >= MIN_TOP2_SINGLE)
            }
        }
    }

    /** La ligne du nom n'est jamais rendue : seules les lignes utiles, de la bonne longueur. */
    @Test
    fun nameLineIsNeverReturned() {
        for (format in MrzFormat.entries) {
            val rendered = SceneRenderer.render(Scene(format, seed = 11, height = if (format == MrzFormat.TD1) 600 else 400))
            val result = found(recognizer.recognize(rendered.frame))
            val expectedLines = if (format == MrzFormat.TD1) 2 else 1
            assertEquals(expectedLines, result.lines.size)
            val text = result.lines.map { line -> line.joinToString("") { it.ranked[0].first.toString() } }
            assertEquals(rendered.useful, text)
            val name = if (format == MrzFormat.TD1) rendered.lines[2] else rendered.lines[0]
            assertTrue(name !in text)
        }
    }

    @Test
    fun candidatesAreRankedScoresInUnitInterval() {
        val rendered = SceneRenderer.render(Scene(MrzFormat.TD3, seed = 3, noiseSigma = 8.0))
        val result = found(recognizer.recognize(rendered.frame))
        for (glyph in result.lines.flatten()) {
            assertTrue(glyph.ranked.size >= 2)
            assertEquals(
                glyph.ranked.size,
                glyph.ranked
                    .map { it.first }
                    .toSet()
                    .size,
            )
            assertTrue(glyph.ranked.all { it.first in OcrbTemplates.CLASSES && it.second in 0f..1f })
            assertTrue(glyph.ranked.zipWithNext().all { (a, b) -> a.second >= b.second })
        }
    }

    private fun found(value: RecognizedMrz?): RecognizedMrz {
        assertNotNull("MRZ non trouvée", value)
        return value!!
    }

    private companion object {
        const val SCENES = 40L

        // Seuils calibrés sur les mesures (voir mrz/README.md), avec une marge.
        const val MIN_DETECTION = 0.95
        const val MIN_RANK1 = 0.95
        const val MIN_TOP2 = 0.99
        const val MIN_RANK1_SINGLE = 0.9
        const val MIN_TOP2_SINGLE = 0.97
    }
}
