package io.github.mgdx.sceau.ui.result

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DecodeLimitsTest {
    @Test
    fun `typical portrait decoded without subsampling`() {
        assertEquals(1, DecodeLimits.sampleSize(413, 531))
        assertEquals(1, DecodeLimits.sampleSize(2048, 2048))
    }

    @Test
    fun `6000x6000 image reduced below 2048 px per side`() {
        assertEquals(4, DecodeLimits.sampleSize(6000, 6000))
    }

    @Test
    fun `one pixel too many doubles the factor`() {
        assertEquals(2, DecodeLimits.sampleSize(2049, 10))
        assertEquals(2, DecodeLimits.sampleSize(10, 4096))
        assertEquals(4, DecodeLimits.sampleSize(10, 4097))
    }

    @Test
    fun `power-of-2 factor and bounded final side`() {
        for (side in listOf(1, 2047, 2048, 2049, 5000, 8191, 8192, 8193, 39_999, 1_000_000, 40_000_000)) {
            val sample = DecodeLimits.sampleSize(side, 1)!!
            assertEquals(0, sample and (sample - 1))
            assertTrue((side + sample - 1) / sample <= DecodeLimits.MAX_SIDE)
        }
    }

    @Test
    fun `image rejected beyond 40 declared megapixels`() {
        assertNull(DecodeLimits.sampleSize(6325, 6325))
        assertNull(DecodeLimits.sampleSize(65_535, 65_535))
        assertNull(DecodeLimits.sampleSize(Int.MAX_VALUE, Int.MAX_VALUE))
    }

    @Test
    fun `invalid dimensions rejected`() {
        assertNull(DecodeLimits.sampleSize(-1, -1))
        assertNull(DecodeLimits.sampleSize(0, 100))
        assertNull(DecodeLimits.sampleSize(100, 0))
    }
}
