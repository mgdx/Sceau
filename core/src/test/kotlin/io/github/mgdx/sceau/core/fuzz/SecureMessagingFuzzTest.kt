package io.github.mgdx.sceau.core.fuzz

import io.github.mgdx.sceau.core.CardTransport
import io.github.mgdx.sceau.core.reading.Crypto
import io.github.mgdx.sceau.core.reading.TransportCardService
import net.sf.scuba.smartcards.APDUWrapper
import net.sf.scuba.smartcards.CommandAPDU
import org.jmrtd.protocol.AESSecureMessagingWrapper
import org.jmrtd.protocol.DESedeSecureMessagingWrapper
import org.jmrtd.protocol.SecureMessagingAPDUSender
import org.junit.Test
import java.util.Random
import javax.crypto.spec.SecretKeySpec

/**
 * Fuzzing des réponses de la puce sous messagerie sécurisée (audit V36) : contrôle de
 * `TransportCardService.transmit`, puis déballage par JMRTD (`SecureMessagingWrapper.unwrap`,
 * DESede et AES). Contrat : une réponse mutée produit une réponse ou une `Exception`, jamais une
 * `Error` (`OutOfMemoryError` d'une longueur de DO'87 démesurée allouée avant le MAC).
 */
class SecureMessagingFuzzTest {
    @Test
    fun mutatedSecuredResponse() {
        Crypto.ensureInstalled()
        FuzzCampaign("SM", seeds(), MAX_RESPONSE, FuzzConfig.iterations(ITERATIONS)).run { input ->
            for (wrapper in wrappers()) exercise(wrapper, input.bytes)
        }
    }

    /** READ BINARY emballé, réponse [response] ; toute `Exception` est un refus admis. */
    private fun exercise(
        wrapper: APDUWrapper,
        response: ByteArray,
    ) {
        val service = TransportCardService(FixedTransport(response)).apply { open() }
        try {
            SecureMessagingAPDUSender(service).transmit(wrapper, CommandAPDU(0x00, 0xB0, 0x00, 0x00, BLOCK))
        } catch (e: Exception) {
            // Réponse refusée par Sceau ou par JMRTD : attendu pour une entrée mutée.
        }
    }

    private fun wrappers(): List<APDUWrapper> =
        listOf(
            DESedeSecureMessagingWrapper(SecretKeySpec(KEY, "DESede"), SecretKeySpec(KEY, "DESede"), 0L),
            AESSecureMessagingWrapper(SecretKeySpec(KEY, "AES"), SecretKeySpec(KEY, "AES"), 0L),
        )

    /** Réponses de forme valide (DO'87 ou DO'85, DO'99, DO'8E), MAC quelconque. */
    private fun seeds(): List<ByteArray> {
        val random = Random(SEED)

        fun bytes(size: Int) = ByteArray(size).also(random::nextBytes)
        val do99 = byteArrayOf(0x99.toByte(), 0x02, 0x90.toByte(), 0x00)
        val do8e = byteArrayOf(0x8E.toByte(), 0x08) + bytes(8)
        val sw = byteArrayOf(0x90.toByte(), 0x00)
        return listOf(
            byteArrayOf(0x87.toByte(), 0x11, 0x01) + bytes(16) + do99 + do8e + sw,
            byteArrayOf(0x87.toByte(), 0x81.toByte(), 0xE1.toByte(), 0x01) + bytes(0xE0) + do99 + do8e + sw,
            byteArrayOf(0x85.toByte(), 0x10) + bytes(16) + do99 + do8e + sw,
            do99 + do8e + sw,
        )
    }

    private class FixedTransport(
        private val response: ByteArray,
    ) : CardTransport {
        override val maxTransceiveLength: Int = 261
        override var timeoutMillis: Int = 1000

        override fun transceive(apdu: ByteArray): ByteArray = response

        override fun close() {}
    }

    private companion object {
        const val ITERATIONS = 3_000
        const val MAX_RESPONSE = 300
        const val BLOCK = 223
        const val SEED = 36L
        val KEY = ByteArray(16) { it.toByte() }
    }
}
