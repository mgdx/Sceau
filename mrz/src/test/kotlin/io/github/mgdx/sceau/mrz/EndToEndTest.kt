package io.github.mgdx.sceau.mrz

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Chaîne complète de [MrzScanner] (localisation, classification, décodage, stabilisation) sur
 * des images de synthèse dégradées : une MRZ trouvée doit être juste, et la plupart doivent
 * l'être en deux images.
 */
class EndToEndTest {
    @Test
    fun `scenes degradees donnent la bonne MRZ ou rien, jamais une fausse`() {
        for (format in MrzFormat.entries) {
            var found = 0
            for (seed in 1L..SCENES) {
                val rendered = SceneRenderer.render(RecognitionRateTest().randomScene(format, seed))
                val scanner = MrzScanner()
                scanner.analyze(rendered.frame)
                val result = scanner.analyze(rendered.frame)
                if (result is MrzScanResult.Found) {
                    found++
                    assertEquals("$format, scène $seed", expected(format, rendered.useful), result.fields)
                }
            }
            assertTrue("$format : $found / $SCENES trouvées", found >= SCENES * MIN_FOUND_RATE)
        }
    }

    @Test
    fun `une seule image ne suffit pas`() {
        val rendered = SceneRenderer.render(Scene(MrzFormat.TD3, seed = 7))
        assertEquals(MrzScanResult.Unstable, MrzScanner().analyze(rendered.frame))
    }

    private fun expected(
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

    private companion object {
        const val SCENES = 60L

        // Mesuré le 2026-10-04 sur ces scènes très dégradées : TD3 53/60 en deux images. En
        // direct, la caméra fournit des dizaines d'images : une scène manquée ici n'est qu'un
        // délai. Aucune fausse MRZ n'est tolérée.
        const val MIN_FOUND_RATE = 0.85
    }
}
