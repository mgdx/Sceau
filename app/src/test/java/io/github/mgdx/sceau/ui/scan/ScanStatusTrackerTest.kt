package io.github.mgdx.sceau.ui.scan

import io.github.mgdx.sceau.mrz.MrzScanResult
import org.junit.Assert.assertEquals
import org.junit.Test

class ScanStatusTrackerTest {
    private val start = 100_000L

    @Test
    fun `searching until an MRZ is seen`() {
        val tracker = ScanStatusTracker()
        assertEquals(ScanStatus.SEARCHING, tracker.onResult(MrzScanResult.NothingFound, start))
        assertEquals(ScanStatus.SEEN, tracker.onResult(MrzScanResult.Unstable, start + 10))
    }

    @Test
    fun `an isolated empty frame does not hide a seen MRZ`() {
        val tracker = ScanStatusTracker()
        tracker.onResult(MrzScanResult.Unstable, start)
        assertEquals(ScanStatus.SEEN, tracker.onResult(MrzScanResult.NothingFound, start + 500))
        assertEquals(
            ScanStatus.SEARCHING,
            tracker.onResult(MrzScanResult.NothingFound, start + ScanStatusTracker.HOLD_MILLIS),
        )
    }

    @Test
    fun `unsupported document number is reported and held`() {
        val tracker = ScanStatusTracker()
        assertEquals(ScanStatus.UNSUPPORTED, tracker.onResult(MrzScanResult.UnsupportedDocumentNumber, start))
        assertEquals(ScanStatus.UNSUPPORTED, tracker.onResult(MrzScanResult.NothingFound, start + 100))
    }

    @Test
    fun `reset returns to searching`() {
        val tracker = ScanStatusTracker()
        tracker.onResult(MrzScanResult.Unstable, start)
        tracker.reset()
        assertEquals(ScanStatus.SEARCHING, tracker.status)
        assertEquals(ScanStatus.SEARCHING, tracker.onResult(MrzScanResult.NothingFound, start + 10))
    }
}
