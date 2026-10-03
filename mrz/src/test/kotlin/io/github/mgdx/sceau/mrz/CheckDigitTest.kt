package io.github.mgdx.sceau.mrz

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CheckDigitTest {
    private fun digit(s: String) = CheckDigit.compute(s.toCharArray())

    @Test
    fun `chiffres de controle des specimens ICAO`() {
        assertEquals(6, digit("L898902C3"))
        assertEquals(2, digit("740812"))
        assertEquals(9, digit("120415"))
        assertEquals(1, digit("ZE184226B<<<<<"))
        assertEquals(0, digit("L898902C36" + "7408122" + "1204159" + "ZE184226B<<<<<1"))
        assertEquals(7, digit("D23145890"))
        assertEquals(6, digit("D231458907" + "7408122" + "1204159" + "<<<<<<<"))
        assertEquals(6, digit("D231458907<<<<<<<<<<<<<<<" + "7408122" + "1204159" + "<<<<<<<<<<<"))
    }

    @Test
    fun `valeurs des caracteres`() {
        assertEquals(0, CheckDigit.value('<'))
        assertEquals(9, CheckDigit.value('9'))
        assertEquals(10, CheckDigit.value('A'))
        assertEquals(35, CheckDigit.value('Z'))
        assertEquals(-1, CheckDigit.value('a'))
        assertEquals(-1, digit("AB#"))
    }

    @Test
    fun `controle refuse un caractere qui n'est pas un chiffre`() {
        val chars = "740812".toCharArray()
        assertTrue(CheckDigit.matches(chars, 0, 6, '2'))
        assertFalse(CheckDigit.matches(chars, 0, 6, '3'))
        assertFalse(CheckDigit.matches(chars, 0, 6, '<'))
    }
}
