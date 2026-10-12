package io.github.mgdx.sceau.ui.result

import androidx.annotation.StringRes
import io.github.mgdx.sceau.R
import io.github.mgdx.sceau.core.report.ChainInfo
import io.github.mgdx.sceau.core.report.Check
import io.github.mgdx.sceau.core.report.CheckDetail
import io.github.mgdx.sceau.core.report.CheckId
import io.github.mgdx.sceau.core.report.CheckStatus
import io.github.mgdx.sceau.core.report.ChipAuthenticationMethod
import io.github.mgdx.sceau.core.report.IssuanceDateSource
import io.github.mgdx.sceau.core.trust.TrustSource
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Text to be resolved on the UI side: a resource and its arguments, which may themselves
 * be [UiText]s. Allows formatting without Compose, and testing it on the JVM.
 */
data class UiText(
    @param:StringRes val res: Int,
    val args: List<Any> = emptyList(),
)

private fun text(
    @StringRes res: Int,
    vararg args: Any,
): UiText = UiText(res, args.toList())

/** Document kind derived from the MRZ code (ICAO 9303 parts 4 and 5). */
enum class DocumentKind(
    @param:StringRes val label: Int,
) {
    PASSPORT(R.string.result_document_passport),
    IDENTITY_CARD(R.string.result_document_identity_card),
    OTHER(R.string.result_document_other),
    ;

    companion object {
        /** "P…": passport; "I…", "A…", "C…" (including "ID"): identity card. */
        fun fromMrzCode(code: String): DocumentKind =
            when (code.trim().firstOrNull()?.uppercaseChar()) {
                'P' -> PASSPORT
                'I', 'A', 'C' -> IDENTITY_CARD
                else -> OTHER
            }
    }
}

/** Formatting of the checklist (SPEC §5.3). */
object CheckFormatting {
    @StringRes
    fun title(id: CheckId): Int =
        when (id) {
            CheckId.SECURE_CHANNEL -> R.string.result_check_secure_channel
            CheckId.SOD_SIGNATURE -> R.string.result_check_sod_signature
            CheckId.CERTIFICATE_CHAIN -> R.string.result_check_chain
            CheckId.DS_VALIDITY -> R.string.result_check_ds_validity
            CheckId.DG_HASHES -> R.string.result_check_dg_hashes
            CheckId.CHIP_AUTHENTICATION -> R.string.result_check_chip_authentication
            CheckId.ACTIVE_AUTHENTICATION -> R.string.result_check_active_authentication
        }

    @StringRes
    fun status(status: CheckStatus): Int =
        when (status) {
            CheckStatus.OK -> R.string.result_status_ok
            CheckStatus.FAILED -> R.string.result_status_failed
            CheckStatus.NOT_AVAILABLE -> R.string.result_status_not_available
            CheckStatus.UNSUPPORTED_ALGORITHM -> R.string.result_status_unsupported
        }

    /**
     * The seven rows in [CheckId] order, completed with a "Not available" row if the report
     * lacks one of them (it must always contain all of them).
     */
    fun orderedChecks(checks: List<Check>): List<Check> =
        CheckId.entries.map { id -> checks.firstOrNull { it.id == id } ?: Check(id, CheckStatus.NOT_AVAILABLE) }

    /**
     * Lines of the expandable detail. [formatDate] formats a date according to the locale;
     * [countryLabel] gives the flag and name of a country from its alpha-2 code.
     */
    fun detailLines(
        check: Check,
        formatDate: (LocalDate) -> String,
        countryLabel: (String) -> String,
    ): List<UiText> {
        val lines =
            when (val detail = check.detail) {
                CheckDetail.None -> {
                    emptyList()
                }

                is CheckDetail.SecureChannel -> {
                    listOf(text(R.string.result_detail_protocol, detail.protocol.name))
                }

                is CheckDetail.Signature -> {
                    listOf(text(R.string.result_detail_algorithm, detail.algorithm))
                }

                is CheckDetail.Chain -> {
                    chainLines(detail.chain, countryLabel)
                }

                CheckDetail.DsCertificateMissing -> {
                    listOf(text(R.string.result_detail_ds_missing))
                }

                is CheckDetail.NoChainForKnownCountry -> {
                    noChainLines(detail, countryLabel)
                }

                is CheckDetail.DsValidity -> {
                    dsValidityLines(detail, formatDate)
                }

                is CheckDetail.DataGroupHashes -> {
                    hashLines(detail)
                }

                is CheckDetail.CountryMismatch -> {
                    listOf(countryMismatchLine(detail, countryLabel))
                }

                is CheckDetail.UnsupportedAlgorithm -> {
                    listOf(text(R.string.result_detail_unsupported, detail.algorithm))
                }

                is CheckDetail.ChipAuthentication -> {
                    listOf(text(R.string.result_detail_ca_method, text(chipAuthenticationMethodLabel(detail.method))))
                }

                is CheckDetail.Error -> {
                    listOf(text(R.string.result_detail_error, detail.code))
                }
            }
        return lines.ifEmpty { listOf(fallbackLine(check)) }
    }

    private fun fallbackLine(check: Check): UiText =
        when {
            check.status != CheckStatus.NOT_AVAILABLE -> text(R.string.result_detail_none)
            check.id == CheckId.CHIP_AUTHENTICATION -> text(R.string.result_detail_no_dg14)
            check.id == CheckId.ACTIVE_AUTHENTICATION -> text(R.string.result_detail_no_dg15)
            else -> text(R.string.result_detail_not_reached)
        }

    private fun chainLines(
        chain: ChainInfo,
        countryLabel: (String) -> String,
    ): List<UiText> =
        buildList {
            add(
                chain.cscaSubject?.let { text(R.string.result_detail_csca, it) }
                    ?: text(R.string.result_detail_csca_none),
            )
            chain.cscaCountry?.let { add(text(R.string.result_detail_country, countryLabel(it))) }
            chain.cscaSource?.let { add(text(R.string.result_detail_source, text(sourceLabel(it)))) }
            add(text(R.string.result_detail_algorithm, chain.signatureAlgorithm))
            add(text(R.string.result_detail_ds_subject, chain.dsSubject))
            if (chain.linkCertificates.isEmpty()) {
                add(text(R.string.result_detail_links_none))
            } else {
                add(text(R.string.result_detail_links))
                chain.linkCertificates.forEach { add(text(R.string.result_detail_link_item, it)) }
            }
        }

    /**
     * Country with known certificates, but no chain (audit V25): no matching CSCA, issuing
     * country, then the attempted DS, or its absence.
     */
    private fun noChainLines(
        detail: CheckDetail.NoChainForKnownCountry,
        countryLabel: (String) -> String,
    ): List<UiText> =
        buildList {
            add(text(R.string.result_detail_csca_none))
            add(text(R.string.result_detail_country, countryLabel(detail.country)))
            val chain = detail.chain
            if (chain == null) {
                add(text(R.string.result_detail_ds_missing))
            } else {
                add(text(R.string.result_detail_algorithm, chain.signatureAlgorithm))
                add(text(R.string.result_detail_ds_subject, chain.dsSubject))
            }
        }

    /**
     * "Inconsistent countries: certification authority <country>, signer certificate <country>,
     * document <code>" (audit V3). The document code is the raw ICAO code from DG1.
     */
    private fun countryMismatchLine(
        detail: CheckDetail.CountryMismatch,
        countryLabel: (String) -> String,
    ): UiText {
        val unknown = text(R.string.result_unknown_value)
        val document =
            detail.issuingState
                ?.replace("<", "")
                ?.trim()
                ?.ifEmpty { null }
        return text(
            R.string.result_detail_country_mismatch,
            detail.cscaCountry?.let(countryLabel) ?: unknown,
            detail.dsCountry?.let(countryLabel) ?: unknown,
            document ?: unknown,
        )
    }

    /** Alpha-2 country codes whose label the detail of [check] needs. */
    fun countryCodes(check: Check): List<String> =
        when (val detail = check.detail) {
            is CheckDetail.Chain -> listOfNotNull(detail.chain.cscaCountry)
            is CheckDetail.NoChainForKnownCountry -> listOf(detail.country)
            is CheckDetail.CountryMismatch -> listOfNotNull(detail.cscaCountry, detail.dsCountry)
            else -> emptyList()
        }.distinct()

    private fun dsValidityLines(
        detail: CheckDetail.DsValidity,
        formatDate: (LocalDate) -> String,
    ): List<UiText> {
        val period =
            text(
                R.string.result_detail_ds_period,
                formatDate(detail.notBefore.toUtcDate()),
                formatDate(detail.notAfter.toUtcDate()),
            )
        val date = detail.issuanceDate
        val source = detail.source
        val issuance =
            when {
                date == null -> text(R.string.result_detail_issuance_unknown)
                source == null -> text(R.string.result_detail_issuance_no_source, formatDate(date))
                else -> text(R.string.result_detail_issuance, formatDate(date), text(issuanceSourceLabel(source)))
            }
        return listOf(period, issuance)
    }

    private fun hashLines(detail: CheckDetail.DataGroupHashes): List<UiText> =
        buildList {
            add(text(R.string.result_detail_digest_algorithm, detail.digestAlgorithm))
            if (detail.checked.isEmpty()) {
                add(text(R.string.result_detail_dg_checked_none))
            } else {
                add(text(R.string.result_detail_dg_checked, dataGroupList(detail.checked)))
            }
            if (detail.mismatched.isNotEmpty()) {
                add(text(R.string.result_detail_dg_mismatched, dataGroupList(detail.mismatched)))
            } else if (detail.checked.isNotEmpty() && detail.missing.isEmpty() && detail.deviations.isEmpty()) {
                add(text(R.string.result_detail_dg_all_match))
            }
            // Tolerated mismatch: known anomaly published by the issuer, DG excluded (decision D34).
            detail.deviations.forEach { deviation ->
                add(text(R.string.result_detail_dg_known_deviation, dataGroupList(listOf(deviation.dataGroup))))
            }
            // DGs signed in the SOD but withheld by the chip (audit V1).
            if (detail.missing.isNotEmpty()) {
                add(text(R.string.result_detail_dg_missing, dataGroupList(detail.missing)))
            }
        }

    /** "DG1, DG2, DG14": sorted numbers, without duplicates. */
    fun dataGroupList(numbers: List<Int>): String = numbers.distinct().sorted().joinToString(", ") { "DG$it" }

    /**
     * True if one of the [chains] (the SOD's, and EF.CardSecurity's with PACE-CAM) leads up to a
     * CSCA imported by the user, through a Master List or on its own (D33): the verdict card flags
     * it without the detail having to be expanded (audits V6 and V17).
     */
    fun isImportedAnchor(vararg chains: ChainInfo?): Boolean =
        chains.any { chain ->
            chain?.cscaSource == TrustSource.IMPORTED_MASTER_LIST ||
                chain?.cscaSource == TrustSource.IMPORTED_CERTIFICATE ||
                chain?.cscaSource == TrustSource.IMPORTED_MASTER_LIST_UNANCHORED
        }

    @StringRes
    fun sourceLabel(source: TrustSource): Int =
        when (source) {
            TrustSource.ANTS -> R.string.result_detail_source_ants
            TrustSource.NATIONAL -> R.string.result_detail_source_national
            TrustSource.EMBEDDED_MASTER_LIST -> R.string.result_detail_source_embedded
            TrustSource.IMPORTED_MASTER_LIST -> R.string.result_detail_source_imported
            TrustSource.IMPORTED_CERTIFICATE -> R.string.trust_source_imported_certificate
            TrustSource.IMPORTED_MASTER_LIST_UNANCHORED -> R.string.trust_source_imported_unanchored
        }

    /** Chip Authentication method: DG14 key, or PACE-CAM (decision D21). */
    @StringRes
    fun chipAuthenticationMethodLabel(method: ChipAuthenticationMethod): Int =
        when (method) {
            ChipAuthenticationMethod.DG14 -> R.string.result_detail_ca_method_dg14
            ChipAuthenticationMethod.PACE_CAM -> R.string.result_detail_ca_method_pace_cam
        }

    @StringRes
    private fun issuanceSourceLabel(source: IssuanceDateSource): Int =
        when (source) {
            IssuanceDateSource.DG12 -> R.string.result_detail_issuance_dg12
            IssuanceDateSource.SOD_SIGNING_TIME -> R.string.result_detail_issuance_sod
            IssuanceDateSource.ESTIMATED -> R.string.result_detail_issuance_estimated
        }
}

/** UTC calendar date of a certificate instant. */
fun Instant.toUtcDate(): LocalDate = atZone(ZoneOffset.UTC).toLocalDate()
