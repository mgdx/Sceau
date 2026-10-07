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
 * Ancre RSA dont le module est premier (D47). Le chargement du magasin ne décode plus les clés
 * RSA des ancres, donc ne fait plus le test de Miller-Rabin de BouncyCastle : une telle ancre
 * entre dans le magasin. Ce contrôle doit alors jouer quand la clé sert, à la recherche de
 * chaîne. Un module premier rend la clé privée triviale (d = e⁻¹ mod p − 1) : ce CSCA signe
 * ici un DS dont la signature est mathématiquement valide, et la chaîne doit pourtant être
 * refusée, jamais `OK`.
 */
class PrimeModulusAnchorTest {
    private val pki = TestPki(TestKeyType.RSA, seed = 47_047L)

    /** Clés RSA de module premier ; SunRsaSign, contrairement à BouncyCastle, accepte d'en signer. */
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

    /** CSCA auto-signé au sujet et au SKI du CSCA de test, mais à clé de module premier. */
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

    /** DS de test (même sujet, AKI et clé) signé par la clé de module premier. */
    private fun dsSignedByPrimeKey(): TestCredential {
        val holder = signWithPrimeKey(X509v3CertificateBuilder(pki.ds.holder))
        val certificate = JcaX509CertificateConverter().setProvider(TestCrypto.provider).getCertificate(holder)
        return TestCredential(certificate, pki.ds.keyPair, pki.ds.keyType)
    }

    @Test
    fun `ancre a module premier - acceptee au chargement d'une Master List`() {
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
    fun `ancre a module premier - chaine refusee bien que les signatures soient valides`() {
        val csca = primeCsca()
        val ds = dsSignedByPrimeKey()
        // Témoin : hors du contrôle de BouncyCastle, CSCA et DS vérifient sous la clé de module premier.
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
