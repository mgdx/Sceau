package io.github.mgdx.sceau.core.trust

import java.security.cert.X509Certificate
import java.time.Instant

enum class TrustSource {
    /** Embedded ANTS DER certificates (passport and e-ID). */
    ANTS,

    /**
     * Embedded DER certificates, published by a State other than France for its own
     * documents (CSCA and link certificates missing from the embedded Master List, D26).
     */
    NATIONAL,

    /** Master List embedded in `core/src/main/resources/trust/`. */
    EMBEDDED_MASTER_LIST,

    /**
     * Master List imported by the user, whose signer is issued by an already known CSCA
     * (embedded or certificate imported on its own, audit V34).
     */
    IMPORTED_MASTER_LIST,

    /**
     * Certificate (self-signed CSCA or link certificate) imported on its own by the user: no
     * State signature vouches for it, it is the user who grants it trust (D33).
     */
    IMPORTED_CERTIFICATE,

    /**
     * Imported Master List whose signer is issued by no already known CSCA (embedded or
     * certificate imported on its own): its signature proves nothing about its origin, it is the user
     * who grants it trust, after a warning at import (audit V34, D44). Lowest
     * priority: a certificate already present from another source keeps that source.
     */
    IMPORTED_MASTER_LIST_UNANCHORED,
}

/** A known CSCA (or link certificate), with its origin. */
class TrustAnchor(
    val certificate: X509Certificate,
    val source: TrustSource,
) {
    /** ISO 3166-1 alpha-2 country code (C attribute of the subject), in upper case. */
    val country: String by lazy { TrustCrypto.country(certificate) }

    val subject: String get() = certificate.subjectX500Principal.name

    /** SHA-256 fingerprint of the DER encoding, lowercase hexadecimal without separator. */
    val sha256: String by lazy { TrustCrypto.sha256Hex(certificate.encoded) }

    val notBefore: Instant get() = certificate.notBefore.toInstant()
    val notAfter: Instant get() = certificate.notAfter.toInstant()

    /** True for a self-signed certificate (root CSCA), false for a link certificate. */
    val isSelfSigned: Boolean by lazy { TrustCrypto.isSelfSigned(certificate) }
}

/** Merged trust store (ANTS, national publications, embedded Master List, imports), read-only. */
interface TrustStore {
    val anchors: List<TrustAnchor>

    /** Metadata of the embedded Master List, for the "About" screen. */
    val embeddedMasterList: MasterListInfo?

    /** Certificates whose subject equals [issuer] (X.500 name compared in canonical form). */
    fun findBySubject(issuer: javax.security.auth.x500.X500Principal): List<TrustAnchor>

    /** Certificates whose Subject Key Identifier is [keyIdentifier]. */
    fun findBySubjectKeyId(keyIdentifier: ByteArray): List<TrustAnchor>
}

class MasterListInfo(
    val signingTime: Instant?,
    val signerSubject: String,
    /** SHA-256 fingerprint of the signer certificate, lowercase hexadecimal. */
    val signerSha256: String,
    val certificateCount: Int,
)

/** Content of a Master List whose CMS signature has been verified. */
class MasterList(
    val info: MasterListInfo,
    val certificates: List<X509Certificate>,
    /** Signer certificate, taken from the CMS `certificates` block. */
    val signerCertificate: X509Certificate,
)

/** Rejected Master List: unreadable CMS or invalid signature. [code] without personal data. */
class InvalidMasterListException(
    val code: String,
    cause: Throwable? = null,
) : Exception(code, cause)

/**
 * Rejected imported certificate. Stable [code], without personal data: `UNREADABLE` (not a
 * single X.509 certificate, DER or PEM, or unreadable public key), `NOT_CA` (cannot sign
 * certificates), `BAD_SIGNATURE` (invalid self-signature).
 */
class InvalidCertificateException(
    val code: String,
    cause: Throwable? = null,
) : Exception(code, cause)

object TrustStores {
    /**
     * Maximum number of certificates in a Master List (audit V22, decision D22): more than three
     * times the largest known real Master List (Sweden, 646 certificates; embedded BSI: 609).
     * Beyond that, the list is rejected with `TOO_MANY_CERTIFICATES`.
     */
    const val MAX_MASTER_LIST_CERTIFICATES = 2000

    /**
     * Parses a CMS Master List (`.ml`, `.der`, `.p7b`) and verifies its signature with the
     * embedded signer certificate. Throws [InvalidMasterListException] if invalid.
     * [checkpoint] is called between costly steps; a `CancellationException` it throws
     * aborts the parsing and propagates as is.
     */
    fun parseMasterList(
        bytes: ByteArray,
        checkpoint: () -> Unit = {},
    ): MasterList = MasterListParser.parse(bytes, checkpoint).masterList

    /**
     * True if the signer of [masterList] is issued by an already known CSCA (audit V34): an
     * embedded certificate of [store] (ANTS, national publication, embedded Master List) or one
     * of the certificates imported on their own [importedCertificates] (raw bytes, unreadable ones are
     * skipped). Certificates from another imported Master List do not count. Same rule as
     * the one that assigns, at load time, the source [TrustSource.IMPORTED_MASTER_LIST] or
     * [TrustSource.IMPORTED_MASTER_LIST_UNANCHORED].
     */
    fun isMasterListAnchored(
        masterList: MasterList,
        store: TrustStore,
        importedCertificates: List<ByteArray> = emptyList(),
    ): Boolean {
        val embedded = store.anchors.filter { it.source in TrustStoreLoader.EMBEDDED_SOURCES }.map { it.certificate }
        return MasterListParser.isSignerAnchored(masterList.signerCertificate, embedded + parseCertificates(importedCertificates))
    }

    /** Certificates imported on their own, read as at load time; unreadable ones are skipped. */
    internal fun parseCertificates(certificates: List<ByteArray>): List<X509Certificate> =
        certificates.mapNotNull { bytes ->
            try {
                CertificateParser.parse(bytes)
            } catch (ignored: InvalidCertificateException) {
                null
            }
        }

    /**
     * Reads a CSCA or link certificate imported on its own, in DER or PEM (a single certificate), and
     * checks that it can sign certificates and, if it claims to be self-signed, its self-signature.
     * Throws [InvalidCertificateException] otherwise.
     */
    fun parseCertificate(bytes: ByteArray): X509Certificate = CertificateParser.parse(bytes)

    /**
     * Loads the embedded store (`trust/` classpath resources) and merges it with
     * the imported Master Lists and certificates, supplied as raw bytes by the app. An invalid
     * embedded Master List throws [InvalidMasterListException]; an invalid import is skipped.
     */
    fun load(
        importedMasterLists: List<ByteArray> = emptyList(),
        importedCertificates: List<ByteArray> = emptyList(),
    ): TrustStore = TrustStoreLoader.load(importedMasterLists, importedCertificates)
}
