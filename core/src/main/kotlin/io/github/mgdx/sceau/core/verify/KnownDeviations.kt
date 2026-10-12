package io.github.mgdx.sceau.core.verify

import io.github.mgdx.sceau.core.report.KnownDeviation
import java.time.LocalDate

/**
 * Elements by which a known anomaly is recognized (decision D34). [PassiveAuthenticator]
 * only builds them once the SOD signature and the DS → CSCA chain are validated, and the
 * DG1 hash is verified: all fields are then signed, except [dg12DateOfIssue].
 */
internal class DeviationEvidence(
    /** Country (ISO 3166-1 alpha-2) of the CSCA the validated chain leads to. */
    val cscaCountry: String?,
    /** DG1 issuing state (three-letter ICAO code). */
    val issuingState: String?,
    /** DG1 document code ("P", "C", "ID"…). */
    val documentCode: String,
    /** DG1 document number, without filler characters. */
    val documentNumber: String?,
    /** DG1 date of expiry, null if unreadable. */
    val dateOfExpiry: LocalDate?,
    /** SOD signingTime signed attribute (UTC date), null if absent. */
    val sodSigningTime: LocalDate?,
    /**
     * Date of issue read from DG12. **Not verified**: DG12 is precisely the group whose hash
     * differs. A rule may only use it to **narrow** its scope.
     */
    val dg12DateOfIssue: LocalDate?,
)

/**
 * Hard-coded known anomaly, with its source.
 *
 * @property deviation stable identifier and affected DG (11 or 12 only)
 * @property source official issuer page or file that publishes the anomaly
 * @property listDate date of the issuer's list (signingTime of the Deviation List)
 * @property listSha256 SHA-256 fingerprint of the published file, recorded in docs/trust-sources.md §2.4
 * @property obsoleteAfter date after which no covered document can still be valid: the rule
 *   only applies to a document whose expiry (DG1) does not exceed it, and a test fails once
 *   this date has passed, as a reminder to remove the rule
 * @property matches criterion, evaluated on signed elements (see [DeviationEvidence])
 */
internal class KnownDeviationRule(
    val deviation: KnownDeviation,
    val source: String,
    val listDate: LocalDate,
    val listSha256: String,
    val obsoleteAfter: LocalDate,
    val matches: (DeviationEvidence) -> Boolean,
) {
    /** True if [today] is after [obsoleteAfter]: the rule no longer has a reason to exist. */
    fun isObsolete(today: LocalDate): Boolean = today.isAfter(obsoleteAfter)

    init {
        // Safeguard: DG1 (MRZ), DG2 (portrait), DG14 and DG15 (chip keys) are never tolerated.
        require(deviation.dataGroup in KnownDeviations.TOLERABLE_DATA_GROUPS) { "DEVIATION_DATA_GROUP" }
    }
}

/**
 * Registry of known anomalies published by issuers (decision D34). An anomaly only tolerates
 * a hash mismatch on DG11 or DG12, and only if the SOD signature and the chain are valid and
 * the DG1 hash is verified ([PassiveAuthenticator]); the tolerated DG is then discarded from
 * the report.
 */
internal class KnownDeviations(
    val rules: List<KnownDeviationRule>,
) {
    /** Anomalies that apply to [evidence], among the DGs in [mismatched]. */
    fun applicable(
        evidence: DeviationEvidence,
        mismatched: Collection<Int>,
    ): List<KnownDeviation> =
        rules
            .filter { rule ->
                val dataGroup = rule.deviation.dataGroup
                val expiry = evidence.dateOfExpiry
                dataGroup in TOLERABLE_DATA_GROUPS &&
                    dataGroup in mismatched &&
                    expiry != null &&
                    !expiry.isAfter(rule.obsoleteAfter) &&
                    rule.matches(evidence)
            }.map { it.deviation }
            .distinct()

    /** Rules obsolete on [today] (see [KnownDeviationRule.obsoleteAfter]). */
    fun obsoleteRules(today: LocalDate): List<KnownDeviationRule> = rules.filter { it.isObsolete(today) }

    companion object {
        /** Only DG11 and DG12 (additional data, with no role in the verification) can be tolerated. */
        val TOLERABLE_DATA_GROUPS: Set<Int> = setOf(11, 12)

        /** No anomaly: for tests. */
        val NONE = KnownDeviations(emptyList())

        /** Real registry (lazy: the rules read [TOLERABLE_DATA_GROUPS] when created). */
        val REGISTRY: KnownDeviations by lazy { KnownDeviations(listOf(ItalianCie3Dg12.RULE)) }
    }
}

/**
 * Italy, CIE 3.0 electronic identity cards: incorrect DG12.
 *
 * Source: Deviation List of the Ministry of the Interior (https://csca-ita.interno.gov.it/, TDDL
 * section), file `TDDL-CIE-Signed-20180530.der` in `IT_CIE_DeviationList.zip`, CMS
 * id-icao-DeviationList (2.23.136.1.1.7) signed on 2018-05-30 by ITDeviationListSigner, issued by
 * the Italian CSCA03. Two deviations (type 2.23.136.1.1.7.2.2, parameter DG 12): the DG12 hash in
 * the SOD does not match the chip's DG12, whose date of issue is 2015-12-05 for all documents.
 * They target document type `C`, issued from 2017-10-01 to 2018-02-05, and 346,275 document
 * numbers (299,400 + 46,875), all of the form `CA` + 5 digits + 2 letters.
 *
 * These numbers do not form ranges (215,394 contiguous runs) and are not embedded (volume,
 * decision D34). The criterion coded here is therefore broader than the list, based on signed
 * elements: Italian CSCA, verified DG1 (state ITA, code starting with `C`, number of the list's
 * form), SOD signingTime within the issuance period widened by one month on each side.
 * Narrowing on unverified data: a readable DG12 whose date of issue is not 2015-12-05 is not
 * covered.
 *
 * Expiry ([OBSOLETE_AFTER], 2029-03-05): last issuance accepted by the criterion (2018-03-05,
 * signingTime with the margin) + 10 years, the maximum validity of an identity card for an
 * adult (decree-law 112/2008, art. 31, converted by law 133/2008), with the expiry date moved to
 * the holder's next birthday (decree-law 5/2012, art. 7, para. 2, converted by law 35/2012): at
 * most one more year, i.e. 2029-03-05 at the latest (2029-02-05 at the latest for the cards in
 * the list, issued until 2018-02-05). The 50-year validity beyond age 70 only applies to cards
 * issued since 2026-07-30.
 */
internal object ItalianCie3Dg12 {
    val DEVIATION = KnownDeviation(id = "IT-CIE3-DG12", dataGroup = 12)

    const val SOURCE = "https://csca-ita.interno.gov.it/certificatiCSCA/IT_CIE_DeviationList.zip"
    const val LIST_SHA256 = "1219d8c74a7acf13c55e265fa8125230167160f4f999b64b28644275a084d31d"
    val LIST_DATE: LocalDate = LocalDate.of(2018, 5, 30)

    /** Issuance period of the list (2017-10-01 to 2018-02-05), widened by one month (D34). */
    val SIGNING_TIME_FROM: LocalDate = LocalDate.of(2017, 9, 1)
    val SIGNING_TIME_TO: LocalDate = LocalDate.of(2018, 3, 5)

    /** No covered card is valid beyond this: 2018-03-05 + 10 years + 1 year at most (D34). */
    val OBSOLETE_AFTER: LocalDate = LocalDate.of(2029, 3, 5)

    /** Incorrect date of issue carried by all affected DG12s. */
    val DG12_DATE_OF_ISSUE: LocalDate = LocalDate.of(2015, 12, 5)

    private val DOCUMENT_NUMBER = Regex("CA[0-9]{5}[A-Z]{2}")

    fun matches(evidence: DeviationEvidence): Boolean {
        val signingTime = evidence.sodSigningTime ?: return false
        return evidence.cscaCountry == "IT" &&
            evidence.issuingState == "ITA" &&
            evidence.documentCode.startsWith("C") &&
            DOCUMENT_NUMBER.matches(evidence.documentNumber.orEmpty()) &&
            !signingTime.isBefore(SIGNING_TIME_FROM) &&
            !signingTime.isAfter(SIGNING_TIME_TO) &&
            (evidence.dg12DateOfIssue == null || evidence.dg12DateOfIssue == DG12_DATE_OF_ISSUE)
    }

    val RULE = KnownDeviationRule(DEVIATION, SOURCE, LIST_DATE, LIST_SHA256, OBSOLETE_AFTER, ::matches)
}
