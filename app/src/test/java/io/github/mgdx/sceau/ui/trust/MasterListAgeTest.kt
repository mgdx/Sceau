package io.github.mgdx.sceau.ui.trust

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class MasterListAgeTest {
    private val signed: Instant = Instant.parse("2026-10-01T11:50:52Z")

    @Test
    fun `same day is zero months and not stale`() {
        val today = LocalDate.of(2026, 10, 1)
        assertEquals(0L, MasterListAge.months(signed, today))
        assertFalse(MasterListAge.isStale(signed, today))
    }

    @Test
    fun `eleven months and twenty-nine days is eleven months and not stale`() {
        val today = LocalDate.of(2027, 9, 30)
        assertEquals(11L, MasterListAge.months(signed, today))
        assertFalse(MasterListAge.isStale(signed, today))
    }

    @Test
    fun `exactly twelve months is twelve months and stale`() {
        val today = LocalDate.of(2027, 10, 1)
        assertEquals(12L, MasterListAge.months(signed, today))
        assertTrue(MasterListAge.isStale(signed, today))
    }

    @Test
    fun `future signing date is zero months`() {
        val today = LocalDate.of(2026, 3, 15)
        assertEquals(0L, MasterListAge.months(signed, today))
        assertFalse(MasterListAge.isStale(signed, today))
    }

    @Test
    fun `three years is thirty-six months`() {
        val today = LocalDate.of(2029, 10, 1)
        assertEquals(36L, MasterListAge.months(signed, today))
        assertTrue(MasterListAge.isStale(signed, today))
    }
}
