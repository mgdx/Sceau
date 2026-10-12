package io.github.mgdx.sceau.trust

import io.github.mgdx.sceau.core.trust.TrustAnchor
import io.github.mgdx.sceau.core.trust.TrustSource

/** Chosen file that would add nothing to the store, detected as early as the preview (D33). */
sealed interface Duplicate {
    /** Certificate already imported standalone (same DER, whatever the format of the chosen file). */
    data object ImportedCertificate : Duplicate

    /** Certificate already present through another [source]: embedded or imported Master List. */
    data class KnownCertificate(
        val source: TrustSource,
    ) : Duplicate

    /** Master List identical, byte for byte, to an already imported Master List. */
    data object ImportedMasterList : Duplicate

    /** Master List identical, byte for byte, to the embedded Master List. */
    data object EmbeddedMasterList : Duplicate
}

/** Import refused because the file is already in the store; the message contains only a stable code. */
class DuplicateImportException(
    val duplicate: Duplicate,
) : Exception("IMPORT_DUPLICATE")

/**
 * Duplicate detection at import (D33), on the SHA-256 fingerprint of a certificate's DER or of a
 * Master List file. Pure functions: fingerprints are in lowercase hexadecimal.
 */
object ImportDuplicates {
    /**
     * Possible duplicate of the certificate with fingerprint [sha256], with respect to the standalone
     * imported certificates ([importedCertificates], fingerprints of the `.der` files) and the merged
     * store ([storeSources], source retained per fingerprint). Null if the certificate is new.
     */
    fun certificate(
        sha256: String,
        importedCertificates: Set<String>,
        storeSources: Map<String, TrustSource>,
    ): Duplicate? {
        if (sha256 in importedCertificates) return Duplicate.ImportedCertificate
        val source = storeSources[sha256] ?: return null
        return if (source == TrustSource.IMPORTED_CERTIFICATE) Duplicate.ImportedCertificate else Duplicate.KnownCertificate(source)
    }

    /**
     * Possible duplicate of the Master List with fingerprint [sha256], with respect to the imported
     * ([importedMasterLists]) and embedded ([embeddedMasterLists]) Master Lists. Null if the file
     * is new, even if all its certificates are already known: it may be more recent.
     */
    fun masterList(
        sha256: String,
        importedMasterLists: Set<String>,
        embeddedMasterLists: Set<String>,
    ): Duplicate? =
        when (sha256) {
            in importedMasterLists -> Duplicate.ImportedMasterList
            in embeddedMasterLists -> Duplicate.EmbeddedMasterList
            else -> null
        }

    /** Number of distinct certificates among [certificates] (fingerprints) absent from [store]. */
    fun newCertificateCount(
        certificates: Collection<String>,
        store: Set<String>,
    ): Int = certificates.toSet().count { it !in store }

    /** Source retained for each fingerprint in the store: the highest-priority one in case of duplicates. */
    fun sourcesBySha256(anchors: List<TrustAnchor>): Map<String, TrustSource> =
        anchors.groupBy { it.sha256 }.mapValues { (_, list) -> list.minOf { it.source } }
}
