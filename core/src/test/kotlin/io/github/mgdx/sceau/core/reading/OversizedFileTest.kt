package io.github.mgdx.sceau.core.reading

import io.github.mgdx.sceau.core.SceauException
import io.github.mgdx.sceau.core.readAndVerify
import io.github.mgdx.sceau.core.report.Verdict
import io.github.mgdx.sceau.testchip.OversizedFile
import io.github.mgdx.sceau.testchip.SimulatedChip
import io.github.mgdx.sceau.testchip.SimulatedDocuments
import io.github.mgdx.sceau.testchip.SimulatedIdentityDocument
import io.github.mgdx.sceau.testchip.resigned
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Audit V11: lengths declared by a forged chip. A file whose header exceeds its cap is
 * rejected before any allocation and without a massive read; a DG2 with a corrupted image
 * header yields a missing portrait without failing the read.
 */
class OversizedFileTest {
    private val card: SimulatedIdentityDocument by lazy { SimulatedDocuments.frenchIdCard() }

    private fun chip(
        card: SimulatedIdentityDocument = this.card,
        oversized: Map<Int, OversizedFile> = emptyMap(),
    ) = SimulatedChip(card.document, card.pace, oversizedFiles = oversized)

    /** APDUs of a normal read of the simulated CNIe, to bound the forged reads. */
    private suspend fun baselineApdus(): Int {
        val chip = chip()
        readAndVerify(chip, checkNotNull(card.canKey), card.trustStore) {}
        return chip.apduCount
    }

    @Test
    fun sodDeclaring2Gb_TooLargeWithoutMassiveRead() =
        runTest {
            val baseline = baselineApdus()
            val chip = chip(oversized = mapOf(FID_SOD to OversizedFile(SOD_TAG, 0x7FFF_FFFFL)))
            try {
                readAndVerify(chip, checkNotNull(card.canKey), card.trustStore) {}
                fail("Unexpected expected")
            } catch (e: SceauException.Unexpected) {
                assertEquals("UNEXPECTED-READ_DATA-SOD-TOO_LARGE", e.code)
            }
            assertTrue("transport closed", chip.closed)
            // SELECT and header read only: far less than a full read.
            assertTrue("${chip.apduCount} APDU", chip.apduCount < baseline)
        }

    @Test
    fun dg11Declaring50Mb_MissingAndReadContinues() =
        runTest {
            val baseline = baselineApdus()
            val chip = chip(oversized = mapOf(FID_DG11 to OversizedFile(DG11_TAG, 50L * 1024 * 1024)))

            val report = readAndVerify(chip, checkNotNull(card.canKey), card.trustStore) {}

            assertNull(report.document.dg11)
            assertNotNull(report.document.dg12)
            assertNotNull(report.document.portrait)
            // The real DG11 (a few reads) is replaced by SELECT and header: no massive read.
            assertTrue("${chip.apduCount} APDUs for $baseline", chip.apduCount <= baseline + 2)
        }

    @Test
    fun dg2WithCorruptedImageHeader_PortraitMissingVerdictUnchanged() =
        runTest {
            val normal = readAndVerify(chip(), checkNotNull(card.canKey), card.trustStore) {}
            assertEquals(Verdict.AUTHENTIC, normal.verdict)

            val corrupted = card.withDataGroup2(corruptImageLength(card.document.dataGroups.getValue(2)))
            val report = readAndVerify(chip(corrupted), checkNotNull(corrupted.canKey), corrupted.trustStore) {}

            assertNull(report.document.portrait)
            assertEquals(normal.verdict, report.verdict)
            assertEquals(normal.checks.map { it.id to it.status }, report.checks.map { it.id to it.status })
        }

    @Test
    fun dg2Parser_CorruptedImageHeader_Null() {
        val dg2 = corruptImageLength(card.document.dataGroups.getValue(2))
        val data = DataGroupParsers.document(card.document.dataGroups + (2 to dg2))
        assertNull(data.portrait)
    }

    @Test
    fun tlvHeader_DeclaredSize() {
        assertEquals(2L + 0x10, FileSizeLimits.announcedSize(byteArrayOf(0x61, 0x10)))
        assertEquals(4L + 0x0123, FileSizeLimits.announcedSize(byteArrayOf(0x77, 0x82.toByte(), 0x01, 0x23)))
        assertEquals(6L + 0x7FFF_FFFF, FileSizeLimits.announcedSize(byteArrayOf(0x77, 0x84.toByte(), 0x7F, -1, -1, -1)))
        assertEquals(Long.MAX_VALUE, FileSizeLimits.announcedSize(byteArrayOf(0x77, 0x85.toByte(), 1, 0, 0, 0, 0)))
        // Two-byte tag (7F61...) and unusable headers.
        assertEquals(3L + 5, FileSizeLimits.announcedSize(byteArrayOf(0x7F, 0x61, 0x05)))
        assertNull(FileSizeLimits.announcedSize(byteArrayOf(0x77, 0x82.toByte(), 0x01)))
        assertNull(FileSizeLimits.announcedSize(byteArrayOf(0x77, 0x80.toByte())))
        assertNull(FileSizeLimits.announcedSize(ByteArray(0)))
    }

    /** Same CNIe, DG2 replaced and SOD signed again: only the DG2 content differs. */
    private fun SimulatedIdentityDocument.withDataGroup2(dg2: ByteArray): SimulatedIdentityDocument =
        SimulatedIdentityDocument(document.resigned(document.dataGroups + (2 to dg2)), pace, documentNumber, dateOfBirth)

    /**
     * ISO 19794-5 DG2 whose facial data block length (4 bytes following the "FAC\0" header,
     * version, record length and number of faces) declares nearly 2 GB.
     */
    private fun corruptImageLength(dg2: ByteArray): ByteArray {
        val magic = byteArrayOf('F'.code.toByte(), 'A'.code.toByte(), 'C'.code.toByte(), 0)
        val start = (0..dg2.size - magic.size).first { i -> magic.indices.all { dg2[i + it] == magic[it] } }
        val blockLength = start + FACE_HEADER_LENGTH
        return dg2.copyOf().also { bytes ->
            byteArrayOf(0x7F, -1, -1, 0x00).copyInto(bytes, blockLength)
        }
    }

    private companion object {
        const val FID_SOD = 0x011D
        const val FID_DG11 = 0x010B
        const val SOD_TAG = 0x77
        const val DG11_TAG = 0x6B

        /** "FAC\0" (4), version (4), record length (4), number of faces (2). */
        const val FACE_HEADER_LENGTH = 14
    }
}
