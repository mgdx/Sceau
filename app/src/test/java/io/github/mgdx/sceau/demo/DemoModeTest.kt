package io.github.mgdx.sceau.demo

import io.github.mgdx.sceau.core.AccessKey
import io.github.mgdx.sceau.core.model.ImageFormat
import io.github.mgdx.sceau.core.readAndVerify
import io.github.mgdx.sceau.core.report.Verdict
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Demo mode of the debug APK (the only variant with unit tests): the simulated CNIe is read end to
 * end by `readAndVerify`, without Android.
 */
class DemoModeTest {
    @Test
    fun `the simulated debug CNIe reads as authentic with its JPEG 2000 portrait`() {
        assertTrue(DemoMode.isAvailable)
        val card = checkNotNull(DemoMode.newSimulatedCnie())
        assertTrue(card.key is AccessKey.Can)

        val report = runBlocking { readAndVerify(card.transport, card.key, card.trustStore) { } }

        assertEquals(Verdict.AUTHENTIC, report.verdict)
        val portrait = checkNotNull(report.document.portrait)
        assertEquals(ImageFormat.JPEG2000, portrait.format)
        assertTrue(portrait.bytes.isNotEmpty())
        report.wipe()
    }

    @Test
    fun `the simulated CNIe carries a JPEG handwritten signature (DG7, D37)`() {
        val card = checkNotNull(DemoMode.newSimulatedCnie())

        val report = runBlocking { readAndVerify(card.transport, card.key, card.trustStore) { } }

        val signature = checkNotNull(report.document.signature)
        assertEquals(ImageFormat.JPEG, signature.format)
        assertTrue(signature.bytes.isNotEmpty())
        report.wipe()
        assertTrue(signature.bytes.all { it == 0.toByte() })
    }
}
