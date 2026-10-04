package io.github.mgdx.sceau.mrz

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * Détecte toute modification des modèles OCR-B embarqués ou de la police dont ils dérivent :
 * leurs empreintes SHA-256 doivent être celles du tableau de `mrz/README.md`. Après une
 * régénération voulue (`scripts/generate-ocrb-templates.sh`), mettre le tableau à jour.
 *
 * Le répertoire de travail des tests Gradle est celui du module (`mrz/`).
 */
class OcrbTemplatesFingerprintTest {
    private val row = Regex("""^\|\s*`([^`]+)`\s*\|.*\|\s*`([0-9a-f]{64})`\s*\|\s*$""")

    private fun documented(): Map<String, String> =
        File("README.md")
            .readLines()
            .mapNotNull { line -> row.find(line.trim())?.destructured?.let { (name, sha) -> name to sha } }
            .toMap()

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun templatesMatchDocumentedFingerprint() {
        val stream = OcrbTemplates::class.java.getResourceAsStream(OcrbTemplates.RESOURCE)
        assertNotNull(stream)
        val bytes = stream!!.use { it.readBytes() }
        assertEquals(documented()[OcrbTemplates.RESOURCE], sha256(bytes))
    }

    @Test
    fun fontMatchesDocumentedFingerprint() {
        assertEquals(documented()[OcrbFont.file.name], sha256(OcrbFont.file.readBytes()))
    }

    @Test
    fun templatesParse() {
        val templates = OcrbTemplates.default
        assertEquals(OcrbTemplates.CLASSES, String(templates.classes))
        assertEquals(templates.classes.size * templates.size, templates.normalized.size)
    }
}
