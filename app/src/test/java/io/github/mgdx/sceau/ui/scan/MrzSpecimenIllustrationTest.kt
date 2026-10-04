package io.github.mgdx.sceau.ui.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MrzSpecimenIllustrationTest {
    @Test
    fun `les lignes du specimen ont la longueur de leur format`() {
        assertEquals(listOf(44, 44), specimenMrz(SpecimenKind.PASSPORT).map { it.length })
        assertEquals(listOf(30, 30, 30), specimenMrz(SpecimenKind.ID_CARD).map { it.length })
    }

    @Test
    fun `la taille de police fait tenir la ligne dans la largeur, presque entierement`() {
        // Avances typiques des polices à chasse fixe d'Android (Droid Sans Mono, Cutive Mono…).
        listOf(0.5f, 0.6f, 0.62f).forEach { advance ->
            listOf(44, 30).forEach { chars ->
                listOf(100f, 312f, 480f).forEach { width ->
                    val size = monospaceFontSizePx(width, chars, advance)
                    val lineWidth = chars * advance * size
                    assertTrue("$chars × $advance à $size px : $lineWidth > $width", lineWidth <= width)
                    assertTrue("$chars × $advance à $size px : $lineWidth trop étroit pour $width", lineWidth >= width * 0.95f)
                }
            }
        }
    }

    @Test
    fun `le format du document suit ICAO 9303`() {
        assertEquals(125f / 88f, specimenAspect(SpecimenKind.PASSPORT), 1e-6f)
        assertEquals(85.6f / 54f, specimenAspect(SpecimenKind.ID_CARD), 1e-6f)
    }
}
