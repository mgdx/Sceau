package io.github.mgdx.sceau.core.reading

import io.github.mgdx.sceau.testchip.SimulatedDocuments
import org.jmrtd.lds.icao.COMFile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BerStructureTest {
    private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun realFiles_Accepted() {
        val card = SimulatedDocuments.frenchIdCard()
        val files = card.document.dataGroups.values + card.document.sod + COMFile("1.7", "4.0.0", intArrayOf(0x61, 0x75, 0x6E)).encoded
        for (file in files) assertTrue(BerStructure.lengthsFit(file))
    }

    @Test
    fun lengthBeyondBuffer_Rejected() {
        // 60 07 { 5F01 : 2 GB }
        assertFalse(BerStructure.lengthsFit(hex("6007" + "5F01847FFFFFFF")))
        // Element that runs past the end of the buffer.
        assertFalse(BerStructure.lengthsFit(hex("3003" + "0405AABB")))
        // Outer length beyond the file.
        assertFalse(BerStructure.lengthsFit(hex("0405AABB")))
        // Length encoded on more than four bytes.
        assertFalse(BerStructure.lengthsFit(hex("048500000000010000")))
    }

    @Test
    fun childBeyondItsShortenedParent_Rejected() {
        // A streaming reader ignores the parent's length: 60 shortened to 3 bytes, then a
        // 2 GB tag list (5C) read anyway (fuzzing, COM campaign).
        assertFalse(BerStructure.lengthsFit(hex("6003" + "5F010430313037" + "5C847FFFFFFF")))
        // Deep well-formed nesting: accepted, without recursion.
        var nested = hex("0500")
        repeat(60) { nested = byteArrayOf(0x30, nested.size.toByte()) + nested }
        assertTrue(BerStructure.lengthsFit(nested))
    }

    @Test
    fun casesLeftToTheParser_Accepted() {
        assertTrue(BerStructure.lengthsFit(ByteArray(0)))
        // Header truncated at the end of the buffer, 00 and FF bytes skipped before a tag (SCUBA).
        assertTrue(BerStructure.lengthsFit(hex("30")))
        assertTrue(BerStructure.lengthsFit(hex("0401AA" + "FFFF00")))
        assertTrue(BerStructure.lengthsFit(hex("3005" + "00" + "0402AABB")))
    }

    @Test
    fun headerThatDiffersFromScubaReading_Rejected() {
        // Fuzzing (DG11, seed=0x5cea2026 index=35761): SCUBA skips FF, reads tag D3 then
        // a length on 0x44 bytes; the check reads like SCUBA.
        assertFalse(BerStructure.lengthsFit(hex("A00B" + "FFD3C4A1E9990A0E" + "535045")))
        // Header of an element truncated by the end of its parent, indefinite length.
        assertFalse(BerStructure.lengthsFit(hex("3002" + "5F01" + "04303130")))
        assertFalse(BerStructure.lengthsFit(hex("3080" + "0400" + "0000")))
    }
}
