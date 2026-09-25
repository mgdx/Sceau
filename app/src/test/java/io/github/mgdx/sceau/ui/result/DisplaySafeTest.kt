package io.github.mgdx.sceau.ui.result

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplaySafeTest {
    @Test
    fun `texte ordinaire inchange, accents et ecritures non latines compris`() {
        assertEquals("MARTIN Élodie", displaySafe("MARTIN Élodie"))
        assertEquals("محمد", displaySafe("محمد"))
        assertEquals("🇫🇷 France", displaySafe("🇫🇷 France"))
    }

    @Test
    fun `surcharge bidirectionnelle retiree`() {
        assertEquals("DUPONTfdp.exe", displaySafe("DUPONT‮fdp.exe"))
        assertEquals("abc", displaySafe("‪a‫b‬‭c‮"))
        assertEquals("abcd", displaySafe("⁦a⁧b⁨c⁩d"))
    }

    @Test
    fun `separateurs de ligne et sauts remplaces par une espace`() {
        assertEquals("Paris ✓ Authentique", displaySafe("Paris ✓ Authentique"))
        assertEquals("a b", displaySafe("a b"))
        assertEquals("a b c d", displaySafe("a\nb\r\n\tc\u0085d"))
        assertEquals("a b", displaySafe("\n\na  \n b\n"))
    }

    @Test
    fun `largeur nulle et controles retires`() {
        assertEquals("ABC", displaySafe("A​B‌‍﻿C"))
        assertEquals("AB", displaySafe("A\u0000\u0007\u001B\u007FB"))
        assertEquals("AB", displaySafe("A­B"))
    }

    @Test
    fun `surrogat isole retire`() {
        assertEquals("ab", displaySafe("a\uD800b"))
    }

    @Test
    fun `troncature avec ellipse`() {
        assertEquals("abcde", displaySafe("abcde", maxLength = 5))
        assertEquals("abcd…", displaySafe("abcdef", maxLength = 5))
        // Les caractères retirés ne comptent pas.
        assertEquals("abcde", displaySafe("a​b​c​d​e", maxLength = 5))
        // Une paire de substitution n'est jamais coupée.
        assertEquals("😀😀…", displaySafe("😀".repeat(10), maxLength = 3))
    }

    @Test
    fun `texte d'un megaoctet tronque a la longueur par defaut`() {
        val huge = "X‮ ".repeat(350_000)
        assertTrue(huge.length > 1_000_000)
        val safe = displaySafe(huge)
        assertEquals(DISPLAY_MAX_LENGTH, safe.codePointCount(0, safe.length))
        assertTrue(safe.endsWith("…"))
        assertTrue(safe.none { it == '‮' || it == ' ' })
    }
}
