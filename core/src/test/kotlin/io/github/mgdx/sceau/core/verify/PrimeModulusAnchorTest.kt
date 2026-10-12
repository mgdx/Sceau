package io.github.mgdx.sceau.core.verify

import io.github.mgdx.sceau.core.report.CheckStatus
import io.github.mgdx.sceau.core.trust.TestMasterLists
import io.github.mgdx.sceau.core.trust.TrustAnchor
import io.github.mgdx.sceau.core.trust.TrustSource
import io.github.mgdx.sceau.core.trust.TrustStores
import io.github.mgdx.sceau.testchip.TestCredential
import io.github.mgdx.sceau.testchip.TestCrypto
import io.github.mgdx.sceau.testchip.TestKeyType
import io.github.mgdx.sceau.testchip.TestPki
import io.github.mgdx.sceau.testchip.TestTrustStore
import org.bouncycastle.asn1.DERNull
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.asn1.x509.SubjectKeyIdentifier
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.operator.jcajce.JcaContentVerifierProviderBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.RSAPrivateKeySpec
import java.security.spec.RSAPublicKeySpec
import java.util.Random
import org.bouncycastle.asn1.pkcs.RSAPublicKey as Asn1RsaPublicKey

/**
 * RSA anchor whose modulus is prime (D47). Loading the store no longer decodes the anchors' RSA
 * keys, so BouncyCastle's Miller-Rabin test no longer runs: such an anchor gets into the store.
 * The check must then apply when the key is used, during chain building. A prime modulus makes
 * the private key trivial (d = e⁻¹ mod p − 1): here this CSCA signs a DS whose signature is
 * mathematically valid, and the chain must still be rejected, never `OK`.
 */
class PrimeModulusAnchorTest {
    private val pki = TestPki(TestKeyType.RSA, seed = 47_047L)

    /** RSA keys with a prime modulus; SunRsaSign, unlike BouncyCastle, agrees to sign with them. */
    private val prime: BigInteger = primeModulus()
    private val sunKeys = KeyFactory.getInstance("RSA", "SunRsaSign")
    private val primePublic: PublicKey = sunKeys.generatePublic(RSAPublicKeySpec(prime, EXPONENT))
    private val primePrivate: PrivateKey =
        sunKeys.generatePrivate(RSAPrivateKeySpec(prime, EXPONENT.modInverse(prime - BigInteger.ONE)))

    private fun primeModulus(): BigInteger {
        val random = Random(47_047L)
        while (true) {
            val candidate = BigInteger.probablePrime(2048, random)
            if (EXPONENT.gcd(candidate - BigInteger.ONE) == BigInteger.ONE) return candidate
        }
    }

    private fun signWithPrimeKey(builder: X509v3CertificateBuilder): X509CertificateHolder =
        builder.build(JcaContentSignerBuilder("SHA256withRSA").setProvider("SunRsaSign").build(primePrivate))

    /** Self-signed CSCA with the subject and SKI of the test CSCA, but a prime-modulus key. */
    private fun primeCsca(): X509CertificateHolder {
        val subject = X500Name.getInstance(pki.oldCsca.certificate.subjectX500Principal.encoded)
        val keyInfo =
            SubjectPublicKeyInfo(
                AlgorithmIdentifier(PKCSObjectIdentifiers.rsaEncryption, DERNull.INSTANCE),
                Asn1RsaPublicKey(prime, EXPONENT),
            )
        val builder =
            X509v3CertificateBuilder(
                subject,
                pki.nextSerial(),
                TestCrypto.date(TestPki.CSCA_NOT_BEFORE),
                TestCrypto.date(TestPki.CSCA_NOT_AFTER),
                subject,
                keyInfo,
            )
        builder.addExtension(
            Extension.subjectKeyIdentifier,
            false,
            SubjectKeyIdentifier(TestTrustStore.subjectKeyId(pki.oldCsca.certificate)),
        )
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(true))
        builder.addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.keyCertSign))
        return signWithPrimeKey(builder)
    }

    /** Test DS (same subject, AKI and key) signed by the prime-modulus key. */
    private fun dsSignedByPrimeKey(): TestCredential {
        val holder = signWithPrimeKey(X509v3CertificateBuilder(pki.ds.holder))
        val certificate = JcaX509CertificateConverter().setProvider(TestCrypto.provider).getCertificate(holder)
        return TestCredential(certificate, pki.ds.keyPair, pki.ds.keyType)
    }

    @Test
    fun `prime-modulus anchor - accepted when loading a Master List`() {
        val csca = primeCsca()
        val signerKeys = TestMasterLists.rsaKeyPair()
        val signer = TestMasterLists.selfSigned("C=ZZ,O=Test,CN=Signataire", signerKeys)
        val bytes = TestMasterLists.signedMasterList(TestMasterLists.content(listOf(csca)), signer, signerKeys)

        val list = TrustStores.parseMasterList(bytes)

        assertEquals(1, list.certificates.size)
        assertTrue(
            list.certificates
                .single()
                .encoded
                .contentEquals(csca.encoded),
        )
    }

    @Test
    fun `prime-modulus anchor - chain rejected even though the signatures are valid`() {
        val csca = primeCsca()
        val ds = dsSignedByPrimeKey()
        // Control: outside BouncyCastle's check, the CSCA and DS verify under the prime-modulus key.
        val sunVerifier = JcaContentVerifierProviderBuilder().setProvider("SunRsaSign").build(primePublic)
        assertTrue(csca.isSignatureValid(sunVerifier))
        assertTrue(ds.holder.isSignatureValid(sunVerifier))
        val certificate = JcaX509CertificateConverter().setProvider(TestCrypto.provider).getCertificate(csca)
        val document = pki.document { this.ds = ds }

        val result =
            PassiveAuthentication.verify(
                sod = document.sod,
                dataGroups = document.dataGroups,
                trustStore = TestTrustStore(listOf(TrustAnchor(certificate, TrustSource.IMPORTED_MASTER_LIST))),
                dateOfIssue = document.dateOfIssue,
                dateOfExpiry = document.dateOfExpiry,
                documentCode = document.documentCode,
            )

        assertNotEquals(CheckStatus.OK, result.certificateChain.status)
        assertTrue(
            result.certificateChain.status.toString(),
            result.certificateChain.status == CheckStatus.FAILED || result.certificateChain.status == CheckStatus.UNSUPPORTED_ALGORITHM,
        )
    }

    private companion object {
        val EXPONENT: BigInteger = BigInteger.valueOf(65_537)
    }
}
