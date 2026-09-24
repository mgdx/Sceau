package io.github.mgdx.sceau.core

import kotlinx.coroutines.test.runTest
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.jmrtd.PassportService
import org.junit.Assert.assertEquals
import org.junit.Test

/** Vérifie que JMRTD, BouncyCastle et kotlinx-coroutines-test sont bien sur le classpath de test. */
class SmokeTest {
    @Test
    fun classpath() =
        runTest {
            assertEquals("BC", BouncyCastleProvider().name)
            assertEquals(0x0101.toShort(), PassportService.EF_DG1)
        }
}
