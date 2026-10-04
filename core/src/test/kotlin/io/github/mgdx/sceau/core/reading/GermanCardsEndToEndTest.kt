package io.github.mgdx.sceau.core.reading

import io.github.mgdx.sceau.core.AccessKey
import io.github.mgdx.sceau.core.SceauException
import io.github.mgdx.sceau.core.model.Sex
import io.github.mgdx.sceau.core.readAndVerify
import io.github.mgdx.sceau.core.report.CheckDetail
import io.github.mgdx.sceau.core.report.CheckId
import io.github.mgdx.sceau.core.report.CheckStatus
import io.github.mgdx.sceau.core.report.Verdict
import io.github.mgdx.sceau.core.report.VerificationReport
import io.github.mgdx.sceau.core.trust.TrustStore
import io.github.mgdx.sceau.testchip.PaceSettings
import io.github.mgdx.sceau.testchip.SimulatedChip
import io.github.mgdx.sceau.testchip.SimulatedDocuments
import io.github.mgdx.sceau.testchip.SimulatedIdentityDocument
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Cartes allemandes (docs/deviations.md §3.1, décision D36), avec la puce simulée :
 *
 * - DE-1 : carte d'identité délivrée avant le 2021-08-02, dont l'application ICAO et EF.SOD sont
 *   réservés aux terminaux étatiques. Après un canal établi, un 6982 à la sélection de
 *   l'application ou à la lecture d'EF.SOD ou de DG1 donne [SceauException.AccessRestricted] ;
 *   ailleurs (avant l'authentification, fichier facultatif), le comportement reste le même.
 * - DE-2 : carte eID-UB, dont DG1 ne porte aucune donnée d'identité (MRZ « UBD<<<… ») : la
 *   lecture et la vérification aboutissent sans exception.
 */
class GermanCardsEndToEndTest {
    private val frenchCard: SimulatedIdentityDocument by lazy { SimulatedDocuments.frenchIdCard() }
    private val eidUb: SimulatedIdentityDocument by lazy { SimulatedDocuments.germanEidUb() }

    private fun restrictedChip(
        applet: Boolean = false,
        files: Set<Int> = emptySet(),
        pace: PaceSettings? = frenchCard.pace,
    ) = SimulatedChip(frenchCard.document, pace, restrictedApplet = applet, restrictedFiles = files)

    private suspend fun read(
        chip: SimulatedChip,
        key: AccessKey,
        trustStore: TrustStore = frenchCard.trustStore,
    ): VerificationReport {
        val report = readAndVerify(chip, key, trustStore) {}
        assertTrue("transport fermé", chip.closed)
        return report
    }

    private suspend fun readFailing(
        chip: SimulatedChip,
        key: AccessKey,
    ): SceauException {
        try {
            readAndVerify(chip, key, frenchCard.trustStore) {}
        } catch (e: SceauException) {
            assertTrue("transport fermé", chip.closed)
            return e
        }
        fail("SceauException attendue")
        throw IllegalStateException()
    }

    // --- DE-1 : accès réservé aux terminaux étatiques -----------------------------------

    @Test
    fun `DE-1 - application ICAO refusee apres PACE - acces reserve`() =
        runTest {
            val chip = restrictedChip(applet = true)
            val error = readFailing(chip, checkNotNull(frenchCard.canKey))
            assertTrue(error is SceauException.AccessRestricted)
            assertEquals("ACCESS_RESTRICTED-SECURE_CHANNEL-SELECT_APPLET", error.code)
            assertNotNull("PACE a abouti", chip.paceCompletedWith)
        }

    @Test
    fun `DE-1 - EF SOD refuse apres PACE - acces reserve`() =
        runTest {
            val chip = restrictedChip(files = setOf(SimulatedChip.FID_SOD))
            val error = readFailing(chip, checkNotNull(frenchCard.canKey))
            assertTrue(error is SceauException.AccessRestricted)
            assertEquals("ACCESS_RESTRICTED-READ_DATA-SOD", error.code)
        }

    @Test
    fun `DE-1 - DG1 refuse apres BAC - acces reserve`() =
        runTest {
            val chip = restrictedChip(files = setOf(FID_DG1), pace = null)
            val error = readFailing(chip, frenchCard.mrzKey)
            assertTrue(error is SceauException.AccessRestricted)
            assertEquals("ACCESS_RESTRICTED-READ_DATA-DG1", error.code)
            assertTrue("BAC a abouti", chip.bacCompleted)
        }

    @Test
    fun `DE-1 - application refusee en clair avant BAC - code technique inchange`() =
        runTest {
            val chip = restrictedChip(applet = true, pace = null)
            val error = readFailing(chip, frenchCard.mrzKey)
            assertTrue(error is SceauException.Unexpected)
            assertEquals("UNEXPECTED-CONNECT-SELECT_APPLET-CardServiceException-6982", error.code)
            assertFalse(chip.bacCompleted)
        }

    @Test
    fun `DE-1 - CAN faux sur une puce a acces reserve - cle refusee`() =
        runTest {
            val error = readFailing(restrictedChip(applet = true), AccessKey.Can("000000"))
            assertTrue(error is SceauException.AccessDenied)
            assertEquals("ACCESS_DENIED", error.code)
        }

    @Test
    fun `EF COM refuse - facultatif, lecture complete`() =
        runTest {
            val report = read(restrictedChip(files = setOf(SimulatedChip.FID_COM)), checkNotNull(frenchCard.canKey))
            assertEquals(Verdict.AUTHENTIC, report.verdict)
        }

    @Test
    fun `DG11 refuse - facultatif, absent du rapport sans erreur de lecture`() =
        runTest {
            val report = read(restrictedChip(files = setOf(FID_DG11)), checkNotNull(frenchCard.canKey))
            assertNull(report.document.dg11)
            val hashes = report.check(CheckId.DG_HASHES)
            assertEquals(CheckStatus.FAILED, hashes.status)
            assertEquals(listOf(11), (hashes.detail as CheckDetail.DataGroupHashes).missing)
        }

    @Test
    fun `DG15 refuse - facultatif, AA en echec sans erreur de lecture`() =
        runTest {
            val report = read(restrictedChip(files = setOf(FID_DG15)), checkNotNull(frenchCard.canKey))
            assertEquals(CheckStatus.FAILED, report.check(CheckId.ACTIVE_AUTHENTICATION).status)
        }

    // --- DE-2 : carte eID-UB --------------------------------------------------------------

    @Test
    fun `DE-2 - DG1 de remplacement analyse sans exception`() {
        val dg1 = DataGroupParsers.parseDg1(SimulatedDocuments.eidUbDg1())
        assertEquals("UB", dg1.documentCode)
        assertEquals("D<<", dg1.issuingState)
        assertEquals("", dg1.documentNumber)
        assertEquals("", dg1.primaryIdentifier)
        assertTrue(dg1.secondaryIdentifiers.isEmpty())
        assertNull(dg1.dateOfBirth)
        assertNull(dg1.dateOfExpiry)
        assertEquals(Sex.UNSPECIFIED, dg1.sex)
        assertNull(dg1.optionalData)
    }

    @Test
    fun `DE-2 - eID-UB lue et verifiee - authentique, validite du DS non evaluable`() =
        runTest {
            val chip = eidUb.chip()
            val report = read(chip, checkNotNull(eidUb.canKey), eidUb.trustStore)
            assertEquals(Verdict.AUTHENTIC, report.verdict)
            assertEquals(CheckStatus.OK, report.check(CheckId.SOD_SIGNATURE).status)
            assertEquals(CheckStatus.OK, report.check(CheckId.CERTIFICATE_CHAIN).status)
            assertEquals(CheckStatus.OK, report.check(CheckId.DG_HASHES).status)
            assertEquals(CheckStatus.OK, report.check(CheckId.CHIP_AUTHENTICATION).status)
            assertEquals(CheckStatus.NOT_AVAILABLE, report.check(CheckId.ACTIVE_AUTHENTICATION).status)
            val validity = report.check(CheckId.DS_VALIDITY)
            assertEquals(CheckStatus.NOT_AVAILABLE, validity.status)
            assertNull((validity.detail as CheckDetail.DsValidity).issuanceDate)
            val dg1 = report.document.dg1
            assertEquals("UB", dg1.documentCode)
            assertEquals("D<<", dg1.issuingState)
            assertNull(dg1.dateOfExpiry)
            assertNull(report.document.dg11)
            assertNull(report.document.dg12)
        }

    private companion object {
        const val FID_DG1 = 0x0101
        const val FID_DG11 = 0x010B
        const val FID_DG15 = 0x010F
    }
}
