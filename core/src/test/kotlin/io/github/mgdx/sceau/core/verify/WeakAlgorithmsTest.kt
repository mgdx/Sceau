package io.github.mgdx.sceau.core.verify

import io.github.mgdx.sceau.core.report.CheckDetail
import io.github.mgdx.sceau.core.report.CheckStatus
import io.github.mgdx.sceau.testchip.SodOptions
import io.github.mgdx.sceau.testchip.TestActiveAuthentication
import io.github.mgdx.sceau.testchip.TestCrypto
import io.github.mgdx.sceau.testchip.TestDocument
import io.github.mgdx.sceau.testchip.TestKeyType
import io.github.mgdx.sceau.testchip.TestPki
import org.bouncycastle.crypto.digests.RIPEMD128Digest
import org.bouncycastle.crypto.engines.RSAEngine
import org.bouncycastle.crypto.params.RSAKeyParameters
import org.bouncycastle.crypto.signers.ISO9796d2Signer
import org.bouncycastle.crypto.util.PrivateKeyFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPair
import java.security.interfaces.RSAPrivateKey

/**
 * Algorithmes obsolètes (audit V9) : MD5 et RIPEMD-128 refusés partout, clés RSA de moins de
 * 1024 bits refusées ; SHA-1 reste accepté (documents anciens encore valides).
 */
class WeakAlgorithmsTest {
    private val pki = TestPki(TestKeyType.RSA, seed = 90_909L)
    private val challenge = ByteArray(8).also { TestCrypto.seededRandom(3).nextBytes(it) }

    private fun verify(document: TestDocument): PassiveAuthResult =
        PassiveAuthentication.verify(
            sod = document.sod,
            dataGroups = document.dataGroups,
            trustStore = pki.trustStore(pki.oldCsca),
            dateOfIssue = document.dateOfIssue,
            dateOfExpiry = document.dateOfExpiry,
            documentCode = document.documentCode,
        )

    private fun PassiveAuthResult.statuses() =
        listOf(sodSignature.status, certificateChain.status, dsValidity.status, dataGroupHashes.status)

    @Test
    fun `SHA-1 toujours accepte - empreintes et signature`() {
        val result = verify(pki.document { sod = SodOptions(digestAlgorithm = "SHA-1", signatureAlgorithm = "SHA1withRSA") })

        assertEquals(List(4) { CheckStatus.OK }, result.statuses())
    }

    @Test
    fun `empreintes des DG en MD5 ou RIPEMD-128 - non pris en charge`() {
        for (digest in listOf("MD5", "RIPEMD128")) {
            val result = verify(pki.document { sod = SodOptions(digestAlgorithm = digest) })

            assertEquals(digest, CheckStatus.UNSUPPORTED_ALGORITHM, result.dataGroupHashes.status)
            assertEquals(digest, CheckDetail.UnsupportedAlgorithm(digest), result.dataGroupHashes.detail)
        }
    }

    @Test
    fun `signature du SOD en MD5withRSA ou RIPEMD128withRSA - non prise en charge`() {
        for (algorithm in listOf("MD5withRSA", "RIPEMD128withRSA")) {
            val result = verify(pki.document { sod = SodOptions(signatureAlgorithm = algorithm) })

            assertEquals(algorithm, CheckStatus.UNSUPPORTED_ALGORITHM, result.sodSignature.status)
            assertEquals(algorithm, CheckStatus.OK, result.certificateChain.status)
        }
    }

    @Test
    fun `DS a cle RSA de 768 bits - signature du SOD non prise en charge`() {
        val ds = pki.issueDs(dsKeyPair = pki.generateRsa(768))
        val result = verify(pki.document { this.ds = ds })

        assertEquals(CheckStatus.UNSUPPORTED_ALGORITHM, result.sodSignature.status)
        assertEquals(CheckDetail.UnsupportedAlgorithm("RSA-768"), result.sodSignature.detail)
    }

    @Test
    fun `certificat DS signe en MD5withRSA - chaine non prise en charge`() {
        val ds = pki.issueDs(signatureAlgorithm = "MD5withRSA")
        val result = verify(pki.document { this.ds = ds })

        assertEquals(CheckStatus.UNSUPPORTED_ALGORITHM, result.certificateChain.status)
        assertEquals(CheckStatus.OK, result.sodSignature.status)
    }

    @Test
    fun `AA avec cle RSA de 512 bits - non prise en charge`() {
        val keys = pki.generateRsa(512)
        val response = TestActiveAuthentication.respond(keys.private, challenge, "SHA-1", pki)

        val check = ActiveAuthentication.verifyResponse(keys.public, null, challenge, response)

        assertEquals(CheckStatus.UNSUPPORTED_ALGORITHM, check.status)
        assertEquals(CheckDetail.UnsupportedAlgorithm("RSA-512"), check.detail)
    }

    @Test
    fun `AA RSA 1024 bits en SHA-1 - toujours acceptee`() {
        val keys = pki.generateRsa(TestDocument.AA_RSA_BITS)
        val response = TestActiveAuthentication.respond(keys.private, challenge, "SHA-1", pki)

        assertEquals(CheckStatus.OK, ActiveAuthentication.verifyResponse(keys.public, null, challenge, response).status)
    }

    @Test
    fun `AA RSA avec trailer RIPEMD-128 - non prise en charge`() {
        val keys = pki.generateRsa(TestDocument.AA_RSA_BITS)
        val response = ripemd128Response(keys)

        val check = ActiveAuthentication.verifyResponse(keys.public, null, challenge, response)

        assertEquals(CheckStatus.UNSUPPORTED_ALGORITHM, check.status)
        assertTrue(check.detail is CheckDetail.UnsupportedAlgorithm)
    }

    /** Réponse ISO/IEC 9796-2 schéma 1 avec RIPEMD-128 et trailer explicite, signée correctement. */
    private fun ripemd128Response(keys: KeyPair): ByteArray {
        val key = keys.private as RSAPrivateKey
        val digest = RIPEMD128Digest()
        val signer = ISO9796d2Signer(RSAEngine(), digest, false)
        signer.init(true, PrivateKeyFactory.createKey(key.encoded) as RSAKeyParameters)
        val m1 = ByteArray((key.modulus.bitLength() + 7) / 8 - digest.digestSize - 3).also { pki.random.nextBytes(it) }
        signer.update(m1, 0, m1.size)
        signer.update(challenge, 0, challenge.size)
        return signer.generateSignature()
    }
}
