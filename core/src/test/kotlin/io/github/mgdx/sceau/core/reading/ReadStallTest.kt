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
 * Audit V29 : une puce forgée qui répond `90 00` sans donnée (ou un octet à la fois) à chaque
 * READ BINARY ne fait plus boucler la lecture ; et toute lecture se termine dans le délai global.
 */
class ReadStallTest {
    private val mrz = AccessKey.Mrz("12AB34567", LocalDate.of(1965, 2, 12), LocalDate.of(2030, 12, 31))

    /** SELECT du fichier [fid] puis READ BINARY : en-tête de [header] à l'offset 0, [reply] ensuite. */
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
    fun reponsesVidesRepetees_ReadStalledEnMoinsDe10Apdu() =
        runTest {
            // EF.CardAccess annonçant 1 000 octets, puis des réponses vides.
            val header = "318203E8".hexToBytes() + ByteArray(4)
            val transport = ScriptedTransport(*stallingFile(FID_CARD_ACCESS, header) { _, _ -> ByteArray(0) })
            try {
                readAndVerify(transport, mrz, TestFixtures.EMPTY_TRUST_STORE) {}
                fail("ReadStalled attendue")
            } catch (e: SceauException.ReadStalled) {
                assertEquals("READ_STALLED-CONNECT-CARD_ACCESS", e.code)
            }
            assertTrue("transport fermé", transport.closed)
            assertTrue("${transport.sent.size} APDU", transport.sent.size < 10)
        }

    @Test
    fun reponsesDUnOctet_ReadStalledAvantLePlafondDEchanges() {
        // DG2 annonçant 200 000 octets (sous son plafond de 256 Ko), livrés un octet à la fois.
        val header = "7583030D40".hexToBytes() + ByteArray(3)
        val fid = TestFixtures.fid(2)
        val transport = ScriptedTransport(*stallingFile(fid, header) { _, _ -> byteArrayOf(0x42) })
        val chip = Chip(transport)
        try {
            chip.readFile(Chip.fidOf(2))
            fail("ReadStalled attendue")
        } catch (e: SceauException.ReadStalled) {
            assertEquals("READ_STALLED-DG2", e.code)
        }
        val readBinaries = transport.sent.count { it.startsWith("00B0") }
        assertTrue("$readBinaries READ BINARY", readBinaries <= FileSizeLimits.maxReadBinaryCommands(Chip.fidOf(2)))
    }

    @Test
    fun dgFacultatifQuiNAvancePlus_ErreurEtNonAbsent() {
        val header = "6B820100".hexToBytes() + ByteArray(4)
        val transport = ScriptedTransport(*stallingFile(TestFixtures.fid(11), header) { _, _ -> ByteArray(0) })
        try {
            Chip(transport).readOptionalFile(Chip.fidOf(11))
            fail("ReadStalled attendue")
        } catch (e: SceauException.ReadStalled) {
            assertEquals("READ_STALLED-DG11", e.code)
        }
    }

    @Test
    fun lectureNormaleParBlocsDe223_Inchangee() {
        // DG2 de 30 Ko, lu par blocs de 223 octets comme sur un passeport réel (sous 32 Ko : le
        // transport scripté ne sert que READ BINARY B0, sans offset codé en DO53).
        val value = ByteArray(30_000) { (it * 31).toByte() }
        val content = "7582".hexToBytes() + byteArrayOf((value.size shr 8).toByte(), value.size.toByte()) + value
        val transport = ScriptedTransport(*ScriptedTransport.file(TestFixtures.fid(2), content))
        val read = Chip(transport).readFile(Chip.fidOf(2))
        assertArrayEquals(content, read)
        val readBinaries = transport.sent.count { it.startsWith("00B0") }
        assertTrue("$readBinaries READ BINARY", readBinaries <= content.size / Chip.BLOCK_SIZE + 2)
    }

    @Test
    fun puceMuette_TimeoutGlobalEtTransportFerme() =
        runTest {
            val transport = HangingTransport()
            val session = ReadingSession(transport, mrz, TestFixtures.EMPTY_TRUST_STORE, {}, globalTimeoutMillis = 200)
            val start = System.nanoTime()
            try {
                session.run()
                fail("Timeout attendue")
            } catch (e: SceauException.Timeout) {
                assertEquals("TIMEOUT-CONNECT-GLOBAL", e.code)
            }
            assertTrue("transport fermé", transport.closed.count == 0L)
            assertTrue("délai respecté", System.nanoTime() - start < TimeUnit.SECONDS.toNanos(5))
        }

    @Test
    fun delaiGlobal_SuspenduPendantLAuthentification() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val expired = CountDownLatch(1)
            val deadline = ReadDeadline(scope, 300) { expired.countDown() }
            deadline.resume()
            Thread.sleep(100)
            deadline.pause()
            // Bien au-delà du budget, mais pendant l'authentification : pas d'échéance.
            assertFalse(expired.await(600, TimeUnit.MILLISECONDS))
            assertFalse(deadline.expired)
            deadline.resume()
            assertTrue(expired.await(5, TimeUnit.SECONDS))
            assertTrue(deadline.expired)
        } finally {
            scope.cancel()
        }
    }

    /** Puce qui ne répond jamais : chaque APDU bloque jusqu'à la fermeture du transport. */
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
