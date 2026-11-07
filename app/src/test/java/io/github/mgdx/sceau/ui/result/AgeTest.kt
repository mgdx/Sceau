package io.github.mgdx.sceau.ui.result

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class AgeTest {
    private val birth = LocalDate.of(1985, 3, 12)

    @Test
    fun `on the birthday, the new age is reached`() {
        assertEquals(41, Age.years(birth, LocalDate.of(2026, 3, 12)))
    }

    @Test
    fun `the day before the birthday, the previous age still holds`() {
        assertEquals(40, Age.years(birth, LocalDate.of(2026, 3, 11)))
    }

    @Test
    fun `the day after the birthday, the new age holds`() {
        assertEquals(41, Age.years(birth, LocalDate.of(2026, 3, 13)))
    }

    @Test
    fun `born on 29 February, the birthday falls on 1 March in common years`() {
        val leapBirth = LocalDate.of(2000, 2, 29)
        assertEquals(25, Age.years(leapBirth, LocalDate.of(2026, 2, 28)))
        assertEquals(26, Age.years(leapBirth, LocalDate.of(2026, 3, 1)))
        assertEquals(28, Age.years(leapBirth, LocalDate.of(2028, 2, 29)))
    }

    @Test
    fun `a birth in the future gives no age`() {
        assertNull(Age.years(LocalDate.of(2026, 3, 13), LocalDate.of(2026, 3, 12)))
    }

    @Test
    fun `an age beyond the maximum gives no age`() {
        assertEquals(Age.MAX_YEARS, Age.years(LocalDate.of(1896, 3, 12), LocalDate.of(2026, 3, 12)))
        assertNull(Age.years(LocalDate.of(1895, 3, 12), LocalDate.of(2026, 3, 12)))
    }
}
