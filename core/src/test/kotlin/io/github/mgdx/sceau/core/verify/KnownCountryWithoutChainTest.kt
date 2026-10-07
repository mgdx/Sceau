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

    // --- Audit V35 : code d'État inconnu, DS d'un autre pays ------------------------------

    @Test
    fun `code d Etat inconnu d ICAO sans chaine - echec`() {
        for (state in listOf("UTO", "ZZZ", "FR<")) {
            val result = verify(forger.document { issuingState = state }, state)

            assertEquals(state, CheckStatus.FAILED, result.certificateChain.status)
            assertEquals(state, CheckDetail.Error("UNKNOWN_STATE_CODE"), result.certificateChain.detail)
            assertEquals(state, CheckStatus.NOT_AVAILABLE, result.sodSignature.status)
            assertEquals(state, Verdict.FAILED, verdict(result))
        }
    }

    @Test
    fun `code d Etat inconnu sans certificat DS - echec`() {
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
    fun `pays reel absent du magasin - emetteur inconnu inchange`() {
        val netherlands = TestPki(TestKeyType.EC, name = "Pays-Bas", country = "NL", seed = SEED + 3)

        val result = verify(netherlands.document { issuingState = "NLD" }, "NLD")

        assertEquals(CheckStatus.NOT_AVAILABLE, result.certificateChain.status)
        assertEquals(Verdict.UNKNOWN_ISSUER, verdict(result))
    }

    @Test
    fun `DS d un autre pays que DG1 sans chaine - echec`() {
        val elsewhere = TestPki(TestKeyType.EC, name = "Ailleurs", country = "XX", seed = SEED + 4)

        val dutch = verify(elsewhere.document { issuingState = "NLD" }, "NLD")

        assertEquals(CheckStatus.FAILED, dutch.certificateChain.status)
        assertEquals(CheckDetail.CountryMismatch(null, "XX", "NLD"), dutch.certificateChain.detail)
        assertEquals(CheckStatus.NOT_AVAILABLE, dutch.sodSignature.status)
        assertEquals(Verdict.FAILED, verdict(dutch))

        // Pays présent dans le magasin : la règle V25 s'applique d'abord, l'issue est la même.
        val french = verify(elsewhere.document { issuingState = "FRA" }, "FRA")
        assertEquals(CheckStatus.FAILED, french.certificateChain.status)
        assertEquals(Verdict.FAILED, verdict(french))
    }

    companion object {
        private const val SEED = 20_261_007L
    }
}
