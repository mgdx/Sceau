package io.github.mgdx.sceau.ui.result

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplaySafeTest {
    @Test
    fun `ordinary text unchanged, including accents and non-Latin scripts`() {
        assertEquals("MARTIN Élodie", displaySafe("MARTIN Élodie"))
        assertEquals("محمد", displaySafe("محمد"))
        assertEquals("🇫🇷 France", displaySafe("🇫🇷 France"))
    }

    @Test
    fun `bidi override removed`() {
        assertEquals("DUPONTfdp.exe", displaySafe("DUPONT‮fdp.exe"))
        assertEquals("abc", displaySafe("‪a‫b‬‭c‮"))
        assertEquals("abcd", displaySafe("⁦a⁧b⁨c⁩d"))
    }

    @Test
    fun `line separators and breaks replaced by a space`() {
        assertEquals("Paris ✓ Authentique", displaySafe("Paris ✓ Authentique"))
        assertEquals("a b", displaySafe("a b"))
        assertEquals("a b c d", displaySafe("a\nb\r\n\tc\u0085d"))
        assertEquals("a b", displaySafe("\n\na  \n b\n"))
    }

    @Test
    fun `zero-width and control characters removed`() {
        assertEquals("ABC", displaySafe("A​B‌‍﻿C"))
        assertEquals("AB", displaySafe("A\u0000\u0007\u001B\u007FB"))
        assertEquals("AB", displaySafe("A­B"))
    }

    @Test
    fun `lone surrogate removed`() {
        assertEquals("ab", displaySafe("a\uD800b"))
    }

    @Test
    fun `truncation with an ellipsis`() {
        assertEquals("abcde", displaySafe("abcde", maxLength = 5))
        assertEquals("abcd…", displaySafe("abcdef", maxLength = 5))
        // Removed characters do not count.
        assertEquals("abcde", displaySafe("a​b​c​d​e", maxLength = 5))
        // A surrogate pair is never split.
        assertEquals("😀😀…", displaySafe("😀".repeat(10), maxLength = 3))
    }

    @Test
    fun `one-megabyte text truncated to the default length`() {
        val huge = "X‮ ".repeat(350_000)
        assertTrue(huge.length > 1_000_000)
        val safe = displaySafe(huge)
        assertEquals(DISPLAY_MAX_LENGTH, safe.codePointCount(0, safe.length))
        assertTrue(safe.endsWith("…"))
        assertTrue(safe.none { it == '‮' || it == ' ' })
    }
}
