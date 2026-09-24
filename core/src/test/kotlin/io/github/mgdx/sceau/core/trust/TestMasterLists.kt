package io.github.mgdx.sceau.core.trust

import org.bouncycastle.asn1.ASN1EncodableVector
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.asn1.DERSet
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.asn1.x9.ECNamedCurveTable
import org.bouncycastle.asn1.x9.X962Parameters
import org.bouncycastle.asn1.x9.X9ObjectIdentifiers
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils
import org.bouncycastle.cms.CMSProcessableByteArray
import org.bouncycastle.cms.CMSSignedDataGenerator
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder
import org.bouncycastle.jce.interfaces.ECPublicKey
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Date

/** Fabrique de Master Lists factices pour les tests (aucune donnée réelle). */
internal object TestMasterLists {
    val provider = BouncyCastleProvider()
    val CSCA_MASTER_LIST = ASN1ObjectIdentifier("2.23.136.1.1.2")

    private val notBefore = Date(1_700_000_000_000L)
    private val notAfter = Date(2_000_000_000_000L)

    fun rsaKeyPair(): KeyPair = KeyPairGenerator.getInstance("RSA", provider).apply { initialize(2048) }.generateKeyPair()

    fun ecKeyPair(): KeyPair =
        KeyPairGenerator
            .getInstance("EC", provider)
            .apply {
                initialize(ECGenParameterSpec("secp256r1"))
            }.generateKeyPair()

    /** Encodage de la clé EC avec les paramètres de courbe explicites (et non un OID nommé). */
    fun explicitEcPublicKeyInfo(publicKey: PublicKey): SubjectPublicKeyInfo {
        val params = X962Parameters(ECNamedCurveTable.getByName("secp256r1"))
        val point = (publicKey as ECPublicKey).q.getEncoded(false)
        return SubjectPublicKeyInfo(AlgorithmIdentifier(X9ObjectIdentifiers.id_ecPublicKey, params), point)
    }

    fun certificate(
        subject: String,
        issuer: String,
        subjectKey: SubjectPublicKeyInfo,
        signingKey: KeyPair,
        serial: BigInteger = BigInteger.valueOf(System.nanoTime()),
        ca: Boolean = true,
    ): X509CertificateHolder {
        val builder =
            X509v3CertificateBuilder(X500Name(issuer), serial, notBefore, notAfter, X500Name(subject), subjectKey)
                .addExtension(Extension.basicConstraints, true, BasicConstraints(ca))
                .addExtension(Extension.subjectKeyIdentifier, false, JcaX509ExtensionUtils().createSubjectKeyIdentifier(subjectKey))
        val algorithm = if (signingKey.private.algorithm == "EC") "SHA256withECDSA" else "SHA256withRSA"
        return builder.build(JcaContentSignerBuilder(algorithm).setProvider(provider).build(signingKey.private))
    }

    fun selfSigned(
        subject: String,
        keyPair: KeyPair,
        subjectKey: SubjectPublicKeyInfo = SubjectPublicKeyInfo.getInstance(keyPair.public.encoded),
        serial: BigInteger = BigInteger.valueOf(System.nanoTime()),
    ): X509CertificateHolder = certificate(subject, subject, subjectKey, keyPair, serial)

    fun content(certificates: List<X509CertificateHolder>): ByteArray {
        val set = ASN1EncodableVector()
        certificates.forEach { set.add(it.toASN1Structure()) }
        return DERSequence(arrayOf(ASN1Integer(0), DERSet(set))).encoded
    }

    fun signedMasterList(
        content: ByteArray,
        signerCertificate: X509CertificateHolder?,
        signerKey: KeyPair?,
        contentType: ASN1ObjectIdentifier = CSCA_MASTER_LIST,
        extraCertificates: List<X509CertificateHolder> = emptyList(),
    ): ByteArray {
        val generator = CMSSignedDataGenerator()
        if (signerCertificate != null && signerKey != null) {
            val signer = JcaContentSignerBuilder("SHA256withRSA").setProvider(provider).build(signerKey.private)
            val digests = JcaDigestCalculatorProviderBuilder().setProvider(provider).build()
            generator.addSignerInfoGenerator(JcaSignerInfoGeneratorBuilder(digests).build(signer, signerCertificate))
            generator.addCertificate(signerCertificate)
        }
        extraCertificates.forEach { generator.addCertificate(it) }
        return generator.generate(CMSProcessableByteArray(contentType, content), true).encoded
    }

    /** Une Master List complète : CSCA RSA, CSCA EC à paramètres explicites et numéro de série négatif. */
    class Fixture {
        val rsaCscaKey = rsaKeyPair()
        val rsaCsca = selfSigned("C=ZZ,O=Test,CN=CSCA RSA", rsaCscaKey)
        val ecCscaKey = ecKeyPair()
        val ecCsca =
            selfSigned(
                "C=YY,O=Test,CN=CSCA EC",
                ecCscaKey,
                subjectKey = explicitEcPublicKeyInfo(ecCscaKey.public),
                serial = BigInteger.valueOf(-4242),
            )
        val signerKey = rsaKeyPair()
        val signer =
            certificate(
                subject = "C=ZZ,O=Test,CN=Master List Signer",
                issuer = "C=ZZ,O=Test,CN=CSCA RSA",
                subjectKey = SubjectPublicKeyInfo.getInstance(signerKey.public.encoded),
                signingKey = rsaCscaKey,
                ca = false,
            )
        val content = content(listOf(rsaCsca, ecCsca))
        val bytes = signedMasterList(content, signer, signerKey)
    }
}
