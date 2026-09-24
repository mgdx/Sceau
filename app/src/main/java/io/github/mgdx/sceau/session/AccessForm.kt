package io.github.mgdx.sceau.session

import io.github.mgdx.sceau.core.AccessKey
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale

/** Onglets de l'écran d'accueil (SPEC §5.1). */
enum class DocumentTab {
    ID_CARD,
    PASSPORT,
}

/**
 * Saisie de l'écran d'accueil. Contient la clé d'accès en clair : vit uniquement dans le
 * [SessionViewModel], jamais dans un Bundle ni sur disque, et son `toString` ne révèle rien.
 */
class AccessForm(
    val tab: DocumentTab = DocumentTab.ID_CARD,
    /** CAN : uniquement des chiffres, au plus [CAN_LENGTH]. */
    val can: String = "",
    /** Numéro de document normalisé : A-Z et 0-9, au plus [DOCUMENT_NUMBER_MAX_LENGTH], sans `<`. */
    val documentNumber: String = "",
    val dateOfBirth: LocalDate? = null,
    val dateOfExpiry: LocalDate? = null,
) {
    /** Vrai si le formulaire de l'onglet courant permet de construire une clé d'accès. */
    val isComplete: Boolean get() = toAccessKey() != null

    /** Clé d'accès de l'onglet courant, ou null si la saisie est incomplète. */
    fun toAccessKey(): AccessKey? =
        when (tab) {
            DocumentTab.ID_CARD -> {
                if (can.length == CAN_LENGTH) AccessKey.Can(can) else null
            }

            DocumentTab.PASSPORT -> {
                val birth = dateOfBirth
                val expiry = dateOfExpiry
                if (documentNumber.isNotEmpty() && birth != null && expiry != null) {
                    AccessKey.Mrz(documentNumber, birth, expiry)
                } else {
                    null
                }
            }
        }

    fun copy(
        tab: DocumentTab = this.tab,
        can: String = this.can,
        documentNumber: String = this.documentNumber,
        dateOfBirth: LocalDate? = this.dateOfBirth,
        dateOfExpiry: LocalDate? = this.dateOfExpiry,
    ): AccessForm = AccessForm(tab, can, documentNumber, dateOfBirth, dateOfExpiry)

    override fun toString(): String = "AccessForm(tab=$tab, ***)"

    companion object {
        const val CAN_LENGTH = 6
        const val DOCUMENT_NUMBER_MAX_LENGTH = 9

        /** Ne garde que les chiffres ASCII, tronqués à [CAN_LENGTH]. */
        fun filterCan(input: String): String = input.filter { it in '0'..'9' }.take(CAN_LENGTH)

        /**
         * Majuscules forcées, seuls A-Z et 0-9 sont gardés (les `<` de remplissage de la MRZ
         * sont ajoutés par JMRTD), tronqué à [DOCUMENT_NUMBER_MAX_LENGTH].
         */
        fun normalizeDocumentNumber(input: String): String =
            input
                .uppercase(Locale.ROOT)
                .filter { it in 'A'..'Z' || it in '0'..'9' }
                .take(DOCUMENT_NUMBER_MAX_LENGTH)

        /** Millisecondes UTC du sélecteur de date Material 3 → date civile. */
        fun dateFromPickerMillis(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()

        /** Date civile → millisecondes UTC attendues par le sélecteur de date Material 3. */
        fun dateToPickerMillis(date: LocalDate): Long = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    }
}
