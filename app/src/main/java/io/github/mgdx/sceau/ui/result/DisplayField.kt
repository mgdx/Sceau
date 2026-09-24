package io.github.mgdx.sceau.ui.result

import androidx.annotation.StringRes
import io.github.mgdx.sceau.R
import io.github.mgdx.sceau.core.model.Dg11Data
import io.github.mgdx.sceau.core.model.Dg12Data
import java.time.LocalDate

/** Un champ affiché : libellé et valeur déjà mise en forme. Pas de toString révélateur. */
class DisplayField(
    @param:StringRes val label: Int,
    val value: String,
) {
    override fun toString(): String = "DisplayField(***)"
}

/**
 * Champs renseignés de DG11 puis de DG12, dans l'ordre de SPEC §5.3. Les champs nuls,
 * vides ou blancs sont omis ; les listes sont jointes par des virgules.
 */
fun additionalFields(
    dg11: Dg11Data?,
    dg12: Dg12Data?,
    formatDate: (LocalDate) -> String,
): List<DisplayField> =
    buildList {
        fun addField(
            @StringRes label: Int,
            value: String?,
        ) {
            if (!value.isNullOrBlank()) add(DisplayField(label, value.trim()))
        }

        fun addListField(
            @StringRes label: Int,
            values: List<String>,
        ) = addField(label, values.filter { it.isNotBlank() }.joinToString(", ") { it.trim() })

        if (dg11 != null) {
            addField(R.string.result_field_full_name, dg11.fullName)
            addListField(R.string.result_field_other_names, dg11.otherNames)
            addField(R.string.result_field_personal_number, dg11.personalNumber)
            addField(R.string.result_field_full_birth_date, dg11.fullDateOfBirth?.let(formatDate))
            addListField(R.string.result_field_place_of_birth, dg11.placeOfBirth)
            addListField(R.string.result_field_address, dg11.address)
            addField(R.string.result_field_telephone, dg11.telephone)
            addField(R.string.result_field_profession, dg11.profession)
            addField(R.string.result_field_title, dg11.title)
            addField(R.string.result_field_personal_summary, dg11.personalSummary)
            addListField(R.string.result_field_other_td_numbers, dg11.otherValidTdNumbers)
            addField(R.string.result_field_custody, dg11.custodyInformation)
        }
        if (dg12 != null) {
            addField(R.string.result_field_issuing_authority, dg12.issuingAuthority)
            addField(R.string.result_field_issue_date, dg12.dateOfIssue?.let(formatDate))
            addListField(R.string.result_field_other_persons, dg12.namesOfOtherPersons)
            addField(R.string.result_field_endorsements, dg12.endorsementsAndObservations)
            addField(R.string.result_field_tax_exit, dg12.taxOrExitRequirements)
            addField(R.string.result_field_personalization_time, dg12.personalizationTime)
            addField(R.string.result_field_personalization_device, dg12.personalizationDeviceSerial)
        }
    }
