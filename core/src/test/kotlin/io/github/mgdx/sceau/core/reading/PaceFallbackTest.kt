package io.github.mgdx.sceau.core.reading

import io.github.mgdx.sceau.core.AccessKey
import io.github.mgdx.sceau.core.CardTransport
import io.github.mgdx.sceau.core.SceauException
import io.github.mgdx.sceau.core.readAndVerify
import io.github.mgdx.sceau.core.reading.ScriptedTransport.Companion.file
import io.github.mgdx.sceau.core.reading.ScriptedTransport.Companion.raise
import io.github.mgdx.sceau.core.reading.ScriptedTransport.Companion.respond
import io.github.mgdx.sceau.core.reading.ScriptedTransport.Companion.toHex
import io.github.mgdx.sceau.core.reading.TestFixtures.AID_SELECT
import io.github.mgdx.sceau.core.reading.TestFixtures.FID_CARD_ACCESS
import io.github.mgdx.sceau.core.report.ChannelProtocol
import io.github.mgdx.sceau.core.report.CheckDetail
import io.github.mgdx.sceau.core.report.CheckId
import io.github.mgdx.sceau.testchip.PaceSettings
import io.github.mgdx.sceau.testchip.SimulatedChip
import io.github.mgdx.sceau.testchip.TestKeyType
import io.github.mgdx.sceau.testchip.TestPki
import kotlinx.coroutines.test.runTest
import org.bouncycastle.jce.ECNamedCurveTable
import org.bouncycastle.jce.interfaces.ECPublicKey
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.jmrtd.lds.CardAccessFile
import org.jmrtd.lds.PACEInfo
import org.jmrtd.lds.SecurityInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.KeyPairGenerator
import java.time.LocalDate

/**
 * Repli de PACE sur BAC : réinitialisation de la liaison puis BAC avec une clé MRZ, jamais
 * après un refus explicite de la clé ni un délai dépassé, jamais avec un CAN. Délai de réponse
 * long pendant l'authentification (puce qui fait patienter après des essais ratés).
 */
class PaceFallbackTest {
    /** EF.CardAccess d'un passeport français : PACE ECDH-GM AES-128 sur brainpoolP256r1. */
    private val cardAccess =
        CardAccessFile(listOf<SecurityInfo>(PACEInfo(SecurityInfo.ID_PACE_ECDH_GM_AES_CBC_CMAC_128, 2, 13))).encoded

    private val can = AccessKey.Can("123456")

    /**
     * Transport qui sert [before] jusqu'à [reconnect], puis [after] (la puce après
     * réinitialisation). Note le délai en vigueur à chaque commande.
     */
    private class ReconnectingTransport(
        private val before: CardTransport,
        private val after: CardTransport? = null,
        private val reconnectFailure: SceauException? = null,
    ) : CardTransport {
        override val maxTransceiveLength: Int = before.maxTransceiveLength
        override var timeoutMillis: Int = INITIAL_TIMEOUT

        var reconnects = 0
            private set
        var closed = false
            private set

        /** Commande (hexadécimal) et délai en vigueur, dans l'ordre. */
        val sent = mutableListOf<Pair<String, Int>>()

        override fun transceive(apdu: ByteArray): ByteArray {
            sent += apdu.toHex() to timeoutMillis
            val target = if (reconnects == 0) before else checkNotNull(after) { "commande après reconnexion" }
            return target.transceive(apdu)
        }

        override fun reconnect() {
            reconnects++
            reconnectFailure?.let { throw it }
        }

        override fun close() {
            closed = true
            before.close()
            after?.close()
        }

        fun timeoutOf(prefix: String): Int = sent.first { it.first.startsWith(prefix) }.second
    }

    /** Début de PACE sur le passeport : EF.CardAccess, MSE:Set AT (clé MRZ) accepté. */
    private fun paceStart() = arrayOf(*file(FID_CARD_ACCESS, cardAccess), respond(MSE_SET_AT, "9000"))

    private suspend inline fun <reified E : SceauException> expect(
        transport: ReconnectingTransport,
        key: AccessKey,
    ): E {
        try {
            readAndVerify(transport, key, TestFixtures.EMPTY_TRUST_STORE) {}
        } catch (e: SceauException) {
            if (e !is E) throw AssertionError("${E::class.simpleName} attendue, reçu ${e.code}", e)
            assertTrue("transport fermé", transport.closed)
            return e
        }
        fail("${E::class.simpleName} attendue")
        throw IllegalStateException()
    }

    @Test
    fun mrzSwInattenduAuPremierGeneralAuthenticate_ReconnexionPuisBac() =
        runTest {
            val pki = TestPki(TestKeyType.RSA)
            val document = pki.document()
            val chip = SimulatedChip(document)
            val passport = ScriptedTransport(*paceStart(), respond(GA_STEP_1, "6A80"))
            val transport = ReconnectingTransport(passport, chip)
            val key = AccessKey.Mrz(DOCUMENT_NUMBER, DATE_OF_BIRTH, document.dateOfExpiry)

            val report = readAndVerify(transport, key, pki.trustStore(pki.oldCsca)) {}

            assertEquals(1, transport.reconnects)
            assertTrue(passport.exhausted)
            assertTrue("BAC mené après la reconnexion", chip.bacCompleted)
            assertEquals(CheckDetail.SecureChannel(ChannelProtocol.BAC), report.check(CheckId.SECURE_CHANNEL).detail)
            assertEquals(DOCUMENT_NUMBER, report.document.dg1.documentNumber)
            // Délai long pendant l'authentification (PACE puis BAC), délai normal ailleurs.
            val auth = SecureChannel.AUTHENTICATION_TIMEOUT_MILLIS
            assertEquals(60_000, auth)
            assertEquals(INITIAL_TIMEOUT, transport.timeoutOf("00A4"))
            assertEquals(auth, transport.timeoutOf(MSE_SET_AT))
            assertEquals(auth, transport.timeoutOf(GA_STEP_1))
            assertEquals(INITIAL_TIMEOUT, transport.timeoutOf(AID_SELECT))
            assertEquals(auth, transport.timeoutOf(GET_CHALLENGE))
            assertEquals(auth, transport.timeoutOf(EXTERNAL_AUTHENTICATE))
            assertEquals(INITIAL_TIMEOUT, transport.sent.last().second)
            assertEquals(INITIAL_TIMEOUT, transport.timeoutMillis)
            assertTrue(transport.closed)
        }

    @Test
    fun mrzDelaiAuPremierGeneralAuthenticate_TimeoutSansRepli() =
        runTest {
            val timeout = SceauException.Timeout(detail = "INS86-L10")
            val passport = ScriptedTransport(*paceStart(), raise(GA_STEP_1, timeout))
            val transport = ReconnectingTransport(passport)
            val key = AccessKey.Mrz(DOCUMENT_NUMBER, DATE_OF_BIRTH, LocalDate.of(2030, 12, 31))

            val error = expect<SceauException.Timeout>(transport, key)

            // Puce en pénalité : ni reconnexion ni BAC, qui ne feraient qu'ajouter un essai.
            assertEquals("TIMEOUT-SECURE_CHANNEL-INS86-L10", error.code)
            assertSame(timeout, error.cause)
            assertEquals(0, transport.reconnects)
            assertTrue(passport.exhausted)
            assertFalse(transport.sent.any { it.first.startsWith(AID_SELECT) })
            assertEquals(SecureChannel.AUTHENTICATION_TIMEOUT_MILLIS, transport.timeoutOf(GA_STEP_1))
            assertEquals(INITIAL_TIMEOUT, transport.timeoutMillis)
        }

    @Test
    fun mrzPaceReussi_DelaiLongPendantPaceSeulement() =
        runTest {
            val pki = TestPki(TestKeyType.RSA)
            val document = pki.document()
            val chip = SimulatedChip(document, PaceSettings(can = "123456"))
            val transport = ReconnectingTransport(chip)
            val key = AccessKey.Mrz(DOCUMENT_NUMBER, DATE_OF_BIRTH, document.dateOfExpiry)

            val report = readAndVerify(transport, key, pki.trustStore(pki.oldCsca)) {}

            assertEquals(CheckDetail.SecureChannel(ChannelProtocol.PACE), report.check(CheckId.SECURE_CHANNEL).detail)
            assertEquals(0, transport.reconnects)
            val auth = SecureChannel.AUTHENTICATION_TIMEOUT_MILLIS
            assertTrue(transport.sent.filter { it.first.startsWith("1086") || it.first.startsWith("0086") }.all { it.second == auth })
            assertEquals(INITIAL_TIMEOUT, transport.timeoutOf("00A4"))
            assertEquals(INITIAL_TIMEOUT, transport.sent.last().second)
            assertEquals(INITIAL_TIMEOUT, transport.timeoutMillis)
        }

    @Test
    fun mrzRefuseeAAuthentificationMutuellePace_AccessDeniedSansRepli() =
        runTest {
            val curve = ECNamedCurveTable.getParameterSpec("brainpoolP256r1")
            val point = {
                (
                    KeyPairGenerator
                        .getInstance(
                            "EC",
                            BouncyCastleProvider(),
                        ).apply { initialize(curve) }
                        .generateKeyPair()
                        .public as ECPublicKey
                ).q
            }
            val mapping = point().getEncoded(false).toHex()
            val ephemeral = point().getEncoded(false).toHex()
            val passport =
                ScriptedTransport(
                    *paceStart(),
                    respond(GA_STEP_1, "7C12801000112233445566778899AABBCCDDEEFF9000"),
                    respond("10860000", "7C438241${mapping}9000"),
                    respond("10860000", "7C438441${ephemeral}9000"),
                    // Jeton d'authentification (dernière commande, CLA sans chaînage) : clé refusée.
                    respond("00860000", "6300"),
                )
            val transport = ReconnectingTransport(passport)
            val key = AccessKey.Mrz(DOCUMENT_NUMBER, DATE_OF_BIRTH, LocalDate.of(2030, 12, 31))

            val error = expect<SceauException.AccessDenied>(transport, key)

            assertEquals("ACCESS_DENIED", error.code)
            assertEquals(0, transport.reconnects)
            assertTrue(passport.exhausted)
            assertFalse(transport.sent.any { it.first.startsWith(AID_SELECT) })
            assertEquals(INITIAL_TIMEOUT, transport.timeoutMillis)
        }

    @Test
    fun canDelaiAuPremierGeneralAuthenticate_TimeoutSansRepli() =
        runTest {
            val timeout = SceauException.Timeout(detail = "INS86-L10")
            val passport = ScriptedTransport(*paceStart(), raise(GA_STEP_1, timeout))
            val transport = ReconnectingTransport(passport)

            val error = expect<SceauException.Timeout>(transport, can)

            assertEquals("TIMEOUT-SECURE_CHANNEL-INS86-L10", error.code)
            assertSame(timeout, error.cause)
            assertEquals(0, transport.reconnects)
            assertTrue(passport.exhausted)
            assertEquals(SecureChannel.AUTHENTICATION_TIMEOUT_MILLIS, transport.timeoutOf(GA_STEP_1))
            assertEquals(INITIAL_TIMEOUT, transport.timeoutMillis)
        }

    @Test
    fun mrzReconnexionImpossible_ConnectionLostAvecDiagnostic() =
        runTest {
            val passport = ScriptedTransport(*paceStart(), raise(GA_STEP_1, SceauException.ConnectionLost(detail = "INS86-L10")))
            val transport =
                ReconnectingTransport(passport, reconnectFailure = SceauException.ConnectionLost(detail = "RECONNECT"))
            val key = AccessKey.Mrz(DOCUMENT_NUMBER, DATE_OF_BIRTH, LocalDate.of(2030, 12, 31))

            val error = expect<SceauException.ConnectionLost>(transport, key)

            assertEquals("CONNECTION_LOST-SECURE_CHANNEL-RECONNECT", error.code)
            assertEquals(1, transport.reconnects)
        }

    private companion object {
        const val INITIAL_TIMEOUT = 10_000
        const val MSE_SET_AT = "0022C1A4"
        const val GET_CHALLENGE = "00840000"
        const val EXTERNAL_AUTHENTICATE = "00820000"

        /** GENERAL AUTHENTICATE étape 1 : CLA 10 (chaînage), 7C 00, Le 00 courte. */
        const val GA_STEP_1 = "10860000027C0000"

        /** MRZ du DG1 factice de la fabrique de test (numéro L898902C3, né le 12/08/1974). */
        const val DOCUMENT_NUMBER = "L898902C3"
        val DATE_OF_BIRTH: LocalDate = LocalDate.of(1974, 8, 12)
    }
}
