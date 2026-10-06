package io.github.mgdx.sceau.core.reading

import io.github.mgdx.sceau.core.SceauException
import io.github.mgdx.sceau.core.reading.ScriptedTransport.Companion.raise
import io.github.mgdx.sceau.core.reading.ScriptedTransport.Companion.respond
import io.github.mgdx.sceau.core.reading.TestFixtures.AID_SELECT
import io.github.mgdx.sceau.core.report.Check
import io.github.mgdx.sceau.core.report.CheckDetail
import io.github.mgdx.sceau.core.report.CheckId
import io.github.mgdx.sceau.core.report.CheckStatus
import io.github.mgdx.sceau.core.report.Verdict
import io.github.mgdx.sceau.core.verify.Verdicts
import org.bouncycastle.asn1.ASN1Encodable
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.BERTags
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.asn1.DERSet
import org.bouncycastle.asn1.DERTaggedObject
import org.bouncycastle.asn1.bsi.BSIObjectIdentifiers
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.asn1.x9.X9ObjectIdentifiers
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.jmrtd.lds.ActiveAuthenticationInfo
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
    fun dg14AvecUneCleCaQueJmrtdEcarte_AlgorithmeNonPrisEnCharge() {
        // Audit V19 : SubjectPublicKeyInfo sans clé ; SecurityInfo.getInstance de JMRTD rend null
        // et l'entrée disparaît. DG14 annonce pourtant une clé : pas de « non disponible ».
        val keyWithoutBits = DERSequence(AlgorithmIdentifier(X9ObjectIdentifiers.id_ecPublicKey, X9ObjectIdentifiers.prime256v1))
        val dg14 = dg14With(keyWithoutBits)
        assertTrue(
            "JMRTD doit écarter la clé",
            DG14File(dg14.inputStream()).securityInfos.none { it is ChipAuthenticationPublicKeyInfo },
        )
        assertCaUnsupported(dg14)
    }

    @Test
    fun dg14AvecUneCleCaSurUneCourbeInconnue_AlgorithmeNonPrisEnCharge() {
        // Audit V19 : courbe inconnue ; JMRTD garde l'entrée, mais sans clé publique.
        val unknownCurveKey =
            SubjectPublicKeyInfo(
                AlgorithmIdentifier(X9ObjectIdentifiers.id_ecPublicKey, ASN1ObjectIdentifier("1.3.6.1.4.1.99999.1")),
                ByteArray(65).also { it[0] = 0x04 },
            )
        assertCaUnsupported(dg14With(unknownCurveKey))
    }

    /** DG14 réduit à une `ChipAuthenticationPublicKeyInfo` id-PK-ECDH de clé [key]. */
    private fun dg14With(key: ASN1Encodable): ByteArray {
        val publicKeyInfo = DERSequence(arrayOf(ASN1ObjectIdentifier(ID_PK_ECDH), key))
        return DERTaggedObject(true, BERTags.APPLICATION, DG14_TAG_NUMBER, DERSet(publicKeyInfo)).encoded
    }

    private fun assertCaUnsupported(dg14: ByteArray) {
        val transport = ScriptedTransport(respond(AID_SELECT, "9000"))

        val check = verifier(transport).chipAuthentication(dg14, signedInSod = true).check

        assertEquals(CheckStatus.UNSUPPORTED_ALGORITHM, check.status)
        assertEquals(CheckDetail.UnsupportedAlgorithm(ID_PK_ECDH), check.detail)
        assertEquals(Verdict.FAILED, Verdicts.compute(nominalChecks(check)))
        assertEquals("aucune commande de CA envoyée", 1, transport.sent.size)
    }

    @Test
    fun dg14AvecSeulementUneInfoAa_NonDisponible() {
        val transport = ScriptedTransport(respond(AID_SELECT, "9000"))
        val onlyAa = DG14File(listOf<SecurityInfo>(ActiveAuthenticationInfo(BSIObjectIdentifiers.ecdsa_plain_SHA256.id))).encoded

        val check = verifier(transport).chipAuthentication(onlyAa, signedInSod = true).check

        assertEquals(CheckStatus.NOT_AVAILABLE, check.status)
    }

    /** Lignes d'une PA réussie, sans AA, avec la ligne CA [ca]. */
    private fun nominalChecks(ca: Check): List<Check> =
        CheckId.entries.map { id ->
            when (id) {
                CheckId.CHIP_AUTHENTICATION -> ca
                CheckId.ACTIVE_AUTHENTICATION -> Check(id, CheckStatus.NOT_AVAILABLE)
                else -> Check(id, CheckStatus.OK)
            }
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

    private companion object {
        const val ID_PK_ECDH = "0.4.0.127.0.7.2.2.1.2"
        const val DG14_TAG_NUMBER = 14
    }
}
