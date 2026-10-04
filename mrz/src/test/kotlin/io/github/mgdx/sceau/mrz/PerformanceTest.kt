package io.github.mgdx.sceau.mrz

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Non-régression grossière du temps de calcul sur JVM (l'objectif de 100 ms par image se
 * mesure sur appareil, au lot G du plan). Le seuil est large pour ne pas dépendre de la machine.
 */
class PerformanceTest {
    @Test
    fun averageTimePerFrame() {
        val recognizer = TemplateLineRecognizer()
        val frames =
            listOf(
                Scene(MrzFormat.TD3, seed = 1, width = 1280, height = 400, angleDegrees = 3.0, noiseSigma = 5.0),
                Scene(MrzFormat.TD1, seed = 2, width = 1280, height = 720, angleDegrees = -4.0, rotationDegrees = 90),
            ).map { SceneRenderer.render(it).frame }
        assertAverage(recognizer, frames)
    }

    /** Recadrage du cadre de visée en 1920 × 1080 portrait (lot I2) : 1080 × 323, pas d'environ 20 px. */
    @Test
    fun averageTimePerDeviceCrop() {
        val recognizer = TemplateLineRecognizer()
        val frames =
            (1L..4L).map { seed ->
                val format = if (seed % 2 == 0L) MrzFormat.TD1 else MrzFormat.TD3
                val chars = if (format == MrzFormat.TD1) 30 else 44
                val scene = DeviceSceneRenderer.randomScene(format, seed, 18f to 22f)
                DeviceSceneRenderer
                    .render(
                        scene.copy(pitch = 1080 * 0.85f / chars, mrzFraction = 0.85f, aspect = 1080f / 323, glare = 0),
                    ).frame
            }
        assertAverage(recognizer, frames)
    }

    private fun assertAverage(
        recognizer: TemplateLineRecognizer,
        frames: List<LumaFrame>,
    ) {
        repeat(WARM_UP) { frames.forEach { assertNotNull(recognizer.recognize(it)) } }
        val start = System.nanoTime()
        repeat(RUNS) { frames.forEach { recognizer.recognize(it) } }
        val averageMillis = (System.nanoTime() - start) / 1e6 / (RUNS * frames.size)
        assertTrue("%.1f ms par image".format(averageMillis), averageMillis < MAX_AVERAGE_MILLIS)
    }

    private companion object {
        const val WARM_UP = 15
        const val RUNS = 20
        const val MAX_AVERAGE_MILLIS = 150.0
    }
}
