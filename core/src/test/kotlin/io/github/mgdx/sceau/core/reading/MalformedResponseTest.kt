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
 * Audit V36 : une réponse dont un objet de messagerie sécurisée (ou de PACE) annonce plus
 * d'octets qu'elle n'en contient est refusée par `TransportCardService` avant JMRTD, qui
 * allouerait sinon la longueur annoncée (`OutOfMemoryError`) avant de vérifier le MAC.
 */
class MalformedResponseTest {
    /** Transport qui répond toujours [response]. */
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

    /** READ BINARY emballé par JMRTD, réponse [response] passée par le service de Sceau. */
    private fun sendSecured(response: ByteArray) {
        val service = TransportCardService(FixedTransport(response)).apply { open() }
        SecureMessagingAPDUSender(service).transmit(wrapper(), CommandAPDU(0x00, 0xB0, 0x00, 0x00, 8))
    }

    private fun assertRejected(response: ByteArray) {
        try {
            sendSecured(response)
            fail("CardServiceException attendue")
        } catch (e: CardServiceException) {
            // Erreur de la puce ordinaire, convertie par le chemin habituel ; jamais une Error.
        }
    }

    @Test
    fun do87Annoncant2Go_CardServiceExceptionEtAucuneError() {
        assertRejected(("87847FFFFFFF01AABBCC" + "99029000" + "8E080102030405060708" + "9000").hexToBytes())
    }

    @Test
    fun do87CacheDansLaValeurDUnAutreTlv_Refuse() {
        // TLV 02 de 5 octets puis FF : bien formé pour un lecteur BER (BerStructure), mais JMRTD
        // lit octet par octet et y trouve un DO'87 de 2 Go.
        val hidden = "0205" + "87847FFFFF" + "FF" + "01AABB" + "8E080102030405060708" + "9000"
        assertFalse(ResponseShape.isWellFormed(0x0C, 0xB0, hidden.hexToBytes()))
        assertRejected(hidden.hexToBytes())
    }

    @Test
    fun do85TronqueOuSansBourrage_Refuse() {
        assertFalse(ResponseShape.isWellFormed(0x0C, 0xB0, "8510AABB9000".hexToBytes()))
        assertFalse(ResponseShape.isWellFormed(0x0C, 0xB0, "8700990290008E0801020304050607089000".hexToBytes()))
        assertFalse(ResponseShape.isWellFormed(0x0C, 0xB0, "87".hexToBytes() + "9000".hexToBytes()))
    }

    @Test
    fun reponseSecuriseeBienFormee_Acceptee() {
        val response = "8709" + "01" + "1122334455667788" + "990290008E08" + "0102030405060708" + "9000"
        assertTrue(ResponseShape.isWellFormed(0x0C, 0xB0, response.hexToBytes()))
        // Forme longue de la longueur (DO'87 de 224 octets d'un bloc de 223).
        val long = "8781E1" + "01" + "00".repeat(0xE0) + "990290008E08" + "0102030405060708" + "9000"
        assertTrue(ResponseShape.isWellFormed(0x0C, 0xB0, long.hexToBytes()))
        // SW seul (erreur sans DO) : rien à contrôler.
        assertTrue(ResponseShape.isWellFormed(0x0C, 0xB0, "6982".hexToBytes()))
    }

    @Test
    fun reponsesEnClair_NonInterpretees() {
        // Défi de GET CHALLENGE qui commence par 87 : données aléatoires, acceptées telles quelles.
        val challenge = "87FF7F0102030405" + "9000"
        val service = TransportCardService(FixedTransport(challenge.hexToBytes())).apply { open() }
        val response = service.transmit(CommandAPDU(0x00, 0x84, 0x00, 0x00, 8))
        assertArrayEquals(challenge.hexToBytes(), response.bytes)
    }

    @Test
    fun generalAuthenticateDePace_LongueursControlees() {
        // Étape 4 de PACE : jeton (86) annonçant 2 Go dans la réponse 7C.
        assertFalse(ResponseShape.isWellFormed(0x00, 0x86, "7C0786847FFFFFFF00 9000".hexToBytes()))
        assertTrue(ResponseShape.isWellFormed(0x00, 0x86, "7C0A86080102030405060708 9000".hexToBytes()))
        assertTrue(ResponseShape.isWellFormed(0x10, 0x86, "7C00 9000".hexToBytes()))
    }

    @Test
    fun tailleBrute_Plafonnee() {
        val tooLarge = ByteArray(ResponseShape.MAX_RESPONSE_DATA + 1) + "9000".hexToBytes()
        assertFalse(ResponseShape.isWellFormed(0x00, 0xB0, tooLarge))
        assertTrue(ResponseShape.isWellFormed(0x00, 0xB0, ByteArray(ResponseShape.MAX_RESPONSE_DATA) + "9000".hexToBytes()))
        assertFalse(ResponseShape.isWellFormed(0x00, 0xB0, "90".hexToBytes()))
        val service = TransportCardService(FixedTransport(tooLarge)).apply { open() }
        try {
            service.transmit(CommandAPDU(0x00, 0xB0, 0x00, 0x00, 8))
            fail("CardServiceException attendue")
        } catch (e: CardServiceException) {
            // Réponse refusée sans être rendue à JMRTD.
        }
    }
}
