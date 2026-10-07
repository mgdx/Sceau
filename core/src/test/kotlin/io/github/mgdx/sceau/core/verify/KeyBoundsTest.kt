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
 * Bornes hautes des clés publiques (audit V30) : une clé géante fournie par la puce (DG15, DG14,
 * EF.CardSecurity) ou par un certificat (DS, émetteur de la chaîne) est refusée avant tout calcul,
 * en `UNSUPPORTED_ALGORITHM`, au lieu de minutes de `modPow` non interruptible.
 */
class KeyBoundsTest {
    private val pki = TestPki(TestKeyType.RSA, seed = 30_303L)
    private val random = TestCrypto.seededRandom(30)
    private val challenge = ByteArray(8).also { random.nextBytes(it) }

    /** Entier impair de [bits] bits exactement : modulus fictif, jamais factorisé ni reconstruit. */
    private fun oddNumber(bits: Int): BigInteger = BigInteger(bits, random).setBit(bits - 1).setBit(0)

    /** Clé RSA publique fictive : aucune vérification de structure, comme une clé lue dans DG15. */
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
    fun `bornes RSA - basse, haute et exposant`() {
        val e = BigInteger.valueOf(65_537)
        assertEquals("RSA-512", Crypto.unsupportedKey(FakeRsaKey(oddNumber(512), e)))
        assertNull(Crypto.unsupportedKey(FakeRsaKey(oddNumber(4096), e)))
        assertNull(Crypto.unsupportedKey(FakeRsaKey(oddNumber(8192), e)))
        assertEquals("RSA-8200", Crypto.unsupportedKey(FakeRsaKey(oddNumber(8200), e)))
        assertEquals("RSA-2048/e-65", Crypto.unsupportedKey(FakeRsaKey(oddNumber(2048), oddNumber(65))))
        assertEquals("RSA-16384", Crypto.unsupportedKey(SubjectPublicKeyInfo.getInstance(FakeRsaKey(oddNumber(16_384), e).encoded)))
    }

    @Test
    fun `cles reelles - acceptees, courbes nommees et explicites`() {
        for (type in TestKeyType.entries) {
            val csca = TestPki(type, seed = 30_304L).oldCsca
            assertNull(type.name, Crypto.unsupportedKey(csca.holder.subjectPublicKeyInfo))
            assertNull(type.name, Crypto.unsupportedKey(csca.certificate.publicKey))
        }
    }

    @Test
    fun `parametres DH et EC explicites geants - refuses sans construire la cle`() {
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
    fun `AA avec une cle RSA de 16 384 bits - non prise en charge, sans calcul`() {
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
    fun `AA avec une cle RSA de 4 096 bits - toujours acceptee`() {
        val keys = pki.generateRsa(4096)
        val response = TestActiveAuthentication.respond(keys.private, challenge, "SHA-256", pki)

        assertEquals(CheckStatus.OK, ActiveAuthentication.verifyResponse(keys.public, null, challenge, response).status)
    }

    // --- Passive Authentication (DS, chaîne) --------------------------------------------

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
    fun `DS a cle RSA de 16 384 bits - signature du SOD non prise en charge`() {
        val signing = pki.generateRsa(TestPki.RSA_BITS)
        val ds = pki.issueDs(dsKeyPair = KeyPair(FakeRsaKey(oddNumber(16_384), BigInteger.valueOf(65_537)), signing.private))

        val result = verify(ds, pki.oldCsca)

        assertEquals(CheckStatus.UNSUPPORTED_ALGORITHM, result.sodSignature.status)
        assertEquals(CheckDetail.UnsupportedAlgorithm("RSA-16384"), result.sodSignature.detail)
    }

    @Test(timeout = 10_000)
    fun `emetteur de la chaine a cle RSA de 16 384 bits - chaine non prise en charge`() {
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
    fun `EF CardSecurity avec une cle EC a parametres explicites de 2 048 bits - non prise en charge`() {
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

    /** EF.CardSecurity signé par le DS de la PKI factice, avec les attributs signés usuels. */
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

    /** Clé EC à paramètres explicites (X9.62) sur un corps premier de [fieldBits] bits, fictive. */
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
