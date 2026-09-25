package io.github.mgdx.sceau.core.reading

import io.github.mgdx.sceau.core.AccessKey
import io.github.mgdx.sceau.core.SceauException
import io.github.mgdx.sceau.core.Step
import io.github.mgdx.sceau.core.model.ImageFormat
import io.github.mgdx.sceau.core.model.Sex
import io.github.mgdx.sceau.core.readAndVerify
import io.github.mgdx.sceau.core.report.ChannelProtocol
import io.github.mgdx.sceau.core.report.CheckDetail
import io.github.mgdx.sceau.core.report.CheckId
import io.github.mgdx.sceau.core.report.CheckStatus
import io.github.mgdx.sceau.core.report.Verdict
import io.github.mgdx.sceau.core.report.VerificationReport
import io.github.mgdx.sceau.testchip.PacePassword
import io.github.mgdx.sceau.testchip.SimCrypto
import io.github.mgdx.sceau.testchip.SimulatedChip
import io.github.mgdx.sceau.testchip.SimulatedDocuments
import io.github.mgdx.sceau.testchip.SimulatedIdentityDocument
import io.github.mgdx.sceau.testchip.SodOptions
import io.github.mgdx.sceau.testchip.TestCrypto
import io.github.mgdx.sceau.testchip.resigned
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Tests de bout en bout de `readAndVerify` contre une CNIe française simulée
 * ([SimulatedDocuments.frenchIdCard]) : PACE-CAN (ECDH GM, AES-128, brainpoolP256r1) mené par
 * JMRTD face à une puce qui implémente PACE de son côté, lecture sous messagerie sécurisée AES,
 * Passive Authentication, Chip Authentication AES et Active Authentication ECDSA.
 */
class FrenchIdCardEndToEndTest {
    private val card: SimulatedIdentityDocument by lazy { SimulatedDocuments.frenchIdCard() }

    private suspend fun read(
        chip: SimulatedChip,
        key: AccessKey,
        steps: MutableList<Step> = mutableListOf(),
    ): VerificationReport {
        val report = readAndVerify(chip, key, card.trustStore) { steps += it }
        assertTrue("transport fermé", chip.closed)
        assertNoForbiddenDataGroup(chip)
        return report
    }

    private suspend inline fun <reified E : SceauException> readExpecting(
        chip: SimulatedChip,
        key: AccessKey,
        steps: MutableList<Step>,
    ): E {
        try {
            readAndVerify(chip, key, card.trustStore) { steps += it }
        } catch (e: SceauException) {
            if (e !is E) throw AssertionError("${E::class.simpleName} attendue, reçu ${e.code}", e)
            assertTrue("transport fermé", chip.closed)
            assertNoForbiddenDataGroup(chip)
            return e
        }
        fail("${E::class.simpleName} attendue")
        throw IllegalStateException()
    }

    private fun assertNoForbiddenDataGroup(chip: SimulatedChip) {
        assertFalse("DG3 demandé", SimulatedChip.FID_DG3 in chip.selectedFids)
        assertFalse("DG4 demandé", SimulatedChip.FID_DG4 in chip.selectedFids)
    }

    private fun assertAllOk(report: VerificationReport) {
        assertEquals(CheckId.entries.toList(), report.checks.map { it.id })
        for (check in report.checks) assertEquals("${check.id} : ${check.detail}", CheckStatus.OK, check.status)
    }

    private fun hashes(report: VerificationReport) = report.check(CheckId.DG_HASHES).detail as CheckDetail.DataGroupHashes

    @Test
    fun canCorrect_PaceAuthentique() =
        runTest {
            val chip = card.chip()
            val steps = mutableListOf<Step>()

            val report = read(chip, checkNotNull(card.canKey), steps)

            assertEquals(Verdict.AUTHENTIC, report.verdict)
            assertAllOk(report)
            assertEquals("progression dans l'ordre, sans répétition", Step.entries.toList(), steps)
            assertEquals(CheckDetail.SecureChannel(ChannelProtocol.PACE), report.check(CheckId.SECURE_CHANNEL).detail)
            assertEquals(listOf(1, 2, 11, 12, 14, 15), hashes(report).checked)
            assertEquals(emptyList<Int>(), hashes(report).mismatched)
            assertTrue(
                report.chain
                    ?.cscaSubject
                    .orEmpty()
                    .contains("CSCA-TEST-FRANCE"),
            )

            // La puce a mené PACE avec le CAN (et non BAC), puis la CA AES et l'AA ECDSA.
            assertEquals(PacePassword.CAN, chip.paceCompletedWith)
            assertFalse(chip.bacCompleted)
            assertEquals(SimCrypto.Algorithm.AES, chip.caAlgorithm)
            assertEquals(1, chip.aaChallenges.size)
            assertEquals("EF.CardAccess lu en premier", SimulatedChip.FID_CARD_ACCESS, chip.selectedFids.first())

            val dg1 = report.document.dg1
            assertEquals("ID", dg1.documentCode)
            assertEquals("FRA", dg1.issuingState)
            assertEquals(SimulatedDocuments.SPECIMEN_DOCUMENT_NUMBER, dg1.documentNumber)
            assertEquals("SPECIMEN", dg1.primaryIdentifier)
            assertEquals(listOf("MARIANNE"), dg1.secondaryIdentifiers)
            assertEquals("FRA", dg1.nationality)
            assertEquals(SimulatedDocuments.SPECIMEN_DATE_OF_BIRTH, dg1.dateOfBirth)
            assertEquals(Sex.FEMALE, dg1.sex)
            assertEquals(SimulatedDocuments.SPECIMEN_DATE_OF_EXPIRY, dg1.dateOfExpiry)

            val portrait = checkNotNull(report.document.portrait)
            assertEquals(ImageFormat.JPEG2000, portrait.format)
            assertArrayEquals(SimulatedDocuments.specimenPortrait(), portrait.bytes)

            val dg11 = checkNotNull(report.document.dg11)
            assertEquals("SPECIMEN, MARIANNE", dg11.fullName)
            assertEquals(SimulatedDocuments.SPECIMEN_DATE_OF_BIRTH, dg11.fullDateOfBirth)
            assertEquals(listOf("VILLE-SPECIMEN"), dg11.placeOfBirth)
            assertEquals(listOf("1 RUE DU SPECIMEN", "99999 VILLE-TEST"), dg11.address)

            val dg12 = checkNotNull(report.document.dg12)
            assertEquals("PREFECTURE DE TEST", dg12.issuingAuthority)
            assertEquals(SimulatedDocuments.SPECIMEN_DATE_OF_ISSUE, dg12.dateOfIssue)
        }

    @Test
    fun cloneRetenantDg2Dg14Dg15_Echec() =
        runTest {
            // Clone d'une CNIe : SOD, DG1, DG11, DG12 rejoués ; photo, DG14 et DG15 retenus et
            // absents d'EF.COM. Sans la correction V1, le verdict serait « puce non vérifiée ».
            val chip =
                SimulatedChip(
                    card.document,
                    card.pace,
                    comDataGroups = listOf(1, 11, 12),
                    withheldDataGroups = listOf(2, 14, 15),
                )

            val report = read(chip, checkNotNull(card.canKey))

            assertEquals(Verdict.FAILED, report.verdict)
            assertEquals(CheckStatus.OK, report.check(CheckId.SOD_SIGNATURE).status)
            assertEquals(CheckStatus.FAILED, report.check(CheckId.DG_HASHES).status)
            assertEquals(listOf(2, 14, 15), hashes(report).missing)
            assertEquals(CheckStatus.FAILED, report.check(CheckId.CHIP_AUTHENTICATION).status)
            assertEquals(CheckStatus.FAILED, report.check(CheckId.ACTIVE_AUTHENTICATION).status)
            assertNull(report.document.portrait)
        }

    @Test
    fun canFaux_AccessDenied() =
        runTest {
            val chip = card.chip()
            val steps = mutableListOf<Step>()

            val error = readExpecting<SceauException.AccessDenied>(chip, AccessKey.Can("654321"), steps)

            assertEquals("ACCESS_DENIED", error.code)
            assertEquals(listOf(Step.CONNECT, Step.SECURE_CHANNEL), steps)
            assertNull(chip.paceCompletedWith)
            assertFalse(chip.bacCompleted)
            // Jeton T.PCD refusé à la dernière étape : 63C2, une seule tentative, pas de repli BAC.
            assertEquals(2, chip.paceRetries)
            assertTrue("aucun fichier de l'applet lu", chip.selectedFids.all { it == SimulatedChip.FID_CARD_ACCESS })
        }

    @Test
    fun mrzSurCartePace_PaceMrzAuthentique() =
        runTest {
            val chip = card.chip()
            val steps = mutableListOf<Step>()

            val report = read(chip, card.mrzKey, steps)

            assertEquals(Verdict.AUTHENTIC, report.verdict)
            assertAllOk(report)
            assertEquals(Step.entries.toList(), steps)
            assertEquals(CheckDetail.SecureChannel(ChannelProtocol.PACE), report.check(CheckId.SECURE_CHANNEL).detail)
            assertEquals("PACE-MRZ, sans repli sur BAC", PacePassword.MRZ, chip.paceCompletedWith)
            assertFalse(chip.bacCompleted)
        }

    @Test
    fun canSurPuceSansCardAccess_CanWithoutPace() =
        runTest {
            // Même document, mais puce sans EF.CardAccess : BAC seul.
            val chip = SimulatedChip(card.document)
            val steps = mutableListOf<Step>()

            val error = readExpecting<SceauException.CanWithoutPace>(chip, checkNotNull(card.canKey), steps)

            assertEquals("CAN_WITHOUT_PACE", error.code)
            assertEquals(listOf(Step.CONNECT, Step.SECURE_CHANNEL), steps)
            assertFalse(chip.bacCompleted)
            assertTrue("aucun fichier de l'applet lu", chip.selectedFids.all { it == SimulatedChip.FID_CARD_ACCESS })
        }

    @Test
    fun dg3Dg4AnnoncesDansComEtSod_JamaisDemandes() =
        runTest {
            val fakeHash = ByteArray(32) { 0x33 }
            val document =
                card.document.resigned(
                    options =
                        SodOptions(
                            signingTime = TestCrypto.instant(SimulatedDocuments.SPECIMEN_DATE_OF_ISSUE),
                            extraHashes = mapOf(3 to fakeHash, 4 to fakeHash),
                        ),
                )
            val chip = SimulatedChip(document, card.pace, comDataGroups = listOf(1, 2, 3, 4, 11, 12, 14, 15))

            val report = read(chip, checkNotNull(card.canKey))

            assertEquals(Verdict.AUTHENTIC, report.verdict)
            assertEquals(listOf(1, 2, 11, 12, 14, 15), hashes(report).checked)
            assertEquals(setOf(1, 2, 11, 12, 14, 15), report.document.rawDataGroups.keys)
        }
}
