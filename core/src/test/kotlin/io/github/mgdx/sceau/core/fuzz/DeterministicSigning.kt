package io.github.mgdx.sceau.core.fuzz

import io.github.mgdx.sceau.testchip.TestCrypto
import org.bouncycastle.asn1.ASN1Encodable
import org.bouncycastle.asn1.ASN1Encoding
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.ASN1Sequence
import org.bouncycastle.asn1.DERBitString
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.asn1.DERSet
import org.bouncycastle.asn1.cms.Attribute
import org.bouncycastle.asn1.cms.AttributeTable
import org.bouncycastle.asn1.cms.CMSAttributes
import org.bouncycastle.asn1.cms.Time
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.asn1.x509.Certificate
import org.bouncycastle.asn1.x9.X9ObjectIdentifiers
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cms.CMSProcessableByteArray
import org.bouncycastle.cms.CMSSignedDataGenerator
import org.bouncycastle.cms.DefaultSignedAttributeTableGenerator
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder
import org.bouncycastle.crypto.digests.SHA1Digest
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.engines.RSAEngine
import org.bouncycastle.crypto.params.RSAKeyParameters
import org.bouncycastle.crypto.signers.ISO9796d2Signer
import org.bouncycastle.crypto.util.PrivateKeyFactory
import org.bouncycastle.operator.ContentSigner
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.RSAPrivateKey
import java.util.Date
import java.util.Random

/**
 * Signatures reproductibles d'une exécution à l'autre : RSA PKCS#1 v1.5 (déterministe par
 * nature) et ECDSA déterministe (RFC 6979, « ECDDSA » de BouncyCastle), heure de signature
 * fixe. Sans elles, les graines EC changeraient à chaque exécution (nonce ECDSA aléatoire) et
 * la graine et l'index d'un échec ne suffiraient plus à le rejouer.
 */
internal object DeterministicSigning {
    private val SIGNING_TIME = Date(1_704_067_200_000L) // 2024-01-01, dans la validité des signataires de test

    fun algorithm(key: PrivateKey): String = if (key.algorithm == "RSA") "SHA256withRSA" else "SHA256withECDDSA"

    /** Même certificat (même TBS, même algorithme annoncé), signé de façon déterministe par [issuerKey]. */
    fun reissue(
        certificate: X509CertificateHolder,
        issuerKey: PrivateKey,
    ): X509CertificateHolder {
        val structure = certificate.toASN1Structure()
        val signature =
            Signature.getInstance(algorithm(issuerKey), TestCrypto.provider).run {
                initSign(issuerKey)
                update(structure.tbsCertificate.getEncoded(ASN1Encoding.DER))
                sign()
            }
        val encodables = arrayOf<ASN1Encodable>(structure.tbsCertificate, structure.signatureAlgorithm, DERBitString(signature))
        return X509CertificateHolder(Certificate.getInstance(DERSequence(encodables)))
    }

    /** CMS SignedData de [content], signataire [signer] embarqué, attributs signés fixes. */
    fun signedData(
        contentType: ASN1ObjectIdentifier,
        content: ByteArray,
        signer: X509CertificateHolder,
        signerKey: PrivateKey,
    ): ByteArray {
        val contentSigner = contentSigner(signerKey)
        val digests = JcaDigestCalculatorProviderBuilder().setProvider(TestCrypto.provider).build()
        val signingTime = AttributeTable(Attribute(CMSAttributes.signingTime, DERSet(Time(SIGNING_TIME))))
        val signerInfo =
            JcaSignerInfoGeneratorBuilder(digests)
                .setSignedAttributeGenerator(DefaultSignedAttributeTableGenerator(signingTime))
                .build(contentSigner, signer)
        val generator = CMSSignedDataGenerator()
        generator.addSignerInfoGenerator(signerInfo)
        generator.addCertificate(signer)
        return generator.generate(CMSProcessableByteArray(contentType, content), true).encoded
    }

    /** JcaContentSignerBuilder ne connaît pas « ECDDSA » : signataire ecdsa-with-SHA256 écrit ici. */
    private fun contentSigner(key: PrivateKey): ContentSigner {
        if (key.algorithm == "RSA") return JcaContentSignerBuilder(algorithm(key)).setProvider(TestCrypto.provider).build(key)
        val signature = Signature.getInstance(algorithm(key), TestCrypto.provider).apply { initSign(key) }
        val buffer = ByteArrayOutputStream()
        return object : ContentSigner {
            override fun getAlgorithmIdentifier() = AlgorithmIdentifier(X9ObjectIdentifiers.ecdsa_with_SHA256)

            override fun getOutputStream(): OutputStream = buffer

            override fun getSignature(): ByteArray {
                signature.update(buffer.toByteArray())
                return signature.sign()
            }
        }
    }

    /**
     * Réponse d'Active Authentication reproductible : RSA ISO/IEC 9796-2 schéma 1 (M1 tiré
     * d'un générateur fixe, SHA-1 à trailer implicite ou SHA-256 explicite), ECDSA r‖s.
     */
    fun aaResponse(
        key: PrivateKey,
        digestAlgorithm: String?,
        challenge: ByteArray,
    ): ByteArray =
        when (key) {
            is RSAPrivateKey -> {
                val implicit = digestAlgorithm == null || digestAlgorithm.replace("-", "").uppercase() == "SHA1"
                val digest = if (implicit) SHA1Digest() else SHA256Digest()
                val signer = ISO9796d2Signer(RSAEngine(), digest, implicit)
                signer.init(true, PrivateKeyFactory.createKey(key.encoded) as RSAKeyParameters)
                val blockLength = (key.modulus.bitLength() + 7) / 8
                val m1 = ByteArray(blockLength - digest.digestSize - if (implicit) 2 else 3).also { Random(M1_SEED).nextBytes(it) }
                signer.update(m1, 0, m1.size)
                signer.update(challenge, 0, challenge.size)
                signer.generateSignature()
            }

            is ECPrivateKey -> {
                val name = "${(digestAlgorithm ?: "SHA-1").replace("-", "")}withECDDSA"
                val der =
                    Signature.getInstance(name, TestCrypto.provider).run {
                        initSign(key)
                        update(challenge)
                        ASN1Sequence.getInstance(sign())
                    }
                val size = (key.params.curve.field.fieldSize + 7) / 8
                unsigned(der.getObjectAt(0), size) + unsigned(der.getObjectAt(1), size)
            }

            else -> {
                error("clé AA")
            }
        }

    private fun unsigned(
        value: ASN1Encodable,
        size: Int,
    ): ByteArray {
        val bytes = (value as ASN1Integer).value.toByteArray()
        val stripped = if (bytes.size > size) bytes.copyOfRange(bytes.size - size, bytes.size) else bytes
        return ByteArray(size - stripped.size) + stripped
    }

    private const val M1_SEED = 9796L
}
