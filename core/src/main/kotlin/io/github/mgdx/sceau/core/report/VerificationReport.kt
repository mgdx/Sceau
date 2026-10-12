package io.github.mgdx.sceau.core.report

import io.github.mgdx.sceau.core.model.DocumentData
import io.github.mgdx.sceau.core.trust.TrustSource
import java.time.Instant
import java.time.LocalDate

/** Overall verdict (SPEC §5.3). */
enum class Verdict {
    /** PA succeeded and CA or AA succeeded. */
    AUTHENTIC,

    /** PA succeeded, neither DG14 nor DG15: the chip could be a clone. */
    SIGNATURE_VALID_CHIP_UNVERIFIED,

    /** The SOD chain leads to no CSCA in the store. */
    UNKNOWN_ISSUER,

    /** Invalid signature, mismatching hash, failed challenge, unsupported algorithm. */
    FAILED,
}

/** Rows of the checklist, in display order (SPEC §5.3). */
enum class CheckId {
    SECURE_CHANNEL,
    SOD_SIGNATURE,
    CERTIFICATE_CHAIN,
    DS_VALIDITY,
    DG_HASHES,
    CHIP_AUTHENTICATION,
    ACTIVE_AUTHENTICATION,
}

enum class CheckStatus {
    OK,
    FAILED,

    /** Step not available (DG14/DG15 absent, step not reached): distinct from a failure. */
    NOT_AVAILABLE,

    /** Algorithm unknown to BouncyCastle: `UnsupportedAlgorithm` (SPEC §6.2). */
    UNSUPPORTED_ALGORITHM,
}

enum class ChannelProtocol { PACE, BAC }

/** Chip Authentication method (row [CheckId.CHIP_AUTHENTICATION]). */
enum class ChipAuthenticationMethod {
    /** Chip Authentication with the static key from DG14 (ICAO 9303-11 §6.2). */
    DG14,

    /** PACE with Chip Authentication Mapping, key from EF.CardSecurity (ICAO 9303-11 §4.4, decision D21). */
    PACE_CAM,
}

enum class IssuanceDateSource {
    /** Date of issue read from DG12. */
    DG12,

    /** signingTime attribute of the SOD. */
    SOD_SIGNING_TIME,

    /** Expiry minus the usual validity period: displayed as "estimated". */
    ESTIMATED,
}

/** Structured detail of a check: the app formats it with its own strings. */
sealed class CheckDetail {
    data object None : CheckDetail()

    data class SecureChannel(
        val protocol: ChannelProtocol,
    ) : CheckDetail()

    /** [algorithm]: declared algorithm name (e.g. "SHA256withECDSA", or OID if unknown). */
    data class Signature(
        val algorithm: String,
    ) : CheckDetail()

    data class Chain(
        val chain: ChainInfo,
    ) : CheckDetail()

    /** Chain not found: the DS is neither in the SOD nor in the store (`DsCertificateMissing`). */
    data object DsCertificateMissing : CheckDetail()

    /**
     * No chain succeeds, although the store contains certificates of the issuing country of
     * DG1 ([country], ISO 3166-1 alpha-2): failure, not "Unknown issuer" (audit V25, decision
     * D40). [chain]: chain attempted from the DS, null if the DS cannot be found.
     */
    data class NoChainForKnownCountry(
        val country: String,
        val chain: ChainInfo?,
    ) : CheckDetail()

    data class DsValidity(
        val notBefore: Instant,
        val notAfter: Instant,
        val issuanceDate: LocalDate?,
        val source: IssuanceDateSource?,
    ) : CheckDetail()

    /**
     * Numbers of the DGs checked, of those whose hash differs from the SOD, and of those the SOD
     * announces among the DGs Sceau reads (1, 2, 7, 11, 12, 14, 15) but the chip did not provide
     * ([missing] not empty = failure: a chip cannot withhold a signed DG, SPEC §6.1).
     *
     * [deviations]: hash mismatches tolerated because they match a known anomaly,
     * published by the issuer (decision D34). A tolerated DG is not listed in [mismatched]; its
     * data is removed from the report.
     */
    data class DataGroupHashes(
        val digestAlgorithm: String,
        val checked: List<Int>,
        val mismatched: List<Int>,
        val missing: List<Int> = emptyList(),
        val deviations: List<KnownDeviation> = emptyList(),
    ) : CheckDetail()

    /**
     * Inconsistent countries: the CSCA country (alpha-2) differs from that of the DS certificate or of the
     * issuing State in DG1 (raw ICAO alpha-3 code, as read). Null values if the attribute is missing.
     */
    data class CountryMismatch(
        val cscaCountry: String?,
        val dsCountry: String?,
        val issuingState: String?,
    ) : CheckDetail()

    data class UnsupportedAlgorithm(
        val algorithm: String,
    ) : CheckDetail()

    /** Method of the Chip Authentication performed (CHIP_AUTHENTICATION row). */
    data class ChipAuthentication(
        val method: ChipAuthenticationMethod,
    ) : CheckDetail()

    /** Technical error during the step, without personal data. */
    data class Error(
        val code: String,
    ) : CheckDetail()
}

/**
 * Known anomaly, published by an issuer (Deviation List, ICAO 9303-12), affecting data
 * group [dataGroup] (11 or 12 only) of a series of genuine documents (decision D34).
 * [id] is a stable identifier, without personal data (e.g. `IT-CIE3-DG12`).
 */
data class KnownDeviation(
    val id: String,
    val dataGroup: Int,
)

data class Check(
    val id: CheckId,
    val status: CheckStatus,
    val detail: CheckDetail = CheckDetail.None,
)

/** Metadata of the DS → (links) → CSCA certification chain. No personal data. */
data class ChainInfo(
    val dsSubject: String,
    val dsSerialNumber: String,
    val dsNotBefore: Instant,
    val dsNotAfter: Instant,
    val signatureAlgorithm: String,
    /** Null if no CSCA in the store matches (verdict UNKNOWN_ISSUER). */
    val cscaSubject: String?,
    /** ISO 3166-1 alpha-2 country code of the CSCA, in upper case. */
    val cscaCountry: String?,
    val cscaSha256: String?,
    val cscaSource: TrustSource?,
    /** Subjects of the link certificates traversed, from the closest to the DS to the closest to the CSCA. */
    val linkCertificates: List<String>,
)

/**
 * Immutable report produced by `readAndVerify`. Contains the personal data read
 * in [document]: it is never persisted, and [wipe] must be called when leaving
 * the result screen and when going to the background.
 */
class VerificationReport(
    val verdict: Verdict,
    /** Exactly one entry per [CheckId], in enumeration order. */
    val checks: List<Check>,
    /** SOD chain. */
    val chain: ChainInfo?,
    val document: DocumentData,
    /**
     * EF.CardSecurity chain if PACE-CAM was performed (decision D21), null otherwise. It underpins the
     * "genuine chip" proof: the screen flags an imported CSCA on either chain
     * (audit V17).
     */
    val cardSecurityChain: ChainInfo? = null,
    /**
     * False if no identity data could be read: reading interrupted while re-establishing the
     * channel after a failed Chip Authentication (audit V24, decision D40). [document] then carries
     * an empty DG1, without portrait, DG7, DG11 or DG12, which the screen must not present as
     * the holder's identity.
     */
    val identityRead: Boolean = true,
) {
    fun check(id: CheckId): Check = checks.first { it.id == id }

    /** Zeroes all byte arrays of the data read. */
    fun wipe() = document.wipe()

    override fun toString(): String = "VerificationReport(verdict=$verdict)"
}
