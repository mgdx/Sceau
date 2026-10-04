package io.github.mgdx.sceau.trust

import io.github.mgdx.sceau.core.trust.MasterListInfo
import io.github.mgdx.sceau.core.trust.TrustAnchor
import io.github.mgdx.sceau.core.trust.TrustSource
import java.security.cert.X509Certificate
import java.time.Instant

/** Ce que l'écran montre d'un certificat importé seul (D33). Aucune donnée personnelle. */
class CertificateSummary(
    val subject: String,
    /** Code pays ISO 3166-1 alpha-2 du sujet, vide s'il n'en indique pas. */
    val country: String,
    val notBefore: Instant,
    val notAfter: Instant,
    /** Empreinte SHA-256 du DER, hexadécimal minuscule. */
    val sha256: String,
    /** Vrai pour un certificat de lien, faux pour un CSCA auto-signé. */
    val isLink: Boolean,
) {
    companion object {
        fun of(certificate: X509Certificate): CertificateSummary {
            val anchor = TrustAnchor(certificate, TrustSource.IMPORTED_CERTIFICATE)
            return CertificateSummary(
                subject = anchor.subject,
                country = anchor.country,
                notBefore = anchor.notBefore,
                notAfter = anchor.notAfter,
                sha256 = anchor.sha256,
                isLink = !anchor.isSelfSigned,
            )
        }
    }
}

/** Élément importé, désigné par son nom de fichier [id] dans `filesDir/trust/`. */
sealed interface ImportedItem {
    val id: String

    /** Master List importée ; [info] null si le fichier ne se vérifie plus (il est alors ignoré). */
    class MasterListItem(
        override val id: String,
        val info: MasterListInfo?,
    ) : ImportedItem

    /** Certificat importé seul ; [summary] null si le fichier ne se lit plus (il est alors ignoré). */
    class CertificateItem(
        override val id: String,
        val summary: CertificateSummary?,
    ) : ImportedItem
}

/** Fichier vérifié, en attente de confirmation. Ses octets ne sont pas des données personnelles. */
sealed interface ImportPreview {
    val bytes: ByteArray

    class MasterList(
        override val bytes: ByteArray,
        val info: MasterListInfo,
    ) : ImportPreview

    class Certificate(
        override val bytes: ByteArray,
        val summary: CertificateSummary,
    ) : ImportPreview
}

/** Fichier qui n'est ni un certificat lisible ni un CMS : message sans code. */
class UnrecognizedFileException : Exception("UNRECOGNIZED")
