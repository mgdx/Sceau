package io.github.mgdx.sceau.core.fuzz

import org.junit.Assert.assertArrayEquals
import org.junit.Test

/** Signed seeds are reproducible: otherwise, seed and index would not replay a failure. */
class FuzzSeedsTest {
    @Test
    fun signedSeedsAreDeterministic() {
        for (seed in FuzzSeeds.documents) {
            assertArrayEquals(seed.name, seed.sod, seed.sod())
            val challenge = ByteArray(8) { it.toByte() }
            assertArrayEquals(seed.name, seed.aaResponse(challenge), seed.aaResponse(challenge))
        }
        assertArrayEquals(MasterListSeed().bytes, MasterListSeed().bytes)
    }
}
