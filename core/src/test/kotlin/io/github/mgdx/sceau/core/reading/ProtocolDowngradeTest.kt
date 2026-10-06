package io.github.mgdx.sceau.core.reading

import io.github.mgdx.sceau.core.AccessKey
import io.github.mgdx.sceau.core.readAndVerify
import io.github.mgdx.sceau.core.report.CheckDetail
import io.github.mgdx.sceau.core.report.CheckId
import io.github.mgdx.sceau.core.report.CheckStatus
import io.github.mgdx.sceau.core.report.Verdict
import io.github.mgdx.sceau.core.report.VerificationReport
import io.github.mgdx.sceau.testchip.AaKeyType
import io.github.mgdx.sceau.testchip.PaceSettings
import io.github.mgdx.sceau.testchip.SimulatedChip
import io.github.mgdx.sceau.testchip.TestDocument
import io.github.mgdx.sceau.testchip.TestKeyType
import io.github.mgdx.sceau.testchip.TestPki
import kotlinx.coroutines.test.runTest
import org.jmrtd.lds.PACEInfo
import org.jmrtd.lds.SecurityInfo
import org.jmrtd.lds.icao.DG14File
import org.junit.Assert.assertEquals
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

    companion object {
        private const val SEED = 20_261_006L
        private const val CAN = "246810"
        private const val DG14 = 14
        private const val PACE_VERSION = 2
        private const val DOCUMENT_NUMBER = "L898902C3"
        private val DATE_OF_BIRTH: LocalDate = LocalDate.of(1974, 8, 12)
    }
}
