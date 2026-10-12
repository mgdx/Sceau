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
 * Audit V22: listing and removing imported items do not depend on loading the merged store,
 * whether that load fails or is still in progress.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class TrustStoreRepositoryLoadTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val directory = File(context.filesDir, "trust")

    /** Arbitrary imported file, unreadable as a Master List: its name is enough. */
    private val importedId = "ab".repeat(32) + ".ml"

    @Before
    fun setUp() {
        directory.deleteRecursively()
        directory.mkdirs()
        File(directory, importedId).writeBytes(byteArrayOf(0x30, 0x00))
    }

    @Test
    fun `removal works when loading the store fails`() {
        val repository = TrustStoreRepository(context) { _, _ -> throw IllegalStateException("TEST_LOAD") }
        runBlocking {
            try {
                repository.get()
                fail("load expected to fail")
            } catch (_: IllegalStateException) {
                // expected
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
    fun `removal works during an ongoing load, stale store is not kept`() {
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

            // The blocked load holds up neither the listing nor the removal.
            withTimeout(5_000) {
                assertEquals(listOf(importedId), repository.importedItems().map { it.id })
                repository.removeImported(importedId)
                assertTrue(repository.importedItems().isEmpty())
            }
            release.countDown()
            val stale = slow.await()

            // The store merged before the removal is not cached: it is reloaded.
            val fresh = repository.get()
            assertEquals(2, loads.get())
            assertSame(stores.last(), fresh)
            assertFalse(stale === fresh)
            assertSame(fresh, repository.get())
        }
    }
}
