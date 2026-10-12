package io.github.mgdx.sceau.ui.result

import io.github.mgdx.sceau.core.report.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VerdictColorsTest {
    @Test
    fun `text-on-background contrast of at least 4,5 in light and dark`() {
        Verdict.entries.forEach { verdict ->
            listOf(false, true).forEach { dark ->
                val palette = VerdictColors.palette(verdict, dark)
                val ratio = VerdictColors.contrastRatio(palette.container, palette.content)
                assertTrue("$verdict dark=$dark: $ratio", ratio >= 4.5)
            }
        }
    }

    @Test
    fun `the four verdicts have distinct backgrounds`() {
        listOf(false, true).forEach { dark ->
            val containers = Verdict.entries.map { VerdictColors.palette(it, dark).container }
            assertEquals(containers.size, containers.toSet().size)
        }
    }

    @Test
    fun `reference contrast black on white`() {
        assertEquals(21.0, VerdictColors.contrastRatio(0xFF000000, 0xFFFFFFFF), 0.01)
    }
}
