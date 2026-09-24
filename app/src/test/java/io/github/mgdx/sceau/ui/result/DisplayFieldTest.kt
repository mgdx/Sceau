package io.github.mgdx.sceau.ui.result

import io.github.mgdx.sceau.R
import io.github.mgdx.sceau.core.model.Dg11Data
import io.github.mgdx.sceau.core.model.Dg12Data
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class DisplayFieldTest {
    private val formatDate: (LocalDate) -> String = { it.toString() }

    @Test
    fun `sans DG11 ni DG12, aucun champ`() {
        assertTrue(additionalFields(null, null, formatDate).isEmpty())
    }

    @Test
    fun `seuls les champs renseignes sont affiches`() {
        val dg11 =
            Dg11Data(
                fullName = "  ",
                otherNames = emptyList(),
                personalNumber = null,
                fullDateOfBirth = LocalDate.of(1990, 1, 2),
                placeOfBirth = listOf("PARIS", " ", "FRANCE"),
                address = emptyList(),
                telephone = "",
                profession = null,
                title = null,
                personalSummary = null,
                otherValidTdNumbers = emptyList(),
                custodyInformation = null,
            )
        val dg12 =
            Dg12Data(
                issuingAuthority = "PREFECTURE",
                dateOfIssue = null,
                namesOfOtherPersons = emptyList(),
                endorsementsAndObservations = null,
                taxOrExitRequirements = null,
                frontImage = null,
                rearImage = null,
                personalizationTime = null,
                personalizationDeviceSerial = null,
            )
        val fields = additionalFields(dg11, dg12, formatDate)
        assertEquals(
            listOf(
                R.string.result_field_full_birth_date to "1990-01-02",
                R.string.result_field_place_of_birth to "PARIS, FRANCE",
                R.string.result_field_issuing_authority to "PREFECTURE",
            ),
            fields.map { it.label to it.value },
        )
    }
}
