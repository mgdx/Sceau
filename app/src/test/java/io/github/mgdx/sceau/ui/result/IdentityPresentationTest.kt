package io.github.mgdx.sceau.ui.result

import io.github.mgdx.sceau.core.model.Dg1Data
import io.github.mgdx.sceau.core.model.Sex
import io.github.mgdx.sceau.core.report.CheckDetail
import io.github.mgdx.sceau.core.report.CheckStatus
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
}
