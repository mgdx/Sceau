package io.github.mgdx.sceau.trust

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.mgdx.sceau.core.trust.TrustSource
import io.github.mgdx.sceau.core.trust.TrustStores
import io.github.mgdx.sceau.testchip.TestPki
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.Base64

/** Duplicates are refused at preview time, without writing anything (D33). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class TrustStoreRepositoryDuplicatesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val directory = File(context.filesDir, "trust")
    private lateinit var repository: TrustStoreRepository

    @Before
    fun setUp() {
        directory.deleteRecursively()
        repository = TrustStoreRepository(context)
    }

    private fun resource(name: String): ByteArray =
        checkNotNull(TrustStores::class.java.getResourceAsStream("/trust/$name")).use { it.readBytes() }

    private fun importedFiles(): List<String> = directory.list().orEmpty().filterNot { it.endsWith(".tmp") }

    private fun duplicateOf(bytes: ByteArray): Duplicate =
        runBlocking {
            try {
                repository.previewImport(bytes)
                fail("duplicate expected")
                error("unreachable")
            } catch (e: DuplicateImportException) {
                e.duplicate
            }
        }

    @Test
    fun `embedded ANTS certificate is refused at preview`() {
        assertEquals(Duplicate.KnownCertificate(TrustSource.ANTS), duplicateOf(resource("ants-csca-2025.der")))
        assertTrue(importedFiles().isEmpty())
    }

    @Test
    fun `embedded nationally published certificate is refused at preview`() {
        assertEquals(Duplicate.KnownCertificate(TrustSource.NATIONAL), duplicateOf(resource("ge-csca-6.der")))
        assertTrue(importedFiles().isEmpty())
    }

    @Test
    fun `embedded certificate is also refused at import`() {
        val bytes = resource("ants-csca-2025.der")
        runBlocking {
            try {
                repository.importCertificate(bytes)
                fail("duplicate expected")
            } catch (e: DuplicateImportException) {
                assertEquals(Duplicate.KnownCertificate(TrustSource.ANTS), e.duplicate)
            }
        }
        assertTrue(importedFiles().isEmpty())
    }

    @Test
    fun `certificate reimported as DER then PEM is refused as already imported`() {
        val der = TestPki(name = "Doublon").oldCsca.certificate.encoded
        val pem =
            (
                "-----BEGIN CERTIFICATE-----\n" +
                    Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der) +
                    "\n-----END CERTIFICATE-----\n"
            ).toByteArray()
        runBlocking {
            assertTrue(repository.previewImport(pem) is ImportPreview.Certificate)
            repository.importCertificate(pem)
        }
        assertEquals(1, importedFiles().size)
        assertEquals(Duplicate.ImportedCertificate, duplicateOf(der))
        assertEquals(Duplicate.ImportedCertificate, duplicateOf(pem))
        assertEquals(1, importedFiles().size)
    }

    @Test
    fun `Master List identical to the embedded Master List is refused`() {
        assertEquals(Duplicate.EmbeddedMasterList, duplicateOf(resource("de-bsi-master-list.ml")))
        assertTrue(importedFiles().isEmpty())
    }

    @Test
    fun `already imported Master List is refused as already imported`() {
        // File imported before this check existed: already imported takes precedence over embedded.
        val bytes = resource("de-bsi-master-list.ml")
        directory.mkdirs()
        File(directory, TrustStoreRepository.importFileName(bytes)).writeBytes(bytes)
        assertEquals(Duplicate.ImportedMasterList, duplicateOf(bytes))
        assertEquals(1, importedFiles().size)
    }
}
