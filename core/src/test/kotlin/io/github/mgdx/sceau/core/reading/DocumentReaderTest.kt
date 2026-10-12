package io.github.mgdx.sceau.core.reading

import io.github.mgdx.sceau.core.SceauException
import io.github.mgdx.sceau.core.reading.ScriptedTransport.Companion.file
import io.github.mgdx.sceau.core.reading.ScriptedTransport.Companion.missingFile
import io.github.mgdx.sceau.core.reading.ScriptedTransport.Companion.raise
import io.github.mgdx.sceau.core.reading.ScriptedTransport.Companion.respond
import io.github.mgdx.sceau.core.reading.TestFixtures.AID_SELECT
import io.github.mgdx.sceau.core.reading.TestFixtures.FID_COM
import io.github.mgdx.sceau.core.reading.TestFixtures.FID_SOD
import io.github.mgdx.sceau.core.reading.TestFixtures.fid
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * READ_DATA step, without secure messaging (applet selected in plain, no BAC), so the
 * APDUs sent can be observed directly.
 */
class DocumentReaderTest {
    /** Both phases of reading, with no Chip Authentication between them. */
    private fun read(transport: ScriptedTransport): LdsContent {
        val chip = Chip(transport)
        chip.service.open()
        chip.service.sendSelectApplet(false)
        val reader = DocumentReader(chip)
        val objects = reader.readSecurityObjects()
        val dataGroups = LinkedHashMap<Int, ByteArray>()
        objects.dg14?.let { dataGroups[14] = it }
        return reader.readDataGroups(objects, dataGroups)
    }

    private fun ScriptedTransport.selectedFiles(): List<String> = sent.filter { it.startsWith("00A4020C02") }.map { it.substring(10, 14) }

    @Test
    fun dgsListedInCom_Dg3AndDg4NeverRequested() {
        val dg1 = TestFixtures.dg1()
        val sod = byteArrayOf(0x77, 0x03, 0x01, 0x02, 0x03)
        val transport =
            ScriptedTransport(
                respond(AID_SELECT, "9000"),
                *file(FID_COM, TestFixtures.com(1, 2, 3, 4, 11, 14)),
                *file(FID_SOD, sod),
                // DG14 before the other DGs: the Chip Authentication it announces precedes them (D20).
                *file(fid(14), TestFixtures.opaqueDataGroup(14)),
                *file(fid(1), dg1),
                *file(fid(2), TestFixtures.opaqueDataGroup(2)),
                // DG11 listed but protected: missing, reading continues.
                missingFile(fid(11), "6982"),
            )

        val content = read(transport)

        assertEquals(listOf(14, 1, 2), content.dataGroups.keys.toList())
        assertArrayEquals(dg1, content.dataGroups[1])
        assertArrayEquals(sod, content.sod)
        assertEquals(listOf(FID_COM, FID_SOD, fid(14), fid(1), fid(2), fid(11)), transport.selectedFiles())
        assertFalse(transport.selectedFiles().any { it == fid(3) || it == fid(4) })
        assertTrue(transport.exhausted)
    }

    @Test
    fun comMissing_DgsListedInSod() {
        val transport =
            ScriptedTransport(
                respond(AID_SELECT, "9000"),
                missingFile(FID_COM),
                *file(FID_SOD, TestFixtures.sod(1, 2, 3, 4, 12, 15)),
                *file(fid(1), TestFixtures.dg1()),
                *file(fid(2), TestFixtures.opaqueDataGroup(2)),
                *file(fid(15), TestFixtures.opaqueDataGroup(15)),
                *file(fid(12), TestFixtures.opaqueDataGroup(12)),
            )

        val content = read(transport)

        assertEquals(listOf(1, 2, 15, 12), content.dataGroups.keys.toList())
        assertEquals("only the SOD is authoritative", setOf(1, 2, 3, 4, 12, 15), content.signedDataGroups)
        assertFalse(transport.selectedFiles().any { it == fid(3) || it == fid(4) })
        assertTrue(transport.exhausted)
    }

    @Test
    fun documentRemovedDuringDg2_ConnectionLostUnchanged() {
        val lost = SceauException.ConnectionLost()
        val transport =
            ScriptedTransport(
                respond(AID_SELECT, "9000"),
                *file(FID_COM, TestFixtures.com(1, 2)),
                *file(FID_SOD, byteArrayOf(0x77, 0x01, 0x00)),
                *file(fid(1), TestFixtures.dg1()),
                respond("00A4020C02${fid(2)}", "9000"),
                raise("00B0", lost),
            )
        try {
            read(transport)
            fail("ConnectionLost expected")
        } catch (e: SceauException.ConnectionLost) {
            assertSame(lost, e)
        }
    }

    @Test
    fun sodMissing_FailureWithTechnicalCode() {
        val transport =
            ScriptedTransport(
                respond(AID_SELECT, "9000"),
                *file(FID_COM, TestFixtures.com(1, 2)),
                missingFile(FID_SOD),
            )
        try {
            read(transport)
            fail("StepFailure expected")
        } catch (e: StepFailure) {
            assertEquals("READ_DATA-SOD-CardServiceException-6A82", technicalCode("READ_DATA", e))
        }
    }
}
