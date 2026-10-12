package io.github.mgdx.sceau.core.verify

import io.github.mgdx.sceau.core.trust.TrustStores
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ICAO alpha-3 → alpha-2 table and country consistency rule (audit V3). */
class IcaoCountriesTest {
    @Test
    fun `ISO codes and ICAO-specific codes`() {
        assertEquals("FR", IcaoCountries.alpha2("FRA"))
        assertEquals("DE", IcaoCountries.alpha2("DEU"))
        assertEquals("DE", IcaoCountries.alpha2("D"))
        assertEquals("DE", IcaoCountries.alpha2("D<<"))
        for (code in listOf("GBR", "GBD", "GBN", "GBO", "GBP", "GBS")) assertEquals(code, "GB", IcaoCountries.alpha2(code))
        assertEquals("KS", IcaoCountries.alpha2("RKS"))
        assertEquals("CH", IcaoCountries.alpha2(" che "))
        assertEquals("AW", IcaoCountries.alpha2("ABW"))
        assertEquals("ZW", IcaoCountries.alpha2("ZWE"))
        assertEquals("249 ISO codes plus the ICAO-specific codes", 250, IcaoCountries.knownCountries.size)
        assertNull(IcaoCountries.alpha2("UTO"))
        assertNull(IcaoCountries.alpha2("UNO"))
    }

    @Test
    fun `consistent CSCA, DS and issuing state`() {
        assertTrue(IcaoCountries.consistent("FR", "FR", "FRA"))
        assertTrue(IcaoCountries.consistent("DE", "de", "D<<"))
        assertTrue(IcaoCountries.consistent("GB", "GB", "GBD"))
        assertTrue("Kosovo: KS and XK", IcaoCountries.consistent("KS", "XK", "RKS"))
        assertTrue("issuing state not provided", IcaoCountries.consistent("FR", "FR", null))
    }

    @Test
    fun `inconsistencies`() {
        assertFalse("CSCA from another country", IcaoCountries.consistent("DE", "DE", "FRA"))
        assertFalse("DS from another country", IcaoCountries.consistent("FR", "DE", "FRA"))
        assertFalse("DS without a country", IcaoCountries.consistent("FR", null, "FRA"))
        assertFalse("CSCA without a country", IcaoCountries.consistent(null, "FR", "FRA"))
        assertFalse("unknown code", IcaoCountries.consistent("FR", "FR", "UTO"))
        assertFalse("empty code", IcaoCountries.consistent("FR", "FR", "<<<"))
    }

    @Test
    fun `organizations - only the CSCA and the DS are compared`() {
        for (code in listOf("UNO", "UNA", "UNK", "EUE", "XXA", "XXB", "XXC", "XXX")) {
            assertTrue(code, IcaoCountries.isOrganization(code))
        }
        assertTrue(IcaoCountries.consistent("UN", "UN", "UNO"))
        assertTrue(IcaoCountries.consistent("EU", "EU", "EUE"))
        assertFalse(IcaoCountries.consistent("UN", "EU", "UNO"))
    }

    @Test
    fun `embedded store - every CSCA carries a country a document can declare`() {
        val anchors = TrustStores.load().anchors
        assertTrue(anchors.isNotEmpty())
        val withoutCountry = anchors.filter { Crypto.country(it.certificate) == null }
        assertEquals("CSCA without a C attribute", emptyList<String>(), withoutCountry.map { it.subject })
        // EU and UN: CSCAs of organizations (EUE, UNO… documents), compared with the DS only.
        val unreachable =
            anchors
                .mapNotNull { Crypto.country(it.certificate) }
                .filter { it !in IcaoCountries.knownCountries && it !in setOf("EU", "UN") }
                .toSet()
        assertEquals(emptySet<String>(), unreachable)
    }
}
