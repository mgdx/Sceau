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
 * DG7, the holder's handwritten signature (decision D37): read only if listed, after Chip
 * Authentication, covered by the hash check and by the check on DGs signed but not provided
 * (audit V1), missing without error if unreadable, zeroed by `wipe()`.
 */
class SignatureDg7Test {
    private val card: SimulatedIdentityDocument by lazy { SimulatedDocuments.frenchIdCard() }
    private val can: AccessKey get() = checkNotNull(card.canKey)

    private suspend fun read(chip: SimulatedChip): VerificationReport {
        val report = readAndVerify(chip, can, card.trustStore) { }
        assertFalse("DG3 requested", SimulatedChip.FID_DG3 in chip.selectedFids)
        assertFalse("DG4 requested", SimulatedChip.FID_DG4 in chip.selectedFids)
        return report
    }

    private fun resigned(groups: Map<Int, ByteArray>): TestDocument =
        card.document.resigned(
            groups = groups,
            options = SodOptions(signingTime = TestCrypto.instant(SimulatedDocuments.SPECIMEN_DATE_OF_ISSUE)),
        )

    private fun hashes(report: VerificationReport) = report.check(CheckId.DG_HASHES).detail as CheckDetail.DataGroupHashes

    @Test
    fun dg7ListedAndProvided_SignatureShownHashVerified() =
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
    fun dg7SignedButWithheldByChip_HashesFail() =
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
    fun dg7Modified_HashesFail() =
        runTest {
            val chip = SimulatedChip(card.document.withModifiedDataGroup(7), card.pace)

            val report = read(chip)

            assertEquals(Verdict.FAILED, report.verdict)
            assertEquals(listOf(7), hashes(report).mismatched)
        }

    @Test
    fun dg7NotListed_NeverRequested() =
        runTest {
            val chip = SimulatedChip(resigned(card.document.dataGroups - 7), card.pace)

            val report = read(chip)

            assertEquals(Verdict.AUTHENTIC, report.verdict)
            assertFalse("DG7 requested", FID_DG7 in chip.selectedFids)
            assertNull(report.document.signature)
            assertFalse(7 in hashes(report).checked)
        }

    @Test
    fun dg7Unreadable_MissingWithoutError() =
        runTest {
            // DG7 signed but truncated: two images declared, the first one cut off.
            val corrupted = byteArrayOf(0x67, 0x07, 0x02, 0x01, 0x02, 0x5F, 0x43, 0x10, 0x01)
            val chip = SimulatedChip(resigned(card.document.dataGroups + (7 to corrupted)), card.pace)

            val report = read(chip)

            assertEquals(Verdict.AUTHENTIC, report.verdict)
            assertNull(report.document.signature)
            assertTrue(7 in hashes(report).checked)
        }

    @Test
    fun parseDg7_FirstImageAndFormatRecognized() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2)
        val jp2 = byteArrayOf(0xFF.toByte(), 0x4F, 0xFF.toByte(), 0x51, 3, 4)

        val first = checkNotNull(DataGroupParsers.parseDg7(TestDataGroups.dg7(jp2, jpeg)))

        assertEquals(ImageFormat.JPEG2000, first.format)
        assertArrayEquals(jp2, first.bytes)
        assertEquals(ImageFormat.JPEG, DataGroupParsers.parseDg7(TestDataGroups.dg7(jpeg))?.format)
    }

    @Test
    fun dg7OversizedOrEmpty_Missing() {
        val dg1 = TestDataGroups.dg1()
        // Image declaring 2 GB in a DG7 of a few bytes: rejected before JMRTD.
        val huge =
            byteArrayOf(0x67, 0x0B, 0x02, 0x01, 0x01, 0x5F, 0x43, 0x84.toByte(), 0x7F, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0)
        for (bytes in listOf(huge, byteArrayOf(0x67, 0x00), ByteArray(0), byteArrayOf(0x75, 0x02, 0x01, 0x02))) {
            assertNull(DataGroupParsers.document(mapOf(1 to dg1, 7 to bytes), TestFixtures.TODAY).signature)
        }
    }

    @Test
    fun wipe_ZeroesDg7() {
        val dg7 = TestDataGroups.dg7()
        val document = DataGroupParsers.document(mapOf(1 to TestDataGroups.dg1(), 7 to dg7), TestFixtures.TODAY)
        val signature = checkNotNull(document.signature)

        document.wipe()

        assertTrue("signature image", signature.bytes.all { it == 0.toByte() })
        assertTrue("raw DG7 bytes", dg7.all { it == 0.toByte() })
    }

    @Test
    fun sizeCap_LikeOtherImages() {
        assertEquals(FileSizeLimits.maxSize(PassportService.EF_DG2), FileSizeLimits.maxSize(PassportService.EF_DG7))
        assertEquals(FileSizeLimits.maxSize(PassportService.EF_DG12), FileSizeLimits.maxSize(PassportService.EF_DG7))
        assertEquals("DG7", FileSizeLimits.nameOf(PassportService.EF_DG7))
    }

    private companion object {
        val FID_DG7 = PassportService.EF_DG7.toInt()
    }
}
