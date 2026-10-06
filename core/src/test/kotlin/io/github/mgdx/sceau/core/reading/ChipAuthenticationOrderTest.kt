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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Décision D20 : la Chip Authentication précède la lecture de DG1, DG2, DG15, DG11 et DG12,
 * qui sont servis sous les clés de session de la CA. La puce simulée note sous quelle session
 * elle a servi chaque fichier ([SimulatedChip.fileReads]).
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

    /** Lecture ; [caDoneBeforeVerifySignature] reçoit, au début de VERIFY_SIGNATURE, l'état de la CA côté puce. */
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
    fun caReussie_Dg1Dg2Dg15LusSousLesClesDeLaCa() =
        runTest {
            val document = document()
            val chip = SimulatedChip(document)
            val steps = mutableListOf<Step>()
            val caDone = mutableListOf<Boolean>()

            val report = read(chip, mrzOf(document), store(), steps, caDone)

            assertEquals(Verdict.AUTHENTIC, report.verdict)
            assertEquals(CheckStatus.OK, report.check(CheckId.CHIP_AUTHENTICATION).status)
            assertEquals(Step.entries.toList(), steps)
            assertEquals("CA menée pendant READ_DATA", listOf(true), caDone)
            // EF.COM, EF.SOD et DG14 sous BAC ; tout le reste sous les clés de la CA.
            assertEquals(setOf(SessionKind.BAC), chip.sessionsServing(SimulatedChip.FID_COM))
            assertEquals(setOf(SessionKind.BAC), chip.sessionsServing(SimulatedChip.FID_SOD))
            assertServedUnder(chip, SessionKind.BAC, 14)
            assertServedUnder(chip, SessionKind.CHIP_AUTHENTICATION, 1, 2, 15)
            assertEquals(0, chip.reconnections)
            assertEquals(DOCUMENT_NUMBER, report.document.dg1.documentNumber)
        }

    @Test
    fun caReussieSousPace_DonneesDeLaCnieLuesSousLesClesDeLaCa() =
        runTest {
            val card = SimulatedDocuments.frenchIdCard()
            val chip = card.chip()

            val report = read(chip, checkNotNull(card.canKey), card.trustStore)

            assertEquals(Verdict.AUTHENTIC, report.verdict)
            assertServedUnder(chip, SessionKind.PACE, 14)
            assertServedUnder(chip, SessionKind.CHIP_AUTHENTICATION, 1, 2, 11, 12, 15)
        }

    @Test
    fun caRefuseeParLaPuce_EchecEtLectureSousLAncienCanal() =
        runTest {
            val document = document()
            val chip = SimulatedChip(document, refuseChipAuthentication = true)

            val report = read(chip, mrzOf(document), store())

            assertEquals(Verdict.FAILED, report.verdict)
            assertEquals(CheckStatus.FAILED, report.check(CheckId.CHIP_AUTHENTICATION).status)
            // L'AA et la PA ont tout de même été menées sur des données lues sous BAC.
            assertEquals(CheckStatus.OK, report.check(CheckId.DG_HASHES).status)
            assertEquals(CheckStatus.OK, report.check(CheckId.ACTIVE_AUTHENTICATION).status)
            assertNull(chip.caAlgorithm)
            assertEquals("canal BAC resté ouvert : pas de reconnexion", 0, chip.reconnections)
            assertServedUnder(chip, SessionKind.BAC, 1, 2, 14, 15)
            assertEquals(DOCUMENT_NUMBER, report.document.dg1.documentNumber)
        }

    @Test
    fun cloneSansLaCleCa_EchecPuisCanalRetabliEtDonneesLues() =
        runTest {
            // DG14 recopié, mais la puce ne détient pas la clé privée : elle bascule sur des clés
            // de session fausses, rejette la confirmation (MAC invalide) et clôt la messagerie.
            val document = document()
            val chip = SimulatedChip(document, caPrivateKey = pki.generateEc(TestPki.CURVE).private)
            val steps = mutableListOf<Step>()

            val report = read(chip, mrzOf(document), store(), steps)

            assertEquals(Verdict.FAILED, report.verdict)
            val ca = report.check(CheckId.CHIP_AUTHENTICATION)
            assertEquals(CheckStatus.FAILED, ca.status)
            assertEquals(Step.entries.toList(), steps)
            assertEquals("canal rétabli par reconnexion", 1, chip.reconnections)
            // Données lues sous un nouveau BAC, avec la même clé MRZ, jamais sous les clés de la CA.
            assertServedUnder(chip, SessionKind.BAC, 1, 2, 15)
            assertEquals(CheckStatus.OK, report.check(CheckId.DG_HASHES).status)
            assertEquals(CheckStatus.OK, report.check(CheckId.ACTIVE_AUTHENTICATION).status)
            assertEquals(DOCUMENT_NUMBER, report.document.dg1.documentNumber)
        }

    @Test
    fun cloneSansLaCleCaSousPace_CanalPaceRetabli() =
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
    fun cloneSansLaCleCaPuisAccesReserveAuRetablissement_EchecEtNonAccesReserve() =
        runTest {
            // Audit V24 : la puce rate la CA, puis répond 6982 à la sélection de l'applet quand le
            // canal est rétabli, pour obtenir le message « réservé aux autorités » au lieu d'un échec.
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
            // Aucune donnée d'identité lue : ni DG1, ni DG2, ni DG11.
            assertEquals("", report.document.dg1.documentNumber)
            assertNull(report.document.portrait)
            assertNull(report.document.dg11)
            assertTrue(chip.closed)
        }

    @Test
    fun dg14Absent_CaNonDisponibleEtDonneesSousBac() =
        runTest {
            val document = document { chipAuthentication = false }
            val chip = SimulatedChip(document)

            val report = read(chip, mrzOf(document), store())

            assertEquals(Verdict.AUTHENTIC, report.verdict)
            assertEquals(CheckStatus.NOT_AVAILABLE, report.check(CheckId.CHIP_AUTHENTICATION).status)
            assertEquals(0, chip.reconnections)
            assertTrue("DG14 jamais demandé", 0x010E !in chip.selectedFids)
            assertServedUnder(chip, SessionKind.BAC, 1, 2, 15)
        }

    companion object {
        private const val SEED = 20_260_928L
        private const val DOCUMENT_NUMBER = "L898902C3"
        private val DATE_OF_BIRTH: LocalDate = LocalDate.of(1974, 8, 12)
    }
}
