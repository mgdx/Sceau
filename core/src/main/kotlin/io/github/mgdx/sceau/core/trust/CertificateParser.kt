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
 * Lecture d'un certificat CSCA ou de lien importé seul (D33), en DER ou en PEM.
 *
 * Contrôles, dans l'ordre :
 * 1. un et un seul certificat X.509, sans octet superflu, à clé publique lisible, sinon `UNREADABLE` ;
 * 2. certificat d'autorité : `basicConstraints` avec cA=TRUE, et `keyUsage`, s'il est présent,
 *    avec keyCertSign, sinon `NOT_CA` (ICAO 9303-12 impose ces deux extensions aux CSCA et aux
 *    certificats de lien) ;
 * 3. certificat qui se dit auto-signé (sujet égal à l'émetteur, sans Authority Key Identifier
 *    distinct de son Subject Key Identifier) : auto-signature vérifiée, sinon `BAD_SIGNATURE`.
 *    Un certificat de lien est signé par l'ancienne clé, inconnue ici : sa signature est
 *    vérifiée plus tard, à la construction de la chaîne, contre un CSCA du magasin.
 */
internal object CertificateParser {
    private const val PEM_BEGIN = "-----BEGIN "
    private const val PEM_CERTIFICATE_BEGIN = "-----BEGIN CERTIFICATE-----"
    private const val PEM_CERTIFICATE_END = "-----END CERTIFICATE-----"

    /** Indice de keyCertSign dans `X509Certificate.getKeyUsage()`. */
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
            // fromByteArray rejette tout octet après le premier objet : un seul certificat.
            val holder = X509CertificateHolder(Certificate.getInstance(ASN1Primitive.fromByteArray(der)))
            TrustCrypto.toX509(holder).also {
                // BouncyCastle ne décode la clé qu'à la demande : null pour un algorithme inconnu.
                checkNotNull(it.publicKey)
            }
        } catch (e: Exception) {
            throw InvalidCertificateException("UNREADABLE", e)
        }
    }

    /**
     * Contenu DER d'un fichier PEM, ou null si [bytes] n'est pas du PEM. Un fichier PEM doit
     * contenir exactement un bloc `CERTIFICATE` et aucun autre bloc (clé, liste de révocation…).
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
        // getBasicConstraints vaut -1 sans l'extension ou avec cA=FALSE.
        if (certificate.basicConstraints < 0) return false
        val keyUsage = certificate.keyUsage ?: return true
        return keyUsage.size > KEY_CERT_SIGN && keyUsage[KEY_CERT_SIGN]
    }

    /**
     * Vrai si le certificat se présente comme auto-signé : sujet égal à l'émetteur et Authority
     * Key Identifier absent, sans identifiant de clé, ou égal au Subject Key Identifier. Un
     * certificat de lien garde souvent le sujet du CSCA mais son AKI désigne l'ancienne clé.
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
