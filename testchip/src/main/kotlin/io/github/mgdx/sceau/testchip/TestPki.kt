package io.github.mgdx.sceau.testchip

import io.github.mgdx.sceau.core.trust.TrustSource
import org.bouncycastle.asn1.ASN1EncodableVector
import org.bouncycastle.asn1.ASN1Encoding
import org.bouncycastle.asn1.DERBitString
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Certificate
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.asn1.x9.ECNamedCurveTable
import org.bouncycastle.asn1.x9.X962Parameters
import org.bouncycastle.asn1.x9.X9ObjectIdentifiers
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Provider
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Date
import java.util.concurrent.atomic.AtomicLong

/** BouncyCastle partagé par la fabrique de test (instance explicite, jamais le nom "BC"). */
object TestCrypto {
    val provider: Provider by lazy { BouncyCastleProvider() }

    /** Aléa déterministe : les tests sont reproductibles d'une exécution à l'autre. */
    fun seededRandom(seed: Long): SecureRandom = SecureRandom.getInstance("SHA1PRNG").apply { setSeed(seed) }

    fun instant(date: LocalDate): Instant = date.atStartOfDay().toInstant(ZoneOffset.UTC)

    fun date(date: LocalDate): Date = Date.from(instant(date))
}

/**
 * Type de clé et algorithme de signature de la hiérarchie factice.
 *
 * @property keyAlgorithm algorithme JCA de la paire de clés
 * @property signatureAlgorithm algorithme de signature des certificats et du SOD
 */
enum class TestKeyType(
    val keyAlgorithm: String,
    val signatureAlgorithm: String,
) {
    /** RSA 2048, PKCS#1 v1.5. */
    RSA("RSA", "SHA256withRSA"),

    /** RSA 2048, RSASSA-PSS (MGF1, SHA-256). */
    RSA_PSS("RSA", "SHA256withRSAandMGF1"),

    /** ECDSA brainpoolP256r1, courbe désignée par son OID. */
    EC("EC", "SHA256withECDSA"),

    /** ECDSA brainpoolP256r1, paramètres de courbe explicites dans le certificat (comme les CSCA français). */
    EC_EXPLICIT("EC", "SHA256withECDSA"),
}

/** Certificat de test et sa paire de clés. */
class TestCredential(
    val certificate: X509Certificate,
    val keyPair: KeyPair,
    val keyType: TestKeyType,
) {
    val holder: X509CertificateHolder get() = X509CertificateHolder(certificate.encoded)
    val privateKey: PrivateKey get() = keyPair.private
}

/** Émetteur du certificat DS : l'ancien CSCA, ou le nouveau (atteignable seulement via le lien). */
enum class CscaGeneration { OLD, NEW }

/**
 * Hiérarchie ICAO factice générée à la volée :
 *
 * - [oldCsca] : CSCA auto-signé, première génération de clé ;
 * - [newCsca] : CSCA auto-signé, nouvelle clé, **même sujet** que l'ancien ;
 * - [link] : certificat de lien, sujet et clé du nouveau CSCA, signé par l'ancienne clé ;
 * - [issueDs] : certificats DS signés par l'un ou l'autre ;
 * - [document] : DG factices et SOD signé (voir [TestDocument]).
 *
 * Deux instances de même [name] et de même [seed] produisent les mêmes clés : donner un
 * [name] différent pour simuler un pays ou un émetteur inconnu.
 */
class TestPki(
    val keyType: TestKeyType = TestKeyType.RSA,
    val name: String = "Sceau Test",
    val country: String = "FR",
    seed: Long = SEEDS.incrementAndGet(),
) {
    val random: SecureRandom = TestCrypto.seededRandom(seed)
    private val serials = AtomicLong(1000)

    val cscaSubject: X500Name = X500Name("C=$country,O=$name,CN=CSCA $name")

    val oldCsca: TestCredential by lazy { selfSigned(generateKeyPair(keyType), CSCA_NOT_BEFORE, CSCA_NOT_AFTER) }

    val newCsca: TestCredential by lazy { selfSigned(generateKeyPair(keyType), NEW_CSCA_NOT_BEFORE, NEW_CSCA_NOT_AFTER) }

    val link: TestCredential by lazy {
        issue(
            subject = cscaSubject,
            subjectKeys = newCsca.keyPair,
            issuer = oldCsca,
            notBefore = NEW_CSCA_NOT_BEFORE,
            notAfter = CSCA_NOT_AFTER,
            serial = nextSerial(),
            ca = true,
        )
    }

    /** Lien inverse : sujet et clé de l'ancien CSCA, signé par la nouvelle clé (pour les cycles). */
    val reverseLink: TestCredential by lazy {
        issue(
            subject = cscaSubject,
            subjectKeys = oldCsca.keyPair,
            issuer = newCsca,
            notBefore = NEW_CSCA_NOT_BEFORE,
            notAfter = CSCA_NOT_AFTER,
            serial = nextSerial(),
            ca = true,
        )
    }

    /** DS par défaut, signé par l'ancien CSCA, valide de 2020 à 2031. */
    val ds: TestCredential by lazy { issueDs() }

    fun csca(generation: CscaGeneration): TestCredential = if (generation == CscaGeneration.OLD) oldCsca else newCsca

    fun nextSerial(): BigInteger = BigInteger.valueOf(serials.incrementAndGet())

    fun issueDs(
        signedBy: CscaGeneration = CscaGeneration.OLD,
        notBefore: LocalDate = DS_NOT_BEFORE,
        notAfter: LocalDate = DS_NOT_AFTER,
        serial: BigInteger = nextSerial(),
        dsKeyType: TestKeyType = keyType,
        commonName: String = "DS $name",
    ): TestCredential =
        issue(
            subject = X500Name("C=$country,O=$name,CN=$commonName"),
            subjectKeys = generateKeyPair(dsKeyType),
            issuer = csca(signedBy),
            notBefore = notBefore,
            notAfter = notAfter,
            serial = serial,
            ca = false,
            subjectKeyType = dsKeyType,
        )

    /** Magasin de test contenant [credentials], tous de la provenance [source]. */
    fun trustStore(
        vararg credentials: TestCredential,
        source: TrustSource = TrustSource.ANTS,
    ): TestTrustStore = TestTrustStore.of(credentials.map { it.certificate }, source)

    /** Document factice signé par [ds]. Voir [TestDocument.Builder] pour les options. */
    fun document(configure: TestDocument.Builder.() -> Unit = {}): TestDocument = TestDocument.Builder(this).apply(configure).build()

    /** Copie de [credential] dont un octet de la signature du certificat est altéré. */
    fun withTamperedSignature(credential: TestCredential): TestCredential {
        val original = Certificate.getInstance(credential.certificate.encoded)
        val signature = original.signature.bytes
        signature[signature.size / 2] = (signature[signature.size / 2].toInt() xor 0x01).toByte()
        val vector = ASN1EncodableVector()
        vector.add(original.tbsCertificate)
        vector.add(original.signatureAlgorithm)
        vector.add(DERBitString(signature))
        val holder = X509CertificateHolder(DERSequence(vector).getEncoded(ASN1Encoding.DER))
        val certificate = JcaX509CertificateConverter().setProvider(TestCrypto.provider).getCertificate(holder)
        return TestCredential(certificate, credential.keyPair, credential.keyType)
    }

    fun generateKeyPair(type: TestKeyType): KeyPair =
        if (type.keyAlgorithm == "RSA") {
            generateRsa(RSA_BITS)
        } else {
            generateEc(CURVE)
        }

    fun generateRsa(bits: Int): KeyPair =
        KeyPairGenerator
            .getInstance("RSA", TestCrypto.provider)
            .apply { initialize(bits, random) }
            .generateKeyPair()

    fun generateEc(curve: String): KeyPair =
        KeyPairGenerator
            .getInstance("EC", TestCrypto.provider)
            .apply { initialize(ECGenParameterSpec(curve), random) }
            .generateKeyPair()

    private fun selfSigned(
        keys: KeyPair,
        notBefore: LocalDate,
        notAfter: LocalDate,
    ): TestCredential {
        val serial = nextSerial()
        val spki = subjectPublicKeyInfo(keys, keyType)
        val builder =
            X509v3CertificateBuilder(cscaSubject, serial, TestCrypto.date(notBefore), TestCrypto.date(notAfter), cscaSubject, spki)
        addExtensions(builder, spki, spki, ca = true)
        return TestCredential(sign(builder, keys.private, keyType), keys, keyType)
    }

    private fun issue(
        subject: X500Name,
        subjectKeys: KeyPair,
        issuer: TestCredential,
        notBefore: LocalDate,
        notAfter: LocalDate,
        serial: BigInteger,
        ca: Boolean,
        subjectKeyType: TestKeyType = keyType,
    ): TestCredential {
        val spki = subjectPublicKeyInfo(subjectKeys, subjectKeyType)
        val issuerSpki = issuer.holder.subjectPublicKeyInfo
        val builder =
            X509v3CertificateBuilder(
                X500Name.getInstance(issuer.certificate.subjectX500Principal.encoded),
                serial,
                TestCrypto.date(notBefore),
                TestCrypto.date(notAfter),
                subject,
                spki,
            )
        addExtensions(builder, spki, issuerSpki, ca)
        return TestCredential(sign(builder, issuer.privateKey, issuer.keyType), subjectKeys, subjectKeyType)
    }

    private fun addExtensions(
        builder: X509v3CertificateBuilder,
        spki: SubjectPublicKeyInfo,
        issuerSpki: SubjectPublicKeyInfo,
        ca: Boolean,
    ) {
        val utils = JcaX509ExtensionUtils()
        builder.addExtension(Extension.subjectKeyIdentifier, false, utils.createSubjectKeyIdentifier(spki))
        builder.addExtension(Extension.authorityKeyIdentifier, false, utils.createAuthorityKeyIdentifier(issuerSpki))
        if (ca) {
            builder.addExtension(Extension.basicConstraints, true, BasicConstraints(0))
            builder.addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.keyCertSign or KeyUsage.cRLSign))
        } else {
            builder.addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.digitalSignature))
        }
    }

    private fun sign(
        builder: X509v3CertificateBuilder,
        signerKey: PrivateKey,
        signerType: TestKeyType,
    ): X509Certificate {
        val signer = JcaContentSignerBuilder(signerType.signatureAlgorithm).setProvider(TestCrypto.provider).build(signerKey)
        return JcaX509CertificateConverter().setProvider(TestCrypto.provider).getCertificate(builder.build(signer))
    }

    companion object {
        private val SEEDS = AtomicLong(20_260_925)

        const val RSA_BITS = 2048
        const val CURVE = "brainpoolP256r1"

        val CSCA_NOT_BEFORE: LocalDate = LocalDate.of(2010, 1, 1)
        val CSCA_NOT_AFTER: LocalDate = LocalDate.of(2040, 1, 1)
        val NEW_CSCA_NOT_BEFORE: LocalDate = LocalDate.of(2018, 1, 1)
        val NEW_CSCA_NOT_AFTER: LocalDate = LocalDate.of(2048, 1, 1)
        val DS_NOT_BEFORE: LocalDate = LocalDate.of(2020, 1, 1)
        val DS_NOT_AFTER: LocalDate = LocalDate.of(2031, 1, 1)

        /**
         * Encodage de la clé publique ; en [TestKeyType.EC_EXPLICIT], la courbe est décrite
         * par ses paramètres (X9ECParameters) au lieu de son OID.
         */
        fun subjectPublicKeyInfo(
            keys: KeyPair,
            type: TestKeyType,
        ): SubjectPublicKeyInfo {
            val named = SubjectPublicKeyInfo.getInstance(keys.public.encoded)
            if (type != TestKeyType.EC_EXPLICIT) return named
            val curve = ECNamedCurveTable.getByName(CURVE)
            val algorithm = AlgorithmIdentifier(X9ObjectIdentifiers.id_ecPublicKey, X962Parameters(curve))
            return SubjectPublicKeyInfo.getInstance(
                SubjectPublicKeyInfo(algorithm, named.publicKeyData.bytes).getEncoded(ASN1Encoding.DER),
            )
        }
    }
}
