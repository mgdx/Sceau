package io.github.mgdx.sceau.core.reading

import io.github.mgdx.sceau.core.AccessKey
import io.github.mgdx.sceau.core.CardTransport
import io.github.mgdx.sceau.core.SceauException
import io.github.mgdx.sceau.core.readAndVerify
import io.github.mgdx.sceau.core.reading.ScriptedTransport.Companion.hexToBytes
import io.github.mgdx.sceau.core.reading.ScriptedTransport.Companion.respond
import io.github.mgdx.sceau.core.reading.TestFixtures.FID_CARD_ACCESS
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Audit V29: a forged chip that answers `90 00` with no data (or one byte at a time) to every
 * READ BINARY no longer makes the read loop, and every read finishes within the global timeout.
 */
class ReadStallTest {
    private val mrz = AccessKey.Mrz("12AB34567", LocalDate.of(1965, 2, 12), LocalDate.of(2030, 12, 31))

    /** SELECT of file [fid] then READ BINARY: [header] header at offset 0, [reply] afterwards. */
    private fun stallingFile(
        fid: String,
        header: ByteArray,
        reply: (offset: Int, le: Int) -> ByteArray,
    ) = arrayOf(
        respond("00A4020C02$fid", "9000"),
        ScriptedTransport.Exchange("00B0", repeatable = true) { command ->
            val offset = ((command[2].toInt() and 0x7F) shl 8) or (command[3].toInt() and 0xFF)
            val le = (command[4].toInt() and 0xFF).let { if (it == 0) 256 else it }
            val data = if (offset == 0) header.copyOf(minOf(le, header.size)) else reply(offset, le)
            data + "9000".hexToBytes()
        },
    )

    @Test
    fun repeatedEmptyResponses_ReadStalledInFewerThan10Apdus() =
        runTest {
            // EF.CardAccess declaring 1,000 bytes, then empty responses.
            val header = "318203E8".hexToBytes() + ByteArray(4)
            val transport = ScriptedTransport(*stallingFile(FID_CARD_ACCESS, header) { _, _ -> ByteArray(0) })
            try {
                readAndVerify(transport, mrz, TestFixtures.EMPTY_TRUST_STORE) {}
                fail("ReadStalled expected")
            } catch (e: SceauException.ReadStalled) {
                assertEquals("READ_STALLED-CONNECT-CARD_ACCESS", e.code)
            }
            assertTrue("transport closed", transport.closed)
            assertTrue("${transport.sent.size} APDU", transport.sent.size < 10)
        }

    @Test
    fun oneByteResponses_ReadStalledBeforeExchangeCap() {
        // DG2 declaring 200,000 bytes (below its 256 KB cap), delivered one byte at a time.
        val header = "7583030D40".hexToBytes() + ByteArray(3)
        val fid = TestFixtures.fid(2)
        val transport = ScriptedTransport(*stallingFile(fid, header) { _, _ -> byteArrayOf(0x42) })
        val chip = Chip(transport)
        try {
            chip.readFile(Chip.fidOf(2))
            fail("ReadStalled expected")
        } catch (e: SceauException.ReadStalled) {
            assertEquals("READ_STALLED-DG2", e.code)
        }
        val readBinaries = transport.sent.count { it.startsWith("00B0") }
        assertTrue("$readBinaries READ BINARY", readBinaries <= FileSizeLimits.maxReadBinaryCommands(Chip.fidOf(2)))
    }

    @Test
    fun optionalDgThatStopsProgressing_ErrorNotMissing() {
        val header = "6B820100".hexToBytes() + ByteArray(4)
        val transport = ScriptedTransport(*stallingFile(TestFixtures.fid(11), header) { _, _ -> ByteArray(0) })
        try {
            Chip(transport).readOptionalFile(Chip.fidOf(11))
            fail("ReadStalled expected")
        } catch (e: SceauException.ReadStalled) {
            assertEquals("READ_STALLED-DG11", e.code)
        }
    }

    @Test
    fun normalReadIn223ByteBlocks_Unchanged() {
        // 30 KB DG2, read in 223-byte blocks as on a real passport (below 32 KB: the scripted
        // transport only serves READ BINARY B0, without an offset encoded in DO53).
        val value = ByteArray(30_000) { (it * 31).toByte() }
        val content = "7582".hexToBytes() + byteArrayOf((value.size shr 8).toByte(), value.size.toByte()) + value
        val transport = ScriptedTransport(*ScriptedTransport.file(TestFixtures.fid(2), content))
        val read = Chip(transport).readFile(Chip.fidOf(2))
        assertArrayEquals(content, read)
        val readBinaries = transport.sent.count { it.startsWith("00B0") }
        assertTrue("$readBinaries READ BINARY", readBinaries <= content.size / Chip.BLOCK_SIZE + 2)
    }

    @Test
    fun silentChip_GlobalTimeoutAndTransportClosed() =
        runTest {
            val transport = HangingTransport()
            val session = ReadingSession(transport, mrz, TestFixtures.EMPTY_TRUST_STORE, {}, globalTimeoutMillis = 200)
            val start = System.nanoTime()
            try {
                session.run()
                fail("Timeout expected")
            } catch (e: SceauException.Timeout) {
                assertEquals("TIMEOUT-CONNECT-GLOBAL", e.code)
            }
            assertTrue("transport closed", transport.closed.count == 0L)
            assertTrue("timeout respected", System.nanoTime() - start < TimeUnit.SECONDS.toNanos(5))
        }

    @Test
    fun globalTimeout_SuspendedDuringAuthentication() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val expired = CountDownLatch(1)
            val deadline = ReadDeadline(scope, 300) { expired.countDown() }
            deadline.resume()
            Thread.sleep(100)
            deadline.pause()
            // Well beyond the budget, but during authentication: no deadline.
            assertFalse(expired.await(600, TimeUnit.MILLISECONDS))
            assertFalse(deadline.expired)
            deadline.resume()
            assertTrue(expired.await(5, TimeUnit.SECONDS))
            assertTrue(deadline.expired)
        } finally {
            scope.cancel()
        }
    }

    /** Chip that never answers: each APDU blocks until the transport is closed. */
    private class HangingTransport : CardTransport {
        val closed = CountDownLatch(1)
        override val maxTransceiveLength: Int = 261
        override var timeoutMillis: Int = 1000

        override fun transceive(apdu: ByteArray): ByteArray {
            closed.await(30, TimeUnit.SECONDS)
            throw SceauException.ConnectionLost()
        }

        override fun close() {
            closed.countDown()
        }
    }
}
