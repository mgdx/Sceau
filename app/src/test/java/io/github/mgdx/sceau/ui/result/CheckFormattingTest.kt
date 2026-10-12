package io.github.mgdx.sceau.ui.result

import io.github.mgdx.sceau.R
import io.github.mgdx.sceau.core.report.ChainInfo
import io.github.mgdx.sceau.core.report.ChannelProtocol
import io.github.mgdx.sceau.core.report.Check
import io.github.mgdx.sceau.core.report.CheckDetail
import io.github.mgdx.sceau.core.report.CheckId
import io.github.mgdx.sceau.core.report.CheckStatus
import io.github.mgdx.sceau.core.report.IssuanceDateSource
import io.github.mgdx.sceau.core.report.KnownDeviation
import io.github.mgdx.sceau.core.trust.TrustSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class CheckFormattingTest {
    private val formatDate: (LocalDate) -> String = { it.toString() }
    private val country: (String) -> String = { "pays:$it" }

    private fun lines(check: Check) = CheckFormatting.detailLines(check, formatDate, country)

    @Test
    fun `seven rows in order, missing rows not available`() {
        val checks =
            listOf(
                Check(CheckId.DG_HASHES, CheckStatus.OK),
                Check(CheckId.SECURE_CHANNEL, CheckStatus.OK),
            )
        val ordered = CheckFormatting.orderedChecks(checks)
        assertEquals(CheckId.entries.toList(), ordered.map { it.id })
        assertEquals(CheckStatus.OK, ordered[0].status)
        assertEquals(CheckStatus.NOT_AVAILABLE, ordered[1].status)
    }

    @Test
    fun `secure channel`() {
        val result = lines(Check(CheckId.SECURE_CHANNEL, CheckStatus.OK, CheckDetail.SecureChannel(ChannelProtocol.PACE)))
        assertEquals(listOf(UiText(R.string.result_detail_protocol, listOf("PACE"))), result)
    }

    @Test
    fun `chain with CSCA, country, source and link certificates`() {
        val chain =
            ChainInfo(
                dsSubject = "CN=DS",
                dsSerialNumber = "01",
                dsNotBefore = Instant.EPOCH,
                dsNotAfter = Instant.EPOCH,
                signatureAlgorithm = "SHA256withECDSA",
                cscaSubject = "CN=CSCA",
                cscaCountry = "FR",
                cscaSha256 = "00",
                cscaSource = TrustSource.ANTS,
                linkCertificates = listOf("CN=Lien"),
            )
        val result = lines(Check(CheckId.CERTIFICATE_CHAIN, CheckStatus.OK, CheckDetail.Chain(chain)))
        assertTrue(UiText(R.string.result_detail_csca, listOf("CN=CSCA")) in result)
        assertTrue(UiText(R.string.result_detail_country, listOf("pays:FR")) in result)
        assertTrue(
            UiText(R.string.result_detail_source, listOf(UiText(R.string.result_detail_source_ants))) in result,
        )
        assertTrue(UiText(R.string.result_detail_algorithm, listOf("SHA256withECDSA")) in result)
        assertTrue(UiText(R.string.result_detail_links) in result)
        assertTrue(UiText(R.string.result_detail_link_item, listOf("CN=Lien")) in result)
    }

    @Test
    fun `chain without a known CSCA`() {
        val chain =
            ChainInfo("CN=DS", "01", Instant.EPOCH, Instant.EPOCH, "SHA256withRSA", null, null, null, null, emptyList())
        val result = lines(Check(CheckId.CERTIFICATE_CHAIN, CheckStatus.FAILED, CheckDetail.Chain(chain)))
        assertEquals(UiText(R.string.result_detail_csca_none), result.first())
        assertTrue(UiText(R.string.result_detail_links_none) in result)
    }

    @Test
    fun `DS validity with estimated date`() {
        val detail =
            CheckDetail.DsValidity(
                notBefore = Instant.parse("2020-01-01T00:00:00Z"),
                notAfter = Instant.parse("2030-12-31T23:00:00Z"),
                issuanceDate = LocalDate.of(2021, 6, 15),
                source = IssuanceDateSource.ESTIMATED,
            )
        val result = lines(Check(CheckId.DS_VALIDITY, CheckStatus.OK, detail))
        assertEquals(
            listOf(
                UiText(R.string.result_detail_ds_period, listOf("2020-01-01", "2030-12-31")),
                UiText(
                    R.string.result_detail_issuance,
                    listOf("2021-06-15", UiText(R.string.result_detail_issuance_estimated)),
                ),
            ),
            result,
        )
    }

    @Test
    fun `DS validity without issuance date`() {
        val detail = CheckDetail.DsValidity(Instant.EPOCH, Instant.EPOCH, null, null)
        val result = lines(Check(CheckId.DS_VALIDITY, CheckStatus.FAILED, detail))
        assertEquals(UiText(R.string.result_detail_issuance_unknown), result.last())
    }

    @Test
    fun `checked and mismatched hashes`() {
        val detail = CheckDetail.DataGroupHashes("SHA-256", checked = listOf(2, 1, 14), mismatched = listOf(2))
        val result = lines(Check(CheckId.DG_HASHES, CheckStatus.FAILED, detail))
        assertEquals(
            listOf(
                UiText(R.string.result_detail_digest_algorithm, listOf("SHA-256")),
                UiText(R.string.result_detail_dg_checked, listOf("DG1, DG2, DG14")),
                UiText(R.string.result_detail_dg_mismatched, listOf("DG2")),
            ),
            result,
        )
    }

    @Test
    fun `all hashes match`() {
        val detail = CheckDetail.DataGroupHashes("SHA-256", checked = listOf(1, 2), mismatched = emptyList())
        val result = lines(Check(CheckId.DG_HASHES, CheckStatus.OK, detail))
        assertEquals(UiText(R.string.result_detail_dg_all_match), result.last())
    }

    @Test
    fun `hashes - known issuer deviation, DG12 excluded`() {
        val detail =
            CheckDetail.DataGroupHashes(
                "SHA-256",
                checked = listOf(1, 2, 12),
                mismatched = emptyList(),
                deviations = listOf(KnownDeviation("IT-CIE3-DG12", 12)),
            )
        val result = lines(Check(CheckId.DG_HASHES, CheckStatus.OK, detail))
        assertEquals(
            listOf(
                UiText(R.string.result_detail_digest_algorithm, listOf("SHA-256")),
                UiText(R.string.result_detail_dg_checked, listOf("DG1, DG2, DG12")),
                UiText(R.string.result_detail_dg_known_deviation, listOf("DG12")),
            ),
            result,
        )
    }

    @Test
    fun `CA and AA not available, step not reached`() {
        assertEquals(
            listOf(UiText(R.string.result_detail_no_dg14)),
            lines(Check(CheckId.CHIP_AUTHENTICATION, CheckStatus.NOT_AVAILABLE)),
        )
        assertEquals(
            listOf(UiText(R.string.result_detail_no_dg15)),
            lines(Check(CheckId.ACTIVE_AUTHENTICATION, CheckStatus.NOT_AVAILABLE)),
        )
        assertEquals(
            listOf(UiText(R.string.result_detail_not_reached)),
            lines(Check(CheckId.SOD_SIGNATURE, CheckStatus.NOT_AVAILABLE)),
        )
        assertEquals(listOf(UiText(R.string.result_detail_none)), lines(Check(CheckId.SOD_SIGNATURE, CheckStatus.OK)))
    }

    @Test
    fun `missing DS, unsupported algorithm and error`() {
        assertEquals(
            listOf(UiText(R.string.result_detail_ds_missing)),
            lines(Check(CheckId.CERTIFICATE_CHAIN, CheckStatus.FAILED, CheckDetail.DsCertificateMissing)),
        )
        assertEquals(
            listOf(UiText(R.string.result_detail_unsupported, listOf("1.2.3.4"))),
            lines(
                Check(CheckId.SOD_SIGNATURE, CheckStatus.UNSUPPORTED_ALGORITHM, CheckDetail.UnsupportedAlgorithm("1.2.3.4")),
            ),
        )
        assertEquals(
            listOf(UiText(R.string.result_detail_error, listOf("AA_IO"))),
            lines(Check(CheckId.ACTIVE_AUTHENTICATION, CheckStatus.FAILED, CheckDetail.Error("AA_IO"))),
        )
    }

    @Test
    fun `document kind from the MRZ code`() {
        assertEquals(DocumentKind.PASSPORT, DocumentKind.fromMrzCode("P<"))
        assertEquals(DocumentKind.PASSPORT, DocumentKind.fromMrzCode("PO"))
        assertEquals(DocumentKind.IDENTITY_CARD, DocumentKind.fromMrzCode("ID"))
        assertEquals(DocumentKind.IDENTITY_CARD, DocumentKind.fromMrzCode("I<"))
        assertEquals(DocumentKind.IDENTITY_CARD, DocumentKind.fromMrzCode("A"))
        assertEquals(DocumentKind.IDENTITY_CARD, DocumentKind.fromMrzCode("C<"))
        assertEquals(DocumentKind.OTHER, DocumentKind.fromMrzCode("V"))
        assertEquals(DocumentKind.OTHER, DocumentKind.fromMrzCode(""))
    }

    @Test
    fun `data groups announced but not provided by the chip`() {
        val detail =
            CheckDetail.DataGroupHashes("SHA-256", checked = listOf(1, 2), mismatched = emptyList(), missing = listOf(15, 14))
        val result = lines(Check(CheckId.DG_HASHES, CheckStatus.FAILED, detail))
        assertEquals(
            listOf(
                UiText(R.string.result_detail_digest_algorithm, listOf("SHA-256")),
                UiText(R.string.result_detail_dg_checked, listOf("DG1, DG2")),
                UiText(R.string.result_detail_dg_missing, listOf("DG14, DG15")),
            ),
            result,
        )
    }

    @Test
    fun `known country without chain - no CSCA, country, then DS tried or absent`() {
        // Audit V25: existing strings only, no new string to translate.
        val chain =
            ChainInfo("CN=DS", "01", Instant.EPOCH, Instant.EPOCH, "SHA256withECDSA", null, null, null, null, emptyList())
        val withDs = Check(CheckId.CERTIFICATE_CHAIN, CheckStatus.FAILED, CheckDetail.NoChainForKnownCountry("FR", chain))
        assertEquals(
            listOf(
                UiText(R.string.result_detail_csca_none),
                UiText(R.string.result_detail_country, listOf("pays:FR")),
                UiText(R.string.result_detail_algorithm, listOf("SHA256withECDSA")),
                UiText(R.string.result_detail_ds_subject, listOf("CN=DS")),
            ),
            lines(withDs),
        )
        assertEquals(listOf("FR"), CheckFormatting.countryCodes(withDs))

        val withoutDs = Check(CheckId.CERTIFICATE_CHAIN, CheckStatus.FAILED, CheckDetail.NoChainForKnownCountry("FR", null))
        assertEquals(UiText(R.string.result_detail_ds_missing), lines(withoutDs).last())
    }

    @Test
    fun `inconsistent countries`() {
        val check =
            Check(CheckId.CERTIFICATE_CHAIN, CheckStatus.FAILED, CheckDetail.CountryMismatch("DE", "FR", "FRA"))
        assertEquals(
            listOf(UiText(R.string.result_detail_country_mismatch, listOf("pays:DE", "pays:FR", "FRA"))),
            lines(check),
        )
        assertEquals(listOf("DE", "FR"), CheckFormatting.countryCodes(check))
    }

    @Test
    fun `inconsistent countries with missing attributes`() {
        val unknown = UiText(R.string.result_unknown_value)
        val result = lines(Check(CheckId.CERTIFICATE_CHAIN, CheckStatus.FAILED, CheckDetail.CountryMismatch(null, "FR", "D<<")))
        assertEquals(listOf(UiText(R.string.result_detail_country_mismatch, listOf(unknown, "pays:FR", "D"))), result)
        val none = lines(Check(CheckId.CERTIFICATE_CHAIN, CheckStatus.FAILED, CheckDetail.CountryMismatch(null, null, "<<<")))
        assertEquals(listOf(UiText(R.string.result_detail_country_mismatch, listOf(unknown, unknown, unknown))), none)
    }

    @Test
    fun `imported anchor flagged on the verdict card`() {
        fun chain(source: TrustSource?) =
            ChainInfo("CN=DS", "01", Instant.EPOCH, Instant.EPOCH, "SHA256withRSA", "CN=CSCA", "FR", "00", source, emptyList())
        assertTrue(CheckFormatting.isImportedAnchor(chain(TrustSource.IMPORTED_MASTER_LIST)))
        assertTrue(CheckFormatting.isImportedAnchor(chain(TrustSource.IMPORTED_CERTIFICATE)))
        assertFalse(CheckFormatting.isImportedAnchor(chain(TrustSource.NATIONAL)))
        assertFalse(CheckFormatting.isImportedAnchor(chain(TrustSource.ANTS)))
        assertFalse(CheckFormatting.isImportedAnchor(chain(TrustSource.EMBEDDED_MASTER_LIST)))
        assertFalse(CheckFormatting.isImportedAnchor(chain(null)))
        assertFalse(CheckFormatting.isImportedAnchor(null))
        // Audit V17: SOD chain embedded, EF.CardSecurity (PACE-CAM) chain imported.
        assertTrue(CheckFormatting.isImportedAnchor(chain(TrustSource.ANTS), chain(TrustSource.IMPORTED_CERTIFICATE)))
        assertFalse(CheckFormatting.isImportedAnchor(chain(TrustSource.ANTS), null))
    }
}
