package io.github.mgdx.sceau.ui.result

import io.github.mgdx.sceau.core.model.Dg1Data
import io.github.mgdx.sceau.core.model.DocumentData
import io.github.mgdx.sceau.core.model.Sex
import io.github.mgdx.sceau.core.report.Check
import io.github.mgdx.sceau.core.report.CheckDetail
import io.github.mgdx.sceau.core.report.CheckId
import io.github.mgdx.sceau.core.report.CheckStatus
import io.github.mgdx.sceau.core.report.Verdict
import io.github.mgdx.sceau.core.report.VerificationReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentityPresentationTest {
    private val eidUb =
        Dg1Data(
            documentCode = "UB",
            issuingState = "D<<",
            documentNumber = "",
            primaryIdentifier = "",
            secondaryIdentifiers = emptyList(),
            nationality = "",
            dateOfBirth = null,
            sex = Sex.UNSPECIFIED,
            dateOfExpiry = null,
            optionalData = null,
        )

    private val dg1Checked = CheckDetail.DataGroupHashes("SHA-256", checked = listOf(1, 2), mismatched = emptyList())

    @Test
    fun `eID-UB authentifiee jusqu'au CSCA - presentation sans identite`() {
        assertTrue(IdentityPresentation.isIdentitylessEidUb(eidUb, CheckStatus.OK, CheckStatus.OK, dg1Checked))
    }

    @Test
    fun `chaine inconnue ou en echec - presentation normale (audit V21)`() {
        listOf(CheckStatus.NOT_AVAILABLE, CheckStatus.FAILED, CheckStatus.UNSUPPORTED_ALGORITHM).forEach { chain ->
            assertFalse(
                chain.name,
                IdentityPresentation.isIdentitylessEidUb(eidUb, CheckStatus.OK, chain, dg1Checked),
            )
        }
    }

    @Test
    fun `signature du SOD non valide - presentation normale`() {
        assertFalse(IdentityPresentation.isIdentitylessEidUb(eidUb, CheckStatus.FAILED, CheckStatus.OK, dg1Checked))
    }

    @Test
    fun `empreinte de DG1 non conforme - presentation normale`() {
        val mismatched = CheckDetail.DataGroupHashes("SHA-256", checked = listOf(1, 2), mismatched = listOf(1))
        assertFalse(IdentityPresentation.isIdentitylessEidUb(eidUb, CheckStatus.OK, CheckStatus.OK, mismatched))
    }

    @Test
    fun `aucune donnee lue - ni identite ni photo (audit V24)`() {
        val checks =
            CheckId.entries.map {
                Check(it, if (it == CheckId.CHIP_AUTHENTICATION) CheckStatus.FAILED else CheckStatus.NOT_AVAILABLE)
            }
        assertEquals(IdentityPresentation.Mode.NOT_READ, IdentityPresentation.mode(report(eidUb, checks, identityRead = false)))
    }

    @Test
    fun `rapport complet - presentation normale, eID-UB inchangee`() {
        val passport = Dg1Data("P<", "UTO", "X1", "DOE", emptyList(), "UTO", null, Sex.UNSPECIFIED, null, null)
        val verified =
            CheckId.entries.map {
                if (it == CheckId.DG_HASHES) Check(it, CheckStatus.OK, dg1Checked) else Check(it, CheckStatus.OK)
            }
        assertEquals(IdentityPresentation.Mode.FULL, IdentityPresentation.mode(report(passport, verified)))
        assertEquals(IdentityPresentation.Mode.IDENTITYLESS_EID_UB, IdentityPresentation.mode(report(eidUb, verified)))
    }

    private fun report(
        dg1: Dg1Data,
        checks: List<Check>,
        identityRead: Boolean = true,
    ) = VerificationReport(
        verdict = Verdict.FAILED,
        checks = checks,
        chain = null,
        document = DocumentData(dg1 = dg1, portrait = null, dg11 = null, dg12 = null, rawDataGroups = emptyMap()),
        identityRead = identityRead,
    )
}
