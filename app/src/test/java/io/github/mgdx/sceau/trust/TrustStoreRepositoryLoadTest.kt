package io.github.mgdx.sceau.trust

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.mgdx.sceau.core.trust.TrustStore
import io.github.mgdx.sceau.core.trust.TrustStores
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Audit V22 : la liste des éléments importés et leur suppression ne dépendent pas du chargement
 * du magasin fusionné, qu'il échoue ou qu'il soit encore en cours.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class TrustStoreRepositoryLoadTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val directory = File(context.filesDir, "trust")

    /** Fichier importé quelconque, illisible comme Master List : son nom suffit. */
    private val importedId = "ab".repeat(32) + ".ml"

    @Before
    fun setUp() {
        directory.deleteRecursively()
        directory.mkdirs()
        File(directory, importedId).writeBytes(byteArrayOf(0x30, 0x00))
    }

    @Test
    fun `suppression possible quand le chargement du magasin echoue`() {
        val repository = TrustStoreRepository(context) { _, _ -> throw IllegalStateException("TEST_LOAD") }
        runBlocking {
            try {
                repository.get()
                fail("chargement censé échouer")
            } catch (_: IllegalStateException) {
                // attendu
            }
            val items = repository.importedItems()
            assertEquals(listOf(importedId), items.map { it.id })
            assertNull((items.single() as ImportedItem.MasterListItem).info)

            repository.removeImported(importedId)

            assertTrue(repository.importedItems().isEmpty())
        }
        assertFalse(File(directory, importedId).exists())
    }

    @Test
    fun `suppression possible pendant un chargement en cours, magasin perime non garde`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val loads = AtomicInteger()
        val stores = mutableListOf<TrustStore>()
        val repository =
            TrustStoreRepository(context) { masterLists, certificates ->
                val call = loads.incrementAndGet()
                if (call == 1) {
                    started.countDown()
                    check(release.await(10, TimeUnit.SECONDS)) { "TEST_TIMEOUT" }
                }
                TrustStores.load(masterLists, certificates).also { synchronized(stores) { stores += it } }
            }
        runBlocking {
            val slow = async(Dispatchers.IO) { repository.get() }
            assertTrue(started.await(10, TimeUnit.SECONDS))

            // Le chargement bloqué ne retient ni la liste ni la suppression.
            withTimeout(5_000) {
                assertEquals(listOf(importedId), repository.importedItems().map { it.id })
                repository.removeImported(importedId)
                assertTrue(repository.importedItems().isEmpty())
            }
            release.countDown()
            val stale = slow.await()

            // Le magasin fusionné avant la suppression n'est pas mis en cache : rechargé.
            val fresh = repository.get()
            assertEquals(2, loads.get())
            assertSame(stores.last(), fresh)
            assertFalse(stale === fresh)
            assertSame(fresh, repository.get())
        }
    }
}
