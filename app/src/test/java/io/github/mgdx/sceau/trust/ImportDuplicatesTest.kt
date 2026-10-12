package io.github.mgdx.sceau.trust

import io.github.mgdx.sceau.core.trust.TrustSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImportDuplicatesTest {
    private val ants = "a".repeat(64)
    private val national = "b".repeat(64)
    private val embeddedList = "c".repeat(64)
    private val importedList = "d".repeat(64)
    private val importedCertificate = "e".repeat(64)
    private val unknown = "f".repeat(64)

    private val store =
        mapOf(
            ants to TrustSource.ANTS,
            national to TrustSource.NATIONAL,
            embeddedList to TrustSource.EMBEDDED_MASTER_LIST,
            importedList to TrustSource.IMPORTED_MASTER_LIST,
            importedCertificate to TrustSource.IMPORTED_CERTIFICATE,
        )

    @Test
    fun `embedded certificate is refused with its source`() {
        assertEquals(Duplicate.KnownCertificate(TrustSource.ANTS), ImportDuplicates.certificate(ants, emptySet(), store))
        assertEquals(Duplicate.KnownCertificate(TrustSource.NATIONAL), ImportDuplicates.certificate(national, emptySet(), store))
        assertEquals(
            Duplicate.KnownCertificate(TrustSource.EMBEDDED_MASTER_LIST),
            ImportDuplicates.certificate(embeddedList, emptySet(), store),
        )
    }

    @Test
    fun `certificate from an imported Master List is refused with that source`() {
        assertEquals(
            Duplicate.KnownCertificate(TrustSource.IMPORTED_MASTER_LIST),
            ImportDuplicates.certificate(importedList, emptySet(), store),
        )
    }

    @Test
    fun `certificate already imported alone is refused as already imported`() {
        assertEquals(Duplicate.ImportedCertificate, ImportDuplicates.certificate(importedCertificate, emptySet(), store))
    }

    @Test
    fun `imported file takes precedence over the store source`() {
        // Certificate imported alone, then found again in an imported Master List: merging gives
        // it the Master List source, but it is indeed already imported.
        assertEquals(Duplicate.ImportedCertificate, ImportDuplicates.certificate(importedList, setOf(importedList), store))
        // Imported file that the store has not reloaded yet.
        assertEquals(Duplicate.ImportedCertificate, ImportDuplicates.certificate(unknown, setOf(unknown), store))
    }

    @Test
    fun `new certificate is accepted`() {
        assertNull(ImportDuplicates.certificate(unknown, setOf(importedCertificate), store))
        assertNull(ImportDuplicates.certificate(unknown, emptySet(), emptyMap()))
    }

    @Test
    fun `already imported Master List is refused`() {
        assertEquals(Duplicate.ImportedMasterList, ImportDuplicates.masterList(importedList, setOf(importedList), setOf(embeddedList)))
    }

    @Test
    fun `Master List identical to the embedded Master List is refused`() {
        assertEquals(Duplicate.EmbeddedMasterList, ImportDuplicates.masterList(embeddedList, setOf(importedList), setOf(embeddedList)))
    }

    @Test
    fun `new Master List is accepted`() {
        assertNull(ImportDuplicates.masterList(unknown, setOf(importedList), setOf(embeddedList)))
        assertNull(ImportDuplicates.masterList(unknown, emptySet(), emptySet()))
    }

    @Test
    fun `new certificates are counted without duplicates`() {
        val known = setOf(ants, national)
        assertEquals(2, ImportDuplicates.newCertificateCount(listOf(ants, unknown, importedList, unknown), known))
        assertEquals(0, ImportDuplicates.newCertificateCount(listOf(ants, national), known))
        assertEquals(0, ImportDuplicates.newCertificateCount(emptyList(), known))
    }
}
