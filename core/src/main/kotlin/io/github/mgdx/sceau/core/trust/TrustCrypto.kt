package io.github.mgdx.sceau.core.trust

import org.bouncycastle.asn1.ASN1OctetString
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x500.style.BCStyle
import org.bouncycastle.asn1.x500.style.IETFUtils
import org.bouncycastle.asn1.x509.AuthorityKeyIdentifier
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.SubjectKeyIdentifier
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentVerifierProviderBuilder
import java.security.MessageDigest
import java.security.PublicKey
import java.security.cert.X509Certificate
import java.util.Locale

/**
 * Outils cryptographiques du magasin de confiance.
 *
 * Toujours l'instance explicite de [BouncyCastleProvider] : sur Android, le fournisseur système
 * nommé « BC » est une version tronquée qui ne sait pas lire tous les certificats CSCA (clés EC à
 * paramètres de courbe explicites, notamment).
 */
internal object TrustCrypto {
    val provider: BouncyCastleProvider by lazy { BouncyCastleProvider() }

    private val converter: JcaX509CertificateConverter by lazy {
        JcaX509CertificateConverter().setProvider(provider)
    }

    private const val HEX_DIGITS = "0123456789abcdef"

    fun sha256Hex(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    /** Hexadécimal minuscule sans séparateur. */
    fun hex(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xff
            out.append(HEX_DIGITS[v ushr 4]).append(HEX_DIGITS[v and 0x0f])
        }
        return out.toString()
    }

    /** Convertit un certificat BouncyCastle en [X509Certificate] via le provider BouncyCastle. */
    fun toX509(holder: X509CertificateHolder): X509Certificate = converter.getCertificate(holder)

    fun toX509(der: ByteArray): X509Certificate = toX509(X509CertificateHolder(der))

    /** Attribut C du sujet, en majuscules ; chaîne vide si le sujet n'en a pas. */
    fun country(certificate: X509Certificate): String {
        val name = X500Name.getInstance(certificate.subjectX500Principal.encoded)
        val rdn = name.getRDNs(BCStyle.C).firstOrNull() ?: return ""
        val value = rdn.first?.value ?: return ""
        return IETFUtils.valueToString(value).trim().uppercase(Locale.ROOT)
    }

    /** Octets bruts de la KeyIdentifier de l'extension Subject Key Identifier, ou null. */
    fun subjectKeyId(certificate: X509Certificate): ByteArray? {
        val extension = certificate.getExtensionValue(Extension.subjectKeyIdentifier.id) ?: return null
        return try {
            val inner = ASN1OctetString.getInstance(extension).octets
            SubjectKeyIdentifier.getInstance(inner).keyIdentifier
        } catch (ignored: IllegalArgumentException) {
            null
        }
    }

    /** Vrai si la signature de [certificate] est vérifiable avec [issuerKey]. */
    fun isSignedBy(
        certificate: X509Certificate,
        issuerKey: PublicKey,
    ): Boolean =
        try {
            val verifier = JcaContentVerifierProviderBuilder().setProvider(provider).build(issuerKey)
            X509CertificateHolder(certificate.encoded).isSignatureValid(verifier)
        } catch (ignored: Exception) {
            false
        }

    /**
     * Octets de la KeyIdentifier de l'extension Authority Key Identifier ; null si l'extension
     * est absente, illisible ou sans identifiant de clé. [present] vaut vrai si l'extension existe.
     */
    private class AuthorityKeyId(
        val present: Boolean,
        val keyId: ByteArray?,
    )

    private fun authorityKeyId(certificate: X509Certificate): AuthorityKeyId {
        val extension = certificate.getExtensionValue(Extension.authorityKeyIdentifier.id) ?: return AuthorityKeyId(false, null)
        return try {
            val inner = ASN1OctetString.getInstance(extension).octets
            AuthorityKeyId(true, AuthorityKeyIdentifier.getInstance(inner).keyIdentifierOctets)
        } catch (ignored: IllegalArgumentException) {
            AuthorityKeyId(true, null)
        }
    }

    /**
     * Vrai pour un CSCA auto-signé, faux pour un certificat de lien, sans vérifier de signature
     * dans le cas courant : un certificat de lien a souvent le même DN sujet et émetteur que le
     * CSCA qu'il renouvelle, mais son Authority Key Identifier désigne l'ancienne clé et diffère
     * donc de son Subject Key Identifier. ICAO 9303-12 impose l'AKI dans les liens : un AKI absent
     * avec un SKI présent désigne un CSCA auto-signé. La signature n'est vérifiée que si les
     * identifiants de clé ne permettent pas de conclure (aucun des deux, ou AKI sans KeyIdentifier).
     * Vérifier 600 signatures, dont des clés EC à paramètres explicites en Java pur, coûtait
     * plusieurs dizaines de secondes sur un téléphone ancien.
     */
    fun isSelfSigned(certificate: X509Certificate): Boolean {
        if (certificate.subjectX500Principal != certificate.issuerX500Principal) return false
        val aki = authorityKeyId(certificate)
        val ski = subjectKeyId(certificate)
        return when {
            aki.keyId != null && ski != null -> aki.keyId.contentEquals(ski)
            !aki.present && ski != null -> true
            else -> isSignedBy(certificate, certificate.publicKey)
        }
    }
}
