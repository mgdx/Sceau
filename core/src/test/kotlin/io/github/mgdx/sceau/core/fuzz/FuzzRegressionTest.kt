package io.github.mgdx.sceau.core.fuzz

import io.github.mgdx.sceau.core.reading.DataGroupParsers
import io.github.mgdx.sceau.core.reading.DocumentReader
import io.github.mgdx.sceau.testchip.SimulatedDocuments
import org.jmrtd.lds.CardAccessFile
import org.jmrtd.lds.icao.DG14File
import org.jmrtd.lds.icao.DG15File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/**
 * Minimal inputs taken from the fuzzing campaigns: oversized inner lengths in chip files of a
 * few bytes (under their cap, which FileSizeLimits only checks on the outer header).
 */
class FuzzRegressionTest {
    /** Four-byte BER length: 2 GB. */
    private val huge = byteArrayOf(0x84.toByte(), 0x7F, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())

    private fun tlv(
        tag: Int,
        value: ByteArray,
    ) = byteArrayOf(tag.toByte(), value.size.toByte()) + value

    /** 5F01 = "0107", 5F36 = "040000": valid EF.COM header. */
    private val comVersions =
        byteArrayOf(0x5F, 0x01, 0x04, 0x30, 0x31, 0x30, 0x37, 0x5F, 0x36, 0x06, 0x30, 0x34, 0x30, 0x30, 0x30, 0x30)

    /** Original input from the COM campaign: the LDS version (5F01) announces 2 GB. */
    @Test
    fun comWithHugeInnerLengthIsUnreadable() {
        val com = byteArrayOf(0x60, 0x07, 0x5F, 0x01, 0x84.toByte(), 0x7F, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())

        assertEquals(emptySet<Int>(), DocumentReader.parseComDataGroups(com))
    }

    /**
     * Same bug, through the tag list (5C), which JMRTD reads without checking its length:
     * `OutOfMemoryError` before the `BerStructure` length checks.
     */
    @Test
    fun comWithHugeTagListIsUnreadable() {
        val com = tlv(0x60, comVersions + byteArrayOf(0x5C) + huge)

        assertEquals(emptySet<Int>(), DocumentReader.parseComDataGroups(com))
    }

    /**
     * COM campaign, seed=0x5cea2026 index=39536 (length-inflate, length-deflate, set-byte): the
     * length of 60 is shortened, and JMRTD, which reads as a stream, still reads the 2 GB tag
     * list that follows it. The check therefore follows the stream, not the bounds of constructed
     * elements.
     */
    @Test
    fun comWithShortenedOuterLengthAndHugeTagListIsUnreadable() {
        val com = byteArrayOf(0x60, 0x03) + comVersions + byteArrayOf(0x5C) + huge

        assertEquals(emptySet<Int>(), DocumentReader.parseComDataGroups(com))
    }

    /**
     * DG11 and DG12: JMRTD reads each field (and the DG12 images) at the announced length; a
     * 2 GB field in a DG of a few bytes gave an `OutOfMemoryError`, caught only by the
     * `DataGroupParsers.orNull` safety net. Now rejected before JMRTD.
     */
    @Test
    fun dg11AndDg12WithHugeFieldAreUnreadable() {
        val cases =
            listOf<() -> Unit>(
                { DataGroupParsers.parseDg11(hex("6B075C") + huge, TODAY) },
                { DataGroupParsers.parseDg11(hex("6B0D5C025F0E5F0E") + huge, TODAY) },
                { DataGroupParsers.parseDg12(hex("6C0D5C025F195F19") + huge, TODAY) },
                { DataGroupParsers.parseDg12(hex("6C0D5C025F1D5F1D") + huge, TODAY) },
            )
        for (case in cases) assertIllegalArgument(case)
    }

    /**
     * DG11 campaign, seed=0x5cea2026 index=35761 (unexpected-tag, set-byte): in the other names
     * list (A0), SCUBA skips the FF byte, reads tag D3 then a length encoded on 0x44 bytes, which
     * overflows into an oversized value. The check reads headers the same way as SCUBA.
     */
    @Test
    fun dg11WithPaddedTagIsUnreadable() {
        val dg11 =
            hex(
                "6B81F05C1A5F0E5F0F5F105F2B5F115F425F125F135F145F155F165F175F185F0E1453504543494D454E3C3C414E4E413C4D41524941" +
                    "A019020101FFD3C4A1E9990A0E53504543494D454E3C3C414E4E455F10093132333435363738395F2B083139373430383132" +
                    "5F110C55544F5049413C56494C4C455F421A312052554520464943544956453C39393939392055544F5049415F120B2B3030" +
                    "20303030303030305F130854455354455553455F140244525F150D524553554DFD204649435449465F1610000102030405060708" +
                    "090A0B0C0D0E0F5F17124C38393839303243333C58303030303030305F1806415543554E45",
            )

        assertIllegalArgument { DataGroupParsers.parseDg11(dg11, TODAY) }
    }

    /**
     * DG2: ISO 19794-5 face image block whose length (4 bytes after the "FAC\0" header)
     * announces 2 GB in a real DG2: `OutOfMemoryError` in JMRTD, caught only by `orNull`. Now
     * rejected before JMRTD.
     */
    @Test
    fun dg2WithHugeFaceImageBlockIsUnreadable() {
        val dg2 =
            SimulatedDocuments
                .frenchIdCard()
                .document.dataGroups
                .getValue(2)
                .copyOf()
        val blockLength = String(dg2, Charsets.ISO_8859_1).indexOf("FAC\u0000") + FACE_HEADER
        byteArrayOf(0x7F, 0xFF.toByte(), 0xFF.toByte(), 0xF0.toByte()).copyInto(dg2, blockLength)

        assertIllegalArgument { DataGroupParsers.parseDg2(dg2) }
        assertNull(DataGroupParsers.document(mapOf(1 to FuzzSeeds.dataGroup(1).first(), 2 to dg2), TODAY).portrait)
    }

    /** DG1 and the other DG2 BER lengths: JMRTD already throws an exception, without allocating. */
    @Test
    fun dg1AndDg2WithHugeBerLengthRaiseNoError() {
        exceptionAtMost { DataGroupParsers.parseDg1(hex("610A5F1F") + huge + hex("0000"), TODAY) }
        exceptionAtMost { DataGroupParsers.parseDg2(tlv(0x75, hex("7F61") + huge)) }
    }

    private fun assertIllegalArgument(block: () -> Unit) {
        try {
            block()
        } catch (e: IllegalArgumentException) {
            return
        }
        throw AssertionError("IllegalArgumentException expected")
    }

    private fun hex(value: String): ByteArray = ByteArray(value.length / 2) { value.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    /**
     * The other chip files parsed by JMRTD in reading/ go through BouncyCastle's
     * ASN1InputStream, which rejects a length beyond the buffer: exception, never
     * `OutOfMemoryError` (likewise, EF.CardSecurity is read by `CMSSignedData`).
     */
    @Test
    fun otherChipFilesWithHugeInnerLengthRaiseNoError() {
        val octetString = byteArrayOf(0x04) + huge + byteArrayOf(0x00)
        val sequence = byteArrayOf(0x30, octetString.size.toByte()) + octetString
        val set = byteArrayOf(0x31, octetString.size.toByte()) + octetString

        assertEquals(emptySet<Int>(), DocumentReader.parseSodDataGroups(tlv(0x77, sequence)))
        assertEquals(emptySet<Int>(), DocumentReader.parseSodDataGroups(tlv(0x77, octetString)))
        exceptionAtMost { DG14File(tlv(0x6E, set).inputStream()) }
        exceptionAtMost { CardAccessFile(set.inputStream()) }
        // DG15: JMRTD allocates the outer length, bounded by the read (16 KB); the inner one is
        // decoded by BouncyCastle.
        exceptionAtMost { DG15File(tlv(0x6F, sequence).inputStream()).publicKey }
    }

    /** Runs [block]: an exception is allowed, an `Error` (OutOfMemoryError) fails the test. */
    private fun exceptionAtMost(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            return
        }
    }

    private companion object {
        val TODAY: LocalDate = LocalDate.of(2026, 9, 29)

        /** ISO 19794-5 header up to the length of the first image block. */
        const val FACE_HEADER = 14
    }
}
