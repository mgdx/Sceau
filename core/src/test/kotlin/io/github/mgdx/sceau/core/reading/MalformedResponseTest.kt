package io.github.mgdx.sceau.core.reading

import io.github.mgdx.sceau.core.CardTransport
import io.github.mgdx.sceau.core.reading.ScriptedTransport.Companion.hexToBytes
import net.sf.scuba.smartcards.CardServiceException
import net.sf.scuba.smartcards.CommandAPDU
import org.jmrtd.protocol.DESedeSecureMessagingWrapper
import org.jmrtd.protocol.SecureMessagingAPDUSender
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import javax.crypto.spec.SecretKeySpec

/**
 * Audit V36: a response in which a secure messaging (or PACE) object declares more bytes
 * than it contains is rejected by `TransportCardService` before JMRTD, which would
 * otherwise allocate the declared length (`OutOfMemoryError`) before checking the MAC.
 */
class MalformedResponseTest {
    /** Transport that always answers [response]. */
    private class FixedTransport(
        private val response: ByteArray,
    ) : CardTransport {
        override val maxTransceiveLength: Int = 261
        override var timeoutMillis: Int = 1000

        override fun transceive(apdu: ByteArray): ByteArray = response

        override fun close() {}
    }

    private fun wrapper(): DESedeSecureMessagingWrapper {
        Crypto.ensureInstalled()
        return DESedeSecureMessagingWrapper(SecretKeySpec(ByteArray(16), "DESede"), SecretKeySpec(ByteArray(16), "DESede"), 0L)
    }

    /** READ BINARY wrapped by JMRTD, response [response] passed through Sceau's service. */
    private fun sendSecured(response: ByteArray) {
        val service = TransportCardService(FixedTransport(response)).apply { open() }
        SecureMessagingAPDUSender(service).transmit(wrapper(), CommandAPDU(0x00, 0xB0, 0x00, 0x00, 8))
    }

    private fun assertRejected(response: ByteArray) {
        try {
            sendSecured(response)
            fail("CardServiceException expected")
        } catch (e: CardServiceException) {
            // Ordinary chip error, converted through the usual path; never an Error.
        }
    }

    @Test
    fun do87Declaring2Gb_CardServiceExceptionAndNoError() {
        assertRejected(("87847FFFFFFF01AABBCC" + "99029000" + "8E080102030405060708" + "9000").hexToBytes())
    }

    @Test
    fun do87HiddenInAnotherTlvValue_Rejected() {
        // 5-byte TLV 02 then FF: well formed for a BER reader (BerStructure), but JMRTD
        // reads byte by byte and finds a 2 GB DO'87 in it.
        val hidden = "0205" + "87847FFFFF" + "FF" + "01AABB" + "8E080102030405060708" + "9000"
        assertFalse(ResponseShape.isWellFormed(0x0C, 0xB0, hidden.hexToBytes()))
        assertRejected(hidden.hexToBytes())
    }

    @Test
    fun do85TruncatedOrWithoutPadding_Rejected() {
        assertFalse(ResponseShape.isWellFormed(0x0C, 0xB0, "8510AABB9000".hexToBytes()))
        assertFalse(ResponseShape.isWellFormed(0x0C, 0xB0, "8700990290008E0801020304050607089000".hexToBytes()))
        assertFalse(ResponseShape.isWellFormed(0x0C, 0xB0, "87".hexToBytes() + "9000".hexToBytes()))
    }

    @Test
    fun wellFormedSecuredResponse_Accepted() {
        val response = "8709" + "01" + "1122334455667788" + "990290008E08" + "0102030405060708" + "9000"
        assertTrue(ResponseShape.isWellFormed(0x0C, 0xB0, response.hexToBytes()))
        // Long length form (224-byte DO'87 from a 223-byte block).
        val long = "8781E1" + "01" + "00".repeat(0xE0) + "990290008E08" + "0102030405060708" + "9000"
        assertTrue(ResponseShape.isWellFormed(0x0C, 0xB0, long.hexToBytes()))
        // SW only (error without DO): nothing to check.
        assertTrue(ResponseShape.isWellFormed(0x0C, 0xB0, "6982".hexToBytes()))
    }

    @Test
    fun plainResponses_NotInterpreted() {
        // GET CHALLENGE challenge starting with 87: random data, accepted as is.
        val challenge = "87FF7F0102030405" + "9000"
        val service = TransportCardService(FixedTransport(challenge.hexToBytes())).apply { open() }
        val response = service.transmit(CommandAPDU(0x00, 0x84, 0x00, 0x00, 8))
        assertArrayEquals(challenge.hexToBytes(), response.bytes)
    }

    @Test
    fun paceGeneralAuthenticate_LengthsChecked() {
        // PACE step 4: token (86) declaring 2 GB in the 7C response.
        assertFalse(ResponseShape.isWellFormed(0x00, 0x86, "7C0786847FFFFFFF00 9000".hexToBytes()))
        assertTrue(ResponseShape.isWellFormed(0x00, 0x86, "7C0A86080102030405060708 9000".hexToBytes()))
        assertTrue(ResponseShape.isWellFormed(0x10, 0x86, "7C00 9000".hexToBytes()))
    }

    @Test
    fun rawSize_Capped() {
        val tooLarge = ByteArray(ResponseShape.MAX_RESPONSE_DATA + 1) + "9000".hexToBytes()
        assertFalse(ResponseShape.isWellFormed(0x00, 0xB0, tooLarge))
        assertTrue(ResponseShape.isWellFormed(0x00, 0xB0, ByteArray(ResponseShape.MAX_RESPONSE_DATA) + "9000".hexToBytes()))
        assertFalse(ResponseShape.isWellFormed(0x00, 0xB0, "90".hexToBytes()))
        val service = TransportCardService(FixedTransport(tooLarge)).apply { open() }
        try {
            service.transmit(CommandAPDU(0x00, 0xB0, 0x00, 0x00, 8))
            fail("CardServiceException expected")
        } catch (e: CardServiceException) {
            // Response rejected without being handed to JMRTD.
        }
    }
}
