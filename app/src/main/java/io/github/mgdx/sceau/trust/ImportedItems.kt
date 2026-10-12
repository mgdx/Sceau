package io.github.mgdx.sceau.trust

import io.github.mgdx.sceau.core.trust.MasterListInfo
import io.github.mgdx.sceau.core.trust.TrustAnchor
import io.github.mgdx.sceau.core.trust.TrustSource
import java.security.cert.X509Certificate
import java.time.Instant

/** What the screen shows of a standalone imported certificate (D33). No personal data. */
class CertificateSummary(
    val subject: String,
    /** ISO 3166-1 alpha-2 country code of the subject, empty if it does not specify one. */
    val country: String,
    val notBefore: Instant,
    val notAfter: Instant,
    /** SHA-256 fingerprint of the DER, lowercase hexadecimal. */
    val sha256: String,
    /** True for a link certificate, false for a self-signed CSCA. */
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

/** Imported item, identified by its file name [id] in `filesDir/trust/`. */
sealed interface ImportedItem {
    val id: String

    /** Imported Master List; [info] is null if the file no longer verifies (it is then ignored). */
    class MasterListItem(
        override val id: String,
        val info: MasterListInfo?,
    ) : ImportedItem

    /** Standalone imported certificate; [summary] is null if the file can no longer be read (it is then ignored). */
    class CertificateItem(
        override val id: String,
        val summary: CertificateSummary?,
    ) : ImportedItem
}

/** Verified file, awaiting confirmation. Its bytes are not personal data. */
sealed interface ImportPreview {
    val bytes: ByteArray

    class MasterList(
        override val bytes: ByteArray,
        val info: MasterListInfo,
        /** Distinct certificates of the list that are absent from the current store (D33). */
        val newCertificates: Int,
        /**
         * True if the signer is issued by an already known CSCA (embedded or standalone imported
         * certificate); if false, the preview warns and asks for a separate confirmation (audit V34, D44).
         */
        val anchored: Boolean,
    ) : ImportPreview

    class Certificate(
        override val bytes: ByteArray,
        val summary: CertificateSummary,
    ) : ImportPreview
}

/** File that is neither a readable certificate nor a CMS: message without a code. */
class UnrecognizedFileException : Exception("UNRECOGNIZED")
