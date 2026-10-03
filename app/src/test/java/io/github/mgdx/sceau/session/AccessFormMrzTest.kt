package io.github.mgdx.sceau.session

import io.github.mgdx.sceau.core.AccessKey
import io.github.mgdx.sceau.mrz.MrzFormat
import io.github.mgdx.sceau.mrz.MrzKeyFields
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/** Remplissage du formulaire par la MRZ lue à la caméra (D32). */
class AccessFormMrzTest {
    private val dmy = DateOrder.DAY_MONTH_YEAR
    private val today = LocalDate.of(2026, 10, 4)

    private fun fields(
        number: String = "X4RTBPFW4",
        birth: String = "900101",
        expiry: String = "340314",
    ) = MrzKeyFields(MrzFormat.TD1, number, birth, expiry)

    @Test
    fun `numero de neuf caracteres et dates converties dans l ordre jour mois annee`() {
        val form = AccessForm().fillFromMrz(fields(), today, dmy)
        assertEquals("X4RTBPFW4", form.documentNumber)
        assertEquals("01011990", form.dateOfBirthDigits)
        assertEquals("14032034", form.dateOfExpiryDigits)
        val key = form.toAccessKey(dmy, today) as AccessKey.Mrz
        assertEquals("X4RTBPFW4", key.documentNumber)
        assertEquals(LocalDate.of(1990, 1, 1), key.dateOfBirth)
        assertEquals(LocalDate.of(2034, 3, 14), key.dateOfExpiry)
    }

    @Test
    fun `les trois ordres de date de la locale`() {
        val f = fields(birth = "850723", expiry = "310102")
        val mdy = AccessForm().fillFromMrz(f, today, DateOrder.MONTH_DAY_YEAR)
        assertEquals("07231985", mdy.dateOfBirthDigits)
        assertEquals("01022031", mdy.dateOfExpiryDigits)
        val ymd = AccessForm().fillFromMrz(f, today, DateOrder.YEAR_MONTH_DAY)
        assertEquals("19850723", ymd.dateOfBirthDigits)
        assertEquals("20310102", ymd.dateOfExpiryDigits)
        val dmyForm = AccessForm().fillFromMrz(f, today, dmy)
        assertEquals("23071985", dmyForm.dateOfBirthDigits)
        assertEquals("02012031", dmyForm.dateOfExpiryDigits)
    }

    @Test
    fun `siecle de naissance de part et d autre d aujourd hui`() {
        // Aujourd'hui même : 20AA.
        assertEquals("04102026", AccessForm().fillFromMrz(fields(birth = "261004"), today, dmy).dateOfBirthDigits)
        // Demain en 20AA : 19AA.
        assertEquals("05101926", AccessForm().fillFromMrz(fields(birth = "261005"), today, dmy).dateOfBirthDigits)
        assertEquals("15032010", AccessForm().fillFromMrz(fields(birth = "100315"), today, dmy).dateOfBirthDigits)
        assertEquals("31121999", AccessForm().fillFromMrz(fields(birth = "991231"), today, dmy).dateOfBirthDigits)
        // Expiration toujours en 20AA, même lointaine.
        assertEquals("31122099", AccessForm().fillFromMrz(fields(expiry = "991231"), today, dmy).dateOfExpiryDigits)
    }

    @Test
    fun `29 fevrier`() {
        val leapDay = LocalDate.of(2024, 2, 29)
        val dayBefore = LocalDate.of(2024, 2, 28)
        assertEquals("29022024", AccessForm().fillFromMrz(fields(birth = "240229"), leapDay, dmy).dateOfBirthDigits)
        // La veille, 2024-02-29 serait dans le futur : 1924, bissextile aussi.
        val form = AccessForm().fillFromMrz(fields(birth = "240229"), dayBefore, dmy)
        assertEquals("29021924", form.dateOfBirthDigits)
        assertNull(AccessForm.dateOfBirthError(form.dateOfBirthDigits, dmy, dayBefore))
        // 29 février d'une année non bissextile : gardé, et signalé comme une saisie au clavier.
        val invalid = AccessForm().fillFromMrz(fields(birth = "010229", expiry = "330229"), today, dmy)
        assertEquals("29022001", invalid.dateOfBirthDigits)
        assertEquals(DateError.INVALID, AccessForm.dateOfBirthError(invalid.dateOfBirthDigits, dmy, today))
        assertEquals(DateError.INVALID, AccessForm.dateOfExpiryError(invalid.dateOfExpiryDigits, dmy))
        assertNull(invalid.toAccessKey(dmy, today))
    }

    @Test
    fun `les autres champs du formulaire sont conserves`() {
        val before = AccessForm(tab = DocumentTab.ID_CARD, idCardKey = IdCardKey.MRZ, can = "123456", documentNumber = "OLD")
        val form = before.fillFromMrz(fields(number = "D23145890"), today, dmy)
        assertEquals(DocumentTab.ID_CARD, form.tab)
        assertEquals(IdCardKey.MRZ, form.idCardKey)
        assertEquals("123456", form.can)
        assertEquals("D23145890", form.documentNumber)
    }

    @Test
    fun `numero normalise et dates mal formees laissees vides`() {
        val form = AccessForm().fillFromMrz(fields(number = "ab12<", birth = "9001", expiry = "34O314"), today, dmy)
        assertEquals("AB12", form.documentNumber)
        assertEquals("", form.dateOfBirthDigits)
        assertEquals("", form.dateOfExpiryDigits)
    }
}
