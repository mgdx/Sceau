package io.github.mgdx.sceau.session

import io.github.mgdx.sceau.core.AccessKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class AccessFormTest {
    @Test
    fun `filterCan keeps only ascii digits and truncates to six`() {
        assertEquals("123456", AccessForm.filterCan("12 34-56789"))
        assertEquals("12", AccessForm.filterCan("a1b2"))
        assertEquals("", AccessForm.filterCan("١٢٣"))
    }

    @Test
    fun `can form is complete only with exactly six digits`() {
        assertFalse(AccessForm(can = "12345").isComplete)
        val form = AccessForm(can = "123456")
        assertTrue(form.isComplete)
        assertEquals("123456", (form.toAccessKey() as AccessKey.Can).value)
    }

    @Test
    fun `document number is uppercased, stripped of filler and truncated to nine`() {
        assertEquals("AB123", AccessForm.normalizeDocumentNumber("ab 1-2<3"))
        assertEquals("ABCDEFGHI", AccessForm.normalizeDocumentNumber("abcdefghijk"))
        assertEquals("E", AccessForm.normalizeDocumentNumber("éE"))
    }

    @Test
    fun `mrz form needs number and both dates`() {
        val birth = LocalDate.of(1990, 5, 17)
        val expiry = LocalDate.of(2031, 1, 2)
        val base = AccessForm(tab = DocumentTab.PASSPORT, can = "123456")
        assertNull(base.toAccessKey())
        assertNull(base.copy(documentNumber = "X1", dateOfBirth = birth).toAccessKey())
        val key = base.copy(documentNumber = "X1", dateOfBirth = birth, dateOfExpiry = expiry).toAccessKey() as AccessKey.Mrz
        assertEquals("X1", key.documentNumber)
        assertEquals(birth, key.dateOfBirth)
        assertEquals(expiry, key.dateOfExpiry)
    }

    @Test
    fun `picker millis round trip in utc`() {
        val date = LocalDate.of(1969, 12, 31)
        assertEquals(date, AccessForm.dateFromPickerMillis(AccessForm.dateToPickerMillis(date)))
        assertEquals(LocalDate.of(1970, 1, 1), AccessForm.dateFromPickerMillis(0))
    }

    @Test
    fun `toString reveals no access data`() {
        val text = AccessForm(can = "123456", documentNumber = "X1").toString()
        assertFalse("123456" in text)
        assertFalse("X1" in text)
    }
}
