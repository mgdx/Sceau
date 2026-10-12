package io.github.mgdx.sceau.ui.trust

import io.github.mgdx.sceau.core.trust.TrustStores
import io.github.mgdx.sceau.testchip.TestPki
import io.github.mgdx.sceau.trust.CertificateSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Audit V23: X.500 subjects from imported files are sanitized before display. */
class SubjectDisplayTest {
    @Test
    fun `subject with bidi control and line break shown on one line`() {
        val hostileCommonName = "CSCA‮ Test\nEmpreinte du signataire\r\n2084 AED7⁦"
        val der = TestPki(name = "Piege", cscaCommonName = hostileCommonName).oldCsca.certificate.encoded
        val summary = CertificateSummary.of(TrustStores.parseCertificate(der))
        // The raw subject does carry the trap characters, so the test is meaningful.
        assertTrue(summary.subject.contains('‮'))

        val shown = displaySubject(summary.subject)

        assertFalse(shown.any { it == '\n' || it == '\r' || it == '‮' || it == '⁦' })
        assertTrue(shown.none { Character.getType(it).toByte() in listOf(Character.CONTROL, Character.FORMAT) })
        assertTrue(shown.contains("CSCA Test Empreinte du signataire 2084 AED7"))
    }

    @Test
    fun `oversized subject truncated with an ellipsis`() {
        val shown = displaySubject("CN=" + "A".repeat(10_000))

        assertEquals(SUBJECT_MAX_LENGTH, shown.codePointCount(0, shown.length))
        assertTrue(shown.endsWith("…"))
    }

    @Test
    fun `unknown country code sanitized and bounded`() {
        assertEquals("ZZ", displayCountryCode("Z‮Z"))
        assertEquals(COUNTRY_CODE_MAX_LENGTH, displayCountryCode("X".repeat(100)).length)
    }
}
