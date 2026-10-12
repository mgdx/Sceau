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
 * Cryptographic tools of the trust store.
 *
 * Always the explicit [BouncyCastleProvider] instance: on Android, the system provider
 * named "BC" is a stripped-down version that cannot read all CSCA certificates (EC keys with
 * explicit curve parameters, in particular).
 */
internal object TrustCrypto {
    val provider: BouncyCastleProvider by lazy { BouncyCastleProvider() }

    private val converter: JcaX509CertificateConverter by lazy {
        JcaX509CertificateConverter().setProvider(provider)
    }

    private const val HEX_DIGITS = "0123456789abcdef"

    fun sha256Hex(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    /** Lowercase hexadecimal without separator. */
    fun hex(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xff
            out.append(HEX_DIGITS[v ushr 4]).append(HEX_DIGITS[v and 0x0f])
        }
        return out.toString()
    }

    /** Converts a BouncyCastle certificate into an [X509Certificate] through the BouncyCastle provider. */
    fun toX509(holder: X509CertificateHolder): X509Certificate = converter.getCertificate(holder)

    fun toX509(der: ByteArray): X509Certificate = toX509(X509CertificateHolder(der))

    /** C attribute of the subject, in upper case; empty string if the subject has none. */
    fun country(certificate: X509Certificate): String {
        val name = X500Name.getInstance(certificate.subjectX500Principal.encoded)
        val rdn = name.getRDNs(BCStyle.C).firstOrNull() ?: return ""
        val value = rdn.first?.value ?: return ""
        return IETFUtils.valueToString(value).trim().uppercase(Locale.ROOT)
    }

    /** Raw bytes of the KeyIdentifier of the Subject Key Identifier extension, or null. */
    fun subjectKeyId(certificate: X509Certificate): ByteArray? {
        val extension = certificate.getExtensionValue(Extension.subjectKeyIdentifier.id) ?: return null
        return try {
            val inner = ASN1OctetString.getInstance(extension).octets
            SubjectKeyIdentifier.getInstance(inner).keyIdentifier
        } catch (ignored: IllegalArgumentException) {
            null
        }
    }

    /** True if the signature of [certificate] verifies with [issuerKey]. */
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
     * Bytes of the KeyIdentifier of the Authority Key Identifier extension; null if the extension
     * is absent, unreadable or without a key identifier. [present] is true if the extension exists.
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
     * True for a self-signed CSCA, false for a link certificate, without verifying a signature
     * in the common case: a link certificate often has the same subject and issuer DN as the
     * CSCA it renews, but its Authority Key Identifier designates the old key and therefore differs
     * from its Subject Key Identifier. ICAO 9303-12 mandates the AKI in link certificates: an absent AKI
     * with a present SKI designates a self-signed CSCA. The signature is verified only if the
     * key identifiers are inconclusive (neither of them, or an AKI without KeyIdentifier).
     * Verifying 600 signatures, including EC keys with explicit parameters in pure Java, cost
     * several tens of seconds on an old phone.
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
