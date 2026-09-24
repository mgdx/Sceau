package io.github.mgdx.sceau.session

import io.github.mgdx.sceau.core.AccessKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

class AccessFormTest {
    private val dmy = DateOrder.DAY_MONTH_YEAR
    private val today = LocalDate.of(2026, 9, 25)

    @Test
    fun `filterCan keeps only ascii digits and truncates to six`() {
        assertEquals("123456", AccessForm.filterCan("12 34-56789"))
        assertEquals("12", AccessForm.filterCan("a1b2"))
        assertEquals("", AccessForm.filterCan("١٢٣"))
    }

    @Test
    fun `can form is complete only with exactly six digits`() {
        assertFalse(AccessForm(can = "12345").isComplete(dmy, today))
        val form = AccessForm(can = "123456")
        assertTrue(form.isComplete(dmy, today))
        assertEquals("123456", (form.toAccessKey(dmy, today) as AccessKey.Can).value)
    }

    @Test
    fun `document number is uppercased, stripped of filler and truncated to nine`() {
        assertEquals("AB123", AccessForm.normalizeDocumentNumber("ab 1-2<3"))
        assertEquals("ABCDEFGHI", AccessForm.normalizeDocumentNumber("abcdefghijk"))
        assertEquals("E", AccessForm.normalizeDocumentNumber("éE"))
    }

    @Test
    fun `mrz form needs number and both valid dates`() {
        val base = AccessForm(tab = DocumentTab.PASSPORT, can = "123456")
        assertNull(base.toAccessKey(dmy, today))
        assertNull(base.copy(documentNumber = "X1", dateOfBirthDigits = "17051990").toAccessKey(dmy, today))
        assertNull(base.copy(documentNumber = "X1", dateOfBirthDigits = "17051990", dateOfExpiryDigits = "0201203").toAccessKey(dmy, today))
        val key =
            base.copy(documentNumber = "X1", dateOfBirthDigits = "17051990", dateOfExpiryDigits = "02012031").toAccessKey(dmy, today)
                as AccessKey.Mrz
        assertEquals("X1", key.documentNumber)
        assertEquals(LocalDate.of(1990, 5, 17), key.dateOfBirth)
        assertEquals(LocalDate.of(2031, 1, 2), key.dateOfExpiry)
    }

    @Test
    fun `mrz form refuses invalid dates`() {
        val base = AccessForm(tab = DocumentTab.PASSPORT, documentNumber = "X1", dateOfExpiryDigits = "02012031")
        assertFalse(base.copy(dateOfBirthDigits = "31021990").isComplete(dmy, today))
        assertFalse(base.copy(dateOfBirthDigits = "26092026").isComplete(dmy, today))
        assertTrue(base.copy(dateOfBirthDigits = "25092026").isComplete(dmy, today))
        assertFalse(base.copy(dateOfBirthDigits = "17051990", dateOfExpiryDigits = "31121989").isComplete(dmy, today))
    }

    @Test
    fun `date digits keep only ascii digits and truncate to eight`() {
        assertEquals("17051990", AccessForm.filterDateDigits("17/05/1990 12"))
        assertEquals("", AccessForm.filterDateDigits("ab/"))
    }

    @Test
    fun `dates are parsed in the order of the locale`() {
        assertEquals(LocalDate.of(1990, 5, 17), AccessForm.parseDate("17051990", DateOrder.DAY_MONTH_YEAR))
        assertEquals(LocalDate.of(1990, 5, 17), AccessForm.parseDate("05171990", DateOrder.MONTH_DAY_YEAR))
        assertEquals(LocalDate.of(1990, 5, 17), AccessForm.parseDate("19900517", DateOrder.YEAR_MONTH_DAY))
        assertNull(AccessForm.parseDate("1705199", dmy))
        assertNull(AccessForm.parseDate("29022023", dmy))
        assertEquals(LocalDate.of(2024, 2, 29), AccessForm.parseDate("29022024", dmy))
        assertNull(AccessForm.parseDate("01131990", dmy))
        assertNull(AccessForm.parseDate("00011990", dmy))
    }

    @Test
    fun `date order follows the locale`() {
        assertEquals(DateOrder.DAY_MONTH_YEAR, DateOrder.forLocale(Locale.FRANCE))
        assertEquals(DateOrder.MONTH_DAY_YEAR, DateOrder.forLocale(Locale.US))
        assertEquals(DateOrder.DAY_MONTH_YEAR, DateOrder.forLocale(Locale.UK))
        assertEquals(DateOrder.YEAR_MONTH_DAY, DateOrder.forLocale(Locale.JAPAN))
    }

    @Test
    fun `errors only for complete dates`() {
        assertNull(AccessForm.dateOfBirthError("3102", dmy, today))
        assertEquals(DateError.INVALID, AccessForm.dateOfBirthError("31021990", dmy, today))
        assertEquals(DateError.FUTURE, AccessForm.dateOfBirthError("26092026", dmy, today))
        assertNull(AccessForm.dateOfBirthError("17051990", dmy, today))
        assertNull(AccessForm.dateOfExpiryError("311219", dmy))
        assertEquals(DateError.INVALID, AccessForm.dateOfExpiryError("32122031", dmy))
        assertEquals(DateError.TOO_OLD, AccessForm.dateOfExpiryError("31121989", dmy))
        assertNull(AccessForm.dateOfExpiryError("01011990", dmy))
    }

    @Test
    fun `toString reveals no access data`() {
        val text = AccessForm(can = "123456", documentNumber = "X1", dateOfBirthDigits = "17051990").toString()
        assertFalse("123456" in text)
        assertFalse("X1" in text)
        assertFalse("1705" in text)
    }
}
