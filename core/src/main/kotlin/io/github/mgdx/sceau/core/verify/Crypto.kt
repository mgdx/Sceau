package io.github.mgdx.sceau.core.verify

import org.bouncycastle.asn1.ASN1Encodable
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.ASN1Sequence
import org.bouncycastle.asn1.ASN1String
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import org.bouncycastle.asn1.pkcs.RSASSAPSSparams
import org.bouncycastle.asn1.teletrust.TeleTrusTObjectIdentifiers
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x500.style.BCStyle
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.asn1.x9.X9FieldID
import org.bouncycastle.asn1.x9.X9ObjectIdentifiers
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.DefaultAlgorithmNameFinder
import org.bouncycastle.operator.OperatorCreationException
import org.bouncycastle.util.encoders.Hex
import java.math.BigInteger
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.security.Provider
import java.security.PublicKey
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import javax.crypto.interfaces.DHPublicKey
import javax.security.auth.x500.X500Principal
import org.bouncycastle.asn1.pkcs.RSAPublicKey as Asn1RsaPublicKey
import org.bouncycastle.jce.interfaces.ECPublicKey as BcECPublicKey

/**
 * Cryptographic helpers shared by the verification.
 *
 * The BouncyCastle provider is an explicit instance passed to each API: on Android, the
 * system provider named "BC" is a stripped-down version that knows neither the Brainpool
 * curves nor explicit EC parameters.
 */
internal object Crypto {
    val provider: Provider by lazy { BouncyCastleProvider() }

    private val nameFinder = DefaultAlgorithmNameFinder()

    /** Readable name of an algorithm (e.g. "SHA256WITHECDSA"), or the OID if unknown. */
    fun algorithmName(oid: ASN1ObjectIdentifier): String = nameFinder.getAlgorithmName(oid)

    /** True if BouncyCastle knows a name for this OID. */
    fun isKnownAlgorithm(oid: ASN1ObjectIdentifier): Boolean = nameFinder.hasAlgorithmName(oid)

    /** SHA-256 fingerprint of a DER encoding, in lowercase hexadecimal without separators. */
    fun sha256Hex(encoded: ByteArray): String = Hex.toHexString(MessageDigest.getInstance("SHA-256").digest(encoded))

    fun fingerprint(certificate: X509Certificate): String = sha256Hex(certificate.encoded)

    /** Country code (subject C attribute) in uppercase, or null if absent. */
    fun country(certificate: X509Certificate): String? =
        runCatching { country(X500Name.getInstance(certificate.subjectX500Principal.encoded)) }.getOrNull()

    /** Country code (C attribute) of an X.500 name in uppercase, or null if absent. */
    fun country(name: X500Name): String? =
        runCatching {
            name
                .getRDNs(BCStyle.C)
                .firstOrNull()
                ?.first
                ?.value
                ?.let { (it as? ASN1String)?.string ?: it.toString() }
                ?.trim()
                ?.uppercase()
        }.getOrNull()

    /**
     * Broken hash functions, rejected for DG hashes, the SOD signature, certificates and AA
     * (audit V9): MD2, MD4, MD5, RIPEMD-128, alone or within a signature OID. SHA-1 remains
     * accepted: documents that are still valid use it.
     */
    private val WEAK_ALGORITHMS: Set<ASN1ObjectIdentifier> =
        setOf(
            PKCSObjectIdentifiers.md2,
            PKCSObjectIdentifiers.md4,
            PKCSObjectIdentifiers.md5,
            TeleTrusTObjectIdentifiers.ripemd128,
            PKCSObjectIdentifiers.md2WithRSAEncryption,
            PKCSObjectIdentifiers.md4WithRSAEncryption,
            PKCSObjectIdentifiers.md5WithRSAEncryption,
            TeleTrusTObjectIdentifiers.rsaSignatureWithripemd128,
        )

    /** JCA names of the same hash functions (AA, where the algorithm is designated by name). */
    private val WEAK_DIGEST_NAMES = setOf("MD2", "MD4", "MD5", "RIPEMD128")

    /** Minimum size of an RSA signing key (DS, chain issuers, AA). */
    const val MIN_RSA_BITS = 1024

    /**
     * Upper bounds for public keys (audit V30), at least twice the largest real keys: RSA
     * modulus, RSA public exponent, DH prime p, EC base field.
     */
    const val MAX_RSA_BITS = 8192
    const val MAX_RSA_EXPONENT_BITS = 64
    const val MAX_DH_BITS = 8192
    const val MAX_EC_FIELD_BITS = 1024

    /** True if [algorithm] (hash or signature, including the hash of an RSASSA-PSS) is broken. */
    fun isWeakAlgorithm(algorithm: AlgorithmIdentifier): Boolean {
        if (algorithm.algorithm in WEAK_ALGORITHMS) return true
        if (algorithm.algorithm != PKCSObjectIdentifiers.id_RSASSA_PSS) return false
        val parameters = runCatching { RSASSAPSSparams.getInstance(algorithm.parameters) }.getOrNull() ?: return false
        return parameters.hashAlgorithm.algorithm in WEAK_ALGORITHMS
    }

    /** True if [digestName] ("MD5", "RIPEMD-128"…) designates a broken hash function. */
    fun isWeakDigestName(digestName: String): Boolean = digestName.uppercase().replace("-", "") in WEAK_DIGEST_NAMES

    /**
     * Description (algorithm and size, never the key) of [key] if its size falls outside the
     * accepted bounds, otherwise null (audits V9 and V30). Lower bound: RSA under [MIN_RSA_BITS]
     * bits. Upper bounds, checked before any computation: a huge key supplied by the chip or by a
     * certificate would cost minutes of non-interruptible computation (`modPow`), whereas no real
     * key comes close (RSA from 2,048 to 4,096 bits, curves from 256 to 521 bits). An unknown key
     * type gives null: the subsequent verification handles it, as before.
     */
    fun unsupportedKey(key: PublicKey): String? =
        when (key) {
            is RSAPublicKey -> {
                rsaBounds(key.modulus, key.publicExponent)
            }

            is ECPublicKey -> {
                key.params
                    ?.curve
                    ?.field
                    ?.fieldSize
                    ?.let(::ecBounds)
            }

            is BcECPublicKey -> {
                key.parameters
                    ?.curve
                    ?.fieldSize
                    ?.let(::ecBounds)
            }

            is DHPublicKey -> {
                key.params?.p?.let { dhBounds(it.bitLength()) }
            }

            else -> {
                null
            }
        }

    /**
     * Same check as [unsupportedKey] on a still-encoded key (certificate, `SecurityInfo`),
     * without reconstructing it: the provider validates the key while reconstructing it
     * (primality tests, modular exponentiation), at a cost that grows with its size. An
     * unreadable structure gives null: the subsequent verification rejects it.
     */
    fun unsupportedKey(info: SubjectPublicKeyInfo): String? =
        runCatching {
            when (info.algorithm.algorithm) {
                PKCSObjectIdentifiers.rsaEncryption, PKCSObjectIdentifiers.id_RSASSA_PSS -> {
                    val key = Asn1RsaPublicKey.getInstance(info.parsePublicKey())
                    rsaBounds(key.modulus, key.publicExponent)
                }

                X9ObjectIdentifiers.id_ecPublicKey -> {
                    explicitFieldBits(info.algorithm.parameters)?.let(::ecBounds)
                }

                PKCSObjectIdentifiers.dhKeyAgreement, X9ObjectIdentifiers.dhpublicnumber -> {
                    // Both DHParameter (PKCS #3) and DomainParameters (X9.42) start with p.
                    val p = ASN1Integer.getInstance(ASN1Sequence.getInstance(info.algorithm.parameters).getObjectAt(0))
                    dhBounds(p.positiveValue.bitLength())
                }

                else -> {
                    null
                }
            }
        }.getOrNull()

    /**
     * Base field size of explicit EC parameters (X9.62 `ECParameters`), read from `fieldID`
     * without building the curve; null for a named curve (known and bounded size).
     */
    private fun explicitFieldBits(parameters: ASN1Encodable?): Int? {
        val sequence = parameters as? ASN1Sequence ?: return null
        val field = X9FieldID.getInstance(sequence.getObjectAt(1))
        return when (field.identifier) {
            X9ObjectIdentifiers.prime_field -> {
                ASN1Integer.getInstance(field.parameters).positiveValue.bitLength()
            }

            // Characteristic 2: the field size is the degree m, the first element.
            X9ObjectIdentifiers.characteristic_two_field -> {
                val m = ASN1Integer.getInstance(ASN1Sequence.getInstance(field.parameters).getObjectAt(0)).positiveValue
                if (m.bitLength() < Int.SIZE_BITS) m.toInt() else Int.MAX_VALUE
            }

            else -> {
                null
            }
        }
    }

    private fun rsaBounds(
        modulus: BigInteger,
        publicExponent: BigInteger,
    ): String? {
        val bits = modulus.bitLength()
        val exponentBits = publicExponent.bitLength()
        return when {
            bits < MIN_RSA_BITS || bits > MAX_RSA_BITS -> "RSA-$bits"
            exponentBits > MAX_RSA_EXPONENT_BITS -> "RSA-$bits/e-$exponentBits"
            else -> null
        }
    }

    private fun ecBounds(fieldBits: Int): String? = "EC-$fieldBits".takeIf { fieldBits > MAX_EC_FIELD_BITS }

    private fun dhBounds(primeBits: Int): String? = "DH-$primeBits".takeIf { primeBits > MAX_DH_BITS }

    fun principal(name: X500Name): X500Principal = X500Principal(name.encoded)

    fun subject(holder: X509CertificateHolder): String = principal(holder.subject).name

    /**
     * True if the exception reflects an algorithm unknown to the provider (and not an
     * invalid signature or malformed data).
     */
    fun isUnsupportedAlgorithm(error: Throwable): Boolean =
        generateSequence(error) { it.cause }.take(MAX_CAUSES).any {
            it is NoSuchAlgorithmException ||
                it is OperatorCreationException ||
                (it is IllegalArgumentException && it.message.orEmpty().startsWith("Unknown signature type"))
        }

    private const val MAX_CAUSES = 10
}
