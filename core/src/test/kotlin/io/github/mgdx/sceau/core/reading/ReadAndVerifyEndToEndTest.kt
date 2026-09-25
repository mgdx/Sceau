package io.github.mgdx.sceau.core.reading

import io.github.mgdx.sceau.core.AccessKey
import io.github.mgdx.sceau.core.SceauException
import io.github.mgdx.sceau.core.Step
import io.github.mgdx.sceau.core.model.Sex
import io.github.mgdx.sceau.core.readAndVerify
import io.github.mgdx.sceau.core.report.ChannelProtocol
import io.github.mgdx.sceau.core.report.CheckDetail
import io.github.mgdx.sceau.core.report.CheckId
import io.github.mgdx.sceau.core.report.CheckStatus
import io.github.mgdx.sceau.core.report.Verdict
import io.github.mgdx.sceau.core.report.VerificationReport
import io.github.mgdx.sceau.core.trust.TrustSource
import io.github.mgdx.sceau.core.trust.TrustStore
import io.github.mgdx.sceau.testchip.AaKeyType
import io.github.mgdx.sceau.testchip.SimCrypto
import io.github.mgdx.sceau.testchip.SimulatedChip
import io.github.mgdx.sceau.testchip.SodOptions
import io.github.mgdx.sceau.testchip.TestDocument
import io.github.mgdx.sceau.testchip.TestKeyType
import io.github.mgdx.sceau.testchip.TestPki
import io.github.mgdx.sceau.testchip.TestTrustStore
import io.github.mgdx.sceau.testchip.resigned
import io.github.mgdx.sceau.testchip.withChipAaKey
import io.github.mgdx.sceau.testchip.withChipAuthenticationProtocol
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
    // Graines fixes : le compteur global de TestPki dépend de l'ordre des tests, et une réponse AA
    // signée par une mauvaise clé RSA peut, pour environ une graine sur mille, porter un trailer
    // ISO 9796-2 inconnu (UNSUPPORTED_ALGORITHM au lieu de FAILED).
    private val rsaPki by lazy { TestPki(TestKeyType.RSA, seed = RSA_SEED) }
    private val ecPki by lazy { TestPki(TestKeyType.EC, seed = EC_SEED) }

    /** Clé MRZ du DG1 factice (numéro L898902C3, né le 12/08/1974). */
    private fun mrzOf(document: TestDocument) = AccessKey.Mrz(DOCUMENT_NUMBER, DATE_OF_BIRTH, document.dateOfExpiry)

    private fun TestPki.store(): TrustStore = trustStore(oldCsca)

    /** Document de la PKI de test (C=FR) : État émetteur FRA, cohérent avec le CSCA (audit V3). */
    private fun TestPki.doc(configure: TestDocument.Builder.() -> Unit = {}): TestDocument =
        document {
            issuingState = "FRA"
            configure()
        }

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
            val document = rsaPki.doc()
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
            assertEquals("FRA", dg1.issuingState)
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
            val document = ecPki.doc { activeAuthentication = AaKeyType.EC }.withChipAuthenticationProtocol()
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
            val document = rsaPki.doc { chipAuthentication = false }
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
            pki.doc {
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

    // --- Clone qui retient des DG signés (audit V1) ---

    @Test
    fun cloneRetenantDg14EtDg15_EchecEtNonPuceNonVerifiee() =
        runTest {
            // Copie d'un vrai document : SOD, DG1, DG2 rejoués ; DG14 et DG15 refusés (6A82) et
            // retirés d'EF.COM, qui n'est pas signé. Le SOD prouve pourtant qu'ils existent.
            val document = rsaPki.doc()
            val chip = SimulatedChip(document, comDataGroups = listOf(1, 2), withheldDataGroups = listOf(14, 15))

            val report = read(chip, rsaPki.store(), mrzOf(document))

            assertEquals(Verdict.FAILED, report.verdict)
            assertStatuses(
                report,
                CheckId.SOD_SIGNATURE to CheckStatus.OK,
                CheckId.CERTIFICATE_CHAIN to CheckStatus.OK,
                CheckId.DG_HASHES to CheckStatus.FAILED,
                CheckId.CHIP_AUTHENTICATION to CheckStatus.FAILED,
                CheckId.ACTIVE_AUTHENTICATION to CheckStatus.FAILED,
            )
            assertEquals(listOf(1, 2), hashes(report).checked)
            assertEquals(emptyList<Int>(), hashes(report).mismatched)
            assertEquals(listOf(14, 15), hashes(report).missing)
            // Les DG annoncés par le SOD ont bien été demandés, malgré EF.COM.
            assertTrue(0x010E in chip.selectedFids)
            assertTrue(0x010F in chip.selectedFids)
            assertTrue(chip.aaChallenges.isEmpty())
        }

    @Test
    fun cloneRetenantDg2_Echec() =
        runTest {
            val document = ecPki.doc()
            val chip = SimulatedChip(document, withheldDataGroups = listOf(2))

            val report = read(chip, ecPki.store(), mrzOf(document))

            assertEquals(Verdict.FAILED, report.verdict)
            assertStatuses(
                report,
                CheckId.DG_HASHES to CheckStatus.FAILED,
                CheckId.CHIP_AUTHENTICATION to CheckStatus.OK,
                CheckId.ACTIVE_AUTHENTICATION to CheckStatus.OK,
            )
            assertEquals(listOf(2), hashes(report).missing)
            assertEquals(null, report.document.portrait)
        }

    // --- Pays incohérents (audit V3) ---

    @Test
    fun documentFraSigneParLeCscaDUnAutrePays_Echec() =
        runTest {
            // Détenteur de la clé d'un CSCA quelconque du magasin (ici « DE ») : il signe un DS et
            // un SOD pour un document « FRA », avec ses propres clés CA et AA.
            val foreign = TestPki(TestKeyType.RSA, name = "Autre Etat", country = "DE", seed = RSA_SEED + 1)
            val document = foreign.doc { issuingState = "FRA" }
            val store = TestTrustStore.of(listOf(rsaPki.oldCsca.certificate, foreign.oldCsca.certificate), TrustSource.ANTS)

            val report = read(SimulatedChip(document), store, mrzOf(document))

            assertEquals(Verdict.FAILED, report.verdict)
            assertStatuses(
                report,
                CheckId.SOD_SIGNATURE to CheckStatus.OK,
                CheckId.CERTIFICATE_CHAIN to CheckStatus.FAILED,
                CheckId.DG_HASHES to CheckStatus.OK,
                CheckId.CHIP_AUTHENTICATION to CheckStatus.OK,
                CheckId.ACTIVE_AUTHENTICATION to CheckStatus.OK,
            )
            assertEquals(CheckDetail.CountryMismatch("DE", "DE", "FRA"), report.check(CheckId.CERTIFICATE_CHAIN).detail)
            assertEquals("DE", report.chain?.cscaCountry)
        }

    @Test
    fun documentDeuSigneParSonCsca_Authentique() =
        runTest {
            val german = TestPki(TestKeyType.RSA, name = "Autre Etat", country = "DE", seed = RSA_SEED + 1)
            val document = german.doc { issuingState = "D" }

            val report = read(SimulatedChip(document), german.store(), mrzOf(document))

            assertEquals(Verdict.AUTHENTIC, report.verdict)
            assertAllOk(report)
        }

    @Test
    fun dg2ModifieRsa_EchecSurEmpreinte() = runTest { modifiedDg2(rsaPki) }

    @Test
    fun dg2ModifieEc_EchecSurEmpreinte() = runTest { modifiedDg2(ecPki) }

    private suspend fun modifiedDg2(pki: TestPki) {
        val document = pki.doc().withModifiedDataGroup(2)
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
        val document = pki.doc()
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
            val document = rsaPki.doc { chipAuthentication = false }
            wrongAaKey(rsaPki, document.withChipAaKey(rsaPki.generateRsa(TestDocument.AA_RSA_BITS)))
        }

    @Test
    fun aaSigneeAvecMauvaiseCleEc_Echec() =
        runTest {
            val document =
                ecPki.doc {
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
            val document = rsaPki.doc { activeAuthentication = null }
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
            val document = rsaPki.doc()
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
            val document = rsaPki.doc()
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
            val base = rsaPki.doc()
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
            val document = rsaPki.doc()
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
        private const val RSA_SEED = 20_260_901L
        private const val EC_SEED = 20_260_902L
    }
}
