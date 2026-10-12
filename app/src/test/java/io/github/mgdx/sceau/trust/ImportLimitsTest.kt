package io.github.mgdx.sceau.trust

import org.junit.Assert.assertEquals
import org.junit.Test

class ImportLimitsTest {
    private val oneMb = 1024L * 1024L

    private fun files(
        count: Int,
        size: Long = oneMb,
    ): List<ImportedFile> = List(count) { ImportedFile("list-$it.ml", size) }

    @Test
    fun `first import is allowed`() {
        assertEquals(ImportDecision.ALLOWED, ImportLimits.decide(emptyList(), "new.ml", oneMb))
    }

    @Test
    fun `import is allowed just below the count limit`() {
        val existing = files(ImportLimits.MAX_IMPORTED_LISTS - 1)
        assertEquals(ImportDecision.ALLOWED, ImportLimits.decide(existing, "new.ml", oneMb))
    }

    @Test
    fun `import is refused once the count limit is reached`() {
        val existing = files(ImportLimits.MAX_IMPORTED_LISTS)
        assertEquals(ImportDecision.TOO_MANY, ImportLimits.decide(existing, "new.ml", oneMb))
    }

    @Test
    fun `reimporting a present list has no effect even at the limit`() {
        val existing = files(ImportLimits.MAX_IMPORTED_LISTS)
        assertEquals(ImportDecision.ALREADY_PRESENT, ImportLimits.decide(existing, "list-3.ml", oneMb))
    }

    @Test
    fun `total size equal to the limit is allowed`() {
        val existing = files(1, ImportLimits.MAX_IMPORTED_TOTAL_BYTES - oneMb)
        assertEquals(ImportDecision.ALLOWED, ImportLimits.decide(existing, "new.ml", oneMb))
    }

    @Test
    fun `total size above the limit is refused`() {
        val existing = files(1, ImportLimits.MAX_IMPORTED_TOTAL_BYTES - oneMb)
        assertEquals(ImportDecision.TOO_LARGE_TOTAL, ImportLimits.decide(existing, "new.ml", oneMb + 1))
    }

    @Test
    fun `reimporting a present list has no effect even above the total size`() {
        val existing = files(2, ImportLimits.MAX_IMPORTED_TOTAL_BYTES / 2)
        assertEquals(ImportDecision.ALREADY_PRESENT, ImportLimits.decide(existing, "list-0.ml", oneMb))
    }

    @Test
    fun `limits of decision D22`() {
        assertEquals(10, ImportLimits.MAX_IMPORTED_LISTS)
        assertEquals(40L * oneMb, ImportLimits.MAX_IMPORTED_TOTAL_BYTES)
    }

    private fun certificates(count: Int): List<ImportedFile> = List(count) { ImportedFile("cert-$it.der", 1500) }

    @Test
    fun `first certificate is allowed`() {
        assertEquals(ImportDecision.ALLOWED, ImportLimits.decideCertificate(emptyList(), "new.der", 1500))
    }

    @Test
    fun `certificate is allowed just below the count limit`() {
        val existing = certificates(ImportLimits.MAX_IMPORTED_CERTIFICATES - 1)
        assertEquals(ImportDecision.ALLOWED, ImportLimits.decideCertificate(existing, "new.der", 1500))
    }

    @Test
    fun `certificate is refused once the count limit is reached`() {
        val existing = certificates(ImportLimits.MAX_IMPORTED_CERTIFICATES)
        assertEquals(ImportDecision.TOO_MANY_CERTIFICATES, ImportLimits.decideCertificate(existing, "new.der", 1500))
    }

    @Test
    fun `reimporting a present certificate has no effect even at the limit`() {
        val existing = certificates(ImportLimits.MAX_IMPORTED_CERTIFICATES)
        assertEquals(ImportDecision.ALREADY_PRESENT, ImportLimits.decideCertificate(existing, "cert-7.der", 1500))
    }

    @Test
    fun `certificate at the maximum size is allowed, above it is refused`() {
        val max = ImportLimits.MAX_CERTIFICATE_BYTES.toLong()
        assertEquals(ImportDecision.ALLOWED, ImportLimits.decideCertificate(emptyList(), "new.der", max))
        assertEquals(ImportDecision.CERTIFICATE_TOO_LARGE, ImportLimits.decideCertificate(emptyList(), "new.der", max + 1))
    }

    @Test
    fun `certificates do not count toward the Master List limits`() {
        // The two kinds are decided separately: 100 certificates do not prevent a Master List.
        assertEquals(ImportDecision.ALLOWED, ImportLimits.decide(emptyList(), "new.ml", oneMb))
    }

    @Test
    fun `limits of decision D33`() {
        assertEquals(100, ImportLimits.MAX_IMPORTED_CERTIFICATES)
        assertEquals(64 * 1024, ImportLimits.MAX_CERTIFICATE_BYTES)
    }
}
