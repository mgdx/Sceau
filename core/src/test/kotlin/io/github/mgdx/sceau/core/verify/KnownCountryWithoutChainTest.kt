package io.github.mgdx.sceau.core.verify

import io.github.mgdx.sceau.core.report.CheckDetail
import io.github.mgdx.sceau.core.report.CheckStatus
import io.github.mgdx.sceau.core.report.Verdict
import io.github.mgdx.sceau.testchip.SodOptions
import io.github.mgdx.sceau.testchip.TestDocument
import io.github.mgdx.sceau.testchip.TestKeyType
import io.github.mgdx.sceau.testchip.TestPki
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Audit V25 (decision D40, option chosen by the author): for a state that has certificates in
 * the store, a missing chain is a failure, not "Unknown issuer"; the SOD signature is not
 * presented as verified until the chain is complete.
 */
class KnownCountryWithoutChainTest {
    /** Store: one French CSCA, the one from the dummy "French" PKI. */
    private val france by lazy { TestPki(TestKeyType.EC, name = "France", country = "FR", seed = SEED) }

    /** Forger: forged CSCA and DS in the name of France, absent from the store. */
    private val forger by lazy { TestPki(TestKeyType.EC, name = "Faussaire", country = "FR", seed = SEED + 1) }

    private fun verify(
        document: TestDocument,
        issuingState: String,
    ): PassiveAuthResult =
        PassiveAuthentication.verify(
            sod = document.sod,
            dataGroups = document.dataGroups,
            trustStore = france.trustStore(france.oldCsca),
            dateOfIssue = document.dateOfIssue,
            dateOfExpiry = document.dateOfExpiry,
            documentCode = document.documentCode,
            issuingState = issuingState,
        )

    private fun verdict(result: PassiveAuthResult) = Verdicts.compute(VerifyTestSupport.checks(result))

    @Test
    fun `authentic French document - chain OK`() {
        val result = verify(france.document { issuingState = "FRA" }, "FRA")

        assertEquals(CheckStatus.OK, result.certificateChain.status)
        assertEquals(CheckStatus.OK, result.sodSignature.status)
    }

    @Test
    fun `fake FRA without a DS certificate - failure`() {
        val forged =
            forger.document {
                issuingState = "FRA"
                sod = SodOptions(embedDs = false)
            }

        val result = verify(forged, "FRA")

        assertEquals(CheckStatus.FAILED, result.certificateChain.status)
        assertEquals(CheckDetail.NoChainForKnownCountry("FR", null), result.certificateChain.detail)
        assertEquals(CheckStatus.NOT_AVAILABLE, result.sodSignature.status)
        assertEquals(Verdict.FAILED, verdict(result))
    }

    @Test
    fun `fake FRA with a forged DS - failure and unverified signature`() {
        val forged = forger.document { issuingState = "FRA" }

        val result = verify(forged, "FRA")

        assertEquals(CheckStatus.FAILED, result.certificateChain.status)
        val detail = result.certificateChain.detail as CheckDetail.NoChainForKnownCountry
        assertEquals("FR", detail.country)
        val chain = detail.chain
        assertNotNull(chain)
        assertNull(chain?.cscaSubject)
        // The signature matches the forged DS, but nothing vouches for that DS.
        assertEquals(CheckStatus.NOT_AVAILABLE, result.sodSignature.status)
        assertEquals(Verdict.FAILED, verdict(result))
    }

    @Test
    fun `country absent from the store - unknown issuer unchanged`() {
        val italy = TestPki(TestKeyType.EC, name = "Italie", country = "IT", seed = SEED + 2)

        val result = verify(italy.document { issuingState = "ITA" }, "ITA")

        assertEquals(CheckStatus.NOT_AVAILABLE, result.certificateChain.status)
        assertEquals(CheckStatus.NOT_AVAILABLE, result.sodSignature.status)
        assertEquals(Verdict.UNKNOWN_ISSUER, verdict(result))
    }

    @Test
    fun `international organization - unknown issuer unchanged`() {
        val result = verify(forger.document { issuingState = "UNO" }, "UNO")

        assertEquals(CheckStatus.NOT_AVAILABLE, result.certificateChain.status)
        assertEquals(Verdict.UNKNOWN_ISSUER, verdict(result))
    }

    // --- Audit V35: unknown state code, DS from another country ------------------------

    @Test
    fun `state code unknown to ICAO without a chain - failure`() {
        for (state in listOf("UTO", "ZZZ", "FR<")) {
            val result = verify(forger.document { issuingState = state }, state)

            assertEquals(state, CheckStatus.FAILED, result.certificateChain.status)
            assertEquals(state, CheckDetail.Error("UNKNOWN_STATE_CODE"), result.certificateChain.detail)
            assertEquals(state, CheckStatus.NOT_AVAILABLE, result.sodSignature.status)
            assertEquals(state, Verdict.FAILED, verdict(result))
        }
    }

    @Test
    fun `unknown state code without a DS certificate - failure`() {
        val forged =
            forger.document {
                issuingState = "UTO"
                sod = SodOptions(embedDs = false)
            }

        val result = verify(forged, "UTO")

        assertEquals(CheckStatus.FAILED, result.certificateChain.status)
        assertEquals(CheckDetail.Error("UNKNOWN_STATE_CODE"), result.certificateChain.detail)
        assertEquals(Verdict.FAILED, verdict(result))
    }

    @Test
    fun `real country absent from the store - unknown issuer unchanged`() {
        val netherlands = TestPki(TestKeyType.EC, name = "Pays-Bas", country = "NL", seed = SEED + 3)

        val result = verify(netherlands.document { issuingState = "NLD" }, "NLD")

        assertEquals(CheckStatus.NOT_AVAILABLE, result.certificateChain.status)
        assertEquals(Verdict.UNKNOWN_ISSUER, verdict(result))
    }

    @Test
    fun `DS from a country other than DG1 without a chain - failure`() {
        val elsewhere = TestPki(TestKeyType.EC, name = "Ailleurs", country = "XX", seed = SEED + 4)

        val dutch = verify(elsewhere.document { issuingState = "NLD" }, "NLD")

        assertEquals(CheckStatus.FAILED, dutch.certificateChain.status)
        assertEquals(CheckDetail.CountryMismatch(null, "XX", "NLD"), dutch.certificateChain.detail)
        assertEquals(CheckStatus.NOT_AVAILABLE, dutch.sodSignature.status)
        assertEquals(Verdict.FAILED, verdict(dutch))

        // Country present in the store: rule V25 applies first, the outcome is the same.
        val french = verify(elsewhere.document { issuingState = "FRA" }, "FRA")
        assertEquals(CheckStatus.FAILED, french.certificateChain.status)
        assertEquals(Verdict.FAILED, verdict(french))
    }

    companion object {
        private const val SEED = 20_261_007L
    }
}
