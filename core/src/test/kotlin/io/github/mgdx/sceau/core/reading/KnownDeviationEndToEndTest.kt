package io.github.mgdx.sceau.core.reading

import io.github.mgdx.sceau.core.AccessKey
import io.github.mgdx.sceau.core.readAndVerify
import io.github.mgdx.sceau.core.report.CheckDetail
import io.github.mgdx.sceau.core.report.CheckId
import io.github.mgdx.sceau.core.report.CheckStatus
import io.github.mgdx.sceau.core.report.IssuanceDateSource
import io.github.mgdx.sceau.core.report.KnownDeviation
import io.github.mgdx.sceau.core.report.Verdict
import io.github.mgdx.sceau.testchip.SimulatedChip
import io.github.mgdx.sceau.testchip.SodOptions
import io.github.mgdx.sceau.testchip.TestCrypto
import io.github.mgdx.sceau.testchip.TestDocument
import io.github.mgdx.sceau.testchip.TestKeyType
import io.github.mgdx.sceau.testchip.TestPki
import kotlinx.coroutines.test.runTest
import org.jmrtd.lds.icao.DG12File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/**
 * Anomalie connue de l'émetteur (décision D34) de bout en bout : CIE 3.0 italienne factice dont
 * le DG12 ne correspond pas au SOD. L'écart est toléré, DG12 est retiré des données du rapport.
 */
class KnownDeviationEndToEndTest {
    private val pki = TestPki(TestKeyType.RSA, name = "Italia Test", country = "IT", seed = SEED)
    private val ds by lazy { pki.issueDs(notBefore = LocalDate.of(2017, 6, 1), notAfter = LocalDate.of(2028, 6, 1)) }

    private fun dg12(
        authority: String,
        dateOfIssue: String = "20151205",
    ): ByteArray = DG12File(authority, dateOfIssue, emptyList(), null, null, null, null, null, null).encoded

    private fun cie(signedDg12: ByteArray = dg12("COMUNE DI UTOPIA")): TestDocument =
        pki.document {
            ds = this@KnownDeviationEndToEndTest.ds
            documentCode = "C"
            issuingState = "ITA"
            documentNumber = DOCUMENT_NUMBER
            extraDataGroups = mapOf(12 to signedDg12)
            sod = SodOptions(signingTime = TestCrypto.instant(LocalDate.of(2017, 12, 15)))
        }

    private fun key(document: TestDocument) = AccessKey.Mrz(DOCUMENT_NUMBER, LocalDate.of(1974, 8, 12), document.dateOfExpiry)

    @Test
    fun `DG12 faux sur document couvert - ecart tolere, DG12 retire du rapport, Authentique`() =
        runTest {
            val document = cie().let { it.withDataGroups(it.dataGroups + (12 to dg12("COMUNE ERRATO"))) }
            val report = readAndVerify(SimulatedChip(document), key(document), pki.trustStore(pki.oldCsca)) {}

            val hashes = report.check(CheckId.DG_HASHES)
            assertEquals(CheckStatus.OK, hashes.status)
            assertEquals(listOf(KnownDeviation("IT-CIE3-DG12", 12)), (hashes.detail as CheckDetail.DataGroupHashes).deviations)
            assertNull(report.document.dg12)
            assertFalse(12 in report.document.rawDataGroups)
            val validity = report.check(CheckId.DS_VALIDITY).detail as CheckDetail.DsValidity
            assertEquals(IssuanceDateSource.SOD_SIGNING_TIME, validity.source)
            assertEquals(Verdict.AUTHENTIC, report.verdict)
        }

    @Test
    fun `DG12 correct - DG12 affiche, aucune deviation`() =
        runTest {
            // DG12 conforme : date de délivrance réelle, dans la période du DS.
            val document = cie(dg12("COMUNE DI UTOPIA", dateOfIssue = "20171215"))
            val report = readAndVerify(SimulatedChip(document), key(document), pki.trustStore(pki.oldCsca)) {}

            assertEquals(emptyList<KnownDeviation>(), (report.check(CheckId.DG_HASHES).detail as CheckDetail.DataGroupHashes).deviations)
            assertNotNull(report.document.dg12)
            assertEquals(Verdict.AUTHENTIC, report.verdict)
        }

    private companion object {
        const val SEED = 0x17A2L
        const val DOCUMENT_NUMBER = "CA12345XY"
    }
}
