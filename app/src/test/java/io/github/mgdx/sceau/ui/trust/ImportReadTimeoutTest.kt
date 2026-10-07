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

/** Lecture d'un fichier importé bornée en durée (audit V38). */
class ImportReadTimeoutTest {
    /** Flux d'un fournisseur hostile : `read` ne rend jamais d'octet et ne se débloque qu'à la fermeture. */
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
    fun `lecture qui n'aboutit pas abandonnee apres le delai, flux ferme`() {
        val stream = StalledStream()
        val start = System.nanoTime()
        runBlocking {
            try {
                readImportedFile({ stream }, MAX_MASTER_LIST_BYTES, timeoutMillis = 200)
                fail("Lecture bloquée acceptée")
            } catch (_: ImportReadTimeoutException) {
                // Attendu.
            }
        }
        val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
        assertTrue("Abandon trop tardif : $elapsedMillis ms", elapsedMillis < 5_000)
        // Fermé à l'abandon : le fil bloqué dans `read` est libéré.
        assertTrue(stream.closed.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun `ouverture qui n'aboutit pas abandonnee apres le delai`() {
        val release = CountDownLatch(1)
        runBlocking {
            try {
                readImportedFile({
                    release.await()
                    null
                }, MAX_MASTER_LIST_BYTES, timeoutMillis = 200)
                fail("Ouverture bloquée acceptée")
            } catch (_: ImportReadTimeoutException) {
                // Attendu.
            }
        }
        release.countDown()
    }

    @Test
    fun `lecture normale inchangee`() {
        val bytes = ByteArray(300_000) { it.toByte() }
        val read = runBlocking { readImportedFile({ ByteArrayInputStream(bytes) }, MAX_MASTER_LIST_BYTES) }
        assertArrayEquals(bytes, read)
    }

    @Test(expected = FileTooLargeException::class)
    fun `limite de taille toujours appliquee`() {
        runBlocking { readImportedFile({ ByteArrayInputStream(ByteArray(1_001)) }, 1_000) }
    }

    @Test(expected = IOException::class)
    fun `fichier introuvable`() {
        runBlocking { readImportedFile({ null }, MAX_MASTER_LIST_BYTES) }
    }
}
