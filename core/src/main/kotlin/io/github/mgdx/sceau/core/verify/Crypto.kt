package io.github.mgdx.sceau.core.verify

import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.ASN1String
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x500.style.BCStyle
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.DefaultAlgorithmNameFinder
import org.bouncycastle.operator.OperatorCreationException
import org.bouncycastle.util.encoders.Hex
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.security.Provider
import java.security.cert.X509Certificate
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
        runCatching {
            X500Name
                .getInstance(certificate.subjectX500Principal.encoded)
                .getRDNs(BCStyle.C)
                .firstOrNull()
                ?.first
                ?.value
                ?.let { (it as? ASN1String)?.string ?: it.toString() }
                ?.trim()
                ?.uppercase()
        }.getOrNull()

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
