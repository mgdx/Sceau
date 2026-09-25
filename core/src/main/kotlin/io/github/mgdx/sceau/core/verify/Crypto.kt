package io.github.mgdx.sceau.core.verify

import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.ASN1String
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import org.bouncycastle.asn1.pkcs.RSASSAPSSparams
import org.bouncycastle.asn1.teletrust.TeleTrusTObjectIdentifiers
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x500.style.BCStyle
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.DefaultAlgorithmNameFinder
import org.bouncycastle.operator.OperatorCreationException
import org.bouncycastle.util.encoders.Hex
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.security.Provider
import java.security.PublicKey
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import javax.security.auth.x500.X500Principal

/**
 * Outils cryptographiques communs à la vérification.
 *
 * Le fournisseur BouncyCastle est une instance explicite passée à chaque API : sur Android,
 * le fournisseur système nommé "BC" est une version tronquée qui ne connaît ni les courbes
 * Brainpool ni les paramètres EC explicites.
 */
internal object Crypto {
    val provider: Provider by lazy { BouncyCastleProvider() }

    private val nameFinder = DefaultAlgorithmNameFinder()

    /** Nom lisible d'un algorithme (ex. "SHA256WITHECDSA"), ou l'OID s'il est inconnu. */
    fun algorithmName(oid: ASN1ObjectIdentifier): String = nameFinder.getAlgorithmName(oid)

    /** Vrai si BouncyCastle connaît un nom pour cet OID. */
    fun isKnownAlgorithm(oid: ASN1ObjectIdentifier): Boolean = nameFinder.hasAlgorithmName(oid)

    /** Empreinte SHA-256 d'un encodage DER, en hexadécimal minuscule sans séparateur. */
    fun sha256Hex(encoded: ByteArray): String = Hex.toHexString(MessageDigest.getInstance("SHA-256").digest(encoded))

    fun fingerprint(certificate: X509Certificate): String = sha256Hex(certificate.encoded)

    /** Code pays (attribut C du sujet) en majuscules, ou null s'il est absent. */
    fun country(certificate: X509Certificate): String? =
        runCatching { country(X500Name.getInstance(certificate.subjectX500Principal.encoded)) }.getOrNull()

    /** Code pays (attribut C) d'un nom X.500 en majuscules, ou null s'il est absent. */
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
     * Fonctions de hachage cassées, refusées pour les empreintes des DG, la signature du SOD, les
     * certificats et l'AA (audit V9) : MD2, MD4, MD5, RIPEMD-128, seules ou dans un OID de
     * signature. SHA-1 reste accepté : des documents encore valides l'emploient.
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

    /** Noms JCA des mêmes fonctions de hachage (AA, où l'algorithme est désigné par son nom). */
    private val WEAK_DIGEST_NAMES = setOf("MD2", "MD4", "MD5", "RIPEMD128")

    /** Taille minimale d'une clé RSA de signature (DS, émetteurs de la chaîne, AA). */
    const val MIN_RSA_BITS = 1024

    /** Vrai si [algorithm] (hachage ou signature, y compris le hachage d'un RSASSA-PSS) est cassé. */
    fun isWeakAlgorithm(algorithm: AlgorithmIdentifier): Boolean {
        if (algorithm.algorithm in WEAK_ALGORITHMS) return true
        if (algorithm.algorithm != PKCSObjectIdentifiers.id_RSASSA_PSS) return false
        val parameters = runCatching { RSASSAPSSparams.getInstance(algorithm.parameters) }.getOrNull() ?: return false
        return parameters.hashAlgorithm.algorithm in WEAK_ALGORITHMS
    }

    /** Vrai si [digestName] ("MD5", "RIPEMD-128"…) désigne une fonction de hachage cassée. */
    fun isWeakDigestName(digestName: String): Boolean = digestName.uppercase().replace("-", "") in WEAK_DIGEST_NAMES

    /** "RSA-<bits>" si [key] est une clé RSA trop courte pour être acceptée, sinon null. */
    fun weakKey(key: PublicKey): String? =
        (key as? RSAPublicKey)
            ?.modulus
            ?.bitLength()
            ?.takeIf { it < MIN_RSA_BITS }
            ?.let { "RSA-$it" }

    fun principal(name: X500Name): X500Principal = X500Principal(name.encoded)

    fun subject(holder: X509CertificateHolder): String = principal(holder.subject).name

    /**
     * Vrai si l'exception traduit un algorithme inconnu du fournisseur (et non une
     * signature invalide ou une donnée mal formée).
     */
    fun isUnsupportedAlgorithm(error: Throwable): Boolean =
        generateSequence(error) { it.cause }.take(MAX_CAUSES).any {
            it is NoSuchAlgorithmException ||
                it is OperatorCreationException ||
                (it is IllegalArgumentException && it.message.orEmpty().startsWith("Unknown signature type"))
        }

    private const val MAX_CAUSES = 10
}
