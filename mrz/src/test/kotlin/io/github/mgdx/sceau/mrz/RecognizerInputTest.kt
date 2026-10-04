package io.github.mgdx.sceau.mrz

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Random

/** Entrées sans MRZ ou invalides : `null`, jamais d'exception. */
class RecognizerInputTest {
    private val recognizer = TemplateLineRecognizer()

    private fun frame(
        width: Int,
        height: Int,
        fill: (Int, Int) -> Int,
    ): LumaFrame {
        val data = ByteArray(width * height)
        for (y in 0 until height) for (x in 0 until width) data[y * width + x] = fill(x, y).toByte()
        return LumaFrame(data, width, height, width, 0)
    }

    @Test
    fun blankAndUniformImagesGiveNull() {
        for (level in intArrayOf(0, 1, 128, 254, 255)) {
            assertNull(recognizer.recognize(frame(1280, 400) { _, _ -> level }))
        }
        assertNull(recognizer.recognize(frame(1280, 400) { x, y -> (x + y) * 255 / 1680 }))
    }

    @Test
    fun pureNoiseGivesNull() {
        val random = Random(1)
        for (sigma in intArrayOf(5, 30, 128)) {
            assertNull(recognizer.recognize(frame(1280, 400) { _, _ -> (128 + random.nextGaussian() * sigma).toInt().coerceIn(0, 255) }))
        }
        assertNull(recognizer.recognize(frame(1280, 720) { _, _ -> random.nextInt(256) }))
    }

    /** Aucune détection sur des pages sans MRZ (texte, aplat, dégradations). */
    @Test
    fun pagesWithoutMrzGiveNull() {
        val rates = RecognitionRateTest()
        var detections = 0
        for (seed in 1L..PAGES) {
            val format = MrzFormat.entries[(seed % 3).toInt()]
            val scene = rates.randomScene(format, seed + 10_000).copy(withMrz = false)
            if (recognizer.recognize(SceneRenderer.render(scene).frame) != null) detections++
        }
        assertEquals("pages sans MRZ détectées", 0, detections)
    }

    @Test
    fun invalidDimensionsGiveNull() {
        val data = ByteArray(1280 * 400)
        val invalid =
            listOf(
                LumaFrame(data, 0, 400, 1280, 0),
                LumaFrame(data, 1280, 0, 1280, 0),
                LumaFrame(data, -1, 400, 1280, 0),
                LumaFrame(data, 1280, -400, 1280, 0),
                LumaFrame(data, 4097, 100, 4097, 0),
                LumaFrame(data, 100, 4097, 100, 0),
                LumaFrame(data, Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE, 0),
                LumaFrame(data, 1280, 400, 1279, 0),
                LumaFrame(data, 1280, 400, 1281, 0),
                LumaFrame(data, 1280, 400, Int.MAX_VALUE, 0),
                LumaFrame(data, 1280, 400, -1, 0),
                LumaFrame(data, 1280, 400, 1280, 45),
                LumaFrame(data, 1280, 400, 1280, -90),
                LumaFrame(data, 1280, 400, 1280, 360),
                LumaFrame(ByteArray(0), 1280, 400, 1280, 0),
                LumaFrame(ByteArray(1280 * 400 - 1), 1280, 400, 1280, 0),
            )
        for (frame in invalid) assertNull(recognizer.recognize(frame))
    }

    @Test
    fun tinyAndExtremeShapesGiveNull() {
        for ((w, h) in listOf(1 to 1, 1 to 4096, 4096 to 1, 2 to 2, 119 to 400, 4096 to 15, 16 to 4096)) {
            assertNull(recognizer.recognize(LumaFrame(ByteArray(w * h) { (it * 31).toByte() }, w, h, w, 0)))
        }
        assertNull(recognizer.recognize(LumaFrame(ByteArray(4096 * 4096) { (it * 7).toByte() }, 4096, 4096, 4096, 270)))
    }

    /** Le dernier rang n'a besoin que de `width` octets : un tampon sans remplissage final est accepté. */
    @Test
    fun lastRowWithoutPaddingIsAccepted() {
        val rendered = SceneRenderer.render(Scene(MrzFormat.TD3, seed = 5, rowPadding = 16))
        val frame = rendered.frame
        val trimmed = frame.data.copyOf((frame.height - 1) * frame.rowStride + frame.width)
        val result = recognizer.recognize(LumaFrame(trimmed, frame.width, frame.height, frame.rowStride, 0))
        assertNotNull(result)
    }

    /** L'analyse ne modifie pas l'image de l'appelant, et deux appels donnent le même résultat. */
    @Test
    fun inputIsUntouchedAndResultIsRepeatable() {
        val frame = SceneRenderer.render(Scene(MrzFormat.TD1, seed = 6, height = 600, angleDegrees = 4.0)).frame
        val copy = frame.data.copyOf()
        val first = recognizer.recognize(frame)
        assertNull(recognizer.recognize(frame(1280, 400) { _, _ -> 200 }))
        val second = recognizer.recognize(frame)
        assertEquals(copy.toList(), frame.data.toList())
        assertEquals(text(first), text(second))
    }

    private fun text(result: RecognizedMrz?): List<String>? =
        result?.lines?.map { line -> line.joinToString("") { "${it.ranked[0].first}${it.ranked[0].second}" } }

    private companion object {
        const val PAGES = 120L
    }
}
