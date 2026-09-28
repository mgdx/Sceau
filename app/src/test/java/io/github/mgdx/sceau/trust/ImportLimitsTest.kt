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
    fun `premier import permis`() {
        assertEquals(ImportDecision.ALLOWED, ImportLimits.decide(emptyList(), "new.ml", oneMb))
    }

    @Test
    fun `import permis juste sous la limite de nombre`() {
        val existing = files(ImportLimits.MAX_IMPORTED_LISTS - 1)
        assertEquals(ImportDecision.ALLOWED, ImportLimits.decide(existing, "new.ml", oneMb))
    }

    @Test
    fun `import refuse une fois la limite de nombre atteinte`() {
        val existing = files(ImportLimits.MAX_IMPORTED_LISTS)
        assertEquals(ImportDecision.TOO_MANY, ImportLimits.decide(existing, "new.ml", oneMb))
    }

    @Test
    fun `reimport d'une liste presente sans effet meme a la limite`() {
        val existing = files(ImportLimits.MAX_IMPORTED_LISTS)
        assertEquals(ImportDecision.ALREADY_PRESENT, ImportLimits.decide(existing, "list-3.ml", oneMb))
    }

    @Test
    fun `taille cumulee egale a la limite permise`() {
        val existing = files(1, ImportLimits.MAX_IMPORTED_TOTAL_BYTES - oneMb)
        assertEquals(ImportDecision.ALLOWED, ImportLimits.decide(existing, "new.ml", oneMb))
    }

    @Test
    fun `taille cumulee au-dela de la limite refusee`() {
        val existing = files(1, ImportLimits.MAX_IMPORTED_TOTAL_BYTES - oneMb)
        assertEquals(ImportDecision.TOO_LARGE_TOTAL, ImportLimits.decide(existing, "new.ml", oneMb + 1))
    }

    @Test
    fun `reimport d'une liste presente sans effet meme au-dela de la taille cumulee`() {
        val existing = files(2, ImportLimits.MAX_IMPORTED_TOTAL_BYTES / 2)
        assertEquals(ImportDecision.ALREADY_PRESENT, ImportLimits.decide(existing, "list-0.ml", oneMb))
    }

    @Test
    fun `limites de la decision D22`() {
        assertEquals(10, ImportLimits.MAX_IMPORTED_LISTS)
        assertEquals(40L * oneMb, ImportLimits.MAX_IMPORTED_TOTAL_BYTES)
    }
}
