package io.github.mgdx.sceau.core.verify

import io.github.mgdx.sceau.core.trust.TrustStores
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Table ICAO alpha-3 → alpha-2 et règle de cohérence des pays (audit V3). */
class IcaoCountriesTest {
    @Test
    fun `codes ISO et codes propres a ICAO`() {
        assertEquals("FR", IcaoCountries.alpha2("FRA"))
        assertEquals("DE", IcaoCountries.alpha2("DEU"))
        assertEquals("DE", IcaoCountries.alpha2("D"))
        assertEquals("DE", IcaoCountries.alpha2("D<<"))
        for (code in listOf("GBR", "GBD", "GBN", "GBO", "GBP", "GBS")) assertEquals(code, "GB", IcaoCountries.alpha2(code))
        assertEquals("KS", IcaoCountries.alpha2("RKS"))
        assertEquals("CH", IcaoCountries.alpha2(" che "))
        assertEquals("AW", IcaoCountries.alpha2("ABW"))
        assertEquals("ZW", IcaoCountries.alpha2("ZWE"))
        assertEquals("249 codes ISO et les codes ICAO propres", 250, IcaoCountries.knownCountries.size)
        assertNull(IcaoCountries.alpha2("UTO"))
        assertNull(IcaoCountries.alpha2("UNO"))
    }

    @Test
    fun `CSCA, DS et Etat emetteur coherents`() {
        assertTrue(IcaoCountries.consistent("FR", "FR", "FRA"))
        assertTrue(IcaoCountries.consistent("DE", "de", "D<<"))
        assertTrue(IcaoCountries.consistent("GB", "GB", "GBD"))
        assertTrue("Kosovo : KS et XK", IcaoCountries.consistent("KS", "XK", "RKS"))
        assertTrue("Etat emetteur non fourni", IcaoCountries.consistent("FR", "FR", null))
    }

    @Test
    fun `incoherences`() {
        assertFalse("CSCA d'un autre pays", IcaoCountries.consistent("DE", "DE", "FRA"))
        assertFalse("DS d'un autre pays", IcaoCountries.consistent("FR", "DE", "FRA"))
        assertFalse("DS sans pays", IcaoCountries.consistent("FR", null, "FRA"))
        assertFalse("CSCA sans pays", IcaoCountries.consistent(null, "FR", "FRA"))
        assertFalse("code inconnu", IcaoCountries.consistent("FR", "FR", "UTO"))
        assertFalse("code vide", IcaoCountries.consistent("FR", "FR", "<<<"))
    }

    @Test
    fun `organisations - seuls le CSCA et le DS sont compares`() {
        for (code in listOf("UNO", "UNA", "UNK", "EUE", "XXA", "XXB", "XXC", "XXX")) {
            assertTrue(code, IcaoCountries.isOrganization(code))
        }
        assertTrue(IcaoCountries.consistent("UN", "UN", "UNO"))
        assertTrue(IcaoCountries.consistent("EU", "EU", "EUE"))
        assertFalse(IcaoCountries.consistent("UN", "EU", "UNO"))
    }

    @Test
    fun `magasin embarque - chaque CSCA porte un pays qu'un document peut annoncer`() {
        val anchors = TrustStores.load().anchors
        assertTrue(anchors.isNotEmpty())
        val withoutCountry = anchors.filter { Crypto.country(it.certificate) == null }
        assertEquals("CSCA sans attribut C", emptyList<String>(), withoutCountry.map { it.subject })
        // EU et UN : CSCA d'organisations (documents EUE, UNO…), comparés au seul DS.
        val unreachable =
            anchors
                .mapNotNull { Crypto.country(it.certificate) }
                .filter { it !in IcaoCountries.knownCountries && it !in setOf("EU", "UN") }
                .toSet()
        assertEquals(emptySet<String>(), unreachable)
    }
}
