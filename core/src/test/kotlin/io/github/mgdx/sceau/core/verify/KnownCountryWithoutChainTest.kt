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
 * Audit V25 (décision D40, option retenue par l'auteur) : pour un État dont le magasin contient
 * des certificats, l'absence de chaîne est un échec, et non « Émetteur inconnu » ; la signature
 * du SOD n'est pas présentée comme vérifiée tant que la chaîne n'aboutit pas.
 */
class KnownCountryWithoutChainTest {
    /** Magasin : un CSCA français, celui de la PKI factice « française ». */
    private val france by lazy { TestPki(TestKeyType.EC, name = "France", country = "FR", seed = SEED) }

    /** Faussaire : CSCA et DS fabriqués, au nom de la France, absents du magasin. */
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
    fun `document francais authentique - chaine OK`() {
        val result = verify(france.document { issuingState = "FRA" }, "FRA")

        assertEquals(CheckStatus.OK, result.certificateChain.status)
        assertEquals(CheckStatus.OK, result.sodSignature.status)
    }

    @Test
    fun `faux FRA sans certificat DS - echec`() {
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
    fun `faux FRA avec un DS fabrique - echec et signature non verifiee`() {
        val forged = forger.document { issuingState = "FRA" }

        val result = verify(forged, "FRA")

        assertEquals(CheckStatus.FAILED, result.certificateChain.status)
        val detail = result.certificateChain.detail as CheckDetail.NoChainForKnownCountry
        assertEquals("FR", detail.country)
        val chain = detail.chain
        assertNotNull(chain)
        assertNull(chain?.cscaSubject)
        // La signature est cohérente avec le DS fabriqué, mais rien ne garantit ce DS.
        assertEquals(CheckStatus.NOT_AVAILABLE, result.sodSignature.status)
        assertEquals(Verdict.FAILED, verdict(result))
    }

    @Test
    fun `pays absent du magasin - emetteur inconnu inchange`() {
        val italy = TestPki(TestKeyType.EC, name = "Italie", country = "IT", seed = SEED + 2)

        val result = verify(italy.document { issuingState = "ITA" }, "ITA")

        assertEquals(CheckStatus.NOT_AVAILABLE, result.certificateChain.status)
        assertEquals(CheckStatus.NOT_AVAILABLE, result.sodSignature.status)
        assertEquals(Verdict.UNKNOWN_ISSUER, verdict(result))
    }

    @Test
    fun `organisation internationale - emetteur inconnu inchange`() {
        val result = verify(forger.document { issuingState = "UNO" }, "UNO")

        assertEquals(CheckStatus.NOT_AVAILABLE, result.certificateChain.status)
        assertEquals(Verdict.UNKNOWN_ISSUER, verdict(result))
    }

    companion object {
        private const val SEED = 20_261_007L
    }
}
