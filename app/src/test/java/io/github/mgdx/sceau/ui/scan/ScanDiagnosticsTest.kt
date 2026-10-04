package io.github.mgdx.sceau.ui.scan

import io.github.mgdx.sceau.mrz.MrzFormat
import io.github.mgdx.sceau.mrz.MrzKeyFields
import io.github.mgdx.sceau.mrz.MrzScanResult
import org.junit.Assert.assertEquals
import org.junit.Test

/** Diagnostic de l'écran de scan (APK de debug, lot I2 de D32) : fenêtre glissante. */
class ScanDiagnosticsTest {
    private fun stats(
        outcome: ScanOutcome,
        millis: Long = 20,
        crop: Int = 1000,
    ) = FrameStats(1920, 1080, crop, 350, millis * 1_000_000, outcome)

    @Test
    fun `empty window shows zeros`() {
        val snapshot = ScanDiagnostics().snapshot()
        assertEquals(0f, snapshot.framesPerSecond)
        assertEquals(0f, snapshot.meanAnalysisMillis)
        assertEquals(0, snapshot.nothingFound + snapshot.unstable + snapshot.unsupported + snapshot.found)
    }

    @Test
    fun `rate, mean time and last sizes over the window`() {
        val diagnostics = ScanDiagnostics(windowSize = 5)
        // Une image toutes les 100 ms : 10 images par seconde.
        listOf(10L, 20L, 30L).forEachIndexed { i, ms ->
            diagnostics.add(stats(ScanOutcome.NOTHING_FOUND, ms, crop = 900 + i), nowNanos = i * 100_000_000L)
        }
        val snapshot = diagnostics.snapshot()
        assertEquals(10f, snapshot.framesPerSecond, 0.01f)
        assertEquals(20f, snapshot.meanAnalysisMillis, 0.01f)
        assertEquals(1920, snapshot.imageWidth)
        assertEquals(1080, snapshot.imageHeight)
        assertEquals(902, snapshot.cropWidth)
        assertEquals(350, snapshot.cropHeight)
    }

    @Test
    fun `only the last frames are counted`() {
        val diagnostics = ScanDiagnostics(windowSize = 4)
        val outcomes =
            listOf(
                ScanOutcome.FOUND,
                ScanOutcome.UNSUPPORTED,
                ScanOutcome.NOTHING_FOUND,
                ScanOutcome.UNSTABLE,
                ScanOutcome.UNSTABLE,
                ScanOutcome.NOTHING_FOUND,
            )
        var snapshot: ScanDiagnosticsSnapshot? = null
        outcomes.forEachIndexed { i, outcome ->
            // Durées 0, 10, ... 50 ms ; les quatre dernières font 20, 30, 40, 50.
            snapshot = diagnostics.add(stats(outcome, millis = i * 10L), nowNanos = i * 50_000_000L)
        }
        val last = checkNotNull(snapshot)
        assertEquals(2, last.nothingFound)
        assertEquals(2, last.unstable)
        assertEquals(0, last.unsupported)
        assertEquals(0, last.found)
        assertEquals(35f, last.meanAnalysisMillis, 0.01f)
        // Quatre images en 150 ms.
        assertEquals(20f, last.framesPerSecond, 0.01f)
    }

    @Test
    fun `default window is 30 frames and reset forgets everything`() {
        val diagnostics = ScanDiagnostics()
        repeat(45) { diagnostics.add(stats(ScanOutcome.UNSTABLE), nowNanos = it * 1_000_000L) }
        assertEquals(30, diagnostics.snapshot().unstable)
        diagnostics.reset()
        val snapshot = diagnostics.snapshot()
        assertEquals(0, snapshot.unstable)
        assertEquals(0, snapshot.imageWidth)
        assertEquals(0f, snapshot.framesPerSecond)
    }

    @Test
    fun `outcomes carry no recognised content`() {
        assertEquals(ScanOutcome.NOTHING_FOUND, ScanOutcome.of(MrzScanResult.NothingFound))
        assertEquals(ScanOutcome.UNSTABLE, ScanOutcome.of(MrzScanResult.Unstable))
        assertEquals(ScanOutcome.UNSUPPORTED, ScanOutcome.of(MrzScanResult.UnsupportedDocumentNumber))
        val found = MrzScanResult.Found(MrzKeyFields(MrzFormat.TD3, "L898902C3", "740812", "120415"))
        assertEquals(ScanOutcome.FOUND, ScanOutcome.of(found))
    }

    @Test
    fun `analyzer reports sizes and outcome of each frame`() {
        val reported = mutableListOf<FrameStats>()
        val analyzer =
            MrzFrameAnalyzer(
                analyzeFrame = { MrzScanResult.Unstable },
                resetScanner = {},
                viewfinder =
                    java.util.concurrent.atomic
                        .AtomicReference(ViewfinderGeometry(8, 4, 0f, 0f, 1f, 1f)),
                onStats = { reported += it },
            ) {}
        analyzer.processFrame(8, 4, 0, PixelRect(0, 0, 8, 4), java.nio.ByteBuffer.wrap(ByteArray(32)), 8, 1)
        val frame = reported.single()
        assertEquals(8, frame.imageWidth)
        assertEquals(4, frame.imageHeight)
        assertEquals(8, frame.cropWidth)
        assertEquals(4, frame.cropHeight)
        assertEquals(ScanOutcome.UNSTABLE, frame.outcome)
    }
}
