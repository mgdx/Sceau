package io.github.mgdx.sceau.core.reading

import io.github.mgdx.sceau.core.AccessKey
import io.github.mgdx.sceau.core.model.ImageFormat
import io.github.mgdx.sceau.core.readAndVerify
import io.github.mgdx.sceau.core.report.CheckDetail
import io.github.mgdx.sceau.core.report.CheckId
import io.github.mgdx.sceau.core.report.CheckStatus
import io.github.mgdx.sceau.core.report.Verdict
import io.github.mgdx.sceau.core.report.VerificationReport
import io.github.mgdx.sceau.testchip.SimulatedChip
import io.github.mgdx.sceau.testchip.SimulatedDocuments
import io.github.mgdx.sceau.testchip.SimulatedIdentityDocument
import io.github.mgdx.sceau.testchip.SodOptions
import io.github.mgdx.sceau.testchip.TestCrypto
import io.github.mgdx.sceau.testchip.TestDataGroups
import io.github.mgdx.sceau.testchip.TestDocument
import io.github.mgdx.sceau.testchip.resigned
import kotlinx.coroutines.test.runTest
import org.jmrtd.PassportService
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DG7, signature manuscrite du titulaire (décision D37) : lu seulement s'il est annoncé, après la
 * Chip Authentication, couvert par le contrôle des empreintes et par celui des DG signés mais
 * non fournis (audit V1), absent sans erreur s'il est illisible, remis à zéro par `wipe()`.
 */
class SignatureDg7Test {
    private val card: SimulatedIdentityDocument by lazy { SimulatedDocuments.frenchIdCard() }
    private val can: AccessKey get() = checkNotNull(card.canKey)

    private suspend fun read(chip: SimulatedChip): VerificationReport {
        val report = readAndVerify(chip, can, card.trustStore) { }
        assertFalse("DG3 demandé", SimulatedChip.FID_DG3 in chip.selectedFids)
        assertFalse("DG4 demandé", SimulatedChip.FID_DG4 in chip.selectedFids)
        return report
    }

    private fun resigned(groups: Map<Int, ByteArray>): TestDocument =
        card.document.resigned(
            groups = groups,
            options = SodOptions(signingTime = TestCrypto.instant(SimulatedDocuments.SPECIMEN_DATE_OF_ISSUE)),
        )

    private fun hashes(report: VerificationReport) = report.check(CheckId.DG_HASHES).detail as CheckDetail.DataGroupHashes

    @Test
    fun dg7AnnonceEtFourni_SignatureAfficheeEmpreinteVerifiee() =
        runTest {
            val chip = card.chip()

            val report = read(chip)

            assertEquals(Verdict.AUTHENTIC, report.verdict)
            assertEquals(CheckStatus.OK, report.check(CheckId.DG_HASHES).status)
            assertTrue(7 in hashes(report).checked)
            val signature = checkNotNull(report.document.signature)
            assertEquals(ImageFormat.JPEG, signature.format)
            assertArrayEquals(SimulatedDocuments.specimenSignature(), signature.bytes)
            assertTrue(7 in report.document.rawDataGroups)
            assertTrue(FID_DG7 in chip.selectedFids)
        }

    @Test
    fun dg7SigneMaisRetenuParLaPuce_EmpreintesEnEchec() =
        runTest {
            val chip = SimulatedChip(card.document, card.pace, withheldDataGroups = setOf(7))

            val report = read(chip)

            assertEquals(Verdict.FAILED, report.verdict)
            assertEquals(CheckStatus.FAILED, report.check(CheckId.DG_HASHES).status)
            assertEquals(listOf(7), hashes(report).missing)
            assertEquals(emptyList<Int>(), hashes(report).mismatched)
            assertNull(report.document.signature)
        }

    @Test
    fun dg7Modifie_EmpreintesEnEchec() =
        runTest {
            val chip = SimulatedChip(card.document.withModifiedDataGroup(7), card.pace)

            val report = read(chip)

            assertEquals(Verdict.FAILED, report.verdict)
            assertEquals(listOf(7), hashes(report).mismatched)
        }

    @Test
    fun dg7NonAnnonce_JamaisDemande() =
        runTest {
            val chip = SimulatedChip(resigned(card.document.dataGroups - 7), card.pace)

            val report = read(chip)

            assertEquals(Verdict.AUTHENTIC, report.verdict)
            assertFalse("DG7 demandé", FID_DG7 in chip.selectedFids)
            assertNull(report.document.signature)
            assertFalse(7 in hashes(report).checked)
        }

    @Test
    fun dg7Illisible_AbsentSansErreur() =
        runTest {
            // DG7 signé mais tronqué : deux images annoncées, la première coupée.
            val corrupted = byteArrayOf(0x67, 0x07, 0x02, 0x01, 0x02, 0x5F, 0x43, 0x10, 0x01)
            val chip = SimulatedChip(resigned(card.document.dataGroups + (7 to corrupted)), card.pace)

            val report = read(chip)

            assertEquals(Verdict.AUTHENTIC, report.verdict)
            assertNull(report.document.signature)
            assertTrue(7 in hashes(report).checked)
        }

    @Test
    fun parseDg7_PremiereImageEtFormatReconnu() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2)
        val jp2 = byteArrayOf(0xFF.toByte(), 0x4F, 0xFF.toByte(), 0x51, 3, 4)

        val first = checkNotNull(DataGroupParsers.parseDg7(TestDataGroups.dg7(jp2, jpeg)))

        assertEquals(ImageFormat.JPEG2000, first.format)
        assertArrayEquals(jp2, first.bytes)
        assertEquals(ImageFormat.JPEG, DataGroupParsers.parseDg7(TestDataGroups.dg7(jpeg))?.format)
    }

    @Test
    fun dg7DemesureOuVide_Absent() {
        val dg1 = TestDataGroups.dg1()
        // Image annonçant 2 Go dans un DG7 de quelques octets : refusée avant JMRTD.
        val huge =
            byteArrayOf(0x67, 0x0B, 0x02, 0x01, 0x01, 0x5F, 0x43, 0x84.toByte(), 0x7F, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0)
        for (bytes in listOf(huge, byteArrayOf(0x67, 0x00), ByteArray(0), byteArrayOf(0x75, 0x02, 0x01, 0x02))) {
            assertNull(DataGroupParsers.document(mapOf(1 to dg1, 7 to bytes), TestFixtures.TODAY).signature)
        }
    }

    @Test
    fun wipe_RemetDg7AZero() {
        val dg7 = TestDataGroups.dg7()
        val document = DataGroupParsers.document(mapOf(1 to TestDataGroups.dg1(), 7 to dg7), TestFixtures.TODAY)
        val signature = checkNotNull(document.signature)

        document.wipe()

        assertTrue("image de la signature", signature.bytes.all { it == 0.toByte() })
        assertTrue("octets bruts de DG7", dg7.all { it == 0.toByte() })
    }

    @Test
    fun plafondDeTaille_CommeLesAutresImages() {
        assertEquals(FileSizeLimits.maxSize(PassportService.EF_DG2), FileSizeLimits.maxSize(PassportService.EF_DG7))
        assertEquals(FileSizeLimits.maxSize(PassportService.EF_DG12), FileSizeLimits.maxSize(PassportService.EF_DG7))
        assertEquals("DG7", FileSizeLimits.nameOf(PassportService.EF_DG7))
    }

    private companion object {
        val FID_DG7 = PassportService.EF_DG7.toInt()
    }
}
