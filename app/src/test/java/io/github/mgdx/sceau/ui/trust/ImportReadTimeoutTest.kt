package io.github.mgdx.sceau.ui.trust

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Reading an imported file is bounded in time (audit V38). */
class ImportReadTimeoutTest {
    /** Stream from a hostile provider: `read` never returns a byte and only unblocks on close. */
    private class StalledStream : InputStream() {
        val closed = CountDownLatch(1)

        override fun read(): Int {
            closed.await()
            throw IOException("CLOSED")
        }

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int = read()

        override fun close() {
            closed.countDown()
        }
    }

    @Test
    fun `stalled read abandoned after the timeout, stream closed`() {
        val stream = StalledStream()
        val start = System.nanoTime()
        runBlocking {
            try {
                readImportedFile({ stream }, MAX_MASTER_LIST_BYTES, timeoutMillis = 200)
                fail("Stalled read accepted")
            } catch (_: ImportReadTimeoutException) {
                // Attendu.
            }
        }
        val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
        assertTrue("Abandoned too late: $elapsedMillis ms", elapsedMillis < 5_000)
        // Closed on abandon: the thread blocked in `read` is released.
        assertTrue(stream.closed.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun `stalled open abandoned after the timeout`() {
        val release = CountDownLatch(1)
        runBlocking {
            try {
                readImportedFile({
                    release.await()
                    null
                }, MAX_MASTER_LIST_BYTES, timeoutMillis = 200)
                fail("Stalled open accepted")
            } catch (_: ImportReadTimeoutException) {
                // Attendu.
            }
        }
        release.countDown()
    }

    @Test
    fun `normal read unchanged`() {
        val bytes = ByteArray(300_000) { it.toByte() }
        val read = runBlocking { readImportedFile({ ByteArrayInputStream(bytes) }, MAX_MASTER_LIST_BYTES) }
        assertArrayEquals(bytes, read)
    }

    @Test(expected = FileTooLargeException::class)
    fun `size limit still enforced`() {
        runBlocking { readImportedFile({ ByteArrayInputStream(ByteArray(1_001)) }, 1_000) }
    }

    @Test(expected = IOException::class)
    fun `file not found`() {
        runBlocking { readImportedFile({ null }, MAX_MASTER_LIST_BYTES) }
    }
}
