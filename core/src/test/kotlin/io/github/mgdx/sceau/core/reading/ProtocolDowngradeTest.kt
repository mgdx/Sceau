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
 * Audit V20 (decision D40): the protocol actually run is compared with what the signed DG14
 * announces. A clone that removes PACE-CAM, or PACE, from its (unsigned) EF.CardAccess must not
 * get "Valid signature, chip not verified" when nothing else authenticates the chip.
 */
class ProtocolDowngradeTest {
    private val pki by lazy { TestPki(TestKeyType.RSA, seed = SEED) }

    /** Document whose signed DG14 only contains the `PACEInfo` [announcedPace]: no CA key, no DG15 by default. */
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
    fun dg14AnnouncesPaceCamAndCardAccessPaceGm_Failure() =
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
    fun dg14AnnouncesPaceAndReadUnderBac_Failure() =
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
    fun dg14AnnouncesPaceGmAndPaceGmChannel_ValidSignatureChipNotVerified() =
        runTest {
            val document = document(PaceSettings.ID_PACE_ECDH_GM_AES_CBC_CMAC_128)
            val chip = SimulatedChip(document, PaceSettings(CAN, PaceSettings.ID_PACE_ECDH_GM_AES_CBC_CMAC_128))

            val report = read(chip, AccessKey.Can(CAN))

            assertEquals(Verdict.SIGNATURE_VALID_CHIP_UNVERIFIED, report.verdict)
        }

    @Test
    fun dg14AnnouncesPaceAndReadUnderBacButAaSucceeded_Authentic() =
        runTest {
            // PACE to BAC fallback (D13) on a chip that answers AA: the chip is authenticated,
            // the protocol run does not change the proof.
            val document = document(PaceSettings.ID_PACE_ECDH_GM_AES_CBC_CMAC_128, activeAuthentication = AaKeyType.RSA)
            val chip = SimulatedChip(document, pace = null)

            val report = read(chip, mrzOf(document))

            assertEquals(Verdict.AUTHENTIC, report.verdict)
            assertEquals(CheckStatus.OK, report.check(CheckId.SECURE_CHANNEL).status)
        }

    // --- Audit V32 (decision D43): document without DG14, CA key in EF.CardSecurity only ---

    /** Document without DG14 or DG15: nothing can authenticate the chip, except EF.CardSecurity. */
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

    /** EF.CardSecurity signed by the document's DS: `PACEInfo` [paceOid] and, if [withKey], the CA key. */
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
    fun withoutDg14_CardSecurityAnnouncesCamAndPaceGmChannel_Failure() =
        runTest {
            val chip = SimulatedChip(bareDocument, gmSettings, cardSecurity = cardSecurity(PaceSettings.ID_PACE_ECDH_CAM_AES_CBC_CMAC_128))

            val report = read(chip, AccessKey.Can(CAN))

            assertDowngraded(report, "VERIFY_CHIP-DOWNGRADE-CAM")
            assertTrue("EF.CardSecurity read", chip.fileReads.any { it.fid == SimulatedChip.FID_CARD_SECURITY })
        }

    @Test
    fun withoutDg14_CardSecurityAnnouncesCaKeyAndPaceGmChannel_Failure() =
        runTest {
            val chip = SimulatedChip(bareDocument, gmSettings, cardSecurity = cardSecurity(PaceSettings.ID_PACE_ECDH_GM_AES_CBC_CMAC_128))

            assertDowngraded(read(chip, AccessKey.Can(CAN)), "VERIFY_CHIP-DOWNGRADE-CA")
        }

    @Test
    fun withoutDg14_CardSecurityWithInvalidSignature_Failure() =
        runTest {
            val tampered =
                cardSecurity(PaceSettings.ID_PACE_ECDH_GM_AES_CBC_CMAC_128, withKey = false, options = SodOptions(tamperSignature = true))
            val chip = SimulatedChip(bareDocument, gmSettings, cardSecurity = tampered)

            assertDowngraded(read(chip, AccessKey.Can(CAN)), "VERIFY_CHIP-DOWNGRADE-CARD_SECURITY")
        }

    @Test
    fun withoutDg14_CardSecurityWithoutKeyOrCam_ValidSignatureChipNotVerified() =
        runTest {
            val plain = cardSecurity(PaceSettings.ID_PACE_ECDH_GM_AES_CBC_CMAC_128, withKey = false)
            val chip = SimulatedChip(bareDocument, gmSettings, cardSecurity = plain)

            assertEquals(Verdict.SIGNATURE_VALID_CHIP_UNVERIFIED, read(chip, AccessKey.Can(CAN)).verdict)
        }

    @Test
    fun withoutDg14OrCardSecurity_PaceGm_ValidSignatureChipNotVerified() =
        runTest {
            val chip = SimulatedChip(bareDocument, gmSettings)

            val report = read(chip, AccessKey.Can(CAN))

            assertEquals(Verdict.SIGNATURE_VALID_CHIP_UNVERIFIED, report.verdict)
            assertEquals(CheckStatus.NOT_AVAILABLE, report.check(CheckId.CHIP_AUTHENTICATION).status)
        }

    @Test
    fun withoutDg14_CardSecurityAnnouncesCamAndPaceCamChannel_Authentic() =
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
    fun underBac_CardSecurityNeverRead() =
        runTest {
            val announcing = cardSecurity(PaceSettings.ID_PACE_ECDH_CAM_AES_CBC_CMAC_128)
            val chip = SimulatedChip(bareDocument, pace = null, cardSecurity = announcing)

            val report = read(chip, mrzOf(bareDocument))

            // If read, it would announce PACE-CAM and make CA fail: under BAC it is not read
            // (at MF, the simulated chip serves it only before the applet selection, which opens BAC).
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
