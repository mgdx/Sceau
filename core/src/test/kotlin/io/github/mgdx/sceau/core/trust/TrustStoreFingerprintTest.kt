package io.github.mgdx.sceau.core.trust

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Détecte toute substitution d'un fichier du magasin embarqué : les empreintes SHA-256 des
 * ressources `trust/` doivent être celles du tableau de `docs/trust-store.md`.
 *
 * Le répertoire de travail des tests Gradle est celui du module (`core/`).
 */
class TrustStoreFingerprintTest {
    private val row = Regex("""^\|\s*`([^`]+)`\s*\|.*\|\s*`([0-9a-f]{64})`\s*\|\s*$""")

    private fun documented(): Map<String, String> {
        val doc = listOf(File("../docs/trust-store.md"), File("docs/trust-store.md")).first { it.isFile }
        return doc
            .readLines()
            .mapNotNull { line -> row.find(line.trim())?.destructured?.let { (name, sha) -> name to sha } }
            .toMap()
    }

    private fun indexed(): List<String> =
        resource("index.txt")
            .toString(Charsets.UTF_8)
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }

    private fun resource(name: String): ByteArray {
        val stream = javaClass.getResourceAsStream("/trust/$name")
        assertNotNull("ressource absente : trust/$name", stream)
        return stream!!.use { it.readBytes() }
    }

    @Test
    fun embeddedFingerprintsMatchDocumentation() {
        val documented = documented()
        assertTrue("aucune ligne d'empreinte lue dans docs/trust-store.md", documented.isNotEmpty())

        for ((name, sha256) in documented) {
            assertEquals("empreinte de $name", sha256, TrustCrypto.sha256Hex(resource(name)))
        }
    }

    @Test
    fun documentationIndexAndResourcesListTheSameFiles() {
        val onDisk =
            File("src/main/resources/trust")
                .listFiles()
                .orEmpty()
                .map { it.name }
                .filter { it != "index.txt" }
                .toSet()

        assertEquals(documented().keys, indexed().toSet())
        assertEquals(onDisk, indexed().toSet())
    }
}
