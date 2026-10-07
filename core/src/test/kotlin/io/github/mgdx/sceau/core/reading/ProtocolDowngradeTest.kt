package io.github.mgdx.sceau.core.reading

import io.github.mgdx.sceau.core.AccessKey
import io.github.mgdx.sceau.core.readAndVerify
import io.github.mgdx.sceau.core.report.CheckDetail
import io.github.mgdx.sceau.core.report.CheckId
import io.github.mgdx.sceau.core.report.CheckStatus
import io.github.mgdx.sceau.core.report.ChipAuthenticationMethod
import io.github.mgdx.sceau.core.report.Verdict
import io.github.mgdx.sceau.core.report.VerificationReport
import io.github.mgdx.sceau.testchip.AaKeyType
import io.github.mgdx.sceau.testchip.PaceSettings
import io.github.mgdx.sceau.testchip.SimulatedChip
import io.github.mgdx.sceau.testchip.SodOptions
import io.github.mgdx.sceau.testchip.TestCardSecurity
import io.github.mgdx.sceau.testchip.TestDocument
import io.github.mgdx.sceau.testchip.TestKeyType
import io.github.mgdx.sceau.testchip.TestPki
import kotlinx.coroutines.test.runTest
import org.jmrtd.lds.ChipAuthenticationInfo
import org.jmrtd.lds.ChipAuthenticationPublicKeyInfo
import org.jmrtd.lds.PACEInfo
import org.jmrtd.lds.SecurityInfo
import org.jmrtd.lds.icao.DG14File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.time.LocalDate

/**
 * Audit V20 (décision D40) : le protocole mené est comparé à ce qu'annonce DG14, signé. Un clone
 * qui retire PACE-CAM, ou PACE, de son EF.CardAccess (non signé) ne doit pas obtenir « Signature
 * valide, puce non vérifiée » quand rien d'autre n'authentifie la puce.
 */
class ProtocolDowngradeTest {
    private val pki by lazy { TestPki(TestKeyType.RSA, seed = SEED) }

    /** Document dont DG14, signé, ne contient que le `PACEInfo` [announcedPace] : ni clé de CA, ni DG15 par défaut. */
    private fun document(
        announcedPace: String,
        activeAuthentication: AaKeyType? = null,
    ): TestDocument =
        pki.document {
            issuingState = "FRA"
            chipAuthentication = false
            this.activeAuthentication = activeAuthentication
            extraDataGroups = mapOf(DG14 to dg14(announcedPace))
        }

    private fun dg14(paceOid: String): ByteArray =
        DG14File(
            listOf<SecurityInfo>(PACEInfo(paceOid, PACE_VERSION, BigInteger.valueOf(PaceSettings.PARAM_ID_BRAINPOOL_P256_R1.toLong()))),
        ).encoded

    private suspend fun read(
        chip: SimulatedChip,
        key: AccessKey,
    ): VerificationReport = readAndVerify(chip, key, pki.trustStore(pki.oldCsca)) {}

    private fun mrzOf(document: TestDocument) = AccessKey.Mrz(DOCUMENT_NUMBER, DATE_OF_BIRTH, document.dateOfExpiry)

    @Test
    fun dg14AnnoncePaceCamEtCardAccessPaceGm_Echec() =
        runTest {
            val document = document(PaceSettings.ID_PACE_ECDH_CAM_AES_CBC_CMAC_128)
            val chip = SimulatedChip(document, PaceSettings(CAN, PaceSettings.ID_PACE_ECDH_GM_AES_CBC_CMAC_128))

            val report = read(chip, AccessKey.Can(CAN))

            assertEquals(Verdict.FAILED, report.verdict)
            val ca = report.check(CheckId.CHIP_AUTHENTICATION)
            assertEquals(CheckStatus.FAILED, ca.status)
            assertEquals(CheckDetail.Error("VERIFY_CHIP-DOWNGRADE-CAM"), ca.detail)
            assertEquals(CheckStatus.OK, report.check(CheckId.SECURE_CHANNEL).status)
            assertEquals(CheckStatus.OK, report.check(CheckId.DG_HASHES).status)
        }

    @Test
    fun dg14AnnoncePaceEtLectureEnBac_Echec() =
        runTest {
            val document = document(PaceSettings.ID_PACE_ECDH_GM_AES_CBC_CMAC_128)
            val chip = SimulatedChip(document, pace = null)

            val report = read(chip, mrzOf(document))

            assertEquals(Verdict.FAILED, report.verdict)
            val channel = report.check(CheckId.SECURE_CHANNEL)
            assertEquals(CheckStatus.FAILED, channel.status)
            assertEquals(CheckDetail.Error("VERIFY_CHIP-DOWNGRADE-BAC"), channel.detail)
        }

    @Test
    fun dg14AnnoncePaceGmEtCanalPaceGm_SignatureValidePuceNonVerifiee() =
        runTest {
            val document = document(PaceSettings.ID_PACE_ECDH_GM_AES_CBC_CMAC_128)
            val chip = SimulatedChip(document, PaceSettings(CAN, PaceSettings.ID_PACE_ECDH_GM_AES_CBC_CMAC_128))

            val report = read(chip, AccessKey.Can(CAN))

            assertEquals(Verdict.SIGNATURE_VALID_CHIP_UNVERIFIED, report.verdict)
        }

    @Test
    fun dg14AnnoncePaceEtLectureEnBacMaisAaReussie_Authentique() =
        runTest {
            // Repli PACE → BAC (D13) sur une puce qui répond à l'AA : la puce est authentifiée,
            // le protocole mené ne change rien à la preuve.
            val document = document(PaceSettings.ID_PACE_ECDH_GM_AES_CBC_CMAC_128, activeAuthentication = AaKeyType.RSA)
            val chip = SimulatedChip(document, pace = null)

            val report = read(chip, mrzOf(document))

            assertEquals(Verdict.AUTHENTIC, report.verdict)
            assertEquals(CheckStatus.OK, report.check(CheckId.SECURE_CHANNEL).status)
        }

    // --- Audit V32 (décision D43) : document sans DG14, clé de CA dans EF.CardSecurity seulement ---

    /** Document sans DG14 ni DG15 : rien ne permet d'authentifier la puce, sauf EF.CardSecurity. */
    private val bareDocument by lazy {
        pki.document {
            issuingState = "FRA"
            chipAuthentication = false
            activeAuthentication = null
        }
    }

    private val chipKey by lazy { pki.generateEc(TestPki.CURVE) }

    private val gmSettings = PaceSettings(CAN, PaceSettings.ID_PACE_ECDH_GM_AES_CBC_CMAC_128)
    private val camSettings = PaceSettings(CAN, PaceSettings.ID_PACE_ECDH_CAM_AES_CBC_CMAC_128)

    /** EF.CardSecurity signé par le DS du document : `PACEInfo` [paceOid] et, si [withKey], la clé de CA. */
    private fun cardSecurity(
        paceOid: String,
        withKey: Boolean = true,
        options: SodOptions = SodOptions(),
    ): ByteArray {
        val parameterId = BigInteger.valueOf(PaceSettings.PARAM_ID_BRAINPOOL_P256_R1.toLong())
        val infos = mutableListOf<SecurityInfo>(PACEInfo(paceOid, PACE_VERSION, parameterId))
        if (withKey) {
            infos += ChipAuthenticationInfo(SecurityInfo.ID_CA_ECDH_AES_CBC_CMAC_128, CA_VERSION, BigInteger.ONE)
            infos += ChipAuthenticationPublicKeyInfo(chipKey.public, BigInteger.ONE)
        }
        return TestCardSecurity.build(bareDocument.ds, infos, options)
    }

    private fun assertDowngraded(
        report: VerificationReport,
        code: String,
    ) {
        assertEquals(Verdict.FAILED, report.verdict)
        val ca = report.check(CheckId.CHIP_AUTHENTICATION)
        assertEquals(CheckStatus.FAILED, ca.status)
        assertEquals(CheckDetail.Error(code), ca.detail)
        assertEquals(CheckStatus.OK, report.check(CheckId.SECURE_CHANNEL).status)
        assertEquals(CheckStatus.OK, report.check(CheckId.DG_HASHES).status)
    }

    @Test
    fun sansDg14_CardSecurityAnnonceCamEtCanalPaceGm_Echec() =
        runTest {
            val chip = SimulatedChip(bareDocument, gmSettings, cardSecurity = cardSecurity(PaceSettings.ID_PACE_ECDH_CAM_AES_CBC_CMAC_128))

            val report = read(chip, AccessKey.Can(CAN))

            assertDowngraded(report, "VERIFY_CHIP-DOWNGRADE-CAM")
            assertTrue("EF.CardSecurity lu", chip.fileReads.any { it.fid == SimulatedChip.FID_CARD_SECURITY })
        }

    @Test
    fun sansDg14_CardSecurityAnnonceUneCleCaEtCanalPaceGm_Echec() =
        runTest {
            val chip = SimulatedChip(bareDocument, gmSettings, cardSecurity = cardSecurity(PaceSettings.ID_PACE_ECDH_GM_AES_CBC_CMAC_128))

            assertDowngraded(read(chip, AccessKey.Can(CAN)), "VERIFY_CHIP-DOWNGRADE-CA")
        }

    @Test
    fun sansDg14_CardSecurityASignatureInvalide_Echec() =
        runTest {
            val tampered =
                cardSecurity(PaceSettings.ID_PACE_ECDH_GM_AES_CBC_CMAC_128, withKey = false, options = SodOptions(tamperSignature = true))
            val chip = SimulatedChip(bareDocument, gmSettings, cardSecurity = tampered)

            assertDowngraded(read(chip, AccessKey.Can(CAN)), "VERIFY_CHIP-DOWNGRADE-CARD_SECURITY")
        }

    @Test
    fun sansDg14_CardSecuritySansCleNiCam_SignatureValidePuceNonVerifiee() =
        runTest {
            val plain = cardSecurity(PaceSettings.ID_PACE_ECDH_GM_AES_CBC_CMAC_128, withKey = false)
            val chip = SimulatedChip(bareDocument, gmSettings, cardSecurity = plain)

            assertEquals(Verdict.SIGNATURE_VALID_CHIP_UNVERIFIED, read(chip, AccessKey.Can(CAN)).verdict)
        }

    @Test
    fun sansDg14NiCardSecurity_PaceGm_SignatureValidePuceNonVerifiee() =
        runTest {
            val chip = SimulatedChip(bareDocument, gmSettings)

            val report = read(chip, AccessKey.Can(CAN))

            assertEquals(Verdict.SIGNATURE_VALID_CHIP_UNVERIFIED, report.verdict)
            assertEquals(CheckStatus.NOT_AVAILABLE, report.check(CheckId.CHIP_AUTHENTICATION).status)
        }

    @Test
    fun sansDg14_CardSecurityAnnonceCamEtCanalPaceCam_Authentique() =
        runTest {
            val chip =
                SimulatedChip(
                    bareDocument,
                    camSettings,
                    caPrivateKey = chipKey.private,
                    cardSecurity = cardSecurity(PaceSettings.ID_PACE_ECDH_CAM_AES_CBC_CMAC_128),
                )

            val report = read(chip, AccessKey.Can(CAN))

            assertEquals(Verdict.AUTHENTIC, report.verdict)
            assertEquals(
                CheckDetail.ChipAuthentication(ChipAuthenticationMethod.PACE_CAM),
                report.check(CheckId.CHIP_AUTHENTICATION).detail,
            )
        }

    @Test
    fun sousBac_CardSecurityJamaisLu() =
        runTest {
            val announcing = cardSecurity(PaceSettings.ID_PACE_ECDH_CAM_AES_CBC_CMAC_128)
            val chip = SimulatedChip(bareDocument, pace = null, cardSecurity = announcing)

            val report = read(chip, mrzOf(bareDocument))

            // Lu, il annoncerait PACE-CAM et mettrait la CA en échec : sous BAC, il ne l'est pas
            // (au MF, la puce simulée ne le sert qu'avant la sélection de l'applet, qui ouvre BAC).
            assertEquals(Verdict.SIGNATURE_VALID_CHIP_UNVERIFIED, report.verdict)
            assertEquals(CheckStatus.NOT_AVAILABLE, report.check(CheckId.CHIP_AUTHENTICATION).status)
        }

    companion object {
        private const val SEED = 20_261_006L
        private const val CAN = "246810"
        private const val DG14 = 14
        private const val PACE_VERSION = 2
        private const val CA_VERSION = 1
        private const val DOCUMENT_NUMBER = "L898902C3"
        private val DATE_OF_BIRTH: LocalDate = LocalDate.of(1974, 8, 12)
    }
}
