package io.github.mgdx.sceau.ui.trust

import io.github.mgdx.sceau.core.trust.TrustStores
import io.github.mgdx.sceau.testchip.TestPki
import io.github.mgdx.sceau.trust.CertificateSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Audit V23 : sujets X.500 des fichiers importés assainis avant affichage. */
class SubjectDisplayTest {
    @Test
    fun `sujet avec controle bidirectionnel et saut de ligne affiche sur une ligne`() {
        val hostileCommonName = "CSCA‮ Test\nEmpreinte du signataire\r\n2084 AED7⁦"
        val der = TestPki(name = "Piege", cscaCommonName = hostileCommonName).oldCsca.certificate.encoded
        val summary = CertificateSummary.of(TrustStores.parseCertificate(der))
        // Le sujet brut transporte bien les caractères piégés : le test n'est pas vide de sens.
        assertTrue(summary.subject.contains('‮'))

        val shown = displaySubject(summary.subject)

        assertFalse(shown.any { it == '\n' || it == '\r' || it == '‮' || it == '⁦' })
        assertTrue(shown.none { Character.getType(it).toByte() in listOf(Character.CONTROL, Character.FORMAT) })
        assertTrue(shown.contains("CSCA Test Empreinte du signataire 2084 AED7"))
    }

    @Test
    fun `sujet demesure borne avec ellipse`() {
        val shown = displaySubject("CN=" + "A".repeat(10_000))

        assertEquals(SUBJECT_MAX_LENGTH, shown.codePointCount(0, shown.length))
        assertTrue(shown.endsWith("…"))
    }

    @Test
    fun `code pays inconnu assaini et borne`() {
        assertEquals("ZZ", displayCountryCode("Z‮Z"))
        assertEquals(COUNTRY_CODE_MAX_LENGTH, displayCountryCode("X".repeat(100)).length)
    }
}
