package io.github.mgdx.sceau.core.trust

import org.bouncycastle.asn1.ASN1OctetString
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x500.style.BCStyle
import org.bouncycastle.asn1.x500.style.IETFUtils
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

    fun isSelfSigned(certificate: X509Certificate): Boolean =
        certificate.subjectX500Principal == certificate.issuerX500Principal &&
            isSignedBy(certificate, certificate.publicKey)
}
