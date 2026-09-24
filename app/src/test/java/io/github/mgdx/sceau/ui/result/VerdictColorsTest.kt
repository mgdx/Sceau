package io.github.mgdx.sceau.ui.result

import io.github.mgdx.sceau.core.report.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VerdictColorsTest {
    @Test
    fun `contraste texte sur fond d'au moins 4,5 en clair et en sombre`() {
        Verdict.entries.forEach { verdict ->
            listOf(false, true).forEach { dark ->
                val palette = VerdictColors.palette(verdict, dark)
                val ratio = VerdictColors.contrastRatio(palette.container, palette.content)
                assertTrue("$verdict dark=$dark : $ratio", ratio >= 4.5)
            }
        }
    }

    @Test
    fun `les quatre verdicts ont des fonds distincts`() {
        listOf(false, true).forEach { dark ->
            val containers = Verdict.entries.map { VerdictColors.palette(it, dark).container }
            assertEquals(containers.size, containers.toSet().size)
        }
    }

    @Test
    fun `contraste de reference noir sur blanc`() {
        assertEquals(21.0, VerdictColors.contrastRatio(0xFF000000, 0xFFFFFFFF), 0.01)
    }
}
