package io.github.mgdx.sceau.core.trust

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Detects any substitution of a file in the embedded store: the SHA-256 fingerprints of the
 * `trust/` resources must match the table in `docs/trust-store.md`.
 *
 * The working directory of Gradle tests is the module directory (`core/`).
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
        assertNotNull("missing resource: trust/$name", stream)
        return stream!!.use { it.readBytes() }
    }

    @Test
    fun embeddedFingerprintsMatchDocumentation() {
        val documented = documented()
        assertTrue("no fingerprint row read from docs/trust-store.md", documented.isNotEmpty())

        for ((name, sha256) in documented) {
            assertEquals("fingerprint of $name", sha256, TrustCrypto.sha256Hex(resource(name)))
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
