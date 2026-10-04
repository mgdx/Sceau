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
    fun `certificat embarque refuse avec sa source`() {
        assertEquals(Duplicate.KnownCertificate(TrustSource.ANTS), ImportDuplicates.certificate(ants, emptySet(), store))
        assertEquals(Duplicate.KnownCertificate(TrustSource.NATIONAL), ImportDuplicates.certificate(national, emptySet(), store))
        assertEquals(
            Duplicate.KnownCertificate(TrustSource.EMBEDDED_MASTER_LIST),
            ImportDuplicates.certificate(embeddedList, emptySet(), store),
        )
    }

    @Test
    fun `certificat d une Master List importee refuse avec cette source`() {
        assertEquals(
            Duplicate.KnownCertificate(TrustSource.IMPORTED_MASTER_LIST),
            ImportDuplicates.certificate(importedList, emptySet(), store),
        )
    }

    @Test
    fun `certificat deja importe seul refuse comme deja importe`() {
        assertEquals(Duplicate.ImportedCertificate, ImportDuplicates.certificate(importedCertificate, emptySet(), store))
    }

    @Test
    fun `fichier importe prime sur la source du magasin`() {
        // Certificat importé seul puis retrouvé dans une Master List importée : la fusion lui
        // donne la source de la Master List, mais il est bien déjà importé.
        assertEquals(Duplicate.ImportedCertificate, ImportDuplicates.certificate(importedList, setOf(importedList), store))
        // Fichier importé que le magasin n'a pas encore rechargé.
        assertEquals(Duplicate.ImportedCertificate, ImportDuplicates.certificate(unknown, setOf(unknown), store))
    }

    @Test
    fun `certificat nouveau accepte`() {
        assertNull(ImportDuplicates.certificate(unknown, setOf(importedCertificate), store))
        assertNull(ImportDuplicates.certificate(unknown, emptySet(), emptyMap()))
    }

    @Test
    fun `Master List deja importee refusee`() {
        assertEquals(Duplicate.ImportedMasterList, ImportDuplicates.masterList(importedList, setOf(importedList), setOf(embeddedList)))
    }

    @Test
    fun `Master List identique a la Master List embarquee refusee`() {
        assertEquals(Duplicate.EmbeddedMasterList, ImportDuplicates.masterList(embeddedList, setOf(importedList), setOf(embeddedList)))
    }

    @Test
    fun `Master List nouvelle acceptee`() {
        assertNull(ImportDuplicates.masterList(unknown, setOf(importedList), setOf(embeddedList)))
        assertNull(ImportDuplicates.masterList(unknown, emptySet(), emptySet()))
    }

    @Test
    fun `nouveaux certificats comptes sans doublon`() {
        val known = setOf(ants, national)
        assertEquals(2, ImportDuplicates.newCertificateCount(listOf(ants, unknown, importedList, unknown), known))
        assertEquals(0, ImportDuplicates.newCertificateCount(listOf(ants, national), known))
        assertEquals(0, ImportDuplicates.newCertificateCount(emptyList(), known))
    }
}
