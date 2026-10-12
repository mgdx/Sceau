package io.github.mgdx.sceau.trust

import io.github.mgdx.sceau.ui.trust.MAX_MASTER_LIST_BYTES

/** Master List or certificate already imported: file name (fingerprint of the content) and size in bytes. */
class ImportedFile(
    val name: String,
    val size: Long,
)

/** Outcome of an import request with respect to the limits (decision D22). */
enum class ImportDecision {
    /** Same content already imported: nothing to write, does not count toward the limits. */
    ALREADY_PRESENT,

    /** Import allowed. */
    ALLOWED,

    /** Refused: [ImportLimits.MAX_IMPORTED_LISTS] Master Lists already imported. */
    TOO_MANY,

    /** Refused: the cumulative size would exceed [ImportLimits.MAX_IMPORTED_TOTAL_BYTES]. */
    TOO_LARGE_TOTAL,

    /** Refused: [ImportLimits.MAX_IMPORTED_CERTIFICATES] certificates already imported (D33). */
    TOO_MANY_CERTIFICATES,

    /** Refused: certificate larger than [ImportLimits.MAX_CERTIFICATE_BYTES] (D33). */
    CERTIFICATE_TOO_LARGE,
}

/** Import refused by the limits; the message contains only a stable code. */
class ImportLimitException(
    val decision: ImportDecision,
) : Exception("IMPORT_LIMIT_${decision.name}")

/**
 * Limits on imported Master Lists (decision D22) and certificates (D33): all of them are reread
 * and merged at startup (D16), so their number and size bound the memory and the preloading
 * time.
 */
object ImportLimits {
    /** Maximum number of imported Master Lists. */
    const val MAX_IMPORTED_LISTS = 10

    /** Maximum cumulative size of the imported Master Lists: two files of maximum size. */
    const val MAX_IMPORTED_TOTAL_BYTES: Long = 2L * MAX_MASTER_LIST_BYTES

    /** Decides whether a Master List named [name], of [size] bytes, can be added to [existing]. */
    fun decide(
        existing: List<ImportedFile>,
        name: String,
        size: Long,
    ): ImportDecision =
        when {
            existing.any { it.name == name } -> ImportDecision.ALREADY_PRESENT
            existing.size >= MAX_IMPORTED_LISTS -> ImportDecision.TOO_MANY
            existing.sumOf { it.size } + size > MAX_IMPORTED_TOTAL_BYTES -> ImportDecision.TOO_LARGE_TOTAL
            else -> ImportDecision.ALLOWED
        }

    /** Maximum number of standalone imported certificates (D33). */
    const val MAX_IMPORTED_CERTIFICATES = 100

    /** Maximum size of a standalone imported certificate, in DER or PEM: 64 KiB (D33). */
    const val MAX_CERTIFICATE_BYTES = 64 * 1024

    /**
     * Decides whether a certificate named [name], of [size] bytes (file as chosen by the user),
     * can be added to the [existing] certificates.
     */
    fun decideCertificate(
        existing: List<ImportedFile>,
        name: String,
        size: Long,
    ): ImportDecision =
        when {
            size > MAX_CERTIFICATE_BYTES -> ImportDecision.CERTIFICATE_TOO_LARGE
            existing.any { it.name == name } -> ImportDecision.ALREADY_PRESENT
            existing.size >= MAX_IMPORTED_CERTIFICATES -> ImportDecision.TOO_MANY_CERTIFICATES
            else -> ImportDecision.ALLOWED
        }
}
