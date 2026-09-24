package io.github.mgdx.sceau.core.reading

import io.github.mgdx.sceau.core.AccessKey
import io.github.mgdx.sceau.core.SceauException
import io.github.mgdx.sceau.core.Step
import io.github.mgdx.sceau.core.model.Sex
import io.github.mgdx.sceau.core.readAndVerify
import io.github.mgdx.sceau.core.reading.sim.SimCrypto
import io.github.mgdx.sceau.core.reading.sim.SimulatedChip
import io.github.mgdx.sceau.core.reading.sim.resigned
import io.github.mgdx.sceau.core.reading.sim.withChipAaKey
import io.github.mgdx.sceau.core.reading.sim.withChipAuthenticationProtocol
import io.github.mgdx.sceau.core.report.ChannelProtocol
import io.github.mgdx.sceau.core.report.CheckDetail
import io.github.mgdx.sceau.core.report.CheckId
import io.github.mgdx.sceau.core.report.CheckStatus
import io.github.mgdx.sceau.core.report.Verdict
import io.github.mgdx.sceau.core.report.VerificationReport
import io.github.mgdx.sceau.core.testing.AaKeyType
import io.github.mgdx.sceau.core.testing.SodOptions
import io.github.mgdx.sceau.core.testing.TestDocument
import io.github.mgdx.sceau.core.testing.TestKeyType
import io.github.mgdx.sceau.core.testing.TestPki
import io.github.mgdx.sceau.core.trust.TrustStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDate

/**
 * Tests de bout en bout de `readAndVerify` contre une puce BAC simulée ([SimulatedChip]) :
 * canal sécurisé, lecture, Passive Authentication, Chip Authentication et Active
 * Authentication réellement exécutés, documents et PKI générés par la fabrique de test.
 */
class ReadAndVerifyEndToEndTest {
    private val rsaPki by lazy { TestPki(TestKeyType.RSA) }
    private val ecPki by lazy { TestPki(TestKeyType.EC) }

    /** Clé MRZ du DG1 factice (numéro L898902C3, né le 12/08/1974). */
    private fun mrzOf(document: TestDocument) = AccessKey.Mrz(DOCUMENT_NUMBER, DATE_OF_BIRTH, document.dateOfExpiry)

    private fun TestPki.store(): TrustStore = trustStore(oldCsca)

    private suspend fun read(
        chip: SimulatedChip,
        trustStore: TrustStore,
        key: AccessKey,
        steps: MutableList<Step> = mutableListOf(),
    ): VerificationReport {
        val report = readAndVerify(chip, key, trustStore) { steps += it }
        assertTrue("transport fermé", chip.closed)
        assertNoForbiddenDataGroup(chip)
        return report
    }

    private suspend inline fun <reified E : SceauException> readExpecting(
        chip: SimulatedChip,
        trustStore: TrustStore,
        key: AccessKey,
        steps: MutableList<Step> = mutableListOf(),
    ): E {
        try {
            readAndVerify(chip, key, trustStore) { steps += it }
        } catch (e: SceauException) {
            if (e !is E) throw AssertionError("${E::class.simpleName} attendue, reçu ${e.code}", e)
            assertTrue("transport fermé", chip.closed)
            return e
        }
        fail("${E::class.simpleName} attendue")
        throw IllegalStateException()
    }

    private fun assertNoForbiddenDataGroup(chip: SimulatedChip) {
        assertFalse("DG3 demandé", SimulatedChip.FID_DG3 in chip.selectedFids)
        assertFalse("DG4 demandé", SimulatedChip.FID_DG4 in chip.selectedFids)
    }

    private fun assertStatuses(
        report: VerificationReport,
        vararg expected: Pair<CheckId, CheckStatus>,
    ) {
        assertEquals(CheckId.entries.toList(), report.checks.map { it.id })
        for ((id, status) in expected) assertEquals(id.name, status, report.check(id).status)
    }

    private fun assertAllOk(report: VerificationReport) {
        for (check in report.checks) assertEquals("${check.id} : ${check.detail}", CheckStatus.OK, check.status)
    }

    private fun hashes(report: VerificationReport) = report.check(CheckId.DG_HASHES).detail as CheckDetail.DataGroupHashes

    // --- Nominal ---

    @Test
    fun nominalRsa_BacCa3desAaRsa_Authentique() =
        runTest {
            val document = rsaPki.document()
            val chip = SimulatedChip(document)
            val steps = mutableListOf<Step>()

            val report = read(chip, rsaPki.store(), mrzOf(document), steps)

            assertEquals(Verdict.AUTHENTIC, report.verdict)
            assertAllOk(report)
            assertEquals(Step.entries.toList(), steps)
            assertEquals(CheckDetail.SecureChannel(ChannelProtocol.BAC), report.check(CheckId.SECURE_CHANNEL).detail)
            assertEquals(listOf(1, 2, 14, 15), hashes(report).checked)
            assertEquals(emptyList<Int>(), hashes(report).mismatched)
            assertNotNull(report.chain?.cscaSubject)
            // La puce a bien mené BAC, la CA 3DES (MSE:Set KAT) et répondu à un challenge AA de 8 octets.
            assertTrue(chip.bacCompleted)
            assertEquals(SimCrypto.Algorithm.DESEDE, chip.caAlgorithm)
            assertEquals(1, chip.aaChallenges.size)
            assertEquals(8, chip.aaChallenges.single().size)

            val dg1 = report.document.dg1
            assertEquals("P", dg1.documentCode)
            assertEquals("UTO", dg1.issuingState)
            assertEquals(DOCUMENT_NUMBER, dg1.documentNumber)
            assertEquals("ERIKSSON", dg1.primaryIdentifier)
            assertEquals(listOf("ANNA", "MARIA"), dg1.secondaryIdentifiers)
            assertEquals("UTO", dg1.nationality)
            assertEquals(DATE_OF_BIRTH, dg1.dateOfBirth)
            assertEquals(Sex.FEMALE, dg1.sex)
            assertEquals(document.dateOfExpiry, dg1.dateOfExpiry)
        }

    @Test
    fun nominalEc_CaAesAaEcdsa_Authentique() =
        runTest {
            val document = ecPki.document { activeAuthentication = AaKeyType.EC }.withChipAuthenticationProtocol()
            val chip = SimulatedChip(document)
            val steps = mutableListOf<Step>()

            val report = read(chip, ecPki.store(), mrzOf(document), steps)

            assertEquals(Verdict.AUTHENTIC, report.verdict)
            assertAllOk(report)
            assertEquals(Step.entries.toList(), steps)
            assertEquals(SimCrypto.Algorithm.AES, chip.caAlgorithm)
            assertEquals(1, chip.aaChallenges.size)
            assertEquals(DOCUMENT_NUMBER, report.document.dg1.documentNumber)
        }

    @Test
    fun aaSeuleSansDg14_Authentique() =
        runTest {
            val document = rsaPki.document { chipAuthentication = false }
            val chip = SimulatedChip(document)

            val report = read(chip, rsaPki.store(), mrzOf(document))

            assertEquals(Verdict.AUTHENTIC, report.verdict)
            assertStatuses(
                report,
                CheckId.CHIP_AUTHENTICATION to CheckStatus.NOT_AVAILABLE,
                CheckId.ACTIVE_AUTHENTICATION to CheckStatus.OK,
            )
            assertEquals(null, chip.caAlgorithm)
        }

    // --- Verdicts dégradés ---

    @Test
    fun niDg14NiDg15Rsa_SignatureValidePuceNonVerifiee() = runTest { withoutChipKeys(rsaPki) }

    @Test
    fun niDg14NiDg15Ec_SignatureValidePuceNonVerifiee() = runTest { withoutChipKeys(ecPki) }

    private suspend fun withoutChipKeys(pki: TestPki) {
        val document =
            pki.document {
                chipAuthentication = false
                activeAuthentication = null
            }
        val chip = SimulatedChip(document)
        val steps = mutableListOf<Step>()

        val report = read(chip, pki.store(), mrzOf(document), steps)

        assertEquals(Verdict.SIGNATURE_VALID_CHIP_UNVERIFIED, report.verdict)
        assertStatuses(
            report,
            CheckId.SECURE_CHANNEL to CheckStatus.OK,
            CheckId.SOD_SIGNATURE to CheckStatus.OK,
            CheckId.CERTIFICATE_CHAIN to CheckStatus.OK,
            CheckId.DS_VALIDITY to CheckStatus.OK,
            CheckId.DG_HASHES to CheckStatus.OK,
            CheckId.CHIP_AUTHENTICATION to CheckStatus.NOT_AVAILABLE,
            CheckId.ACTIVE_AUTHENTICATION to CheckStatus.NOT_AVAILABLE,
        )
        assertEquals(listOf(1, 2), hashes(report).checked)
        assertEquals(Step.entries.toList(), steps)
        assertTrue(chip.aaChallenges.isEmpty())
        // Ni DG14 ni DG15 demandés : ils ne sont annoncés ni dans EF.COM ni dans le SOD.
        assertFalse(0x010E in chip.selectedFids)
        assertFalse(0x010F in chip.selectedFids)
    }

    @Test
    fun dg2ModifieRsa_EchecSurEmpreinte() = runTest { modifiedDg2(rsaPki) }

    @Test
    fun dg2ModifieEc_EchecSurEmpreinte() = runTest { modifiedDg2(ecPki) }

    private suspend fun modifiedDg2(pki: TestPki) {
        val document = pki.document().withModifiedDataGroup(2)
        val report = read(SimulatedChip(document), pki.store(), mrzOf(document))

        assertEquals(Verdict.FAILED, report.verdict)
        assertStatuses(
            report,
            CheckId.SOD_SIGNATURE to CheckStatus.OK,
            CheckId.CERTIFICATE_CHAIN to CheckStatus.OK,
            CheckId.DG_HASHES to CheckStatus.FAILED,
            CheckId.CHIP_AUTHENTICATION to CheckStatus.OK,
            CheckId.ACTIVE_AUTHENTICATION to CheckStatus.OK,
        )
        assertEquals(listOf(2), hashes(report).mismatched)
    }

    @Test
    fun dsNonRattacheAuMagasinRsa_EmetteurInconnu() = runTest { unknownIssuer(rsaPki, TestKeyType.RSA) }

    @Test
    fun dsNonRattacheAuMagasinEc_EmetteurInconnu() = runTest { unknownIssuer(ecPki, TestKeyType.EC) }

    private suspend fun unknownIssuer(
        pki: TestPki,
        keyType: TestKeyType,
    ) {
        val document = pki.document()
        val otherCountry = TestPki(keyType, name = "Autre Pays", country = "UT")
        val report = read(SimulatedChip(document), otherCountry.store(), mrzOf(document))

        assertEquals(Verdict.UNKNOWN_ISSUER, report.verdict)
        assertStatuses(
            report,
            CheckId.CERTIFICATE_CHAIN to CheckStatus.NOT_AVAILABLE,
            CheckId.DG_HASHES to CheckStatus.OK,
            CheckId.CHIP_AUTHENTICATION to CheckStatus.OK,
            CheckId.ACTIVE_AUTHENTICATION to CheckStatus.OK,
        )
        assertEquals(null, report.chain?.cscaSubject)
    }

    @Test
    fun aaSigneeAvecMauvaiseCleRsa_Echec() =
        runTest {
            val document = rsaPki.document { chipAuthentication = false }
            wrongAaKey(rsaPki, document.withChipAaKey(rsaPki.generateRsa(TestDocument.AA_RSA_BITS)))
        }

    @Test
    fun aaSigneeAvecMauvaiseCleEc_Echec() =
        runTest {
            val document =
                ecPki.document {
                    chipAuthentication = false
                    activeAuthentication = AaKeyType.EC
                }
            wrongAaKey(ecPki, document.withChipAaKey(ecPki.generateEc(TestPki.CURVE)))
        }

    private suspend fun wrongAaKey(
        pki: TestPki,
        document: TestDocument,
    ) {
        val chip = SimulatedChip(document)
        val report = read(chip, pki.store(), mrzOf(document))

        assertEquals(Verdict.FAILED, report.verdict)
        assertStatuses(
            report,
            CheckId.DG_HASHES to CheckStatus.OK,
            CheckId.CHIP_AUTHENTICATION to CheckStatus.NOT_AVAILABLE,
            CheckId.ACTIVE_AUTHENTICATION to CheckStatus.FAILED,
        )
        assertEquals("la puce a bien reçu un challenge", 1, chip.aaChallenges.size)
    }

    @Test
    fun caAvecMauvaiseCleDePuce_Echec() =
        runTest {
            // Puce clonée : DG14 recopié, mais la clé privée CA n'est pas celle de DG14.
            val document = rsaPki.document { activeAuthentication = null }
            val chip = SimulatedChip(document, caPrivateKey = rsaPki.generateEc(TestPki.CURVE).private)

            val report = read(chip, rsaPki.store(), mrzOf(document))

            assertEquals(Verdict.FAILED, report.verdict)
            assertStatuses(
                report,
                CheckId.DG_HASHES to CheckStatus.OK,
                CheckId.CHIP_AUTHENTICATION to CheckStatus.FAILED,
                CheckId.ACTIVE_AUTHENTICATION to CheckStatus.NOT_AVAILABLE,
            )
        }

    // --- Erreurs de lecture ---

    @Test
    fun mrzFausse_AccessDenied() =
        runTest {
            val document = rsaPki.document()
            val chip = SimulatedChip(document)
            val steps = mutableListOf<Step>()
            val wrongKey = AccessKey.Mrz(DOCUMENT_NUMBER, DATE_OF_BIRTH.plusDays(1), document.dateOfExpiry)

            val error = readExpecting<SceauException.AccessDenied>(chip, rsaPki.store(), wrongKey, steps)

            assertEquals("ACCESS_DENIED", error.code)
            assertEquals(listOf(Step.CONNECT, Step.SECURE_CHANNEL), steps)
            assertFalse(chip.bacCompleted)
            assertTrue("aucun fichier de l'applet lu", chip.selectedFids.all { it == 0x011C })
        }

    @Test
    fun documentRetirePendantLecture_ConnectionLost() =
        runTest {
            val document = rsaPki.document()
            val chip = SimulatedChip(document)
            val steps = mutableListOf<Step>()
            val key = mrzOf(document)

            val error =
                try {
                    readAndVerify(chip, key, rsaPki.store()) { step ->
                        steps += step
                        // Retrait quelques APDU après le début de READ_DATA (en pleine lecture d'EF.SOD).
                        if (step == Step.READ_DATA) chip.removeAfterApdus = chip.apduCount + APDUS_BEFORE_REMOVAL
                    }
                    fail("ConnectionLost attendue")
                    throw IllegalStateException()
                } catch (e: SceauException.ConnectionLost) {
                    e
                }

            assertEquals("CONNECTION_LOST", error.code)
            assertEquals(listOf(Step.CONNECT, Step.SECURE_CHANNEL, Step.READ_DATA), steps)
            assertTrue(chip.closed)
            assertTrue(chip.aaChallenges.isEmpty())
        }

    // --- Règles de lecture et d'effacement ---

    @Test
    fun dg3Dg4AnnoncesDansComEtSod_JamaisDemandes() =
        runTest {
            val base = rsaPki.document()
            val fakeHash = ByteArray(32) { 0x33 }
            val document = base.resigned(options = SodOptions(extraHashes = mapOf(3 to fakeHash, 4 to fakeHash)))
            val chip = SimulatedChip(document, comDataGroups = listOf(1, 2, 3, 4, 14, 15))

            val report = read(chip, rsaPki.store(), mrzOf(document))

            assertNoForbiddenDataGroup(chip)
            assertEquals(Verdict.AUTHENTIC, report.verdict)
            assertEquals(listOf(1, 2, 14, 15), hashes(report).checked)
            assertEquals(setOf(1, 2, 14, 15), report.document.rawDataGroups.keys)
        }

    @Test
    fun wipe_RemetAZeroLesOctetsBrutsDesDg() =
        runTest {
            val document = rsaPki.document()
            val report = read(SimulatedChip(document), rsaPki.store(), mrzOf(document))
            val raw = report.document.rawDataGroups
            assertEquals(setOf(1, 2, 14, 15), raw.keys)
            assertTrue(raw.values.all { bytes -> bytes.any { it != 0.toByte() } })

            report.wipe()

            for ((number, bytes) in raw) assertTrue("DG$number remis à zéro", bytes.all { it == 0.toByte() })
            report.document.portrait?.let { portrait -> assertTrue("photo remise à zéro", portrait.bytes.all { it == 0.toByte() }) }
            // Les octets du document simulé ne sont pas touchés : Sceau a bien travaillé sur ses copies.
            assertTrue(document.dataGroups.getValue(1).any { it != 0.toByte() })
        }

    companion object {
        private const val DOCUMENT_NUMBER = "L898902C3"
        private val DATE_OF_BIRTH: LocalDate = LocalDate.of(1974, 8, 12)
        private const val APDUS_BEFORE_REMOVAL = 4
    }
}
