package io.github.mgdx.sceau.core.trust

import org.bouncycastle.asn1.ASN1OctetString
import org.bouncycastle.asn1.ASN1Primitive
import org.bouncycastle.asn1.x509.AuthorityKeyIdentifier
import org.bouncycastle.asn1.x509.Certificate
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.cert.X509CertificateHolder
import java.security.cert.X509Certificate
import java.util.Base64

/**
 * Reading of a CSCA or link certificate imported on its own (D33), in DER or PEM.
 *
 * Checks, in order:
 * 1. exactly one X.509 certificate, with no trailing byte, with a readable public key, otherwise `UNREADABLE`;
 * 2. authority certificate: `basicConstraints` with cA=TRUE, and `keyUsage`, if present,
 *    with keyCertSign, otherwise `NOT_CA` (ICAO 9303-12 mandates these two extensions for CSCAs and
 *    link certificates);
 * 3. certificate that claims to be self-signed (subject equal to the issuer, without an Authority Key Identifier
 *    distinct from its Subject Key Identifier): self-signature verified, otherwise `BAD_SIGNATURE`.
 *    A link certificate is signed by the old key, unknown here: its signature is
 *    verified later, when the chain is built, against a CSCA from the store.
 */
internal object CertificateParser {
    private const val PEM_BEGIN = "-----BEGIN "
    private const val PEM_CERTIFICATE_BEGIN = "-----BEGIN CERTIFICATE-----"
    private const val PEM_CERTIFICATE_END = "-----END CERTIFICATE-----"

    /** Index of keyCertSign in `X509Certificate.getKeyUsage()`. */
    private const val KEY_CERT_SIGN = 5

    private val WHITESPACE = Regex("\\s+")

    fun parse(bytes: ByteArray): X509Certificate {
        val certificate = read(bytes)
        if (!canSignCertificates(certificate)) throw InvalidCertificateException("NOT_CA")
        if (claimsSelfSigned(certificate) && !TrustCrypto.isSignedBy(certificate, certificate.publicKey)) {
            throw InvalidCertificateException("BAD_SIGNATURE")
        }
        return certificate
    }

    private fun read(bytes: ByteArray): X509Certificate {
        val der = pemToDer(bytes) ?: bytes
        return try {
            // fromByteArray rejects any byte after the first object: a single certificate.
            val holder = X509CertificateHolder(Certificate.getInstance(ASN1Primitive.fromByteArray(der)))
            TrustCrypto.toX509(holder).also {
                // BouncyCastle decodes the key only on demand: null for an unknown algorithm.
                checkNotNull(it.publicKey)
            }
        } catch (e: Exception) {
            throw InvalidCertificateException("UNREADABLE", e)
        }
    }

    /**
     * DER content of a PEM file, or null if [bytes] is not PEM. A PEM file must
     * contain exactly one `CERTIFICATE` block and no other block (key, revocation list…).
     */
    private fun pemToDer(bytes: ByteArray): ByteArray? {
        val text = bytes.toString(Charsets.US_ASCII)
        if (!text.contains(PEM_BEGIN)) return null
        val begin = text.indexOf(PEM_CERTIFICATE_BEGIN)
        val end = text.indexOf(PEM_CERTIFICATE_END)
        val single =
            begin >= 0 &&
                end > begin &&
                text.indexOf(PEM_BEGIN, begin + PEM_CERTIFICATE_BEGIN.length) < 0 &&
                text.indexOf(PEM_BEGIN) == begin
        if (!single) throw InvalidCertificateException("UNREADABLE")
        val body = text.substring(begin + PEM_CERTIFICATE_BEGIN.length, end).replace(WHITESPACE, "")
        return try {
            Base64.getDecoder().decode(body)
        } catch (e: IllegalArgumentException) {
            throw InvalidCertificateException("UNREADABLE", e)
        }
    }

    private fun canSignCertificates(certificate: X509Certificate): Boolean {
        // getBasicConstraints is -1 without the extension or with cA=FALSE.
        if (certificate.basicConstraints < 0) return false
        val keyUsage = certificate.keyUsage ?: return true
        return keyUsage.size > KEY_CERT_SIGN && keyUsage[KEY_CERT_SIGN]
    }

    /**
     * True if the certificate presents itself as self-signed: subject equal to the issuer and Authority
     * Key Identifier absent, without a key identifier, or equal to the Subject Key Identifier. A
     * link certificate often keeps the CSCA subject but its AKI designates the old key.
     */
    private fun claimsSelfSigned(certificate: X509Certificate): Boolean {
        if (certificate.subjectX500Principal != certificate.issuerX500Principal) return false
        val aki = authorityKeyId(certificate) ?: return true
        val ski = TrustCrypto.subjectKeyId(certificate) ?: return true
        return aki.contentEquals(ski)
    }

    private fun authorityKeyId(certificate: X509Certificate): ByteArray? {
        val extension = certificate.getExtensionValue(Extension.authorityKeyIdentifier.id) ?: return null
        return try {
            AuthorityKeyIdentifier.getInstance(ASN1OctetString.getInstance(extension).octets).keyIdentifierOctets
        } catch (ignored: IllegalArgumentException) {
            null
        }
    }
}
