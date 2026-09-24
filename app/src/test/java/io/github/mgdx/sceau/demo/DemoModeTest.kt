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
 * Mode démo de l'APK de debug (seule variante dotée de tests unitaires) : la CNIe simulée est lue
 * de bout en bout par `readAndVerify`, sans Android.
 */
class DemoModeTest {
    @Test
    fun `la CNIe simulee du debug est lue comme authentique avec son portrait JPEG 2000`() {
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
}
