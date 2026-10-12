package io.github.mgdx.sceau.core.verify

import io.github.mgdx.sceau.core.reading.ChipAuthenticationMapping
import io.github.mgdx.sceau.core.reading.PaceCamEvidence
import io.github.mgdx.sceau.core.report.CheckDetail
import io.github.mgdx.sceau.core.report.CheckStatus
import io.github.mgdx.sceau.testchip.TestActiveAuthentication
import io.github.mgdx.sceau.testchip.TestCredential
import io.github.mgdx.sceau.testchip.TestCrypto
import io.github.mgdx.sceau.testchip.TestKeyType
import io.github.mgdx.sceau.testchip.TestPki
import org.bouncycastle.asn1.ASN1Encodable
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.DERNull
import org.bouncycastle.asn1.DEROctetString
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.asn1.DERSet
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.asn1.x9.X9ObjectIdentifiers
import org.bouncycastle.cms.CMSProcessableByteArray
import org.bouncycastle.cms.CMSSignedDataGenerator
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.KeyPair
import java.security.interfaces.RSAPublicKey
import org.bouncycastle.asn1.pkcs.RSAPublicKey as Asn1RsaPublicKey

/**
 * Upper bounds on public keys (audit V30): a giant key supplied by the chip (DG15, DG14,
 * EF.CardSecurity) or by a certificate (DS, chain issuer) is rejected before any computation,
 * as `UNSUPPORTED_ALGORITHM`, instead of minutes of non-interruptible `modPow`.
 */
class KeyBoundsTest {
    private val pki = TestPki(TestKeyType.RSA, seed = 30_303L)
    private val random = TestCrypto.seededRandom(30)
    private val challenge = ByteArray(8).also { random.nextBytes(it) }

    /** Odd integer of exactly [bits] bits: dummy modulus, never factored or rebuilt. */
    private fun oddNumber(bits: Int): BigInteger = BigInteger(bits, random).setBit(bits - 1).setBit(0)

    /** Dummy RSA public key: no structure check, like a key read from DG15. */
    private class FakeRsaKey(
        private val n: BigInteger,
        private val e: BigInteger,
    ) : RSAPublicKey {
        override fun getModulus(): BigInteger = n

        override fun getPublicExponent(): BigInteger = e

        override fun getAlgorithm(): String = "RSA"

        override fun getFormat(): String = "X.509"

        override fun getEncoded(): ByteArray =
            SubjectPublicKeyInfo(AlgorithmIdentifier(PKCSObjectIdentifiers.rsaEncryption, DERNull.INSTANCE), Asn1RsaPublicKey(n, e))
                .encoded
    }

    // --- Crypto.unsupportedKey ----------------------------------------------------------

    @Test
    fun `RSA bounds - lower, upper and exponent`() {
        val e = BigInteger.valueOf(65_537)
        assertEquals("RSA-512", Crypto.unsupportedKey(FakeRsaKey(oddNumber(512), e)))
        assertNull(Crypto.unsupportedKey(FakeRsaKey(oddNumber(4096), e)))
        assertNull(Crypto.unsupportedKey(FakeRsaKey(oddNumber(8192), e)))
        assertEquals("RSA-8200", Crypto.unsupportedKey(FakeRsaKey(oddNumber(8200), e)))
        assertEquals("RSA-2048/e-65", Crypto.unsupportedKey(FakeRsaKey(oddNumber(2048), oddNumber(65))))
        assertEquals("RSA-16384", Crypto.unsupportedKey(SubjectPublicKeyInfo.getInstance(FakeRsaKey(oddNumber(16_384), e).encoded)))
    }

    @Test
    fun `real keys - accepted, named and explicit curves`() {
        for (type in TestKeyType.entries) {
            val csca = TestPki(type, seed = 30_304L).oldCsca
            assertNull(type.name, Crypto.unsupportedKey(csca.holder.subjectPublicKeyInfo))
            assertNull(type.name, Crypto.unsupportedKey(csca.certificate.publicKey))
        }
    }

    @Test
    fun `giant explicit DH and EC parameters - rejected without building the key`() {
        val dh =
            SubjectPublicKeyInfo(
                AlgorithmIdentifier(X9ObjectIdentifiers.dhpublicnumber, DERSequence(arrayOf(ASN1Integer(oddNumber(9000)), ASN1Integer(2)))),
                ASN1Integer(oddNumber(8000)),
            )
        assertEquals("DH-9000", Crypto.unsupportedKey(dh))
        assertEquals("EC-2048", Crypto.unsupportedKey(explicitEcKey(fieldBits = 2048)))
    }

    // --- Active Authentication (DG15) ---------------------------------------------------

    @Test(timeout = 10_000)
    fun `AA with a 16 384-bit RSA key - unsupported, without computation`() {
        val key = FakeRsaKey(oddNumber(16_384), oddNumber(16_384))
        val response = oddNumber(16_000).toByteArray()

        val start = System.nanoTime()
        val check = ActiveAuthentication.verifyResponse(key, null, challenge, response)
        val elapsedMillis = (System.nanoTime() - start) / 1_000_000

        assertEquals(CheckStatus.UNSUPPORTED_ALGORITHM, check.status)
        assertEquals(CheckDetail.UnsupportedAlgorithm("RSA-16384"), check.detail)
        assertTrue("$elapsedMillis ms", elapsedMillis < 1_000)
    }

    @Test
    fun `AA with a 4 096-bit RSA key - still accepted`() {
        val keys = pki.generateRsa(4096)
        val response = TestActiveAuthentication.respond(keys.private, challenge, "SHA-256", pki)

        assertEquals(CheckStatus.OK, ActiveAuthentication.verifyResponse(keys.public, null, challenge, response).status)
    }

    // --- Passive Authentication (DS, chain) ---------------------------------------------

    private fun verify(
        ds: TestCredential,
        vararg store: TestCredential,
    ): PassiveAuthResult {
        val document = pki.document { this.ds = ds }
        return PassiveAuthentication.verify(
            sod = document.sod,
            dataGroups = document.dataGroups,
            trustStore = pki.trustStore(*store),
            dateOfIssue = document.dateOfIssue,
            dateOfExpiry = document.dateOfExpiry,
            documentCode = document.documentCode,
        )
    }

    @Test(timeout = 10_000)
    fun `DS with a 16 384-bit RSA key - SOD signature unsupported`() {
        val signing = pki.generateRsa(TestPki.RSA_BITS)
        val ds = pki.issueDs(dsKeyPair = KeyPair(FakeRsaKey(oddNumber(16_384), BigInteger.valueOf(65_537)), signing.private))

        val result = verify(ds, pki.oldCsca)

        assertEquals(CheckStatus.UNSUPPORTED_ALGORITHM, result.sodSignature.status)
        assertEquals(CheckDetail.UnsupportedAlgorithm("RSA-16384"), result.sodSignature.detail)
    }

    @Test(timeout = 10_000)
    fun `chain issuer with a 16 384-bit RSA key - chain unsupported`() {
        val signing = pki.generateRsa(TestPki.RSA_BITS)
        val giant = KeyPair(FakeRsaKey(oddNumber(16_384), BigInteger.valueOf(65_537)), signing.private)
        val link = pki.issueCustom(pki.oldCsca, "LIEN", basicConstraintsCa = true, keyUsage = KeyUsage.keyCertSign, keyPair = giant)
        val ds = pki.issueCustom(link, "DS", basicConstraintsCa = null, keyUsage = KeyUsage.digitalSignature)

        val result = verify(ds, pki.oldCsca, link)

        assertEquals(CheckStatus.UNSUPPORTED_ALGORITHM, result.certificateChain.status)
        assertEquals(CheckDetail.UnsupportedAlgorithm("RSA-16384"), result.certificateChain.detail)
    }

    // --- PACE-CAM (EF.CardSecurity) -----------------------------------------------------

    @Test(timeout = 10_000)
    fun `EF CardSecurity with a 2 048-bit explicit-parameter EC key - unsupported`() {
        val keyInfo =
            DERSequence(
                arrayOf<ASN1Encodable>(ASN1ObjectIdentifier(ID_PK_ECDH), explicitEcKey(fieldBits = 2048), ASN1Integer(1)),
            )
        val cardSecurity = signedCardSecurity(DERSet(keyInfo).encoded)
        val mappingKey = pki.generateEc(TestPki.CURVE).public
        val evidence = PaceCamEvidence(byteArrayOf(1, 2, 3), mappingKey, cardSecurity)

        val result =
            ChipAuthenticationMapping.verify(evidence, pki.trustStore(), "FRA", CheckStatus.NOT_AVAILABLE, null)

        assertEquals(CheckStatus.UNSUPPORTED_ALGORITHM, result.check.status)
        assertEquals(CheckDetail.UnsupportedAlgorithm("EC-2048"), result.check.detail)
    }

    /** EF.CardSecurity signed by the dummy PKI's DS, with the usual signed attributes. */
    private fun signedCardSecurity(content: ByteArray): ByteArray {
        val provider = TestCrypto.provider
        val signer = JcaContentSignerBuilder(pki.ds.keyType.signatureAlgorithm).setProvider(provider).build(pki.ds.privateKey)
        val generator = CMSSignedDataGenerator()
        generator.addSignerInfoGenerator(
            JcaSignerInfoGeneratorBuilder(JcaDigestCalculatorProviderBuilder().setProvider(provider).build()).build(signer, pki.ds.holder),
        )
        generator.addCertificate(pki.ds.holder)
        return generator.generate(CMSProcessableByteArray(ASN1ObjectIdentifier(ID_SECURITY_OBJECT), content), true).encoded
    }

    /** Dummy EC key with explicit parameters (X9.62) over a prime field of [fieldBits] bits. */
    private fun explicitEcKey(fieldBits: Int): SubjectPublicKeyInfo {
        val p = oddNumber(fieldBits)
        val coordinateBytes = fieldBits / 8
        val parameters =
            DERSequence(
                arrayOf(
                    ASN1Integer(1),
                    DERSequence(arrayOf(X9ObjectIdentifiers.prime_field, ASN1Integer(p))),
                    DERSequence(arrayOf(DEROctetString(ByteArray(coordinateBytes)), DEROctetString(ByteArray(coordinateBytes)))),
                    DEROctetString(byteArrayOf(4) + ByteArray(2 * coordinateBytes) { 1 }),
                    ASN1Integer(oddNumber(fieldBits)),
                    ASN1Integer(1),
                ),
            )
        val point = byteArrayOf(4) + ByteArray(2 * coordinateBytes) { 2 }
        return SubjectPublicKeyInfo(AlgorithmIdentifier(X9ObjectIdentifiers.id_ecPublicKey, parameters), point)
    }

    private companion object {
        const val ID_PK_ECDH = "0.4.0.127.0.7.2.2.1.2"
        const val ID_SECURITY_OBJECT = "0.4.0.127.0.7.3.2.1"
    }
}
