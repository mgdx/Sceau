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
 * Texte à résoudre côté interface : une ressource et ses arguments, qui peuvent eux-mêmes
 * être des [UiText]. Permet de mettre en forme sans Compose, et de le tester sur JVM.
 */
data class UiText(
    @param:StringRes val res: Int,
    val args: List<Any> = emptyList(),
)

private fun text(
    @StringRes res: Int,
    vararg args: Any,
): UiText = UiText(res, args.toList())

/** Genre de document déduit du code MRZ (ICAO 9303 partie 4 et 5). */
enum class DocumentKind(
    @param:StringRes val label: Int,
) {
    PASSPORT(R.string.result_document_passport),
    IDENTITY_CARD(R.string.result_document_identity_card),
    OTHER(R.string.result_document_other),
    ;

    companion object {
        /** « P… » : passeport ; « I… », « A… », « C… » (dont « ID ») : carte d'identité. */
        fun fromMrzCode(code: String): DocumentKind =
            when (code.trim().firstOrNull()?.uppercaseChar()) {
                'P' -> PASSPORT
                'I', 'A', 'C' -> IDENTITY_CARD
                else -> OTHER
            }
    }
}

/** Mise en forme de la liste de contrôle (SPEC §5.3). */
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
     * Les sept lignes dans l'ordre de [CheckId], complétées par une ligne « non disponible »
     * si le rapport n'en contient pas une (il doit toujours les contenir toutes).
     */
    fun orderedChecks(checks: List<Check>): List<Check> =
        CheckId.entries.map { id -> checks.firstOrNull { it.id == id } ?: Check(id, CheckStatus.NOT_AVAILABLE) }

    /**
     * Lignes du détail dépliable. [formatDate] met une date en forme selon la locale ;
     * [countryLabel] donne le drapeau et le nom d'un pays à partir de son code alpha-2.
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
     * « Pays incohérents : autorité de certification <pays>, certificat du signataire <pays>,
     * document <code> » (audit V3). Le code du document est le code ICAO brut de DG1.
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

    /** Codes pays alpha-2 dont le détail de [check] a besoin du libellé. */
    fun countryCodes(check: Check): List<String> =
        when (val detail = check.detail) {
            is CheckDetail.Chain -> listOfNotNull(detail.chain.cscaCountry)
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
            // Écart toléré : anomalie connue publiée par l'émetteur, DG écarté (décision D34).
            detail.deviations.forEach { deviation ->
                add(text(R.string.result_detail_dg_known_deviation, dataGroupList(listOf(deviation.dataGroup))))
            }
            // DG signés dans le SOD mais retenus par la puce (audit V1).
            if (detail.missing.isNotEmpty()) {
                add(text(R.string.result_detail_dg_missing, dataGroupList(detail.missing)))
            }
        }

    /** « DG1, DG2, DG14 » : numéros triés, sans doublon. */
    fun dataGroupList(numbers: List<Int>): String = numbers.distinct().sorted().joinToString(", ") { "DG$it" }

    /**
     * Vrai si l'une des [chains] (celle du SOD, et celle d'EF.CardSecurity avec PACE-CAM) remonte
     * à un CSCA importé par l'utilisateur, par une Master List ou seul (D33) : la carte du verdict
     * le signale sans qu'il faille déplier le détail (audits V6 et V17).
     */
    fun isImportedAnchor(vararg chains: ChainInfo?): Boolean =
        chains.any { chain ->
            chain?.cscaSource == TrustSource.IMPORTED_MASTER_LIST || chain?.cscaSource == TrustSource.IMPORTED_CERTIFICATE
        }

    @StringRes
    fun sourceLabel(source: TrustSource): Int =
        when (source) {
            TrustSource.ANTS -> R.string.result_detail_source_ants
            TrustSource.NATIONAL -> R.string.result_detail_source_national
            TrustSource.EMBEDDED_MASTER_LIST -> R.string.result_detail_source_embedded
            TrustSource.IMPORTED_MASTER_LIST -> R.string.result_detail_source_imported
            TrustSource.IMPORTED_CERTIFICATE -> R.string.trust_source_imported_certificate
        }

    /** Méthode de Chip Authentication : clé de DG14, ou PACE-CAM (décision D21). */
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

/** Date calendaire UTC d'un instant de certificat. */
fun Instant.toUtcDate(): LocalDate = atZone(ZoneOffset.UTC).toLocalDate()
