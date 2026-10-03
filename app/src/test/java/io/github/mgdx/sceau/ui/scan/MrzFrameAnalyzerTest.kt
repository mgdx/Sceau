package io.github.mgdx.sceau.ui.scan

import io.github.mgdx.sceau.mrz.LumaFrame
import io.github.mgdx.sceau.mrz.MrzFormat
import io.github.mgdx.sceau.mrz.MrzKeyFields
import io.github.mgdx.sceau.mrz.MrzScanResult
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReference

class MrzFrameAnalyzerTest {
    private val width = 8
    private val height = 4
    private val plane = ByteBuffer.wrap(ByteArray(width * height) { (it + 1).toByte() })

    /** Cadre couvrant toute la vue, vue au format de l'image : la zone est l'image entière. */
    private val wholeImage = AtomicReference<ViewfinderGeometry?>(ViewfinderGeometry(width, height, 0f, 0f, 1f, 1f))

    private fun process(analyzer: MrzFrameAnalyzer) =
        analyzer.processFrame(width, height, 0, PixelRect(0, 0, width, height), plane, width, 1)

    @Test
    fun `frame is copied, analysed, then wiped`() {
        var seen: ByteArray? = null
        var seenData: LumaFrame? = null
        val results = mutableListOf<MrzScanResult>()
        val analyzer =
            MrzFrameAnalyzer(
                analyzeFrame = { frame ->
                    seen = frame.data.copyOf()
                    seenData = frame
                    MrzScanResult.NothingFound
                },
                resetScanner = {},
                viewfinder = wholeImage,
            ) { results += it }

        process(analyzer)

        assertArrayEquals(ByteArray(width * height) { (it + 1).toByte() }, seen)
        val frame = seenData!!
        assertEquals(width, frame.width)
        assertEquals(height, frame.height)
        assertEquals(width, frame.rowStride)
        assertTrue("tableau remis à zéro après l'analyse", frame.data.all { it == 0.toByte() })
        assertEquals(listOf(MrzScanResult.NothingFound), results)
    }

    @Test
    fun `buffer is wiped even if the scanner throws`() {
        var data: ByteArray? = null
        val analyzer =
            MrzFrameAnalyzer(
                analyzeFrame = { frame ->
                    data = frame.data
                    throw IllegalStateException()
                },
                resetScanner = {},
                viewfinder = wholeImage,
            ) { }
        try {
            process(analyzer)
        } catch (_: IllegalStateException) {
            // attendu
        }
        assertTrue(data!!.all { it == 0.toByte() })
    }

    @Test
    fun `found is delivered once and analysis stops`() {
        val fields = MrzKeyFields(MrzFormat.TD3, "AB1234567", "800101", "300101")
        var calls = 0
        val results = mutableListOf<MrzScanResult>()
        val analyzer =
            MrzFrameAnalyzer(
                analyzeFrame = {
                    calls++
                    MrzScanResult.Found(fields)
                },
                resetScanner = {},
                viewfinder = wholeImage,
            ) { results += it }

        process(analyzer)
        process(analyzer)

        assertEquals(1, calls)
        assertEquals(1, results.size)
    }

    @Test
    fun `nothing is analysed before the viewfinder is known`() {
        var calls = 0
        val analyzer =
            MrzFrameAnalyzer(
                analyzeFrame = {
                    calls++
                    MrzScanResult.NothingFound
                },
                resetScanner = {},
                viewfinder = AtomicReference(null),
            ) { }
        process(analyzer)
        assertEquals(0, calls)
    }

    @Test
    fun `reset and release forget the scanner state`() {
        var resets = 0
        val analyzer =
            MrzFrameAnalyzer(
                analyzeFrame = { MrzScanResult.Unstable },
                resetScanner = { resets++ },
                viewfinder = wholeImage,
            ) { }
        process(analyzer)
        analyzer.reset()
        analyzer.release()
        assertEquals(2, resets)
    }
}
