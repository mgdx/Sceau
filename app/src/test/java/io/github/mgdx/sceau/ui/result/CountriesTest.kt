package io.github.mgdx.sceau.ui.result

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class CountriesTest {
    @Test
    fun `codes ISO alpha-3 usuels`() {
        assertEquals("FR", Countries.fromIcao("FRA").alpha2)
        assertEquals("DE", Countries.fromIcao("DEU").alpha2)
        assertEquals("US", Countries.fromIcao("usa").alpha2)
        assertEquals("NL", Countries.fromIcao("NLD").alpha2)
    }

    @Test
    fun `Allemagne en code a une lettre, remplissage MRZ ignore`() {
        val ref = Countries.fromIcao("D<<")
        assertEquals("D", ref.code)
        assertEquals("DE", ref.alpha2)
        assertNull(ref.special)
    }

    @Test
    fun `variantes britanniques rattachees au Royaume-Uni`() {
        listOf("GBD", "GBN", "GBO", "GBP", "GBS").forEach {
            assertEquals(it, "GB", Countries.fromIcao(it).alpha2)
        }
    }

    @Test
    fun `codes speciaux Nations unies, UE et apatrides`() {
        assertEquals("EU", Countries.fromIcao("EUE").alpha2)
        assertEquals(SpecialCountry.UNITED_NATIONS, Countries.fromIcao("UNO").special)
        assertEquals(SpecialCountry.UN_SPECIALIZED_AGENCY, Countries.fromIcao("UNA").special)
        assertEquals(SpecialCountry.UN_KOSOVO, Countries.fromIcao("UNK").special)
        assertEquals("UN", Countries.fromIcao("UNO").alpha2)
        assertEquals(SpecialCountry.STATELESS, Countries.fromIcao("XXA").special)
        assertEquals(SpecialCountry.REFUGEE, Countries.fromIcao("XXB").special)
        assertEquals(SpecialCountry.REFUGEE_OTHER, Countries.fromIcao("XXC").special)
        assertEquals(SpecialCountry.UNSPECIFIED, Countries.fromIcao("XXX").special)
        assertNull(Countries.fromIcao("XXA").alpha2)
        assertEquals("XK", Countries.fromIcao("RKS").alpha2)
    }

    @Test
    fun `code inconnu conserve tel quel`() {
        val ref = Countries.fromIcao("ZZZ")
        assertEquals("ZZZ", ref.code)
        assertNull(ref.alpha2)
        assertNull(ref.special)
    }

    @Test
    fun `table alpha-3 coherente avec les codes ISO de la plateforme`() {
        Locale.getISOCountries().forEach { alpha2 ->
            val alpha3 =
                Locale
                    .Builder()
                    .setRegion(alpha2)
                    .build()
                    .isO3Country
            if (alpha3.isNotEmpty()) assertEquals(alpha3, alpha2, Countries.fromIcao(alpha3).alpha2)
        }
    }

    @Test
    fun `drapeau emoji depuis le code alpha-2`() {
        assertEquals("🇫🇷", Countries.flagEmoji("FR"))
        assertEquals("🇩🇪", Countries.flagEmoji("de"))
        assertEquals("🇪🇺", Countries.flagEmoji("EU"))
        assertNull(Countries.flagEmoji(null))
        assertNull(Countries.flagEmoji("FRA"))
        assertNull(Countries.flagEmoji("1A"))
    }

    @Test
    fun `nom localise du pays`() {
        assertEquals("France", Countries.displayName("FR", Locale.FRENCH))
        assertEquals("Germany", Countries.displayName("DE", Locale.ENGLISH))
        assertEquals("Allemagne", Countries.displayName("DE", Locale.FRENCH))
        assertNull(Countries.displayName(null, Locale.FRENCH))
        assertNull(Countries.displayName("ZZZ", Locale.FRENCH))
    }

    @Test
    fun `pays d'un certificat par code alpha-2`() {
        assertEquals("FR", Countries.fromAlpha2("fr").alpha2)
        assertNull(Countries.fromAlpha2("").alpha2)
    }
}
