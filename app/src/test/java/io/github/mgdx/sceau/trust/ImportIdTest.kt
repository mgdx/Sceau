package io.github.mgdx.sceau.trust

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Identifiants acceptés par `TrustStoreRepository.removeImported` (D33) : protection contre la traversée de chemin. */
class ImportIdTest {
    private val hash = "0123456789abcdef".repeat(4)

    @Test
    fun `noms produits par le depot acceptes`() {
        val der = byteArrayOf(1, 2, 3)
        assertTrue(TrustStoreRepository.isValidImportId(TrustStoreRepository.importFileName(der)))
        assertTrue(TrustStoreRepository.isValidImportId(TrustStoreRepository.certificateFileName(der)))
        assertTrue(TrustStoreRepository.isValidImportId("$hash.ml"))
        assertTrue(TrustStoreRepository.isValidImportId("$hash.der"))
    }

    @Test
    fun `nom de certificat egal a l'empreinte SHA-256 du DER`() {
        val der = byteArrayOf(1, 2, 3)
        assertEquals(sha256Hex(der) + ".der", TrustStoreRepository.certificateFileName(der))
    }

    @Test
    fun `traversee de chemin et noms inattendus refuses`() {
        listOf(
            "",
            "..",
            "../$hash.ml",
            "$hash.ml/..",
            "/data/data/x/$hash.der",
            "sub/$hash.der",
            "..\\$hash.der",
            "$hash.tmp",
            "$hash.ml.der.tmp",
            "$hash.pem",
            hash.uppercase() + ".der",
            hash.dropLast(1) + ".der",
            hash + "0.der",
            "$hash.der\n",
            "$hash.der\u0000",
            "import-123.tmp",
        ).forEach { assertFalse(it, TrustStoreRepository.isValidImportId(it)) }
    }
}
