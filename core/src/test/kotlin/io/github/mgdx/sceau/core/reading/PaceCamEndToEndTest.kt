package io.github.mgdx.sceau.core.reading

import io.github.mgdx.sceau.core.AccessKey
import io.github.mgdx.sceau.core.Step
import io.github.mgdx.sceau.core.readAndVerify
import io.github.mgdx.sceau.core.report.CheckDetail
import io.github.mgdx.sceau.core.report.CheckId
import io.github.mgdx.sceau.core.report.CheckStatus
import io.github.mgdx.sceau.core.report.ChipAuthenticationMethod
import io.github.mgdx.sceau.core.report.Verdict
import io.github.mgdx.sceau.core.report.VerificationReport
import io.github.mgdx.sceau.core.trust.TrustAnchor
import io.github.mgdx.sceau.core.trust.TrustSource
import io.github.mgdx.sceau.core.trust.TrustStore
import io.github.mgdx.sceau.testchip.PaceSettings
import io.github.mgdx.sceau.testchip.SessionKind
import io.github.mgdx.sceau.testchip.SimulatedChip
import io.github.mgdx.sceau.testchip.SimulatedDocuments
import io.github.mgdx.sceau.testchip.SimulatedIdentityDocument
import io.github.mgdx.sceau.testchip.SodOptions
import io.github.mgdx.sceau.testchip.TestCardSecurity
import io.github.mgdx.sceau.testchip.TestKeyType
import io.github.mgdx.sceau.testchip.TestPki
import io.github.mgdx.sceau.testchip.TestTrustStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PACE-CAM (ICAO 9303-11 §4.4, décision D21) de bout en bout : la CNIe simulée annonce
 * id-PACE-ECDH-CAM-AES-CBC-CMAC-128, prouve détenir la clé de Chip Authentication pendant PACE
 * et sert EF.CardSecurity, signé par le DS factice, au MF sous messagerie sécurisée.
 */
class PaceCamEndToEndTest {
    private val card: SimulatedIdentityDocument by lazy { SimulatedDocuments.frenchIdCard() }

    private val camSettings by lazy {
        PaceSettings(SimulatedDocuments.SPECIMEN_CAN, protocolOid = PaceSettings.ID_PACE_ECDH_CAM_AES_CBC_CMAC_128)
    }

    private fun chip(
        cardSecurity: ByteArray? = TestCardSecurity.of(card.document, camSettings),
        tamperCam: Boolean = false,
    ) = SimulatedChip(card.document, camSettings, cardSecurity = cardSecurity, tamperChipAuthenticationMapping = tamperCam)

    private suspend fun read(
        chip: SimulatedChip,
        trustStore: TrustStore = card.trustStore,
        key: AccessKey = checkNotNull(card.canKey),
        steps: MutableList<Step> = mutableListOf(),
    ): VerificationReport {
        val report = readAndVerify(chip, key, trustStore) { steps += it }
        assertTrue("transport fermé", chip.closed)
        return report
    }

    private fun VerificationReport.ca() = check(CheckId.CHIP_AUTHENTICATION)

    @Test
    fun camReussi_AuthentiqueSansCaViaDg14() =
        runTest {
            val chip = chip()
            val steps = mutableListOf<Step>()

            val report = read(chip, steps = steps)

            assertEquals(Verdict.AUTHENTIC, report.verdict)
            for (check in report.checks) assertEquals("${check.id} : ${check.detail}", CheckStatus.OK, check.status)
            assertEquals(CheckDetail.ChipAuthentication(ChipAuthenticationMethod.PACE_CAM), report.ca().detail)
            assertEquals(Step.entries.toList(), steps)
            // Pas de CA via DG14 : la puce n'a reçu aucun MSE de CA, tout est lu sous PACE.
            assertNull(chip.caAlgorithm)
            assertEquals(0, chip.reconnections)
            assertTrue("EF.CardSecurity lu", chip.fileReads.any { it.fid == SimulatedChip.FID_CARD_SECURITY })
            // Audit V17 : EF.CardSecurity remonte au même CSCA que le SOD, et sa chaîne est dans le rapport.
            assertEquals(report.chain?.cscaSha256, checkNotNull(report.cardSecurityChain).cscaSha256)
            for (fid in listOf(0x0101, 0x0102, 0x010B, 0x010C, 0x010E, 0x010F)) {
                assertEquals("FID $fid", setOf(SessionKind.PACE), chip.sessionsServing(fid))
            }
        }

    @Test
    fun camReussiAvecLaCleMrz_Authentique() =
        runTest {
            val report = read(chip(), key = card.mrzKey)

            assertEquals(Verdict.AUTHENTIC, report.verdict)
            assertEquals(CheckStatus.OK, report.ca().status)
        }

    @Test
    fun cardSecurityASignatureInvalide_Echec() =
        runTest {
            val tampered = TestCardSecurity.of(card.document, camSettings, SodOptions(tamperSignature = true))

            val report = read(chip(cardSecurity = tampered))

            assertEquals(Verdict.FAILED, report.verdict)
            assertEquals(CheckStatus.FAILED, report.ca().status)
            assertEquals(CheckDetail.Error("VERIFY_CHIP-CAM-CARD_SECURITY_SIGNATURE"), report.ca().detail)
            // Le reste de la vérification est intact : seule la CA échoue.
            assertEquals(CheckStatus.OK, report.check(CheckId.SOD_SIGNATURE).status)
            assertEquals(CheckStatus.OK, report.check(CheckId.DG_HASHES).status)
        }

    @Test
    fun donneesDeChipAuthenticationFausses_Echec() =
        runTest {
            val report = read(chip(tamperCam = true))

            assertEquals(Verdict.FAILED, report.verdict)
            assertEquals(CheckStatus.FAILED, report.ca().status)
            assertEquals(CheckDetail.Error("VERIFY_CHIP-CAM-KEY_MISMATCH"), report.ca().detail)
        }

    @Test
    fun cardSecurityAbsent_Echec() =
        runTest {
            val report = read(chip(cardSecurity = null))

            assertEquals(Verdict.FAILED, report.verdict)
            assertEquals(CheckDetail.Error("VERIFY_CHIP-CAM-CARD_SECURITY_MISSING"), report.ca().detail)
        }

    @Test
    fun cardSecuritySigneParUnDsInconnuAlorsQueLeSodEstReconnu_Echec() =
        runTest {
            // Clé CA de la puce et CA.IC cohérents, mais EF.CardSecurity signé hors du magasin :
            // rien ne rattache la clé de la puce au pays émetteur.
            val stranger = TestPki(TestKeyType.EC, name = "Inconnu", country = "FR", seed = STRANGER_SEED)
            val forged = TestCardSecurity.of(card.document, camSettings, signer = stranger.ds)

            val report = read(chip(cardSecurity = forged))

            assertEquals(Verdict.FAILED, report.verdict)
            assertEquals(CheckStatus.OK, report.check(CheckId.CERTIFICATE_CHAIN).status)
            assertEquals(CheckDetail.Error("VERIFY_CHIP-CAM-CARD_SECURITY_UNKNOWN_ISSUER"), report.ca().detail)
        }

    @Test
    fun cardSecurityAncreSurUnAutreCscaImporteDuMemePays_Echec() =
        runTest {
            // Audit V17 : SOD et DG authentiques (CSCA embarqué), EF.CardSecurity forgé avec la clé
            // de la puce, signé par un DS émis sous un CSCA français que l'utilisateur a importé.
            val attacker = TestPki(TestKeyType.EC, name = "Piege", country = "FR", seed = STRANGER_SEED)
            val forged = TestCardSecurity.of(card.document, camSettings, signer = attacker.ds)
            val store =
                TestTrustStore(
                    card.trustStore.anchors + TrustAnchor(attacker.oldCsca.certificate, TrustSource.IMPORTED_CERTIFICATE),
                )

            val report = read(chip(cardSecurity = forged), trustStore = store)

            assertEquals(Verdict.FAILED, report.verdict)
            assertEquals(CheckStatus.OK, report.check(CheckId.CERTIFICATE_CHAIN).status)
            assertEquals(CheckStatus.FAILED, report.ca().status)
            assertEquals(CheckDetail.Error("VERIFY_CHIP-CAM-CARD_SECURITY_ANCHOR"), report.ca().detail)
            assertEquals(TrustSource.IMPORTED_CERTIFICATE, report.cardSecurityChain?.cscaSource)
        }

    @Test
    fun cardSecuritySigneParUnAutreDsDuMemeCsca_Authentique() =
        runTest {
            val otherDs = card.pki.issueDs(dsKeyType = TestKeyType.EC, commonName = "DS-TEST-FRANCE-CARD-SECURITY")
            val cardSecurity = TestCardSecurity.of(card.document, camSettings, signer = otherDs)

            val report = read(chip(cardSecurity = cardSecurity))

            assertEquals(Verdict.AUTHENTIC, report.verdict)
            assertEquals(report.chain?.cscaSha256, report.cardSecurityChain?.cscaSha256)
        }

    @Test
    fun emetteurInconnuDuMagasin_EmetteurInconnu() =
        runTest {
            val otherStore =
                TestPki(
                    TestKeyType.EC,
                    name = "Autre Pays",
                    country = "UT",
                    seed = STRANGER_SEED,
                ).let { it.trustStore(it.oldCsca) }

            val report = read(chip(), trustStore = otherStore)

            assertEquals(Verdict.UNKNOWN_ISSUER, report.verdict)
            assertEquals(CheckStatus.NOT_AVAILABLE, report.check(CheckId.CERTIFICATE_CHAIN).status)
            assertEquals(CheckStatus.NOT_AVAILABLE, report.ca().status)
        }

    companion object {
        private const val STRANGER_SEED = 20_260_929L
    }
}
