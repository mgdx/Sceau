package io.github.mgdx.sceau.session

import io.github.mgdx.sceau.mrz.MrzFormat
import io.github.mgdx.sceau.mrz.MrzKeyFields
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** Résultat du scan de la MRZ dans la session (D32) : remplissage, message ponctuel, oubli. */
@RunWith(RobolectricTestRunner::class)
class SessionViewModelMrzScanTest {
    private val fields = MrzKeyFields(MrzFormat.TD3, "L898902C3", "740812", "120415")

    @Test
    fun `le scan remplit le formulaire sans lancer de lecture et le message n est donne qu une fois`() {
        val session = SessionViewModel(RuntimeEnvironment.getApplication())
        session.selectTab(DocumentTab.ID_CARD)
        session.selectIdCardKey(IdCardKey.MRZ)
        session.onDateOrder(DateOrder.YEAR_MONTH_DAY)

        session.onMrzScanned(fields)

        assertEquals("L898902C3", session.form.documentNumber)
        assertEquals("19740812", session.form.dateOfBirthDigits)
        assertEquals("20120415", session.form.dateOfExpiryDigits)
        assertEquals(DocumentTab.ID_CARD, session.form.tab)
        assertEquals(IdCardKey.MRZ, session.form.idCardKey)
        assertEquals(ReadState.Idle, session.state.value)
        assertTrue(session.mrzScanned)
        assertTrue(session.consumeMrzScanned())
        assertFalse(session.mrzScanned)
        assertFalse(session.consumeMrzScanned())
    }

    @Test
    fun `effacer oublie les valeurs scannees et le message`() {
        val session = SessionViewModel(RuntimeEnvironment.getApplication())
        session.onMrzScanned(fields)

        session.clear()

        assertEquals("", session.form.documentNumber)
        assertEquals("", session.form.dateOfBirthDigits)
        assertEquals("", session.form.dateOfExpiryDigits)
        assertFalse(session.consumeMrzScanned())
    }
}
