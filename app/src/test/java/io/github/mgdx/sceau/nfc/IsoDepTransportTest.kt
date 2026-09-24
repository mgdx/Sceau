package io.github.mgdx.sceau.nfc

import io.github.mgdx.sceau.core.SceauException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class IsoDepTransportTest {
    /** General Authenticate PACE de 60 octets : CLA 10, INS 86. */
    private val paceCommand =
        ByteArray(58).also {
            it[0] = 0x10
            it[1] = 0x86.toByte()
        } + byteArrayOf(0x00, 0x00)

    private fun classify(
        message: String?,
        tagLost: Boolean = false,
        connected: Boolean = true,
        closed: Boolean = false,
        apdu: ByteArray = paceCommand,
    ) = IsoDepTransport.classifyIoFailure(message, tagLost, connected, closed, apdu)

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

    @Test
    fun `transceive failed with the card still present is a technical error, not a lost connection`() {
        val error = classify("Transceive failed")
        assertTrue(error is SceauException.Unexpected)
        assertEquals("UNEXPECTED-IO-TRANSCEIVE_FAILED-INS86-L60", error.code)
    }

    @Test
    fun `too long apdu has its own code and rounded length`() {
        val apdu = ByteArray(263).also { it[1] = 0xB0.toByte() }
        assertEquals("UNEXPECTED-IO-TOO_LONG-INSB0-L260", classify("Transceive length exceeds supported maximum", apdu = apdu).code)
    }

    @Test
    fun `unknown messages never leak into the code`() {
        val error = classify("secret 0123456789ABCDEF")
        assertEquals("UNEXPECTED-IO-OTHER-INS86-L60", error.code)
        assertFalse(error.code.contains("secret"))
        assertEquals("UNEXPECTED-IO-NO_MESSAGE-INS86-L60", classify(null).code)
        assertEquals("UNEXPECTED-IO-SERVICE_DIED-INS86-L60", classify("NFC service died").code)
    }

    @Test
    fun `lost card, disconnected link or requested close give ConnectionLost`() {
        assertTrue(classify("Transceive failed", tagLost = true) is SceauException.ConnectionLost)
        assertTrue(classify("Transceive failed", connected = false) is SceauException.ConnectionLost)
        assertTrue(classify("Transceive failed", closed = true) is SceauException.ConnectionLost)
    }

    @Test
    fun `timeout message on a present card gives Timeout`() {
        val cause = IOException("Transceive failed: timeout")
        val error = IsoDepTransport.classifyIoFailure(cause.message, false, true, false, paceCommand, cause)
        assertTrue(error is SceauException.Timeout)
        assertSame(cause, error.cause)
    }

    @Test
    fun `technical detail survives an empty apdu`() {
        assertEquals("STATE-INSNA-L0", IsoDepTransport.technicalDetail("STATE", ByteArray(0)))
        assertEquals("IO-OTHER-INS84-L10", IsoDepTransport.technicalDetail("IO-OTHER", ByteArray(5).also { it[1] = 0x84.toByte() }))
    }
}
