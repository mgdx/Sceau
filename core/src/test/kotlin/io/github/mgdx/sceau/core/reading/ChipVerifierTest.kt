package io.github.mgdx.sceau.core.reading

import io.github.mgdx.sceau.core.SceauException
import io.github.mgdx.sceau.core.reading.ScriptedTransport.Companion.raise
import io.github.mgdx.sceau.core.reading.ScriptedTransport.Companion.respond
import io.github.mgdx.sceau.core.reading.TestFixtures.AID_SELECT
import io.github.mgdx.sceau.core.report.CheckDetail
import io.github.mgdx.sceau.core.report.CheckId
import io.github.mgdx.sceau.core.report.CheckStatus
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.jmrtd.lds.ChipAuthenticationInfo
import org.jmrtd.lds.ChipAuthenticationPublicKeyInfo
import org.jmrtd.lds.SecurityInfo
import org.jmrtd.lds.icao.DG14File
import org.jmrtd.lds.icao.DG15File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec

/**
 * Étape VERIFY_CHIP hors vérification cryptographique de la réponse AA (lot B) : états
 * « non disponible », refus de la puce, perte de connexion, challenge AA.
 */
class ChipVerifierTest {
    /** Tirage déterministe pour observer le challenge dans l'APDU émise. */
    private val fixedRandom =
        object : SecureRandom() {
            override fun nextBytes(bytes: ByteArray) = bytes.fill(0x42)
        }

    private fun verifier(transport: ScriptedTransport): ChipVerifier {
        Crypto.ensureInstalled()
        val chip = Chip(transport)
        chip.service.open()
        chip.service.sendSelectApplet(false)
        return ChipVerifier(chip, fixedRandom)
    }

    private val dg14 by lazy {
        val keyPair =
            KeyPairGenerator
                .getInstance("EC", BouncyCastleProvider.PROVIDER_NAME)
                .apply {
                    initialize(ECGenParameterSpec("brainpoolP256r1"))
                }.generateKeyPair()
        DG14File(
            listOf<SecurityInfo>(
                ChipAuthenticationPublicKeyInfo(keyPair.public),
                ChipAuthenticationInfo(SecurityInfo.ID_CA_ECDH_AES_CBC_CMAC_128, 1),
            ),
        ).encoded
    }

    private val dg15 by lazy {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair()
        DG15File(keyPair.public).encoded
    }

    @Test
    fun sansDg14NiDg15_NonDisponibles() {
        val transport = ScriptedTransport(respond(AID_SELECT, "9000"))
        val verifier = verifier(transport)
        assertEquals(CheckStatus.NOT_AVAILABLE, verifier.chipAuthentication(null, signedInSod = false).check.status)
        assertEquals(CheckStatus.NOT_AVAILABLE, verifier.activeAuthentication(null, null, signedInSod = false).status)
        assertEquals(1, transport.sent.size)
    }

    @Test
    fun dg14EtDg15AnnoncesDansLeSodMaisNonFournis_Echec() {
        // Audit V1 : un clone qui retient DG14/DG15 ne doit pas passer pour une puce sans CA ni AA.
        val transport = ScriptedTransport(respond(AID_SELECT, "9000"))
        val verifier = verifier(transport)

        val ca = verifier.chipAuthentication(null, signedInSod = true).check
        val aa = verifier.activeAuthentication(null, null, signedInSod = true)

        assertEquals(CheckStatus.FAILED, ca.status)
        assertEquals(CheckDetail.Error("READ_DATA-CA-DG14Missing"), ca.detail)
        assertEquals(CheckStatus.FAILED, aa.status)
        assertEquals(CheckDetail.Error("VERIFY_CHIP-AA-DG15Missing"), aa.detail)
        assertEquals("aucune commande envoyée à la puce", 1, transport.sent.size)
    }

    @Test
    fun dg14SansCleCa_NonDisponible() {
        val transport = ScriptedTransport(respond(AID_SELECT, "9000"))
        val onlyAa = DG14File(emptyList()).encoded
        assertEquals(CheckStatus.NOT_AVAILABLE, verifier(transport).chipAuthentication(onlyAa, signedInSod = true).check.status)
    }

    @Test
    fun dg15Illisible_AlgorithmeNonPrisEnCharge() {
        val transport = ScriptedTransport(respond(AID_SELECT, "9000"))
        val check = verifier(transport).activeAuthentication(TestFixtures.opaqueDataGroup(15), null, signedInSod = true)
        assertEquals(CheckId.ACTIVE_AUTHENTICATION, check.id)
        assertEquals(CheckStatus.UNSUPPORTED_ALGORITHM, check.status)
    }

    @Test
    fun caRefuseeParLaPuce_EchecEtCanalConserve() {
        // La puce refuse le MSE : JMRTD garde l'ancien canal, qui répond encore (SELECT et
        // READ BINARY de confirmation) : la lecture pourra continuer sans reconnexion.
        val transport =
            ScriptedTransport(
                respond(AID_SELECT, "9000"),
                respond("0022", "6A80"),
                respond("00A4020C02${TestFixtures.fid(1)}", "9000"),
                respond("00B0000001", "619000"),
            )
        val outcome = verifier(transport).chipAuthentication(dg14, signedInSod = true)
        val check = outcome.check
        assertEquals(CheckId.CHIP_AUTHENTICATION, check.id)
        assertEquals(CheckStatus.FAILED, check.status)
        assertTrue((check.detail as CheckDetail.Error).code.startsWith("READ_DATA-CA-"))
        assertTrue("canal toujours utilisable", outcome.channelUsable)
        assertTrue(transport.exhausted)
    }

    @Test
    fun caRefuseeEtPuceMuetteEnsuite_CanalARetablir() {
        val transport =
            ScriptedTransport(
                respond(AID_SELECT, "9000"),
                respond("0022", "6A80"),
                respond("00A4020C02${TestFixtures.fid(1)}", "6988"),
            )
        val outcome = verifier(transport).chipAuthentication(dg14, signedInSod = true)
        assertEquals(CheckStatus.FAILED, outcome.check.status)
        assertFalse("canal à rétablir", outcome.channelUsable)
    }

    @Test
    fun documentRetirePendantCa_ConnectionLostInchangee() {
        val lost = SceauException.ConnectionLost()
        val transport = ScriptedTransport(respond(AID_SELECT, "9000"), raise("0022", lost))
        try {
            verifier(transport).chipAuthentication(dg14, signedInSod = true)
            fail("ConnectionLost attendue")
        } catch (e: SceauException.ConnectionLost) {
            assertSame(lost, e)
        }
    }

    @Test
    fun aaRefuseeParLaPuce_EchecEtChallengeDeHuitOctets() {
        val transport = ScriptedTransport(respond(AID_SELECT, "9000"), respond("00880000", "6982"))
        val check = verifier(transport).activeAuthentication(dg15, null, signedInSod = true)
        assertEquals(CheckStatus.FAILED, check.status)
        // INTERNAL AUTHENTICATE, Lc = 8, challenge tiré du SecureRandom fourni.
        assertTrue(transport.sent.last().startsWith("0088000008" + "42".repeat(8)))
    }

    @Test
    fun documentRetirePendantAa_ConnectionLostInchangee() {
        val lost = SceauException.ConnectionLost()
        val transport = ScriptedTransport(respond(AID_SELECT, "9000"), raise("00880000", lost))
        try {
            verifier(transport).activeAuthentication(dg15, null, signedInSod = true)
            fail("ConnectionLost attendue")
        } catch (e: SceauException.ConnectionLost) {
            assertSame(lost, e)
        }
    }
}
