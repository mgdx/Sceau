package io.github.mgdx.sceau.core.reading

import io.github.mgdx.sceau.core.AccessKey
import io.github.mgdx.sceau.core.Step
import io.github.mgdx.sceau.core.readAndVerify
import io.github.mgdx.sceau.core.report.CheckDetail
import io.github.mgdx.sceau.core.report.CheckId
import io.github.mgdx.sceau.core.report.CheckStatus
import io.github.mgdx.sceau.core.report.Verdict
import io.github.mgdx.sceau.core.report.VerificationReport
import io.github.mgdx.sceau.testchip.SessionKind
import io.github.mgdx.sceau.testchip.SimulatedChip
import io.github.mgdx.sceau.testchip.SimulatedDocuments
import io.github.mgdx.sceau.testchip.TestDocument
import io.github.mgdx.sceau.testchip.TestKeyType
import io.github.mgdx.sceau.testchip.TestPki
import io.github.mgdx.sceau.testchip.TestTrustStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Decision D20: Chip Authentication comes before reading DG1, DG2, DG15, DG11 and DG12,
 * which are served under the CA session keys. The simulated chip records under which session
 * it served each file ([SimulatedChip.fileReads]).
 */
class ChipAuthenticationOrderTest {
    private val pki by lazy { TestPki(TestKeyType.RSA, seed = SEED) }

    private fun document(configure: TestDocument.Builder.() -> Unit = {}): TestDocument =
        pki.document {
            issuingState = "FRA"
            configure()
        }

    private fun store(): TestTrustStore = pki.trustStore(pki.oldCsca)

    private fun mrzOf(document: TestDocument) = AccessKey.Mrz(DOCUMENT_NUMBER, DATE_OF_BIRTH, document.dateOfExpiry)

    /** Reads; [caDoneBeforeVerifySignature] receives the chip-side CA state at the start of VERIFY_SIGNATURE. */
    private suspend fun read(
        chip: SimulatedChip,
        key: AccessKey,
        trustStore: TestTrustStore,
        steps: MutableList<Step> = mutableListOf(),
        caDoneBeforeVerifySignature: MutableList<Boolean> = mutableListOf(),
    ): VerificationReport =
        readAndVerify(chip, key, trustStore) { step ->
            steps += step
            if (step == Step.VERIFY_SIGNATURE) caDoneBeforeVerifySignature += chip.caAlgorithm != null
        }

    private fun assertServedUnder(
        chip: SimulatedChip,
        session: SessionKind,
        vararg dataGroups: Int,
    ) {
        for (number in dataGroups) {
            assertEquals("DG$number", setOf(session), chip.sessionsServing(SimulatedChip.FID_DG_BASE + number))
        }
    }

    @Test
    fun caSucceeded_Dg1Dg2Dg15ReadUnderCaKeys() =
        runTest {
            val document = document()
            val chip = SimulatedChip(document)
            val steps = mutableListOf<Step>()
            val caDone = mutableListOf<Boolean>()

            val report = read(chip, mrzOf(document), store(), steps, caDone)

            assertEquals(Verdict.AUTHENTIC, report.verdict)
            assertEquals(CheckStatus.OK, report.check(CheckId.CHIP_AUTHENTICATION).status)
            assertEquals(Step.entries.toList(), steps)
            assertEquals("CA performed during READ_DATA", listOf(true), caDone)
            // EF.COM, EF.SOD and DG14 under BAC; everything else under the CA keys.
            assertEquals(setOf(SessionKind.BAC), chip.sessionsServing(SimulatedChip.FID_COM))
            assertEquals(setOf(SessionKind.BAC), chip.sessionsServing(SimulatedChip.FID_SOD))
            assertServedUnder(chip, SessionKind.BAC, 14)
            assertServedUnder(chip, SessionKind.CHIP_AUTHENTICATION, 1, 2, 15)
            assertEquals(0, chip.reconnections)
            assertEquals(DOCUMENT_NUMBER, report.document.dg1.documentNumber)
        }

    @Test
    fun caSucceededUnderPace_CnieDataReadUnderCaKeys() =
        runTest {
            val card = SimulatedDocuments.frenchIdCard()
            val chip = card.chip()

            val report = read(chip, checkNotNull(card.canKey), card.trustStore)

            assertEquals(Verdict.AUTHENTIC, report.verdict)
            assertServedUnder(chip, SessionKind.PACE, 14)
            assertServedUnder(chip, SessionKind.CHIP_AUTHENTICATION, 1, 2, 11, 12, 15)
        }

    @Test
    fun caRefusedByChip_FailureAndReadUnderPreviousChannel() =
        runTest {
            val document = document()
            val chip = SimulatedChip(document, refuseChipAuthentication = true)

            val report = read(chip, mrzOf(document), store())

            assertEquals(Verdict.FAILED, report.verdict)
            assertEquals(CheckStatus.FAILED, report.check(CheckId.CHIP_AUTHENTICATION).status)
            // AA and PA were still performed on data read under BAC.
            assertEquals(CheckStatus.OK, report.check(CheckId.DG_HASHES).status)
            assertEquals(CheckStatus.OK, report.check(CheckId.ACTIVE_AUTHENTICATION).status)
            assertNull(chip.caAlgorithm)
            assertEquals("BAC channel stayed open: no reconnection", 0, chip.reconnections)
            assertServedUnder(chip, SessionKind.BAC, 1, 2, 14, 15)
            assertEquals(DOCUMENT_NUMBER, report.document.dg1.documentNumber)
        }

    @Test
    fun cloneWithoutCaKey_FailureThenChannelRestoredAndDataRead() =
        runTest {
            // DG14 copied, but the chip does not hold the private key: it switches to wrong
            // session keys, rejects the confirmation (invalid MAC) and closes secure messaging.
            val document = document()
            val chip = SimulatedChip(document, caPrivateKey = pki.generateEc(TestPki.CURVE).private)
            val steps = mutableListOf<Step>()

            val report = read(chip, mrzOf(document), store(), steps)

            assertEquals(Verdict.FAILED, report.verdict)
            val ca = report.check(CheckId.CHIP_AUTHENTICATION)
            assertEquals(CheckStatus.FAILED, ca.status)
            assertEquals(Step.entries.toList(), steps)
            assertEquals("channel restored by reconnection", 1, chip.reconnections)
            // Data read under a new BAC, with the same MRZ key, never under the CA keys.
            assertServedUnder(chip, SessionKind.BAC, 1, 2, 15)
            assertEquals(CheckStatus.OK, report.check(CheckId.DG_HASHES).status)
            assertEquals(CheckStatus.OK, report.check(CheckId.ACTIVE_AUTHENTICATION).status)
            assertEquals(DOCUMENT_NUMBER, report.document.dg1.documentNumber)
            assertTrue(report.identityRead)
        }

    @Test
    fun cloneWithoutCaKeyUnderPace_PaceChannelRestored() =
        runTest {
            val card = SimulatedDocuments.frenchIdCard()
            val chip = SimulatedChip(card.document, card.pace, caPrivateKey = card.pki.generateEc(TestPki.CURVE).private)

            val report = read(chip, checkNotNull(card.canKey), card.trustStore)

            assertEquals(Verdict.FAILED, report.verdict)
            assertEquals(CheckStatus.FAILED, report.check(CheckId.CHIP_AUTHENTICATION).status)
            assertEquals(1, chip.reconnections)
            assertServedUnder(chip, SessionKind.PACE, 1, 2, 11, 12, 15)
            assertNotNull(report.document.dg11)
        }

    @Test
    fun cloneWithoutCaKeyThenRestrictedOnRestore_FailureNotAccessRestricted() =
        runTest {
            // Audit V24: the chip fails CA, then answers 6982 to the applet selection when the
            // channel is restored, to get the "reserved for authorities" message instead of a failure.
            val card = SimulatedDocuments.frenchIdCard()
            val chip =
                SimulatedChip(
                    card.document,
                    card.pace,
                    caPrivateKey = card.pki.generateEc(TestPki.CURVE).private,
                    restrictAppletAfterReconnect = true,
                )

            val report = read(chip, checkNotNull(card.canKey), card.trustStore)

            assertEquals(Verdict.FAILED, report.verdict)
            assertEquals(CheckStatus.FAILED, report.check(CheckId.CHIP_AUTHENTICATION).status)
            assertEquals(1, chip.reconnections)
            val notDone = report.check(CheckId.SOD_SIGNATURE)
            assertEquals(CheckStatus.NOT_AVAILABLE, notDone.status)
            assertEquals(
                CheckDetail.Error("READ_DATA-REESTABLISH-ACCESS_RESTRICTED-SELECT_APPLET"),
                notDone.detail,
            )
            // No identity data read: no DG1, DG2 or DG11.
            assertFalse(report.identityRead)
            assertEquals("", report.document.dg1.documentNumber)
            assertNull(report.document.portrait)
            assertNull(report.document.dg11)
            assertTrue(chip.closed)
        }

    @Test
    fun dg14Missing_CaNotAvailableAndDataUnderBac() =
        runTest {
            val document = document { chipAuthentication = false }
            val chip = SimulatedChip(document)

            val report = read(chip, mrzOf(document), store())

            assertEquals(Verdict.AUTHENTIC, report.verdict)
            assertEquals(CheckStatus.NOT_AVAILABLE, report.check(CheckId.CHIP_AUTHENTICATION).status)
            assertEquals(0, chip.reconnections)
            assertTrue("DG14 never requested", 0x010E !in chip.selectedFids)
            assertServedUnder(chip, SessionKind.BAC, 1, 2, 15)
        }

    companion object {
        private const val SEED = 20_260_928L
        private const val DOCUMENT_NUMBER = "L898902C3"
        private val DATE_OF_BIRTH: LocalDate = LocalDate.of(1974, 8, 12)
    }
}
