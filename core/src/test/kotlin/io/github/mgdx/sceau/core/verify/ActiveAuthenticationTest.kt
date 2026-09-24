package io.github.mgdx.sceau.core.verify

import io.github.mgdx.sceau.core.report.CheckDetail
import io.github.mgdx.sceau.core.report.CheckId
import io.github.mgdx.sceau.core.report.CheckStatus
import io.github.mgdx.sceau.core.testing.AaKeyType
import io.github.mgdx.sceau.core.testing.TestActiveAuthentication
import io.github.mgdx.sceau.core.testing.TestCrypto
import io.github.mgdx.sceau.core.testing.TestDocument
import io.github.mgdx.sceau.core.testing.TestPki
import org.jmrtd.lds.icao.DG15File
import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator

/** Active Authentication : RSA ISO/IEC 9796-2 schéma 1 et ECDSA brut (SPEC §6.1 étape 7). */
class ActiveAuthenticationTest {
    private val pki = TestPki(name = "AA", seed = 97_962L)
    private val challenge = ByteArray(8).also { TestCrypto.seededRandom(1).nextBytes(it) }

    private val rsaKeys: KeyPair by lazy { pki.generateRsa(TestDocument.AA_RSA_BITS) }
    private val otherRsaKeys: KeyPair by lazy { pki.generateRsa(TestDocument.AA_RSA_BITS) }
    private val ecKeys: KeyPair by lazy { pki.generateEc(TestPki.CURVE) }
    private val otherEcKeys: KeyPair by lazy { pki.generateEc(TestPki.CURVE) }

    private fun respond(
        keys: KeyPair,
        digest: String,
        message: ByteArray = challenge,
    ) = TestActiveAuthentication.respond(keys.private, message, digest, pki)

    @Test
    fun `RSA SHA-1 trailer implicite - OK`() {
        val check = ActiveAuthentication.verifyResponse(rsaKeys.public, null, challenge, respond(rsaKeys, "SHA-1"))

        assertEquals(CheckId.ACTIVE_AUTHENTICATION, check.id)
        assertEquals(CheckStatus.OK, check.status)
        assertEquals(CheckDetail.Signature("SHA1withRSA/ISO9796-2"), check.detail)
    }

    @Test
    fun `RSA SHA-256 trailer explicite - OK`() {
        val check = ActiveAuthentication.verifyResponse(rsaKeys.public, null, challenge, respond(rsaKeys, "SHA-256"))

        assertEquals(CheckStatus.OK, check.status)
        assertEquals(CheckDetail.Signature("SHA256withRSA/ISO9796-2"), check.detail)
    }

    @Test
    fun `RSA reponse signee avec une mauvaise cle - echec`() {
        val check = ActiveAuthentication.verifyResponse(rsaKeys.public, null, challenge, respond(otherRsaKeys, "SHA-1"))

        assertEquals(CheckStatus.FAILED, check.status)
    }

    @Test
    fun `RSA reponse a un autre challenge - echec`() {
        val other = challenge.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        val check = ActiveAuthentication.verifyResponse(rsaKeys.public, null, challenge, respond(rsaKeys, "SHA-1", other))

        assertEquals(CheckStatus.FAILED, check.status)
    }

    @Test
    fun `RSA reponse mal formee - echec sans exception`() {
        for (response in listOf(ByteArray(0), ByteArray(3), ByteArray(128) { -1 }, ByteArray(200) { 1 })) {
            assertEquals(CheckStatus.FAILED, ActiveAuthentication.verifyResponse(rsaKeys.public, null, challenge, response).status)
        }
    }

    @Test
    fun `ECDSA avec l algorithme annonce dans DG14 - OK`() {
        for (digest in listOf("SHA-1", "SHA-256", "SHA-384")) {
            val check = ActiveAuthentication.verifyResponse(ecKeys.public, digest, challenge, respond(ecKeys, digest))
            assertEquals(digest, CheckStatus.OK, check.status)
        }
    }

    @Test
    fun `ECDSA sans algorithme annonce - essai des hachages usuels`() {
        val check = ActiveAuthentication.verifyResponse(ecKeys.public, null, challenge, respond(ecKeys, "SHA-384"))

        assertEquals(CheckStatus.OK, check.status)
        assertEquals(CheckDetail.Signature("SHA384withECDSA"), check.detail)
    }

    @Test
    fun `ECDSA mauvaise cle - echec`() {
        val response = respond(otherEcKeys, "SHA-256")

        assertEquals(CheckStatus.FAILED, ActiveAuthentication.verifyResponse(ecKeys.public, "SHA-256", challenge, response).status)
        assertEquals(CheckStatus.FAILED, ActiveAuthentication.verifyResponse(ecKeys.public, null, challenge, response).status)
    }

    @Test
    fun `ECDSA mauvais hachage annonce - echec`() {
        val check = ActiveAuthentication.verifyResponse(ecKeys.public, "SHA-512", challenge, respond(ecKeys, "SHA-256"))

        assertEquals(CheckStatus.FAILED, check.status)
    }

    @Test
    fun `ECDSA reponse mal formee - echec sans exception`() {
        for (response in listOf(ByteArray(0), ByteArray(63), ByteArray(64))) {
            assertEquals(CheckStatus.FAILED, ActiveAuthentication.verifyResponse(ecKeys.public, "SHA-256", challenge, response).status)
        }
    }

    @Test
    fun `ECDSA hachage inconnu - UnsupportedAlgorithm`() {
        val check = ActiveAuthentication.verifyResponse(ecKeys.public, "NOPE-999", challenge, respond(ecKeys, "SHA-256"))

        assertEquals(CheckStatus.UNSUPPORTED_ALGORITHM, check.status)
    }

    @Test
    fun `type de cle inconnu - UnsupportedAlgorithm`() {
        val dsa = KeyPairGenerator.getInstance("DSA", TestCrypto.provider).apply { initialize(1024, pki.random) }.generateKeyPair()
        val check = ActiveAuthentication.verifyResponse(dsa.public, null, challenge, ByteArray(40))

        assertEquals(CheckStatus.UNSUPPORTED_ALGORITHM, check.status)
        assertEquals(CheckDetail.UnsupportedAlgorithm("DSA"), check.detail)
    }

    @Test
    fun `cle AA relue depuis DG15 par JMRTD - OK`() {
        val document = pki.document { activeAuthentication = AaKeyType.EC }
        val dg15 = DG15File(document.dataGroups.getValue(15).inputStream())
        val check =
            ActiveAuthentication.verifyResponse(dg15.publicKey, document.aaDigestAlgorithm, challenge, document.aaResponse(challenge))

        assertEquals(CheckStatus.OK, check.status)
    }
}
