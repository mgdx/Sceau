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

/** Doublons refusés dès l'aperçu, sans rien écrire (D33). */
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
                fail("doublon attendu")
                error("inaccessible")
            } catch (e: DuplicateImportException) {
                e.duplicate
            }
        }

    @Test
    fun `certificat ANTS embarque refuse des l apercu`() {
        assertEquals(Duplicate.KnownCertificate(TrustSource.ANTS), duplicateOf(resource("ants-csca-2025.der")))
        assertTrue(importedFiles().isEmpty())
    }

    @Test
    fun `certificat de publication nationale embarque refuse des l apercu`() {
        assertEquals(Duplicate.KnownCertificate(TrustSource.NATIONAL), duplicateOf(resource("ge-csca-6.der")))
        assertTrue(importedFiles().isEmpty())
    }

    @Test
    fun `certificat embarque refuse aussi a l import`() {
        val bytes = resource("ants-csca-2025.der")
        runBlocking {
            try {
                repository.importCertificate(bytes)
                fail("doublon attendu")
            } catch (e: DuplicateImportException) {
                assertEquals(Duplicate.KnownCertificate(TrustSource.ANTS), e.duplicate)
            }
        }
        assertTrue(importedFiles().isEmpty())
    }

    @Test
    fun `certificat reimporte en DER puis en PEM refuse comme deja importe`() {
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
    fun `Master List identique a la Master List embarquee refusee`() {
        assertEquals(Duplicate.EmbeddedMasterList, duplicateOf(resource("de-bsi-master-list.ml")))
        assertTrue(importedFiles().isEmpty())
    }

    @Test
    fun `Master List deja importee refusee comme deja importee`() {
        // Fichier importé avant cette vérification : déjà importé prime sur embarqué.
        val bytes = resource("de-bsi-master-list.ml")
        directory.mkdirs()
        File(directory, TrustStoreRepository.importFileName(bytes)).writeBytes(bytes)
        assertEquals(Duplicate.ImportedMasterList, duplicateOf(bytes))
        assertEquals(1, importedFiles().size)
    }
}
