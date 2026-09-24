package io.github.mgdx.sceau.nfc

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IsoDepTransportTest {
    @Test
    fun `timeout messages are recognised`() {
        assertTrue(IsoDepTransport.isTimeoutMessage("Transceive failed: Timeout"))
        assertTrue(IsoDepTransport.isTimeoutMessage("Operation timed out"))
    }

    @Test
    fun `other io messages are not timeouts`() {
        assertFalse(IsoDepTransport.isTimeoutMessage(null))
        assertFalse(IsoDepTransport.isTimeoutMessage("Transceive failed"))
        assertFalse(IsoDepTransport.isTimeoutMessage("Tag was lost."))
    }
}
