package io.github.mgdx.sceau.session

import io.github.mgdx.sceau.core.AccessKey
import java.time.DateTimeException
import java.time.LocalDate
import java.time.chrono.IsoChronology
import java.time.format.DateTimeFormatterBuilder
import java.time.format.FormatStyle
import java.util.Locale

/** Onglets de l'écran d'accueil (SPEC §5.1), dans l'ordre d'affichage : le passeport d'abord (D29). */
enum class DocumentTab {
    PASSPORT,
    ID_CARD,
}

/** Partie d'une date saisie au clavier, avec son nombre de chiffres. */
enum class DatePart(
    val length: Int,
) {
    DAY(2),
    MONTH(2),
    YEAR(4),
}

/** Ordre de saisie des dates, suivant la locale : JJ/MM/AAAA en français, MM/DD/YYYY en en-US. */
enum class DateOrder(
    val parts: List<DatePart>,
) {
    DAY_MONTH_YEAR(listOf(DatePart.DAY, DatePart.MONTH, DatePart.YEAR)),
    MONTH_DAY_YEAR(listOf(DatePart.MONTH, DatePart.DAY, DatePart.YEAR)),
    YEAR_MONTH_DAY(listOf(DatePart.YEAR, DatePart.MONTH, DatePart.DAY)),
    ;

    /** Positions, dans les chiffres saisis, où s'insère un séparateur (fin des deux premières parties). */
    val separatorPositions: List<Int> = listOf(parts[0].length, parts[0].length + parts[1].length)

    companion object {
        /** Ordre du format de date court de [locale] ; jour/mois/année par défaut. */
        fun forLocale(locale: Locale): DateOrder {
            val pattern =
                try {
                    DateTimeFormatterBuilder.getLocalizedDateTimePattern(FormatStyle.SHORT, null, IsoChronology.INSTANCE, locale)
                } catch (e: IllegalArgumentException) {
                    return DAY_MONTH_YEAR
                }
            val day = pattern.indexOf('d')
            val month = pattern.indexOfFirst { it == 'M' || it == 'L' }
            val year = pattern.indexOfFirst { it == 'y' || it == 'u' }
            if (day < 0 || month < 0 || year < 0) return DAY_MONTH_YEAR
            return when {
                year < month && month < day -> YEAR_MONTH_DAY
                month < day && day < year -> MONTH_DAY_YEAR
                else -> DAY_MONTH_YEAR
            }
        }
    }
}

/** Raison pour laquelle une date complète est refusée. */
enum class DateError {
    /** La date n'existe pas (31/02, mois 13…). */
    INVALID,

    /** Date de naissance postérieure à aujourd'hui. */
    FUTURE,

    /** Date d'expiration antérieure à [AccessForm.MIN_EXPIRY_YEAR]. */
    TOO_OLD,
}

/**
 * Saisie de l'écran d'accueil. Contient la clé d'accès en clair : vit uniquement dans le
 * [SessionViewModel], jamais dans un Bundle ni sur disque, et son `toString` ne révèle rien.
 *
 * Les dates sont gardées telles que tapées ([DATE_DIGITS] chiffres au plus, sans séparateur) ;
 * leur interprétation dépend de l'ordre de saisie de la locale, fourni par l'écran.
 */
class AccessForm(
    val tab: DocumentTab = DocumentTab.PASSPORT,
    /** CAN : uniquement des chiffres, au plus [CAN_LENGTH]. */
    val can: String = "",
    /** Numéro de document normalisé : A-Z et 0-9, au plus [DOCUMENT_NUMBER_MAX_LENGTH], sans `<`. */
    val documentNumber: String = "",
    /** Chiffres de la date de naissance, au plus [DATE_DIGITS]. */
    val dateOfBirthDigits: String = "",
    /** Chiffres de la date d'expiration, au plus [DATE_DIGITS]. */
    val dateOfExpiryDigits: String = "",
) {
    /** Vrai si le formulaire de l'onglet courant permet de construire une clé d'accès. */
    fun isComplete(
        order: DateOrder,
        today: LocalDate = LocalDate.now(),
    ): Boolean = toAccessKey(order, today) != null

    /** Clé d'accès de l'onglet courant, ou null si la saisie est incomplète ou invalide. */
    fun toAccessKey(
        order: DateOrder,
        today: LocalDate = LocalDate.now(),
    ): AccessKey? =
        when (tab) {
            DocumentTab.ID_CARD -> {
                if (can.length == CAN_LENGTH) AccessKey.Can(can) else null
            }

            DocumentTab.PASSPORT -> {
                val birth = validDateOfBirth(dateOfBirthDigits, order, today)
                val expiry = validDateOfExpiry(dateOfExpiryDigits, order)
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
        dateOfBirthDigits: String = this.dateOfBirthDigits,
        dateOfExpiryDigits: String = this.dateOfExpiryDigits,
    ): AccessForm = AccessForm(tab, can, documentNumber, dateOfBirthDigits, dateOfExpiryDigits)

    override fun toString(): String = "AccessForm(tab=$tab, ***)"

    companion object {
        const val CAN_LENGTH = 6
        const val DOCUMENT_NUMBER_MAX_LENGTH = 9

        /** Nombre de chiffres d'une date complète : jour (2), mois (2), année (4). */
        const val DATE_DIGITS = 8

        /** Aucun document ICAO lisible par NFC n'expire avant cette année. */
        const val MIN_EXPIRY_YEAR = 1990

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

        /** Ne garde que les chiffres ASCII, tronqués à [DATE_DIGITS]. */
        fun filterDateDigits(input: String): String = input.filter { it in '0'..'9' }.take(DATE_DIGITS)

        /** Date réelle lue dans [digits] selon [order], ou null si incomplète ou inexistante. */
        fun parseDate(
            digits: String,
            order: DateOrder,
        ): LocalDate? {
            if (digits.length != DATE_DIGITS || digits.any { it !in '0'..'9' }) return null
            var start = 0
            val values = HashMap<DatePart, Int>()
            for (part in order.parts) {
                values[part] = digits.substring(start, start + part.length).toInt()
                start += part.length
            }
            return try {
                LocalDate.of(values.getValue(DatePart.YEAR), values.getValue(DatePart.MONTH), values.getValue(DatePart.DAY))
            } catch (e: DateTimeException) {
                null
            }
        }

        /** Erreur d'une date de naissance complète, ou null si elle est valide ou pas encore complète. */
        fun dateOfBirthError(
            digits: String,
            order: DateOrder,
            today: LocalDate = LocalDate.now(),
        ): DateError? {
            if (digits.length < DATE_DIGITS) return null
            val date = parseDate(digits, order) ?: return DateError.INVALID
            return if (date > today) DateError.FUTURE else null
        }

        /** Erreur d'une date d'expiration complète, ou null si elle est valide ou pas encore complète. */
        fun dateOfExpiryError(
            digits: String,
            order: DateOrder,
        ): DateError? {
            if (digits.length < DATE_DIGITS) return null
            val date = parseDate(digits, order) ?: return DateError.INVALID
            return if (date.year < MIN_EXPIRY_YEAR) DateError.TOO_OLD else null
        }

        private fun validDateOfBirth(
            digits: String,
            order: DateOrder,
            today: LocalDate,
        ): LocalDate? = parseDate(digits, order)?.takeIf { dateOfBirthError(digits, order, today) == null }

        private fun validDateOfExpiry(
            digits: String,
            order: DateOrder,
        ): LocalDate? = parseDate(digits, order)?.takeIf { dateOfExpiryError(digits, order) == null }
    }
}
